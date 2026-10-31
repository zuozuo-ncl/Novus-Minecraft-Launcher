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

public class GameDownloader {

    private static final Logger logger = LoggerFactory.getLogger(GameDownloader.class);

    private final BMCLApiClient apiClient;
    private final Downloader downloader;
    private final Path gameDir;
    private final Gson gson;

    private ProgressCallback callback;
    private volatile boolean cancelled = false;

    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger completedFiles = new AtomicInteger(0);
    private final AtomicInteger failedFiles = new AtomicInteger(0);

    private volatile boolean downloadFailed = false;

    public GameDownloader(BMCLApiClient apiClient, Path gameDir) {
        this.apiClient = apiClient;
        this.downloader = new Downloader();
        this.gameDir = gameDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public void setCallback(ProgressCallback callback) {
        this.callback = callback;
    }

    public boolean downloadVersion(String versionId) {
        cancelled = false;
        downloadFailed = false;
        try {
            reportProgress(0, 100, L.t("s_a0a16feb25") + versionId);

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

            reportProgress(5, 100, L.t("s_afd33fb63d"));
            String versionJson = apiClient.getVersionDetailJson(versionInfo.getUrl());
            VersionInfo info = gson.fromJson(versionJson, VersionInfo.class);

            Path versionDir = gameDir.resolve("versions").resolve(versionId);
            Files.createDirectories(versionDir);

            Path versionJsonPath = versionDir.resolve(versionId + ".json");
            Files.writeString(versionJsonPath, versionJson, StandardCharsets.UTF_8);
            logger.info("版本 JSON 已保存: {}", versionJsonPath);

            reportProgress(10, 100, L.t("s_9a265de912"));
            downloadClientJar(versionId, versionDir, info);

            reportProgress(40, 100, L.t("s_173b5acb54"));
            downloadLibraries(info);

            reportProgress(70, 100, L.t("s_407f10aec4"));
            downloadNativeLibraries(info);
            extractNatives(info, versionDir);

            reportProgress(80, 100, L.t("s_454d067676"));
            downloadAssets(info);

            if (downloadFailed) {

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

    private void downloadClientJar(String versionId, Path versionDir, VersionInfo info)
            throws IOException {
        Path jarPath = versionDir.resolve(versionId + ".jar");

        String downloadUrl = null;
        String expectedSha1 = null;
        long expectedSize = -1;

        if (info.getDownloads() != null && info.getDownloads().getClient() != null) {
            downloadUrl = info.getDownloads().getClient().getUrl();
            expectedSha1 = info.getDownloads().getClient().getSha1();

            
            expectedSize = info.getDownloads().getClient().getSize();
            if (downloadUrl != null) {
                downloadUrl = apiClient.convertToMirror(downloadUrl);
            }
        }

        if (downloadUrl == null || downloadUrl.isBlank()) {
            downloadUrl = apiClient.getClientDownloadUrl(versionId);
        }

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

        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            String actual = FileUtils.sha1(jarPath);
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(jarPath);
                throw new IOException("客户端 JAR 校验失败（SHA-1 不匹配）");
            }
        }
    }

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

        

        

        

        ExecutorService executor = Executors.newFixedThreadPool(32);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (VersionInfo.Library library : applicableLibraries) {
            futures.add(executor.submit(() -> downloadLibrary(library)));
        }

        int libraryFailed = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (!Boolean.TRUE.equals(future.get(2, TimeUnit.MINUTES))) {
                    libraryFailed++;
                }
            } catch (TimeoutException e) {

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

        
        downloadFailed = failedFiles.get() > 0 || libraryFailed > 0;
    }

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

            for (String key : classifiers.keySet()) {
                if (key.contains(getCurrentOsName())) {
                    return key;
                }
            }
        }

        if (base.contains("${arch}")) {
            return base.replace("${arch}", is64 ? "64" : "32");
        }
        return base + archSuffix;
    }

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

            
            Path libraryPath = gameDir.resolve("libraries").resolve(mavenPath);

            String expectedSha1 = null;
            String downloadUrl = null;

            if (library.getDownloads() != null && library.getDownloads().getArtifact() != null) {
                VersionInfo.DownloadInfo artifact = library.getDownloads().getArtifact();
                expectedSha1 = artifact.getSha1();
                downloadUrl = artifact.getUrl();
            }

            if (Files.exists(libraryPath) && Files.size(libraryPath) > 0) {
                if (expectedSha1 == null || expectedSha1.isBlank()
                        || expectedSha1.equalsIgnoreCase(FileUtils.sha1(libraryPath))) {
                    completedFiles.incrementAndGet();
                    updateLibraryProgress(library.getName());
                    return true;
                }

                logger.warn("库文件校验失败，重新下载: {}", library.getName());
                Files.deleteIfExists(libraryPath);
            }

            String displayName = library.getName();
            int remaining = Math.max(0, totalFiles.get() - completedFiles.get());
            reportDetail(displayName, 0, remaining);

            

            

            

            

            Downloader.DownloadResult result = null;
            MirrorPool mirrorPool = MirrorPool.getInstance();

            if (mirrorPool.isEnabled()) {

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

            if (result == null || !result.isSuccess()) {
                if (downloadUrl == null || downloadUrl.isBlank()) {
                    downloadUrl = apiClient.getLibraryDownloadUrl(mavenPath);
                } else {
                    downloadUrl = apiClient.convertToMirror(downloadUrl);
                }
                result = downloader.download(downloadUrl, libraryPath, speedListener(displayName));
            }

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

    private void updateLibraryProgress(String libraryName) {
        int completed = completedFiles.get();
        int total = totalFiles.get();
        if (total > 0) {
            int percent = 40 + (int) ((double) completed / total * 30);
            reportProgress(percent, 100, L.t("s_bd59a735ae") + completed + "/" + total + ")");
        }
    }

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

    private String getCurrentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    private String getCurrentArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("64")) {
            return arch.contains("aarch64") ? "arm64" : "x86_64";
        }
        return "x86";
    }

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

            String classifier = library.getNatives().get(currentOs);
            if (classifier == null) {
                continue;
            }

            String mavenPath = getNativesMavenPath(library.getName(), classifier);
            Path nativesJar = gameDir.resolve("libraries").resolve(mavenPath);

            if (!Files.exists(nativesJar)) {
                logger.warn("Natives JAR 不存在: {}", nativesJar);

                downloadFailed = true;
                continue;
            }

            try {
                extractNativesJar(nativesJar, nativesDir, library.getExtract());
                logger.info("解压 Natives 完成: {}", nativesJar.getFileName());
            } catch (IOException e) {
                logger.error("解压 Natives 失败: {}", e.getMessage());
                downloadFailed = true;
            }
        }
    }

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

    private String getNativesMavenPath(String libraryName, String classifier) {
        String[] parts = libraryName.split(":");
        if (parts.length >= 3) {
            String group = parts[0].replace(".", "/");
            String artifact = parts[1];
            String version = parts[2];

            if (version.contains("@")) {
                version = version.split("@")[0];
            }

            return group + "/" + artifact + "/" + version + "/" +
                   artifact + "-" + version + "-" + classifier + ".jar";
        }
        return libraryName;
    }

    private void downloadAssets(VersionInfo info) throws IOException {
        if (info.getAssetIndex() == null) {
            logger.info("没有资源索引，跳过资源下载");
            return;
        }

        String assetIndexId = info.getAssetIndex().getId();

        if (assetIndexId == null || !assetIndexId.matches("[A-Za-z0-9._\\-]+") || assetIndexId.contains("..")) {
            downloadFailed = true;
            throw new IOException("资源索引 ID 非法: " + assetIndexId);
        }
        String indexSha1 = info.getAssetIndex().getSha1();
        Path indexesDir = gameDir.resolve("assets").resolve("indexes");
        Path objectsDir = gameDir.resolve("assets").resolve("objects");

        Files.createDirectories(indexesDir);
        Files.createDirectories(objectsDir);

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

        String indexJson = Files.readString(indexFile, StandardCharsets.UTF_8);
        JsonObject indexObj = JsonParser.parseString(indexJson).getAsJsonObject();
        JsonObject objects = indexObj.getAsJsonObject("objects");

        if (objects == null || objects.size() == 0) {
            logger.info("资源索引为空");
            return;
        }

        logger.info("需要下载 {} 个资源文件", objects.size());

        List<String> missingHashes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            String hash = obj.get("hash").getAsString();

            String prefix = hash.substring(0, 2);
            Path objectPath = objectsDir.resolve(prefix).resolve(hash);

            if (!Files.exists(objectPath)) {
                missingHashes.add(hash);
            }
        }

        logger.info("缺失 {} 个资源文件", missingHashes.size());

        if (missingHashes.isEmpty()) {
            return;
        }

        

        

        
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

                    
                    if (result.isSuccess()) {
                        String actual = FileUtils.sha1(objectPath);
                        if (!hash.equalsIgnoreCase(actual)) {
                            logger.debug("资源文件校验失败，重试: {}", hash);
                            Files.deleteIfExists(objectPath);

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

            failedFiles.addAndGet(assetFailed.get());
            downloadFailed = true;
        }
        if (cancelled && notOk > 0) {

            logger.info("资源下载被取消，剩余 {} 个", notOk);
        }
    }

    public void cancel() {
        cancelled = true;
        downloader.cancel();
    }

    private void reportProgress(int percent, int total, String message) {
        if (callback != null) {
            callback.onProgress(percent, total, message);
        }
    }

    private void reportError(String message) {
        if (callback != null) {
            callback.onError(message);
        }
    }

    public void shutdown() {
        downloader.shutdown();
    }

    public interface ProgressCallback {
        void onProgress(int current, int total, String message);
        void onError(String error);

        default void onDetail(String currentFile, long speed, int remaining) {}
    }

    private void reportDetail(String currentFile, long speed, int remaining) {
        if (callback != null) {
            callback.onDetail(currentFile, speed, remaining);
        }
    }
}
