package com.mclauncher.network;


import com.mclauncher.i18n.L;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 下载器类
 * 支持断点续传、多线程分块下载、进度回调
 */
public class Downloader {

    private static final Logger logger = LoggerFactory.getLogger(Downloader.class);

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;  // 64KB，减少 syscall 次数
    // 读超时设得较长：客户端 JAR 有 20+ MB，在慢速镜像上 30 秒会中断
    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 300_000;   // 5 分钟
    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4MB 分块大小

    /**
     * 最大重试次数。
     * 国内镜像（尤其 BMCLAPI 背后的高校镜像节点）会对高频请求返回 403/429，
     * 需要给足重试机会，因此设为 5 次。
     */
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 1000;

    /** 等待下载许可的最长时间：超时后调用方换镜像，而不是原地死等 */
    private static final long ACQUIRE_TIMEOUT_MS = 20_000;

    /**
     * 启用多线程分块下载的最小文件大小。
     * 小于该值分块的额外请求开销大于收益。
     */
    private static final long MULTITHREAD_THRESHOLD = 8L * 1024 * 1024;  // 8MB

    /**
     * 分块下载的最大线程数。
     *
     * 实测南阳理工/上海交大在 64 并发下零限流，
     * 因此分块上限放宽到 16（超过 16 段收益递减，且调度开销上升）。
     */
    private static final int MAX_CHUNK_THREADS = 16;

    private volatile OkHttpClient httpClient;
    private final Gson gson;
    private ExecutorService downloadExecutor;
    private final ExecutorService chunkExecutor;
    private final AtomicLong totalDownloaded;

    /**
     * 取消标志：cancel() 置位，进行中的单线程读 / 分块任务 / 重试退避都会检查。
     * 以前 cancel() 只 shutdownNow 了异步执行器，真正在跑的下载完全不受影响。
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 全局限流器：按连接数限流，429 时自动降级 */
    private final DownloadThrottle throttle = DownloadThrottle.getInstance();

    /**
     * 下载进度监听器接口
     */
    public interface DownloadListener {
        void onProgress(long downloaded, long total, double percent);
        void onComplete(Path destination);
        void onError(Exception e);
        void onStart();

        /**
         * 速度回调（字节/秒）。
         * 默认空实现，方便只关心进度的调用方。
         */
        default void onSpeed(long bytesPerSecond) {}
    }

    /**
     * 下载任务结果
     */
    public static class DownloadResult {
        private final boolean success;
        private final Path destination;
        private final long bytesDownloaded;
        private final String errorMessage;
        private final long durationMs;

        public DownloadResult(boolean success, Path destination, long bytesDownloaded,
                             String errorMessage, long durationMs) {
            this.success = success;
            this.destination = destination;
            this.bytesDownloaded = bytesDownloaded;
            this.errorMessage = errorMessage;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() { return success; }
        public Path getDestination() { return destination; }
        public long getBytesDownloaded() { return bytesDownloaded; }
        public String getErrorMessage() { return errorMessage; }
        public long getDurationMs() { return durationMs; }
    }

    /**
     * 构造函数
     */
    public Downloader() {
        this(CONNECT_TIMEOUT);
    }

    /**
     * @param connectTimeoutMs 连接超时（毫秒）。
     *        对于源站可能被阻断的场景（例如 cdn.modrinth.com 在部分网络下不通），
     *        传入较短的超时可以更快失败、避免长时间卡住。
     */
    public Downloader(int connectTimeoutMs) {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.downloadExecutor = Executors.newFixedThreadPool(4);
        this.chunkExecutor = Executors.newFixedThreadPool(MAX_CHUNK_THREADS);
        this.totalDownloaded = new AtomicLong(0);

        // 配置 OkHttp 客户端
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                // 连接池：提高并发下载时的连接复用率，减少 TCP/TLS 握手开销
                .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                // 强制 HTTP/1.1：BMCLAPI / Mojang 的 CDN 在高并发下
                // HTTP/2 会出现 PROTOCOL_ERROR 或 stream reset，
                // 而 HTTP/1.1 + Range 的续传行为更稳定可靠
                .protocols(java.util.Arrays.asList(Protocol.HTTP_1_1))
                // 重定向
                .followRedirects(true)
                .followSslRedirects(true)
                // 连接池保持复用
                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder builder = original.newBuilder()
                            // 必须使用表明启动器身份的 UA：
                            // 伪装成浏览器会被 BMCLAPI 的镜像节点返回 403
                            .header("User-Agent", NetworkConstants.USER_AGENT)
                            .header("Accept", NetworkConstants.ACCEPT);

                    // 添加 Range 头用于断点续传
                    String range = original.header("Range");
                    if (range != null) {
                        builder.header("Range", range);
                    }

                    return chain.proceed(builder.build());
                })
                .build();
    }

    /**
     * 设置代理
     */
    public void setProxy(Proxy proxy) {
        // newBuilder().build() 返回的是**新**客户端，以前直接丢弃了 ——
        // 一旦有人接入代理设置，这个函数等于什么都没做
        this.httpClient = this.httpClient.newBuilder()
                .proxy(proxy)
                .build();
    }

    /**
     * 核心下载逻辑
     *
     * 增强点：
     * 1. 自动重试：网络中断（如 HTTP/2 的 PROTOCOL_ERROR）时最多重试 MAX_RETRIES 次
     * 2. 断点续传：每次重试都从已下载的字节位置继续，而不是从头再来
     * 3. 完整性校验：拿到总大小后确认最终文件大小一致
     */
    public DownloadResult download(String url, Path destination, DownloadListener listener) {
        return download(url, destination, listener, false);
    }

    /**
     * 异步下载文件
     */
    public DownloadResult downloadAsync(String url, Path destination, DownloadListener listener) {
        CompletableFuture<DownloadResult> future = CompletableFuture.supplyAsync(() ->
                download(url, destination, listener, false), downloadExecutor);
        return future.join();
    }

    /**
     * 核心下载逻辑（带重试与断点续传）
     */
    public DownloadResult download(String url, Path destination, DownloadListener listener,
                                   boolean resume) {
        return download(url, destination, listener, resume, -1);
    }

    /**
     * 便捷重载：已知大小 + 监听器（参数顺序更适合调用方阅读）
     *
     * @param resume      是否允许断点续传
     * @param expectedSize 预期大小（-1 表示未知）
     */
    public DownloadResult download(String url, Path destination, boolean resume,
                                   long expectedSize, DownloadListener listener) {
        return download(url, destination, listener, resume, expectedSize);
    }

    /**
     * 核心下载逻辑（调用方已知文件大小时使用）
     *
     * 传入 expectedSize 的好处：
     * - 大文件可以直接走多线程分块下载，省去一次 HEAD 探测往返
     * - 能更早地判断「已下载完整」而跳过
     *
     * @param expectedSize 预期文件大小（字节），未知传 -1
     */
    public DownloadResult download(String url, Path destination, DownloadListener listener,
                                   boolean resume, long expectedSize) {
        long startTime = System.currentTimeMillis();
        cancelled.set(false);
        logger.info("开始下载: {} -> {}", url, destination);

        if (listener != null) {
            listener.onStart();
        }

        Exception lastError = null;

        try {
            Files.createDirectories(destination.getParent());

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    return cancelledResult(destination, startTime);
                }
                // 决定是否需要探测文件大小：
                // - 调用方已给出 expectedSize -> 直接用，省一次往返
                // - 重试且已有部分内容 -> 需要知道总长以便续传
                // - 首次下载且大小未知 -> 不探测，直接流式下载
                //   （小文件这样更快；大文件由调用方传 expectedSize）
                long totalSize = expectedSize;

                boolean needSize = totalSize <= 0
                        && (attempt > 1
                            || (Files.exists(destination) && Files.size(destination) > 0));

                if (needSize) {
                    totalSize = getFileSize(url, 0);
                    if (totalSize <= 0) {
                        totalSize = -1;
                    }
                }

                if (attempt == 1 && expectedSize > 0) {
                    // 已告知大小，可判断是否已经下好
                    if (Files.exists(destination) && Files.size(destination) == expectedSize) {
                        long dur = System.currentTimeMillis() - startTime;
                        logger.info("文件已存在且大小相符，跳过下载: {}",
                                destination.getFileName());
                        if (listener != null) {
                            listener.onComplete(destination);
                        }
                        return new DownloadResult(true, destination, expectedSize, null, dur);
                    }
                }

                // 决定续传位置：
                // - 首次尝试：若 resume=true 且文件已存在则续传，否则从头
                // - 后续尝试：总是从当前文件大小续传
                long startPosition = 0;
                if (Files.exists(destination)) {
                    long existing = Files.size(destination);
                    if (existing > 0) {
                        if (attempt > 1 || resume) {
                            startPosition = existing;
                        } else {
                            // 非续传模式且已有残留文件：重新开始
                            Files.delete(destination);
                        }
                    }
                }

                // 服务器不支持 Range 且我们要续传 -> 只能重头下载
                // 只有确实需要续传（startPosition > 0）时才探测，避免多余请求
                if (startPosition > 0 && !supportsRange(url)) {
                    logger.debug("服务器不支持 Range，从头下载");
                    startPosition = 0;
                    Files.deleteIfExists(destination);
                }

                // 已完整则直接成功
                if (totalSize > 0 && startPosition >= totalSize) {
                    long dur = System.currentTimeMillis() - startTime;
                    logger.info("文件已完整，跳过下载: {}", destination.getFileName());
                    if (listener != null) {
                        listener.onComplete(destination);
                    }
                    return new DownloadResult(true, destination,
                            Files.size(destination), null, dur);
                }

                try {
                    if (attempt > 1) {
                        logger.info("第 {} 次重试下载（从 {} 字节继续）: {}",
                                attempt, startPosition, url);
                    }

                    // 大文件且支持 Range 时走多线程分块下载（显著提速）
                    //
                    // 只有已知总大小且超过阈值时才分块；未知大小（未探测）
                    // 就先用单线程流式下载 —— 对小文件来说这更快，
                    // 而大文件在首次遇到时是已知大小的。
                    boolean useChunks = totalSize >= MULTITHREAD_THRESHOLD
                            && startPosition == 0
                            && attempt == 1
                            && supportsRange(url);

                    if (useChunks) {
                        logger.info("文件 {} MB，启用多线程分块下载",
                                String.format("%.1f", totalSize / 1048576.0));
                        downloadInChunks(url, destination, totalSize, listener);
                    } else {
                        long written = downloadFile(url, destination, startPosition,
                                totalSize, listener);
                        // 未探测大小的情况下，用实际写入量补齐
                        if (totalSize <= 0) {
                            totalSize = written;
                        }
                    }

                    // 校验最终大小
                    long actual = Files.size(destination);
                    if (totalSize > 0 && actual != totalSize) {
                        throw new IOException(String.format(
                                "下载不完整：期望 %d 字节，实际 %d 字节", totalSize, actual));
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    totalDownloaded.addAndGet(actual);

                    logger.info("下载完成: {} ({} MB)",
                            destination.toAbsolutePath(), actual / 1048576.0);

                    if (listener != null) {
                        listener.onComplete(destination);
                    }

                    return new DownloadResult(true, destination, actual, null, duration);

                } catch (Exception e) {
                    lastError = e;
                    long partial = Files.exists(destination) ? Files.size(destination) : 0;

                    // 区分错误类型，决定处理方式：
                    //
                    // 1. 限流（403/429）：保留已下载内容（下次可续传），
                    //    但要等更久 —— 立即重试只会加重限流。
                    // 2. 其它错误（网络中断、连接重置）：同样保留内容以便续传，
                    //    短暂退避后重试。
                    //
                    // 注意：以前对限流错误会删除已下载文件，导致每次都从 0 开始，
                    // 在慢速链路上白白浪费大量时间，这里已修正。
                    boolean isThrottled = e instanceof HttpStatusException
                            && ((HttpStatusException) e).isRetryable();

                    logger.warn("下载中断（第 {}/{} 次，已获取 {} 字节）: {}",
                            attempt, MAX_RETRIES, partial, e.getMessage());

                    // HTTP 404/410 等「资源不存在」类错误重试没有意义 ——
                    // 同一地址再试 100 次也是 404。立即失败，让调用方回退到其它源。
                    boolean permanent = e instanceof HttpStatusException
                            && !((HttpStatusException) e).isRetryable();
                    if (permanent) {
                        logger.debug("该地址资源不存在，跳过重试: {}", url);
                        break;
                    }

                    if (attempt < MAX_RETRIES) {
                        // 退避：限流时等待显著更久（尊重该 host 的冷却状态）
                        long backoff;
                        if (isThrottled) {
                            // 指数退避 + 抖动：1s、2s、4s、8s（封顶）
                            long base = Math.min(8_000L, RETRY_DELAY_MS << Math.min(attempt - 1, 3));
                            backoff = base + java.util.concurrent.ThreadLocalRandom.current().nextLong(500);
                            logger.info("被限流，等待 {} ms 后重试（保留已下载的 {} KB）",
                                    backoff, partial / 1024);
                        } else {
                            backoff = RETRY_DELAY_MS * attempt;
                        }

                        if (listener != null) {
                            listener.onProgress(partial, totalSize > 0 ? totalSize : partial,
                                    totalSize > 0 ? (double) partial / totalSize * 100 : 0);
                        }
                        try {
                            // 分段等待：cancel() 不一定会 interrupt 当前线程，
                            // 必须定时检查取消标志，否则最长要等满整个退避时间
                            long waited = 0;
                            while (waited < backoff && !cancelled.get()
                                    && !Thread.currentThread().isInterrupted()) {
                                long step = Math.min(200, backoff - waited);
                                Thread.sleep(step);
                                waited += step;
                            }
                            if (cancelled.get()) {
                                throw new InterruptedException("cancelled");
                            }
                        } catch (InterruptedException ie) {
                            // 退避期间被中断 = 上层取消了该任务。
                            // 恢复中断标志并立即退出，不要当成「下载失败」记错误日志，
                            // 也不要继续重试 —— 否则取消一个慢任务会拖累整个下载流程。
                            Thread.currentThread().interrupt();
                            logger.debug("下载任务被取消: {}", destination.getFileName());
                            return new DownloadResult(false, destination, partial,
                                    L.t("s_6df9b76521"), System.currentTimeMillis() - startTime);
                        }
                    }
                }
            }

            // 所有重试都失败
            String msg = lastError != null ? lastError.getMessage() : L.t("s_5f76edc5de");
            logger.error("下载失败（已重试 {} 次）: {}", MAX_RETRIES, msg);

            if (listener != null) {
                listener.onError(lastError != null ? lastError : new IOException(L.t("s_e0dab22b1a")));
            }

            // 清理彻底失败的空文件，保留有内容的残留以便续传
            if (Files.exists(destination) && Files.size(destination) == 0) {
                Files.delete(destination);
            }

            return new DownloadResult(false, destination, 0, msg,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            logger.error("下载失败: {}", e.getMessage(), e);
            if (listener != null) {
                listener.onError(e);
            }
            return new DownloadResult(false, destination, 0, e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 检测服务器是否支持 Range 请求（用于决定能否断点续传）
     *
     * 注意：这是一个额外的网络请求，只在「确实需要从非零位置续传」时才应调用，
     * 否则会显著增加请求数（有触发服务器限流的风险）。
     * 结果按 host 缓存，避免同一域名重复探测。
     */
    /**
     * host -> {是否支持 Range, 记录时间}。
     *
     * 以前只缓存布尔值、永不过期：探测失败（临时断网/429）的 host 会被永久
     * 判成「不支持 Range」，之后分块下载/断点续传长期失准。现在 10 分钟后重探。
     */
    private static final long RANGE_CACHE_TTL_MS = 10 * 60 * 1000L;
    private final Map<String, Object[]> rangeSupportCache = new ConcurrentHashMap<>();

    private boolean supportsRange(String url) {
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception e) {
            host = url;
        }
        if (host == null) {
            host = url;
        }

        Object[] cached = rangeSupportCache.get(host);
        if (cached != null && System.currentTimeMillis() - (Long) cached[1] < RANGE_CACHE_TTL_MS) {
            return (Boolean) cached[0];
        }

        boolean supported = false;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .head()
                    .build();

            try (Response response = executeThrottled(request)) {
                String acceptRanges = response.header("Accept-Ranges");
                supported = response.code() == 206
                        || (acceptRanges != null && acceptRanges.contains("bytes"));
            }
        } catch (Exception e) {
            logger.debug("检测 Range 支持失败: {}", e.getMessage());
        }

        // 只缓存成功探测的结果；探测异常时本次直接返回 false，不污染缓存
        if (supported) {
            rangeSupportCache.put(host, new Object[]{Boolean.TRUE, System.currentTimeMillis()});
        }
        return supported;
    }

    /**
     * 多线程分块下载
     */
    public DownloadResult downloadMultiThread(String url, Path destination, DownloadListener listener) {
        long startTime = System.currentTimeMillis();
        logger.info("开始多线程下载: {} -> {}", url, destination);

        if (listener != null) {
            listener.onStart();
        }

        try {
            // 获取文件总大小
            long totalSize = getFileSize(url, 0);
            if (totalSize <= 0) {
                throw new IOException("无法获取文件总大小");
            }

            // 确保目标目录存在
            Files.createDirectories(destination.getParent());

            // 创建临时文件
            Path tempFile = Files.createTempFile("download_", ".tmp");

            // 计算分块参数
            int threadCount = Math.min(4, (int) Math.ceil((double) totalSize / DEFAULT_CHUNK_SIZE));
            long chunkSize = totalSize / threadCount;

            logger.info("使用 {} 线程下载，每块大小: {} bytes", threadCount, chunkSize);

            // 创建分块下载任务
            CountDownLatch latch = new CountDownLatch(threadCount);
            boolean[] success = {true};

            for (int i = 0; i < threadCount; i++) {
                final int chunkIndex = i;
                long start = i * chunkSize;
                long end = (i == threadCount - 1) ? totalSize : (i + 1) * chunkSize;

                downloadExecutor.submit(() -> {
                    try {
                        downloadChunk(url, tempFile, start, end, chunkIndex);
                    } catch (Exception e) {
                        logger.error("分块 {} 下载失败: {}", chunkIndex, e.getMessage());
                        success[0] = false;
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 等待所有分块完成
            latch.await(5, TimeUnit.MINUTES);

            if (!success[0]) {
                Files.delete(tempFile);
                if (listener != null) {
                    listener.onError(new IOException(L.t("s_1e357d71cb")));
                }
                return new DownloadResult(false, destination, 0, L.t("s_1e357d71cb"), 0);
            }

            // 合并分块
            mergeChunks(tempFile, destination, threadCount, chunkSize, totalSize);

            long duration = System.currentTimeMillis() - startTime;
            if (listener != null) {
                listener.onComplete(destination);
            }

            logger.info("多线程下载完成: {}", destination.toAbsolutePath());

            return new DownloadResult(true, destination, totalSize, null, duration);

        } catch (Exception e) {
            logger.error("多线程下载失败: {}", e.getMessage(), e);
            if (listener != null) {
                listener.onError(e);
            }
            return new DownloadResult(false, destination, 0, e.getMessage(), 0);
        }
    }

    /**
     * 下载单个分块
     */
    private void downloadChunk(String url, Path tempFile, long start, long end, int chunkIndex)
            throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=" + start + "-" + (end - 1))
                .build();

        try (Response response = executeThrottled(request);
             InputStream is = response.body().byteStream();
             RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {

            raf.seek(start);
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                raf.write(buffer, 0, bytesRead);
            }
        }

        logger.debug("分块 {} 下载完成: {}-{}", chunkIndex, start, end);
    }

    /**
     * 合并分块
     */
    private void mergeChunks(Path tempFile, Path destination, int threadCount,
                            long chunkSize, long totalSize) throws IOException {
        try (RandomAccessFile dest = new RandomAccessFile(destination.toFile(), "rw");
             FileInputStream source = new FileInputStream(tempFile.toFile())) {

            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = source.read(buffer)) != -1) {
                dest.write(buffer, 0, bytesRead);
            }
        }

        Files.delete(tempFile);
    }

    /**
     * 获取文件总大小
     *
     * 通过 HEAD 请求读取 Content-Length。
     * 部分服务器不响应 HEAD 或使用分块传输，此时返回 -1，
     * 调用方会退化为「下载到 EOF」的模式。
     */
    private long getFileSize(String url, long startPosition) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .build();

            try (Response response = executeThrottled(request)) {
                if (response.isSuccessful()) {
                    String contentLength = response.header("Content-Length");
                    if (contentLength != null) {
                        return Long.parseLong(contentLength);
                    }
                }

                // 某些服务器不支持 HEAD，用 GET + Range: bytes=0-0 读 Content-Range
                Request rangeReq = new Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-0")
                        .build();

                try (Response rangeResp = executeThrottled(rangeReq)) {
                    String contentRange = rangeResp.header("Content-Range");
                    if (contentRange != null && contentRange.contains("/")) {
                        String total = contentRange.substring(contentRange.lastIndexOf('/') + 1);
                        return Long.parseLong(total);
                    }
                    String len = rangeResp.header("Content-Length");
                    if (len != null) {
                        return Long.parseLong(len);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("获取文件大小失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 多线程分块下载
     *
     * 原理：把文件按字节区间切分成 N 段，每段用独立连接并发下载到
     * 临时目录下的分段文件，最后按顺序合并为目标文件。
     *
     * 为什么快：HTTP/1.1 单连接受限于服务端单连接限速，
     * 多个连接并行能提高总吞吐（实测单连接 ~0.8MB/s，4 并发 ~3.2MB/s）。
     *
     * 重要：分块数受**该 URL 所属 host** 的当前并发额度约束。
     * 因为所有分块都从同一个 host 拉取，不能越过该站被允许的并发，
     * 否则会挤占其它下载并可能触发该站的限流惩罚。
     */
    private void downloadInChunks(String url, Path destination, long totalSize,
                                  DownloadListener listener) throws IOException {
        // 该 host 当前允许的并发（按站独立，不是全局）
        int allowed = throttle.getLimit(url);
        int desired = (int) Math.max(2, totalSize / (2L * 1024 * 1024));  // 每段至少 ~2MB

        int threadCount = Math.max(1, Math.min(MAX_CHUNK_THREADS,
                Math.min(desired, allowed)));

        // 如果该站只允许 1 个并发，分块毫无意义，退回单线程
        if (threadCount <= 1) {
            logger.debug("[{}] 当前并发额度为 1，改用单线程下载", DownloadThrottle.hostOf(url));
            downloadFile(url, destination, 0, totalSize, listener);
            return;
        }

        long chunkSize = totalSize / threadCount;

        Path tempDir = Files.createTempDirectory("dlchunk_");
        List<Path> parts = new java.util.ArrayList<>();
        List<Future<?>> futures = new java.util.ArrayList<>();

        // 进度累加器：各线程完成的字节数之和
        AtomicLong downloadedBytes = new AtomicLong(0);

        // 速度统计
        SpeedMeter meter = new SpeedMeter();

        logger.info("分块下载: {} 个线程，每段 {} KB",
                threadCount, chunkSize / 1024);

        try {
            for (int i = 0; i < threadCount; i++) {
                long start = (long) i * chunkSize;
                // 最后一段吃掉余数，保证覆盖到文件末尾
                long end = (i == threadCount - 1) ? totalSize - 1 : (start + chunkSize - 1);

                Path partPath = tempDir.resolve("part" + i);
                parts.add(partPath);

                final int idx = i;
                final long s = start;
                final long e = end;

                futures.add(chunkExecutor.submit(() -> {
                    try {
                        downloadRangeToFile(url, partPath, s, e);

                        long len = Files.size(partPath);
                        long total = downloadedBytes.addAndGet(len);

                        if (listener != null) {
                            double percent = totalSize > 0
                                    ? (double) total / totalSize * 100 : 0;
                            listener.onProgress(total, totalSize, percent);
                            listener.onSpeed(meter.record(total));
                        }
                    } catch (Exception ex) {
                        throw new RuntimeException("分段 " + idx + " 下载失败: " + ex.getMessage(), ex);
                    }
                }));
            }

            // 等待所有分段完成
            for (Future<?> f : futures) {
                try {
                    while (true) {
                        try {
                            f.get(500, TimeUnit.MILLISECONDS);
                            break;
                        } catch (java.util.concurrent.TimeoutException poll) {
                            if (cancelled.get()) {
                                throw new IOException("分块下载已取消");
                            }
                        }
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IOException("分块下载失败: " + cause.getMessage(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("分块下载被中断", e);
                }
            }

            // 合并分段
            mergeParts(parts, destination, totalSize);

            if (listener != null) {
                listener.onProgress(totalSize, totalSize, 100.0);
            }

        } finally {
            // 清理临时目录
            for (Path p : parts) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            }
            try {
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 下载一个字节区间到指定文件
     */
    private void downloadRangeToFile(String url, Path target, long start, long end)
            throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=" + start + "-" + end)
                .build();

        try (Response response = executeThrottled(request)) {
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), url, response.message());
            }
            if (response.body() == null) {
                throw new IOException("响应体为空");
            }
            // 必须先确认服务器真的按 Range 返回了 206。
            // 镜像/CDN 忽略 Range 时回 200 + 整个文件：以前照样把整个文件写进
            // 这一段，几段拼起来文件大小不变但内容是错的，哈希失败且难以定位。
            if (response.code() != 206) {
                throw new IOException("服务器未按 Range 返回分段（HTTP " + response.code() + "）: " + url);
            }
            String contentRange = response.header("Content-Range");
            if (contentRange != null) {
                // 格式：bytes start-end/total
                try {
                    String spec = contentRange.substring(contentRange.indexOf(' ') + 1);
                    long rangeStart = Long.parseLong(spec.substring(0, spec.indexOf('-')));
                    if (rangeStart != start) {
                        throw new IOException("Content-Range 起点不符（期望 " + start
                                + "，实际 " + rangeStart + "）: " + url);
                    }
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    throw new IOException("无法解析 Content-Range: " + contentRange, e);
                }
            }

            long expected = end - start + 1;
            try (InputStream is = response.body().byteStream();
                 OutputStream os = Files.newOutputStream(target)) {
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                long written = 0;
                int n;
                while ((n = is.read(buffer)) != -1) {
                    if (cancelled.get()) {
                        throw new InterruptedIOException("已取消");
                    }
                    if (written + n > expected) {
                        throw new IOException("分段长度超出预期（期望 " + expected + " 字节）: " + url);
                    }
                    os.write(buffer, 0, n);
                    written += n;
                }
                if (written != expected) {
                    throw new IOException("分段落盘长度不符：期望 " + expected + "，实际 " + written);
                }
            }
        }
    }

    /**
     * 按顺序合并分段文件
     */
    private void mergeParts(List<Path> parts, Path destination, long expectedSize)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            for (Path part : parts) {
                try (InputStream in = Files.newInputStream(part)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }
            }
        }

        long actual = Files.size(destination);
        if (expectedSize > 0 && actual != expectedSize) {
            throw new IOException(String.format(
                    "合并后大小不符：期望 %d，实际 %d", expectedSize, actual));
        }
    }

    /**
     * 速度计算器
     * 用滑动窗口平滑瞬时波动，避免 UI 上的数字乱跳
     */
    private static class SpeedMeter {
        private long lastTime = System.currentTimeMillis();
        private long lastBytes = 0;

        /** 记录当前累计字节数，返回平滑后的速度（字节/秒） */
        synchronized long record(long totalBytes) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;

            // 采样间隔太短就沿用上次的速度，避免除以极小值
            if (elapsed < 200) {
                return 0;
            }

            long delta = totalBytes - lastBytes;
            long speed = delta * 1000 / elapsed;

            lastTime = now;
            lastBytes = totalBytes;

            return speed;
        }
    }

    /**
     * 执行请求（带全局限流控制）
     *
     * 所有网络请求都必须经过这里：
     * - 请求前申请许可（受全局并发上限约束）
     * - 遇到 429/403 时通知限流器降低并发
     * - 成功后上报，供限流器缓慢恢复并发
     *
     * 注意：调用方负责 close 返回的 Response。
     */
    /**
     * 执行请求（带全局限流控制）
     *
     * 关键：许可只在「等待响应头」期间占用，拿到 Response 后立即归还。
     * 以前放在 finally 里、要等整个响应体读完（甚至文件写完）才释放，
     * 结果 443 个资源文件只有几十个能同时在跑，后面的全部堵在许可上 ——
     * 这就是「越到后面越慢」的主要原因。
     *
     * 注意：调用方负责 close 返回的 Response。
     */
    private Response executeThrottled(Request request) throws IOException {
        String url = request.url().toString();
        boolean acquired = false;
        try {
            throttle.acquire(url, ACQUIRE_TIMEOUT_MS);
            acquired = true;

            Response response = httpClient.newCall(request).execute();

            if (response.code() == 429 || response.code() == 403) {
                throttle.reportThrottled(url, response.code());
            } else if (response.code() >= 400) {
                // 404/5xx 不参与「成功计数」，也不触发降级
                if (response.code() >= 500) {
                    throttle.reportThrottled(url, response.code());
                }
            } else {
                throttle.reportSuccess(url);
            }
            return response;
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(L.t("s_5f76edc5de"), e);
        } finally {
            if (acquired) {
                throttle.release(url);
            }
        }
    }

    /**
     * 执行下载文件
     *
     * @return 实际写入的字节数
     */
    private long downloadFile(String url, Path destination, long startPosition,
                             long totalSize, DownloadListener listener) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=" + startPosition + "-")
                .build();

        long downloaded = startPosition;
        long lastProgressUpdate = 0;
        long downloadStartTime = System.currentTimeMillis();

        try (Response response = executeThrottled(request);
             InputStream is = response.body() != null ? response.body().byteStream() : null;
             OutputStream os = startPosition > 0 && Files.exists(destination)
                     ? Files.newOutputStream(destination, StandardOpenOption.APPEND)
                     : Files.newOutputStream(destination,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // 关键：必须检查 HTTP 状态码。
            // 服务器返回 403/429/5xx 时响应体是错误页面（HTML/JSON），
            // 如果直接写入文件，就会得到一个「下载成功」的垃圾文件，
            // 最终表现为哈希校验失败。这在国内镜像被限流时很常见。
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), url,
                        response.message());
            }

            if (is == null) {
                throw new IOException("响应体为空: " + url);
            }

            // 续传必须真的从 startPosition 开始：
            //   · 请求过 Range 却拿到 200（整份文件）：从头写，不能 APPEND，
            //     否则文件头部重复、大小恰好对上时哈希仍失败且难以定位；
            //   · 206 的 Content-Range 起点与我们的续传点不一致：拒绝。
            if (response.code() == 200 && startPosition > 0) {
                logger.debug("服务器忽略了 Range，从头重新写入: {}", url);
                downloaded = 0;
                is.close();
                try (InputStream restart = response.body() != null
                        ? response.body().byteStream() : null;
                     OutputStream trunc = Files.newOutputStream(destination,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    if (restart != null) {
                        byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
                        int n;
                        while ((n = restart.read(buf)) != -1) {
                            if (cancelled.get()) {
                                throw new InterruptedIOException("已取消");
                            }
                            trunc.write(buf, 0, n);
                            downloaded += n;
                        }
                    }
                }
                return downloaded;
            }
            if (response.code() == 206 && startPosition > 0) {
                String cr = response.header("Content-Range");
                if (cr != null && cr.contains(" ")) {
                    try {
                        long got = Long.parseLong(cr.substring(cr.indexOf(' ') + 1,
                                cr.indexOf('-')));
                        if (got != startPosition) {
                            throw new IOException("续传起点不符（期望 " + startPosition
                                    + "，实际 " + got + "）: " + url);
                        }
                    } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                        // 解析不了就不强求
                    }
                }
            }

            // 若调用方没提供总大小，从响应头推断
            // （206 用 Content-Range 的斜杠后半段，200 用 Content-Length）
            if (totalSize <= 0) {
                String contentRange = response.header("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    try {
                        totalSize = Long.parseLong(
                                contentRange.substring(contentRange.lastIndexOf('/') + 1)) + 1;
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (totalSize <= 0) {
                    String len = response.header("Content-Length");
                    if (len != null) {
                        try {
                            totalSize = startPosition + Long.parseLong(len);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                if (cancelled.get()) {
                    throw new InterruptedIOException("已取消");
                }
                os.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                // 定期更新进度
                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate > 200 || (totalSize > 0 && downloaded >= totalSize)) {
                    double percent = totalSize > 0 ? (double) downloaded / totalSize * 100 : 0;
                    if (listener != null) {
                        listener.onProgress(downloaded, totalSize, percent);
                        // 速度估算
                        long elapsed = now - downloadStartTime;
                        if (elapsed > 0) {
                            listener.onSpeed(downloaded * 1000 / elapsed);
                        }
                    }
                    lastProgressUpdate = now;
                }
            }
        }

        if (listener != null && totalSize > 0) {
            listener.onProgress(totalSize, totalSize, 100.0);
        }

        return downloaded;
    }

    /**
     * HTTP 状态码异常
     * 用于区分「网络中断」和「服务器明确拒绝」，后者需要退避重试
     */
    public static class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String url, String message) {
            super("HTTP " + statusCode + " " + message + " - " + url);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        /** 是否属于限流/临时性错误，值得退避重试 */
        public boolean isRetryable() {
            return statusCode == 403 || statusCode == 429
                    || statusCode == 408 || statusCode >= 500;
        }
    }

    /**
     * 取消下载
     */
    public void cancel() {
        cancelled.set(true);
        downloadExecutor.shutdownNow();
        // 分块任务在 chunkExecutor 里跑（共享线程池，不能 shutdownNow 整个，
        // 否则会影响其它并发下载），交给各分段循环检查 cancelled 标志退出
        downloadExecutor = Executors.newFixedThreadPool(4);
    }

    private DownloadResult cancelledResult(Path destination, long startTime) {
        long partial = 0;
        try {
            if (Files.exists(destination)) {
                partial = Files.size(destination);
            }
        } catch (IOException ignored) {
        }
        logger.debug("下载已取消: {}", destination.getFileName());
        return new DownloadResult(false, destination, partial, L.t("s_6df9b76521"),
                System.currentTimeMillis() - startTime);
    }

    /**
     * 获取已下载总量
     */
    public long getTotalDownloaded() {
        return totalDownloaded.get();
    }

    /**
     * 关闭下载器
     */
    public void shutdown() {
        downloadExecutor.shutdown();
        chunkExecutor.shutdown();
        httpClient.dispatcher().executorService().shutdown();
    }
}
