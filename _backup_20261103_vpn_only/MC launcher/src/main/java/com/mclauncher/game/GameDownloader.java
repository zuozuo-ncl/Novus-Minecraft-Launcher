package com.mclauncher.game;


import com.mclauncher.i18n.L;
import com.google.gson.*;
import com.mclauncher.network.BMCLApiClient;
import com.mclauncher.network.Downloader;
import com.mclauncher.network.MirrorPool;
import com.mclauncher.utils.FileUtils;
import com.mclauncher.utils.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏下载管理器
 * 负责下载游戏本体、库文件、资源文件
 */
public class GameDownloader {

    private static final Logger logger = LoggerFactory.getLogger(GameDownloader.class);

    private final BMCLApiClient apiClient;
    private final Downloader downloader;
    private final Path gameDir;
    private final Gson gson;

    // 进度回调
    private ProgressCallback callback;
    private volatile boolean cancelled = false;

    // 下载统计
    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger completedFiles = new AtomicInteger(0);
    private final AtomicInteger failedFiles = new AtomicInteger(0);

    /** 本次 downloadVersion 是否有致命缺失（库 / natives / 资源）。 */
    private volatile boolean downloadFailed = false;

    /** 只取异常消息第一行（Gson 的消息可能很长） */
    private static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int i = msg.indexOf((char) 10);
        return i < 0 ? msg : msg.substring(0, i);
    }

    public GameDownloader(BMCLApiClient apiClient, Path gameDir) {
        this.apiClient = apiClient;
        this.downloader = new Downloader();
        this.gameDir = gameDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * 设置进度回调
     */
    public void setCallback(ProgressCallback callback) {
        this.callback = callback;
    }

    /**
     * 下载游戏版本
     *
     * @param versionId 版本 ID
     * @return 是否成功
     */
    public boolean downloadVersion(String versionId) {
        cancelled = false;
        downloadFailed = false;
        try {
            reportProgress(0, 100, L.t("s_a0a16feb25") + versionId);

            // 1. 获取版本清单
            reportProgress(2, 100, L.t("s_7ff65f460d"));
            BMCLApiClient.VersionManifest manifest = apiClient.getVersionManifest();

            BMCLApiClient.VersionManifest.Version versionInfo = null;
            for (BMCLApiClient.VersionManifest.Version v : manifest.getVersions()) {
                if (v.getId().equals(versionId)) {
                    versionInfo = v;
                    break;
                }
            }

            if (versionInfo == null) {
                throw new IOException("找不到版本: " + versionId);
            }

            // 2. 获取版本详细 JSON
            reportProgress(5, 100, L.t("s_afd33fb63d"));
            String versionJson = apiClient.getVersionDetailJson(versionInfo.getUrl());
            VersionInfo info;
            try {
                info = gson.fromJson(versionJson, VersionInfo.class);
            } catch (RuntimeException e) {
                // 某个字段类型与强类型模型不符时整份 JSON 都会解析失败：
                // 以前直接抛出去，日志里只有一句「JsonSyntaxException」，无从下手
                throw new IOException("版本 " + versionId + " 的 JSON 格式异常（"
                        + e.getClass().getSimpleName() + "）: " + firstLine(e.getMessage()), e);
            }
            if (info == null) {
                throw new IOException("版本 " + versionId + " 的 JSON 为空");
            }

            // 3. 保存版本 JSON
            Path versionDir = gameDir.resolve("versions").resolve(versionId);
            Files.createDirectories(versionDir);

            Path versionJsonPath = versionDir.resolve(versionId + ".json");
            Files.writeString(versionJsonPath, versionJson, StandardCharsets.UTF_8);
            logger.info("版本 JSON 已保存: {}", versionJsonPath);

            // 4. 下载客户端 JAR
            reportProgress(10, 100, L.t("s_9a265de912"));
            downloadClientJar(versionId, versionDir, info);

            // 5. 下载库文件
            reportProgress(40, 100, L.t("s_173b5acb54"));
            downloadLibraries(info);

            // 6. 解压 Natives
            reportProgress(70, 100, L.t("s_407f10aec4"));
            downloadNativeLibraries(info);
            extractNatives(info, versionDir);

            // 7. 下载资源文件
            reportProgress(80, 100, L.t("s_454d067676"));
            downloadAssets(info);

            if (downloadFailed) {
                // 缺库 / 缺 natives / 缺资源，游戏起来必然崩，不能报成功
                logger.error("版本 {} 下载不完整（失败文件数 {}）", versionId, failedFiles.get());
                reportError("下载不完整: 有 " + failedFiles.get() + " 个文件下载失败，请重试");
                return false;
            }

            reportProgress(100, 100, L.t("s_4bbcf94739"));
            logger.info("版本 {} 下载完成", versionId);
            return true;

        } catch (Exception e) {
            logger.error("下载版本失败: {}", e.getMessage(), e);
            reportError("下载失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 下载客户端 JAR
     */
    private void downloadClientJar(String versionId, Path versionDir, VersionInfo info)
            throws IOException {
        Path jarPath = versionDir.resolve(versionId + ".jar");

        // 优先使用版本 JSON 中的 URL
        String downloadUrl = null;
        String expectedSha1 = null;
        long expectedSize = -1;

        if (info.getDownloads() != null && info.getDownloads().getClient() != null) {
            downloadUrl = info.getDownloads().getClient().getUrl();
            expectedSha1 = info.getDownloads().getClient().getSha1();
            // 版本 JSON 里有客户端 JAR 的确切大小，传下去可省一次探测往返
            // 并让大文件直接走多线程分块下载
            expectedSize = info.getDownloads().getClient().getSize();
            if (downloadUrl != null) {
                downloadUrl = apiClient.convertToMirror(downloadUrl);
            }
        }

        if (downloadUrl == null || downloadUrl.isBlank()) {
            downloadUrl = apiClient.getClientDownloadUrl(versionId);
        }

        // 已存在且校验通过 -> 跳过
        if (Files.exists(jarPath) && Files.size(jarPath) > 0) {
            if (expectedSha1 == null || expectedSha1.isBlank()
                    || expectedSha1.equalsIgnoreCase(FileUtils.sha1(jarPath))) {
                logger.info("客户端 JAR 已存在且校验通过: {}", jarPath);
                return;
            }
            logger.warn("客户端 JAR 校验失败，重新下载");
            Files.deleteIfExists(jarPath);
        }

        logger.info("下载客户端 JAR: {}", downloadUrl);

        reportDetail(versionId + ".jar", 0, -1);

        Downloader.DownloadResult result = downloader.download(downloadUrl, jarPath, false, expectedSize,
                new Downloader.DownloadListener() {
                    @Override
                    public void onProgress(long downloaded, long total, double percent) {
                        reportProgress(10 + (int) (percent * 0.3), 100, L.t("s_f5662b62d3"));
                    }

                    @Override
                    public void onSpeed(long bytesPerSecond) {
                        reportDetail(versionId + ".jar", bytesPerSecond, -1);
                    }

                    @Override
                    public void onComplete(Path destination) {}

                    @Override
                    public void onError(Exception e) {}

                    @Override
                    public void onStart() {}
                });

        if (!result.isSuccess()) {
            throw new IOException("客户端 JAR 下载失败: " + result.getErrorMessage());
        }

        // 校验
        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            String actual = FileUtils.sha1(jarPath);
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(jarPath);
                throw new IOException("客户端 JAR 校验失败（SHA-1 不匹配）");
            }
        }
    }

    /**
     * 下载库文件
     */
    private void downloadLibraries(VersionInfo info) throws IOException {
        if (info.getLibraries() == null || info.getLibraries().isEmpty()) {
            logger.info("没有需要下载的库文件");
            return;
        }

        List<VersionInfo.Library> applicableLibraries = new ArrayList<>();

        for (VersionInfo.Library library : info.getLibraries()) {
            if (isLibraryApplicable(library)) {
                applicableLibraries.add(library);
            }
        }

        totalFiles.set(applicableLibraries.size());
        completedFiles.set(0);
        failedFiles.set(0);

        logger.info("需要下载 {} 个库文件", applicableLibraries.size());

        // 并发下载库文件。
        //
        // 线程池可以开得比较大，因为真正限制并发的是 DownloadThrottle：
        // - 它按 host 独立限流（每站初始 4，上限 16）
        // - 全局总上限 64
        // 文件会被轮询分配到 5 个高校镜像站，因此总并发能叠加到几十，
        // 而每个站实际承受的并发仍在其安全范围内。
        ExecutorService executor = Executors.newFixedThreadPool(32);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (VersionInfo.Library library : applicableLibraries) {
            futures.add(executor.submit(() -> downloadLibrary(library)));
        }

        // 等待所有下载完成
        int libraryFailed = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (!Boolean.TRUE.equals(future.get(2, TimeUnit.MINUTES))) {
                    libraryFailed++;
                }
            } catch (TimeoutException e) {
                // 单个文件超时不应卡住整个下载流程
                logger.warn("库文件下载超时（已取消）");
                future.cancel(true);
                libraryFailed++;
            } catch (Exception e) {
                logger.warn("库文件下载任务异常: {}", e.getMessage());
                libraryFailed++;
            }
        }

        executor.shutdown();

        logger.info("库文件下载完成: {}/{}", completedFiles.get(), totalFiles.get());

        // 库文件 / natives 缺失时游戏必然启动失败（UnsatisfiedLinkError），
        // 必须让上层知道，不能静默当作下载成功
        downloadFailed = failedFiles.get() > 0 || libraryFailed > 0;
    }

    /**
     * 下载 natives JAR（downloads.classifiers 里的独立文件）。
     *
     * 旧版（≈1.13 以前，LWJGL 2）的 natives 不在主 JAR 里，而是
     * downloads.classifiers["natives-windows"]（以及 -64）。以前只下载主 JAR，
     * extractNatives 找不到文件就静默跳过，启动后 lwjgl.dll 缺失直接崩溃。
     *
     * 新格式（LWJGL 3.3+）没有 classifier 依赖，对应的库名本身就以
     * :natives-xxx 结尾，会作为普通库被 downloadLibrary 下载，这里不处理。
     */
    private void downloadNativeLibraries(VersionInfo info) {
        if (info.getLibraries() == null) {
            return;
        }

        for (VersionInfo.Library library : info.getLibraries()) {
            if (cancelled) {
                return;
            }
            if (library.getNatives() == null || library.getNatives().isEmpty() || !isLibraryApplicable(library)) {
                continue;
            }

            String classifier = pickNativeClassifier(library);
            if (classifier == null) {
                logger.warn("无法确定 natives classifier，跳过: {}", library.getName());
                downloadFailed = true;
                continue;
            }

            // 有 downloads.classifiers 时以其中实际存在的路径 / 地址 / 哈希为准
            VersionInfo.DownloadInfo target = null;
            String mavenPath = null;
            if (library.getDownloads() != null && library.getDownloads().getClassifiers() != null) {
                VersionInfo.DownloadInfo dl = library.getDownloads().getClassifiers().get(classifier);
                if (dl != null) {
                    target = dl;
                    mavenPath = dl.getPath() != null
                            ? VersionInfo.Library.safeRelativePath(dl.getPath())
                            : VersionInfo.Library.safeRelativePath(getNativesMavenPath(library.getName(), classifier));
                }
            }
            if (mavenPath == null) {
                mavenPath = VersionInfo.Library.safeRelativePath(
                        getNativesMavenPath(library.getName(), classifier));
            }
            if (mavenPath == null) {
                logger.warn("natives 路径非法，跳过: {}", library.getName());
                downloadFailed = true;
                continue;
            }

            Path nativesJar = gameDir.resolve("libraries").resolve(mavenPath);
            String expectedSha1 = target != null ? target.getSha1() : null;
            try {
                boolean ok = Files.exists(nativesJar) && Files.size(nativesJar) > 0
                        && (expectedSha1 == null || expectedSha1.isBlank()
                            || expectedSha1.equalsIgnoreCase(FileUtils.sha1(nativesJar)));
                if (ok) {
                    continue;
                }
                Files.deleteIfExists(nativesJar);

                reportDetail(library.getName() + " (" + classifier + ")", 0,
                        Math.max(0, totalFiles.get() - completedFiles.get()));

                Downloader.DownloadResult result = null;
                String fileName = mavenPath.substring(mavenPath.lastIndexOf('/') + 1);

                MirrorPool mirrorPool = MirrorPool.getInstance();
                if (mirrorPool.isEnabled()) {
                    MirrorPool.Mirror mirror = mirrorPool.next();
                    if (mirror != null) {
                        String mirrorUrl = mirrorPool.buildUrl(mirror, mavenPath);
                        result = downloader.download(mirrorUrl, nativesJar, speedListener(fileName));
                        if (result.isSuccess()) {
                            mirrorPool.reportSuccess(mirror);
                        } else {
                            mirrorPool.reportFailure(mirror);
                            Files.deleteIfExists(nativesJar);
                        }
                    }
                }

                if (result == null || !result.isSuccess()) {
                    List<String> urls = new ArrayList<>();
                    if (target != null && target.getUrl() != null && !target.getUrl().isBlank()) {
                        urls.add(apiClient.convertToMirror(target.getUrl()));
                    }
                    urls.add("https://libraries.minecraft.net/" + mavenPath);
                    for (String url : urls) {
                        result = downloader.download(url, nativesJar, speedListener(fileName));
                        if (result.isSuccess()) {
                            break;
                        }
                    }
                }

                if (result == null || !result.isSuccess()) {
                    logger.warn("natives 下载失败: {} ({})", library.getName(), classifier);
                    failedFiles.incrementAndGet();
                    downloadFailed = true;
                    continue;
                }

                if (expectedSha1 != null && !expectedSha1.isBlank()
                        && !expectedSha1.equalsIgnoreCase(FileUtils.sha1(nativesJar))) {
                    logger.warn("natives 哈希不匹配: {}", nativesJar);
                    Files.deleteIfExists(nativesJar);
                    failedFiles.incrementAndGet();
                    downloadFailed = true;
                }
            } catch (Exception e) {
                logger.warn("natives 下载异常: {} - {}", library.getName(), e.getMessage());
                failedFiles.incrementAndGet();
                downloadFailed = true;
            }
        }
    }

    /**
     * 按当前平台与架构选择 natives classifier。
     *
     * 老版本 JSON 写的是 "natives-windows"，64 位与 32 位各一个 classifier
     * （natives-windows-64 / natives-windows-32）；优先取与架构匹配的那个。
     */
    private String pickNativeClassifier(VersionInfo.Library library) {
        String base = library.getNatives().get(getCurrentOsName());
        if (base == null || base.isBlank()) {
            return null;
        }
        boolean is64 = "x86_64".equals(getCurrentArch()) || "arm64".equals(getCurrentArch());
        String archSuffix = is64 ? "-64" : "-32";

        VersionInfo.LibraryDownloads downloads = library.getDownloads();
        Map<String, VersionInfo.DownloadInfo> classifiers =
                downloads != null ? downloads.getClassifiers() : null;
        if (classifiers != null && !classifiers.isEmpty()) {
            // 以 JSON 中实际存在的 key 为准，不硬拼
            if (classifiers.containsKey(base + archSuffix)) {
                return base + archSuffix;
            }
            if (classifiers.containsKey(base)) {
                return base;
            }
            String other = is64 ? "-32" : "-64";
            if (classifiers.containsKey(base + other)) {
                return base + other;
            }
            // key 不以 base 开头时（极少见），取第一个 windows 相关的
            for (String key : classifiers.keySet()) {
                if (key.contains(getCurrentOsName())) {
                    return key;
                }
            }
        }
        // 没有 classifiers 信息：按 Mojang 的命名习惯推导
        if (base.contains("${arch}")) {
            return base.replace("${arch}", is64 ? "64" : "32");
        }
        return base + archSuffix;
    }

    /**
     * 下载单个库文件
     */
    private boolean downloadLibrary(VersionInfo.Library library) {
        if (cancelled) return false;

        try {
            String mavenPath = library.getMavenPath();
            if (mavenPath == null) {
                logger.warn("库文件路径非法，跳过: {} ({})", library.getName(),
                        library.getDownloads() != null && library.getDownloads().getArtifact() != null
                                ? library.getDownloads().getArtifact().getPath() : null);
                failedFiles.incrementAndGet();
                return false;
            }
            // 主 JAR 只负责进入 classpath，不负责解压 natives；
            // natives 是 downloads.classifiers 里的单独 JAR，由 downloadNativeLibraries 处理
            Path libraryPath = gameDir.resolve("libraries").resolve(mavenPath);

            // 获取期望的 SHA-1（用于完整性校验）
            String expectedSha1 = null;
            String downloadUrl = null;

            if (library.getDownloads() != null && library.getDownloads().getArtifact() != null) {
                VersionInfo.DownloadInfo artifact = library.getDownloads().getArtifact();
                expectedSha1 = artifact.getSha1();
                downloadUrl = artifact.getUrl();
            }

            // 已存在且校验通过 -> 跳过
            if (Files.exists(libraryPath) && Files.size(libraryPath) > 0) {
                if (expectedSha1 == null || expectedSha1.isBlank()
                        || expectedSha1.equalsIgnoreCase(FileUtils.sha1(libraryPath))) {
                    completedFiles.incrementAndGet();
                    updateLibraryProgress(library.getName());
                    return true;
                }
                // 校验不通过，删除后重新下载
                logger.warn("库文件校验失败，重新下载: {}", library.getName());
                Files.deleteIfExists(libraryPath);
            }

            // 上报当前文件信息，供 UI 展示
            String displayName = library.getName();
            int remaining = Math.max(0, totalFiles.get() - completedFiles.get());
            reportDetail(displayName, 0, remaining);

            // ===== 确定下载地址 =====
            //
            // 优先级：
            // 1. 高校镜像池（实测 3-8 MB/s，无连接数限流）
            // 2. BMCLAPI 官方节点 / 官方源（兜底）
            //
            // 注意：镜像池的路径是「根路径 + Maven 坐标」（无 /maven/ 前缀），
            // 与 BMCLAPI 官方节点的结构不同。镜像站不支持 fabric-meta 等
            // API，但库文件和 assets 都支持。
            Downloader.DownloadResult result = null;
            MirrorPool mirrorPool = MirrorPool.getInstance();

            if (mirrorPool.isEnabled()) {
                // 轮询选取镜像，把不同文件分散到不同站点
                MirrorPool.Mirror mirror = mirrorPool.next();
                if (mirror != null) {
                    String mirrorUrl = mirrorPool.buildUrl(mirror, mavenPath);
                    result = downloader.download(mirrorUrl, libraryPath, speedListener(displayName));

                    if (result.isSuccess()) {
                        mirrorPool.reportSuccess(mirror);
                    } else {
                        mirrorPool.reportFailure(mirror);
                        logger.debug("镜像 {} 下载失败，回退其它源: {}",
                                mirror.getName(), library.getName());
                        try {
                            Files.deleteIfExists(libraryPath);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }

            // 兜底：BMCLAPI 官方节点 或 官方源
            if (result == null || !result.isSuccess()) {
                if (downloadUrl == null || downloadUrl.isBlank()) {
                    downloadUrl = apiClient.getLibraryDownloadUrl(mavenPath);
                } else {
                    downloadUrl = apiClient.convertToMirror(downloadUrl);
                }
                result = downloader.download(downloadUrl, libraryPath, speedListener(displayName));
            }

            // 最后兜底：libraries.minecraft.net
            if (!result.isSuccess()) {
                String fallback = "https://libraries.minecraft.net/" + mavenPath;
                if (!fallback.equals(downloadUrl)) {
                    result = downloader.download(fallback, libraryPath, speedListener(displayName));
                }
            }

            if (!result.isSuccess()) {
                failedFiles.incrementAndGet();
                logger.warn("库文件下载失败: {} - {}", library.getName(), result.getErrorMessage());
                return false;
            }

            // 校验哈希
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                String actualSha1 = FileUtils.sha1(libraryPath);
                if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                    logger.warn("库文件哈希不匹配: {} (期望 {} 实际 {})",
                            library.getName(), expectedSha1, actualSha1);
                    Files.deleteIfExists(libraryPath);
                    failedFiles.incrementAndGet();
                    return false;
                }
            }

            completedFiles.incrementAndGet();
            updateLibraryProgress(library.getName());
            return true;

        } catch (Exception e) {
            failedFiles.incrementAndGet();
            logger.error("库文件下载异常: {} - {}", library.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * 更新库文件下载进度
     */
    private void updateLibraryProgress(String libraryName) {
        int completed = completedFiles.get();
        int total = totalFiles.get();
        if (total > 0) {
            int percent = 40 + (int) ((double) completed / total * 30);
            reportProgress(percent, 100, L.t("s_bd59a735ae") + completed + "/" + total + ")");
        }
    }

    /**
     * 构建带速度上报的下载监听器（避免在多处重复写匿名类）
     */
    private Downloader.DownloadListener speedListener(String displayName) {
        return new Downloader.DownloadListener() {
            public void onStart() {}

            public void onProgress(long downloaded, long total, double percent) {}

            public void onSpeed(long bytesPerSecond) {
                reportDetail(displayName, bytesPerSecond,
                        Math.max(0, totalFiles.get() - completedFiles.get()));
            }

            public void onComplete(Path destination) {}

            public void onError(Exception e) {}
        };
    }

    /**
     * 判断库文件是否适用于当前平台
     */
    private boolean isLibraryApplicable(VersionInfo.Library library) {
        if (library.getRules() == null || library.getRules().isEmpty()) {
            return true;
        }

        boolean allowed = false;
        String currentOs = getCurrentOsName();
        String currentArch = getCurrentArch();

        for (VersionInfo.Rule rule : library.getRules()) {
            boolean matches = true;

            if (rule.getOs() != null) {
                VersionInfo.Rule.Os os = rule.getOs();

                if (os.getName() != null && !os.getName().isEmpty()) {
                    if (!os.getName().equalsIgnoreCase(currentOs)) {
                        matches = false;
                    }
                }

                if (os.getArch() != null && !os.getArch().isEmpty()) {
                    if (!os.getArch().equalsIgnoreCase(currentArch)) {
                        matches = false;
                    }
                }
            }

            if (matches) {
                if ("allow".equals(rule.getAction())) {
                    allowed = true;
                } else if ("disallow".equals(rule.getAction())) {
                    allowed = false;
                }
            }
        }

        return allowed;
    }

    /**
     * 获取当前操作系统名称（Minecraft 格式）
     */
    private String getCurrentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    /**
     * 获取当前架构
     */
    private String getCurrentArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("64")) {
            return arch.contains("aarch64") ? "arm64" : "x86_64";
        }
        return "x86";
    }

    /**
     * 解压 Natives 文件
     */
    private void extractNatives(VersionInfo info, Path versionDir) throws IOException {
        Path nativesDir = versionDir.resolve("natives");
        Files.createDirectories(nativesDir);

        if (info.getLibraries() == null) {
            return;
        }

        String currentOs = getCurrentOsName();

        for (VersionInfo.Library library : info.getLibraries()) {
            if (library.getNatives() == null || library.getNatives().isEmpty()) {
                continue;
            }

            // 获取当前平台的 natives classifier
            String classifier = library.getNatives().get(currentOs);
            if (classifier == null) {
                continue;
            }

            // 解析 natives JAR 路径
            String mavenPath = getNativesMavenPath(library.getName(), classifier);
            Path nativesJar = gameDir.resolve("libraries").resolve(mavenPath);

            if (!Files.exists(nativesJar)) {
                logger.warn("Natives JAR 不存在: {}", nativesJar);
                // downloadNativeLibraries 已经尝试下载；这里仍缺说明下载失败
                downloadFailed = true;
                continue;
            }

            // 解压（排除 META-INF）
            try {
                extractNativesJar(nativesJar, nativesDir, library.getExtract());
                logger.info("解压 Natives 完成: {}", nativesJar.getFileName());
            } catch (IOException e) {
                logger.error("解压 Natives 失败: {}", e.getMessage());
                downloadFailed = true;
            }
        }
    }

    /**
     * 解压 Natives JAR，排除指定文件
     */
    private void extractNativesJar(Path jarPath, Path destDir, VersionInfo.Extract extract)
            throws IOException {
        List<String> excludes = new ArrayList<>();
        excludes.add("META-INF/");

        if (extract != null && extract.getExclude() != null) {
            excludes.addAll(extract.getExclude());
        }

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                Files.newInputStream(jarPath))) {

            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 检查是否需要排除
                boolean skip = false;
                for (String exclude : excludes) {
                    if (entry.getName().startsWith(exclude) || entry.getName().equals(exclude)) {
                        skip = true;
                        break;
                    }
                }

                if (skip || entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    zis.closeEntry();
                    continue;
                }

                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                zis.closeEntry();
            }
        }
    }

    /**
     * 获取 Natives 的 Maven 路径
     */
    private String getNativesMavenPath(String libraryName, String classifier) {
        String[] parts = libraryName.split(":");
        if (parts.length >= 3) {
            String group = parts[0].replace(".", "/");
            String artifact = parts[1];
            String version = parts[2];

            // 处理 version@jar 格式
            if (version.contains("@")) {
                version = version.split("@")[0];
            }

            return group + "/" + artifact + "/" + version + "/" +
                   artifact + "-" + version + "-" + classifier + ".jar";
        }
        return libraryName;
    }

    /**
     * 下载资源文件
     */
    private void downloadAssets(VersionInfo info) throws IOException {
        if (info.getAssetIndex() == null) {
            logger.info("没有资源索引，跳过资源下载");
            return;
        }

        String assetIndexId = info.getAssetIndex().getId();
        // id 来自远程 JSON，会被拼进文件名：拒绝穿越与非法字符
        if (assetIndexId == null || !assetIndexId.matches("[A-Za-z0-9._\\-]+") || assetIndexId.contains("..")) {
            downloadFailed = true;
            throw new IOException("资源索引 ID 非法: " + assetIndexId);
        }
        String indexSha1 = info.getAssetIndex().getSha1();
        Path indexesDir = gameDir.resolve("assets").resolve("indexes");
        Path objectsDir = gameDir.resolve("assets").resolve("objects");

        Files.createDirectories(indexesDir);
        Files.createDirectories(objectsDir);

        // 下载资源索引
        Path indexFile = indexesDir.resolve(assetIndexId + ".json");

        boolean indexOk = Files.exists(indexFile) && Files.size(indexFile) > 0;
        if (indexOk && indexSha1 != null && !indexSha1.isBlank()) {
            indexOk = indexSha1.equalsIgnoreCase(FileUtils.sha1(indexFile));
        }

        if (!indexOk) {
            String indexUrl = apiClient.convertToMirror(info.getAssetIndex().getUrl());
            logger.info("下载资源索引: {}", indexUrl);

            Downloader.DownloadResult result = downloader.download(indexUrl, indexFile, null);
            if (!result.isSuccess()) {
                throw new IOException("资源索引下载失败: " + result.getErrorMessage());
            }

            if (indexSha1 != null && !indexSha1.isBlank()
                    && !indexSha1.equalsIgnoreCase(FileUtils.sha1(indexFile))) {
                Files.deleteIfExists(indexFile);
                throw new IOException("资源索引校验失败（SHA-1 不匹配）");
            }
        }

        // 解析资源索引
        String indexJson = Files.readString(indexFile, StandardCharsets.UTF_8);
        JsonObject indexObj = JsonParser.parseString(indexJson).getAsJsonObject();
        JsonObject objects = indexObj.getAsJsonObject("objects");

        if (objects == null || objects.size() == 0) {
            logger.info("资源索引为空");
            return;
        }

        logger.info("需要下载 {} 个资源文件", objects.size());

        // 收集缺失的资源
        List<String> missingHashes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();

            String prefix = hash.substring(0, 2);
            Path objectPath = objectsDir.resolve(prefix).resolve(hash);

            // 资源文件名就是 SHA-1：已存在但长度不对的一律当作缺失重下
            // （以前只看「存在」，U盘掉电/磁盘满留下的截断文件会让游戏一进世界就崩）
            if (!Files.exists(objectPath)) {
                missingHashes.add(hash);
            } else {
                try {
                    if (Files.size(objectPath) <= 0) {
                        missingHashes.add(hash);
                    }
                } catch (IOException e) {
                    missingHashes.add(hash);
                }
            }
        }

        logger.info("缺失 {} 个资源文件", missingHashes.size());

        if (missingHashes.isEmpty()) {
            return;
        }

        // 并发下载资源文件。
        //
        // 资源文件有几千个，是总耗时的大头。这里开到 48 个线程，
        // 实际并发由 DownloadThrottle 控制：
        // 全局封顶 64、每站封顶 16，文件轮询分发到 5 个镜像站，
        // 因此每个站承受的并发约 10-13，仍在其安全范围内。
        ExecutorService executor = Executors.newFixedThreadPool(48);
        List<Future<Boolean>> futures = new ArrayList<>();

        AtomicInteger assetCompleted = new AtomicInteger(0);
        AtomicInteger assetFailed = new AtomicInteger(0);
        int totalAssets = missingHashes.size();

        for (String hash : missingHashes) {
            futures.add(executor.submit(() -> {
                if (cancelled) return false;
                try {
                    String prefix = hash.substring(0, 2);
                    Path objectPath = objectsDir.resolve(prefix).resolve(hash);
                    Files.createDirectories(objectPath.getParent());

                    // 优先走高校镜像池（assets 路径是扁平的 /assets/<hash>），
                    // 失败再回退 BMCLAPI 官方节点（/assets/<前2位>/<hash>）
                    Downloader.DownloadResult result = null;
                    MirrorPool pool = MirrorPool.getInstance();

                    if (pool.isEnabled()) {
                        MirrorPool.Mirror m = pool.next();
                        if (m != null) {
                            String mirrorUrl = pool.buildAssetUrl(m, hash);
                            result = downloader.download(mirrorUrl, objectPath, null);
                            if (result.isSuccess()) {
                                pool.reportSuccess(m);
                            } else {
                                pool.reportFailure(m);
                                try {
                                    Files.deleteIfExists(objectPath);
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    }

                    if (result == null || !result.isSuccess()) {
                        String url = apiClient.getAssetDownloadUrl(hash, "0");
                        result = downloader.download(url, objectPath, null);
                    }

                    // 资源文件的名字就是它的 SHA-1，可直接用于校验完整性；
                    // 损坏的资源会导致游戏启动时崩溃，因此这里必须验证
                    if (result.isSuccess()) {
                        String actual = FileUtils.sha1(objectPath);
                        if (!hash.equalsIgnoreCase(actual)) {
                            logger.debug("资源文件校验失败，重试: {}", hash);
                            Files.deleteIfExists(objectPath);

                            // 回退到官方资源服务器
                            String fallback = "https://resources.download.minecraft.net/"
                                    + prefix + "/" + hash;
                            Downloader.DownloadResult retry =
                                    downloader.download(fallback, objectPath, null);

                            if (!retry.isSuccess()
                                    || !hash.equalsIgnoreCase(FileUtils.sha1(objectPath))) {
                                Files.deleteIfExists(objectPath);
                                assetCompleted.incrementAndGet();
                                assetFailed.incrementAndGet();
                                return false;
                            }
                        }
                    }

                    int completed = assetCompleted.incrementAndGet();
                    if (completed % 20 == 0 || completed == totalAssets) {
                        int percent = 80 + (int) ((double) completed / totalAssets * 18);
                        reportProgress(percent, 100, L.t("s_e5a10cd726") + completed + "/" + totalAssets + ")");
                        // 上报剩余数量，让用户知道还有多少
                        reportDetail(null, 0, totalAssets - completed);
                    }

                    return result.isSuccess();
                } catch (Exception e) {
                    logger.debug("资源下载失败: {} - {}", hash, e.getMessage());
                    assetCompleted.incrementAndGet();
                    assetFailed.incrementAndGet();
                    return false;
                }
            }));
        }

        int notOk = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (!Boolean.TRUE.equals(future.get(3, TimeUnit.MINUTES))) {
                    notOk++;
                }
            } catch (TimeoutException e) {
                logger.warn("资源文件下载超时（已取消）");
                future.cancel(true);
                notOk++;
            } catch (Exception e) {
                logger.warn("资源下载任务异常: {}", e.getMessage());
                notOk++;
            }
        }

        executor.shutdown();

        if (assetFailed.get() > 0) {
            // 资源缺失会导致游戏在加载界面崩溃或贴图缺失，必须上报
            failedFiles.addAndGet(assetFailed.get());
            downloadFailed = true;
        }
        if (cancelled && notOk > 0) {
            // 取消下载不算失败，交给上层按取消处理
            logger.info("资源下载被取消，剩余 {} 个", notOk);
        }
    }

    /**
     * 取消下载
     */
    public void cancel() {
        cancelled = true;
        downloader.cancel();
    }

    /**
     * 报告进度
     */
    private void reportProgress(int percent, int total, String message) {
        if (callback != null) {
            callback.onProgress(percent, total, message);
        }
    }

    /**
     * 报告错误
     */
    private void reportError(String message) {
        if (callback != null) {
            callback.onError(message);
        }
    }

    /**
     * 关闭下载器
     */
    public void shutdown() {
        downloader.shutdown();
    }

    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(int current, int total, String message);
        void onError(String error);

        /**
         * 详细进度（用于 UI 展示当前文件 / 速度 / 剩余数量）
         *
         * @param currentFile 当前正在下载的文件名（可为 null）
         * @param speed       速度（字节/秒），0 表示未知
         * @param remaining   剩余待下载文件数，-1 表示未知
         */
        default void onDetail(String currentFile, long speed, int remaining) {}
    }

    /**
     * 报告详细进度
     */
    private void reportDetail(String currentFile, long speed, int remaining) {
        if (callback != null) {
            callback.onDetail(currentFile, speed, remaining);
        }
    }
}