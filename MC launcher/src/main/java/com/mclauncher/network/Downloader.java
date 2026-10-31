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

public class Downloader {

    private static final Logger logger = LoggerFactory.getLogger(Downloader.class);

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;  

    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 300_000;   
    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; 

    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 1000;

    private static final long MULTITHREAD_THRESHOLD = 8L * 1024 * 1024;  

    private static final int MAX_CHUNK_THREADS = 16;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private ExecutorService downloadExecutor;
    private final ExecutorService chunkExecutor;
    private final AtomicLong totalDownloaded;

    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final DownloadThrottle throttle = DownloadThrottle.getInstance();

    public interface DownloadListener {
        void onProgress(long downloaded, long total, double percent);
        void onComplete(Path destination);
        void onError(Exception e);
        void onStart();

        default void onSpeed(long bytesPerSecond) {}
    }

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

    public Downloader() {
        this(CONNECT_TIMEOUT);
    }

    public Downloader(int connectTimeoutMs) {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.downloadExecutor = Executors.newFixedThreadPool(4);
        this.chunkExecutor = Executors.newFixedThreadPool(MAX_CHUNK_THREADS);
        this.totalDownloaded = new AtomicLong(0);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)

                .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)

                

                .protocols(java.util.Arrays.asList(Protocol.HTTP_1_1))

                .followRedirects(true)
                .followSslRedirects(true)

                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder builder = original.newBuilder()

                            
                            .header("User-Agent", NetworkConstants.USER_AGENT)
                            .header("Accept", NetworkConstants.ACCEPT);

                    String range = original.header("Range");
                    if (range != null) {
                        builder.header("Range", range);
                    }

                    return chain.proceed(builder.build());
                })
                .build();
    }

    public void setProxy(Proxy proxy) {
        this.httpClient.newBuilder()
                .proxy(proxy)
                .build();
    }

    public DownloadResult download(String url, Path destination, DownloadListener listener) {
        return download(url, destination, listener, false);
    }

    public DownloadResult downloadAsync(String url, Path destination, DownloadListener listener) {
        CompletableFuture<DownloadResult> future = CompletableFuture.supplyAsync(() ->
                download(url, destination, listener, false), downloadExecutor);
        return future.join();
    }

    public DownloadResult download(String url, Path destination, DownloadListener listener,
                                   boolean resume) {
        return download(url, destination, listener, resume, -1);
    }

    public DownloadResult download(String url, Path destination, boolean resume,
                                   long expectedSize, DownloadListener listener) {
        return download(url, destination, listener, resume, expectedSize);
    }

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

                

                long startPosition = 0;
                if (Files.exists(destination)) {
                    long existing = Files.size(destination);
                    if (existing > 0) {
                        if (attempt > 1 || resume) {
                            startPosition = existing;
                        } else {

                            Files.delete(destination);
                        }
                    }
                }

                
                if (startPosition > 0 && !supportsRange(url)) {
                    logger.debug("服务器不支持 Range，从头下载");
                    startPosition = 0;
                    Files.deleteIfExists(destination);
                }

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

                        if (totalSize <= 0) {
                            totalSize = written;
                        }
                    }

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

                    

                    

                    

                    

                    boolean isThrottled = e instanceof HttpStatusException
                            && ((HttpStatusException) e).isRetryable();

                    logger.warn("下载中断（第 {}/{} 次，已获取 {} 字节）: {}",
                            attempt, MAX_RETRIES, partial, e.getMessage());

                    
                    boolean permanent = e instanceof HttpStatusException
                            && !((HttpStatusException) e).isRetryable();
                    if (permanent) {
                        logger.debug("该地址资源不存在，跳过重试: {}", url);
                        break;
                    }

                    if (attempt < MAX_RETRIES) {

                        long backoff;
                        if (isThrottled) {
                            backoff = Math.max(
                                    RETRY_DELAY_MS * attempt * 5L,
                                    throttle.isThrottled(url) ? 5_000L : 0L);
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

                            

                            Thread.currentThread().interrupt();
                            logger.debug("下载任务被取消: {}", destination.getFileName());
                            return new DownloadResult(false, destination, partial,
                                    L.t("s_6df9b76521"), System.currentTimeMillis() - startTime);
                        }
                    }
                }
            }

            String msg = lastError != null ? lastError.getMessage() : L.t("s_5f76edc5de");
            logger.error("下载失败（已重试 {} 次）: {}", MAX_RETRIES, msg);

            if (listener != null) {
                listener.onError(lastError != null ? lastError : new IOException(L.t("s_e0dab22b1a")));
            }

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

    private final Map<String, Boolean> rangeSupportCache = new ConcurrentHashMap<>();

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

        Boolean cached = rangeSupportCache.get(host);
        if (cached != null) {
            return cached;
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

        rangeSupportCache.put(host, supported);
        return supported;
    }

    public DownloadResult downloadMultiThread(String url, Path destination, DownloadListener listener) {
        long startTime = System.currentTimeMillis();
        logger.info("开始多线程下载: {} -> {}", url, destination);

        if (listener != null) {
            listener.onStart();
        }

        try {

            long totalSize = getFileSize(url, 0);
            if (totalSize <= 0) {
                throw new IOException("无法获取文件总大小");
            }

            Files.createDirectories(destination.getParent());

            Path tempFile = Files.createTempFile("download_", ".tmp");

            int threadCount = Math.min(4, (int) Math.ceil((double) totalSize / DEFAULT_CHUNK_SIZE));
            long chunkSize = totalSize / threadCount;

            logger.info("使用 {} 线程下载，每块大小: {} bytes", threadCount, chunkSize);

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

            latch.await(5, TimeUnit.MINUTES);

            if (!success[0]) {
                Files.delete(tempFile);
                if (listener != null) {
                    listener.onError(new IOException(L.t("s_1e357d71cb")));
                }
                return new DownloadResult(false, destination, 0, L.t("s_1e357d71cb"), 0);
            }

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

    private void downloadInChunks(String url, Path destination, long totalSize,
                                  DownloadListener listener) throws IOException {

        int allowed = throttle.getLimit(url);
        int desired = (int) Math.max(2, totalSize / (2L * 1024 * 1024));  

        int threadCount = Math.max(1, Math.min(MAX_CHUNK_THREADS,
                Math.min(desired, allowed)));

        if (threadCount <= 1) {
            logger.debug("[{}] 当前并发额度为 1，改用单线程下载", DownloadThrottle.hostOf(url));
            downloadFile(url, destination, 0, totalSize, listener);
            return;
        }

        long chunkSize = totalSize / threadCount;

        Path tempDir = Files.createTempDirectory("dlchunk_");
        List<Path> parts = new java.util.ArrayList<>();
        List<Future<?>> futures = new java.util.ArrayList<>();

        AtomicLong downloadedBytes = new AtomicLong(0);

        SpeedMeter meter = new SpeedMeter();

        logger.info("分块下载: {} 个线程，每段 {} KB",
                threadCount, chunkSize / 1024);

        try {
            for (int i = 0; i < threadCount; i++) {
                long start = (long) i * chunkSize;

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

            mergeParts(parts, destination, totalSize);

            if (listener != null) {
                listener.onProgress(totalSize, totalSize, 100.0);
            }

        } finally {

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

            

            if (response.code() != 206) {
                throw new IOException("服务器未按 Range 返回分段（HTTP " + response.code() + "）: " + url);
            }
            String contentRange = response.header("Content-Range");
            if (contentRange != null) {

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

    private static class SpeedMeter {
        private long lastTime = System.currentTimeMillis();
        private long lastBytes = 0;

        synchronized long record(long totalBytes) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;

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

    private Response executeThrottled(Request request) throws IOException {
        String url = request.url().toString();
        try {

            
            throttle.acquire(url, 60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载许可等待超时或已取消", e);
        }

        boolean success = false;
        try {
            Response response = httpClient.newCall(request).execute();

            if (response.code() == 429 || response.code() == 403) {

                throttle.reportThrottled(url, response.code());
            } else {
                success = true;
                throttle.reportSuccess(url);
            }

            return response;
        } finally {
            throttle.release(url);

            if (!success) {

            }
        }
    }

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
             OutputStream os = Files.exists(destination)
                     ? Files.newOutputStream(destination, StandardOpenOption.APPEND)
                     : Files.newOutputStream(destination)) {

            

            
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), url,
                        response.message());
            }

            if (is == null) {
                throw new IOException("响应体为空: " + url);
            }

            
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

                long now = System.currentTimeMillis();
                if (now - lastProgressUpdate > 200 || (totalSize > 0 && downloaded >= totalSize)) {
                    double percent = totalSize > 0 ? (double) downloaded / totalSize * 100 : 0;
                    if (listener != null) {
                        listener.onProgress(downloaded, totalSize, percent);

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

    public static class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String url, String message) {
            super("HTTP " + statusCode + " " + message + " - " + url);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isRetryable() {
            return statusCode == 403 || statusCode == 429
                    || statusCode == 408 || statusCode >= 500;
        }
    }

    public void cancel() {
        cancelled.set(true);
        downloadExecutor.shutdownNow();

        
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

    public long getTotalDownloaded() {
        return totalDownloaded.get();
    }

    public void shutdown() {
        downloadExecutor.shutdown();
        chunkExecutor.shutdown();
        httpClient.dispatcher().executorService().shutdown();
    }
}
