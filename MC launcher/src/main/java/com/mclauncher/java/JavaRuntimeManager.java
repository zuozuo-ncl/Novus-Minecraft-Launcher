package com.mclauncher.java;


import com.mclauncher.i18n.L;
import com.google.gson.*;
import com.mclauncher.network.Downloader;
import com.mclauncher.utils.FileUtils;
import com.mclauncher.utils.JavaDetector;
import com.mclauncher.utils.PlatformUtils;
import com.mclauncher.utils.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Java 运行时管理器
 * 负责检测、下载、管理 Java 运行环境
 */
public class JavaRuntimeManager {

    private static final Logger logger = LoggerFactory.getLogger(JavaRuntimeManager.class);

    // Adoptium API（用于查询最新版本信息，国内可能超时）
    private static final String ADOPTIUM_API = "https://api.adoptium.net/v3";

    // 国内 JRE 镜像站（实测可用，按速度排序）
    // 目录结构：<镜像>/adoptium/<major>/jre/<arch>/<os>/<文件名>
    //
    // 注意：32 位的目录名是 "x32"，不是 "x86-32"。
    // 实测（2026-10-26）：南大镜像上 x86-32 返回 404，只有 x32 可用。
    private static final String[] JRE_MIRRORS = {
            "https://mirror.nju.edu.cn/adoptium",      // 南京大学
            "https://mirrors.huaweicloud.com/adoptium", // 华为云
            "https://mirrors.ustc.edu.cn/adoptium"      // 中科大（备用，部分版本可能 404）
    };

    /**
     * BellSoft Liberica 的 CDN
     *
     * 为什么引入它：Adoptium **不提供 32 位 Java 21**，
     * 而在 32 位 Windows 上跑 MC 1.20.5+ 恰恰需要 Java 21。
     * Liberica 三个 LTS 版本（8/17/21）都有 32 位和 64 位构建。
     *
     * 实测（2026-10-26）速度：1.2~4.1 秒响应，比 Adoptium 镜像还快。
     */
    private static final String LIBERICA_CDN = "https://download.bell-sw.com/java";

    /**
     * Azul Zulu FX 的 CDN
     *
     * **这是 32 位 JavaFX 的唯一来源。**
     *
     * 为什么必须用它：启动器依赖 JavaFX，而
     *   - Adoptium      : 有 32 位，但**不含 JavaFX**
     *   - BellSoft      : 有 32 位，但**不含 JavaFX**
     *   - Azul Zulu FX  : 有 32 位，**含 JavaFX**  ← 只有这个能用
     *
     * 实测（2026-10-26）：用 zulu17 的 32 位 JRE 跑本启动器，
     * JavaFX 界面、主题、网络、版本清单全部正常。
     */
    private static final String ZULU_CDN = "https://cdn.azul.com/zulu/bin";

    /**
     * Zulu FX 已知文件名（按 版本 + 架构）
     *
     * 格式：{majorVersion, bitness, filename}
     * 命名规律：zulu<build>-ca-fx-jre<javaVersion>-win_<arch>.zip
     *   32 位 arch = i686，64 位 arch = x64
     *
     * 需随 Zulu 发版更新。这些是最新稳定版（2026-10）。
     */
    private static final String[][] ZULU_KNOWN = {
            // Java 8
            {"8",  "32", "zulu8.96.0.205-ca-fx-jre8.0.504-win_i686.zip"},
            {"8",  "64", "zulu8.96.0.205-ca-fx-jre8.0.504-win_x64.zip"},
            // Java 17（更新到 17.0.20.1，与 64 位 Adoptium 版本号一致）
            {"17", "32", "zulu17.68.203-ca-fx-jre17.0.20.1-win_i686.zip"},
            {"17", "64", "zulu17.68.203-ca-fx-jre17.0.20.1-win_x64.zip"},
            // Java 21：Zulu 无 32 位 JavaFX 构建，故 32 位不给候选
            {"21", "64", "zulu21.44.17-ca-fx-jre21.0.8-win_x64.zip"},
    };

    /**
     * Liberica 已知的 JRE 文件名（按 版本 + 架构）
     *
     * 格式：{majorVersion, bitness, version, filename}
     * 32 位文件名含 i586，64 位含 amd64。
     *
     * 这些版本号需随 Liberica 发版更新。若失效，
     * discoverLibericaFileName() 会通过 API 查最新。
     */
    private static final String[][] LIBERICA_KNOWN = {
            // Java 8
            {"8",  "32", "8u513+7",   "bellsoft-jre8u513+7-windows-i586.zip"},
            {"8",  "64", "8u513+7",   "bellsoft-jre8u513+7-windows-amd64.zip"},
            // Java 17
            {"17", "32", "17.0.9+11",  "bellsoft-jre17.0.9+11-windows-i586.zip"},
            {"17", "64", "17.0.9+11",  "bellsoft-jre17.0.9+11-windows-amd64.zip"},
            // Java 21
            {"21", "32", "21.0.9+15",  "bellsoft-jre21.0.9+15-windows-i586.zip"},
            {"21", "64", "21.0.9+15",  "bellsoft-jre21.0.9+15-windows-amd64.zip"},
    };

    private final Path launcherDir;
    private final Path javaDir;
    private final Downloader downloader;
    private final Gson gson;

    private final List<JavaDetector.JavaInfo> detectedJava;

    public JavaRuntimeManager(Path launcherDir) {
        this.launcherDir = launcherDir;
        this.javaDir = launcherDir.resolve("java");
        this.downloader = new Downloader();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.detectedJava = new ArrayList<>();

        try {
            Files.createDirectories(javaDir);
        } catch (IOException e) {
            logger.error("创建 Java 目录失败: {}", e.getMessage());
        }
    }

    /**
     * 检测系统中已安装的 Java
     */
    public List<JavaDetector.JavaInfo> detectInstalledJava() {
        logger.info("开始检测系统 Java...");
        detectedJava.clear();
        detectedJava.addAll(JavaDetector.detectJavaVersions());

        for (JavaDetector.JavaInfo info : detectedJava) {
            logger.info("检测到 Java: {} (版本 {})", info.getPath(), info.getMajorVersion());
        }

        // 检测启动器目录下的 Java
        detectLauncherJava();

        return new ArrayList<>(detectedJava);
    }

    /**
     * 检测启动器目录下的 Java
     */
    private void detectLauncherJava() {
        if (!Files.exists(javaDir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(javaDir)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir)) {
                    JavaDetector.JavaInfo info = JavaDetector.checkJavaInstallation(dir.toString());
                    if (info != null) {
                        detectedJava.add(info);
                        logger.info("检测到启动器 Java: {} (版本 {})", info.getPath(), info.getMajorVersion());
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("扫描启动器 Java 目录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取所有可用的 Java
     */
    public List<JavaDetector.JavaInfo> getAllJava() {
        return new ArrayList<>(detectedJava);
    }

    /**
     * 查找满足版本要求的 Java
     */
    public JavaDetector.JavaInfo findJava(int requiredVersion) {
        // 优先使用启动器目录下的 Java
        for (JavaDetector.JavaInfo info : detectedJava) {
            if (info.getPath().startsWith(javaDir.toString()) &&
                info.getMajorVersion() >= requiredVersion) {
                return info;
            }
        }

        // 其次使用系统的 Java
        return JavaDetector.findJavaByVersion(detectedJava, requiredVersion);
    }

    /**
     * 检查是否需要下载 Java
     */
    public boolean needDownloadJava(int requiredVersion) {
        return findJava(requiredVersion) == null;
    }

    /**
     * 自动下载 Java
     *
     * @param majorVersion Java 主版本号（如 17, 21）
     * @param callback     进度回调
     * @return 下载后的 Java 可执行文件路径
     */
    public Path downloadJava(int majorVersion, DownloadCallback callback) throws IOException {
        logger.info("开始下载 Java {}", majorVersion);

        // 获取系统信息
        String os = getAdoptiumOs();
        // arch 用于 Adoptium API 参数（32 位是 "x86-32"）
        String arch = getAdoptiumArch();
        // mirrorArch 用于镜像目录（32 位是 "x32"，与 API 命名不同）
        String mirrorArch = getMirrorArch();

        // 构造版本路径（Adoptium 镜像结构：<major>/jre/<arch>/<os>/）
        // 必须用 mirrorArch —— 实测南大镜像上 /x86-32/ 是 404，只有 /x32/ 可用
        String versionPath = majorVersion + "/jre/" + mirrorArch + "/" + os + "/";

        // 构造文件名（Adoptium 标准命名）
        // 示例：OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip
        // 注意：这里使用通配符匹配，因为具体补丁版本可能变化
        // 先尝试从 Adoptium API 获取确切文件名，失败则使用已知最新版本
        String fileName = null;
        String downloadUrl = null;
        long fileSize = 0;

        // 尝试从 Adoptium API 获取最新版本信息（国内可能超时，设 8 秒超时）
        if (callback != null) {
            callback.onStatus(L.t("s_3d7f4a7092") + majorVersion + L.t("s_6bcb517146"));
        }

        try {
            String releaseInfoUrl = ADOPTIUM_API + "/assets/latest/" + majorVersion +
                    "/hotspot?architecture=" + arch +
                    "&image_type=jre&os=" + os + "&vendor=eclipse";

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(releaseInfoUrl)
                    .addHeader("User-Agent", com.mclauncher.network.NetworkConstants.USER_AGENT)
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String releaseJson = response.body().string();
                    JsonObject release = JsonParser.parseString(releaseJson).getAsJsonArray().get(0).getAsJsonObject();
                    JsonArray binaries = release.getAsJsonArray("binaries");

                    for (JsonElement element : binaries) {
                        JsonObject binary = element.getAsJsonObject();
                        JsonObject binaryPackage = binary.getAsJsonObject("binary");

                        downloadUrl = binaryPackage.get("package").getAsJsonObject().get("link").getAsString();
                        fileName = binaryPackage.get("package").getAsJsonObject().get("name").getAsString();
                        fileSize = binaryPackage.get("package").getAsJsonObject().get("size").getAsLong();
                        logger.info("Adoptium API 返回: {} ({} MB)", fileName, fileSize / 1048576.0);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Adoptium API 不可达 ({})，将使用国内镜像硬编码文件名", e.getMessage());
        }

        // API 不可达：先从国内镜像的目录列表发现最新文件名，再退回硬编码文件名
        if (downloadUrl == null || fileName == null) {
            if (callback != null) {
                callback.onStatus(L.t("s_d55f804556") + majorVersion + L.t("s_9868d71ed8"));
            }
            fileName = discoverFileNameFromMirrors(versionPath, os);
            if (fileName != null) {
                logger.info("镜像目录列表发现文件: {}", fileName);
            } else {
                fileName = getKnownJreFileName(majorVersion, arch, os);
                if (fileName != null) {
                    logger.info("使用已知文件名: {}", fileName);
                }
            }
        }

        // 三者都没有才认为无法下载。
        // 注意 Liberica 的 URL 由版本号直接拼出、不依赖 fileName，
        // 所以即使 Adoptium 的 API 和镜像列表都失败，仍可能下得成。
        if (fileName == null && downloadUrl == null
                && zuluUrl(majorVersion) == null
                && libericaUrl(majorVersion) == null) {
            throw new IOException("无法确定 Java " + majorVersion
                    + " 下载地址：Liberica / 国内镜像 / Adoptium API 均不可用");
        }

        // 下载 Java - 使用 Downloader 的多源重试机制
        // 构造多个候选 URL（Liberica 优先 → 国内镜像 → 官方 API 兜底）
        List<String> candidateUrls = new ArrayList<>();

        // 1. Azul Zulu FX（**首选**）
        //
        // 为什么排第一：**它是唯一自带 JavaFX 的 32 位构建**。
        // 启动器的界面完全依赖 JavaFX，而：
        //   Adoptium 有 32 位但无 JavaFX
        //   Liberica 有 32 位但无 JavaFX
        //   Zulu FX  有 32 位且含 JavaFX   ← 只有这个能让 32 位系统跑起来
        //
        // 实测：用 zulu17 的 32 位 JRE 跑本启动器，JavaFX 界面完全正常。
        String zulu = zuluUrl(majorVersion);
        if (zulu != null) {
            candidateUrls.add(zulu);
            logger.info("Zulu FX 候选（含 JavaFX）: {}", zulu);
        }

        // 2. Liberica 官方 CDN
        //
        // 备选：32 位覆盖最全（连 Java 21 都有），但**不含 JavaFX**。
        // 只在 Zulu 没有对应版本时才有意义（如 32 位的 Java 21）。
        String libUrl = libericaUrl(majorVersion);
        if (libUrl != null) {
            candidateUrls.add(libUrl);
            logger.info("Liberica 候选: {}", libUrl);
        }

        // 3. 国内镜像站（Adoptium 结构）
        if (fileName != null) {
            String relPath = versionPath + fileName;
            for (String mirror : JRE_MIRRORS) {
                candidateUrls.add(mirror + "/" + relPath);
            }
        }

        // 4. 官方 API 返回的地址（兜底）
        if (downloadUrl != null && !candidateUrls.contains(downloadUrl)) {
            candidateUrls.add(downloadUrl);
        }

        if (candidateUrls.isEmpty()) {
            throw new IOException("没有可用的 Java " + majorVersion + " 下载地址");
        }

        logger.info("共 {} 个候选下载地址", candidateUrls.size());

        Path downloadPath = javaDir.resolve(fileName != null ? fileName : "jre" + majorVersion + ".zip");
        Exception lastError = null;

        for (int i = 0; i < candidateUrls.size(); i++) {
            String url = candidateUrls.get(i);
            if (callback != null) {
                callback.onStatus(L.t("s_806027bfdd") + majorVersion + L.t("s_0c4bf352aa") + (i + 1) + "/" + candidateUrls.size() + "）");
            }
            logger.info("[{}/{}] 尝试下载: {}", i + 1, candidateUrls.size(), url);

            Files.deleteIfExists(downloadPath);

            Downloader.DownloadResult result = downloader.download(url, downloadPath,
                    new Downloader.DownloadListener() {
                        @Override
                        public void onProgress(long downloaded, long total, double percent) {
                            if (callback != null) {
                                callback.onProgress(percent, downloaded, total);
                            }
                        }

                        @Override
                        public void onComplete(Path destination) {}

                        @Override
                        public void onError(Exception e) {}

                        @Override
                        public void onStart() {}
                    });

            if (result.isSuccess()) {
                downloadUrl = url; // 记录成功的 URL
                logger.info("下载成功，使用源: {}", url);
                break;
            }

            lastError = new IOException(result.getErrorMessage());
            logger.warn("该源失败: {}", result.getErrorMessage());
        }

        if (downloadUrl == null || !Files.exists(downloadPath) || Files.size(downloadPath) == 0) {
            throw new IOException("所有下载源均失败: " + (lastError != null ? lastError.getMessage() : "未知错误"));
        }

        // 解压
        if (callback != null) {
            callback.onStatus(L.t("s_434123e873"));
        }

        Path extractDir = javaDir.resolve("jre-" + majorVersion);

        if (downloadPath.toString().endsWith(".zip")) {
            ZipUtils.unzip(downloadPath, extractDir);
        } else if (downloadPath.toString().endsWith(".tar.gz") || downloadPath.toString().endsWith(".tar")) {
            extractTarGz(downloadPath, extractDir);
        }

        // 查找 Java 可执行文件
        Path javaExe = findJavaExecutable(extractDir);
        if (javaExe == null) {
            throw new IOException("无法找到解压后的 Java 可执行文件");
        }

        // 设置执行权限（Unix）
        if (!PlatformUtils.isWindows()) {
            try {
                PlatformUtils.setExecutable(javaExe);
            } catch (IOException e) {
                logger.warn("设置执行权限失败: {}", e.getMessage());
            }
        }

        // 删除下载的压缩包
        try {
            Files.deleteIfExists(downloadPath);
        } catch (IOException e) {
            logger.debug("删除安装包失败: {}", e.getMessage());
        }

        logger.info("Java {} 安装完成: {}", majorVersion, javaExe);

        if (callback != null) {
            callback.onStatus("Java " + majorVersion + " 安装完成");
        }

        // 重新检测
        detectInstalledJava();

        return javaExe;
    }

    /**
     * 从国内镜像的目录列表（autoindex）中发现实际的 JRE 文件名
     *
     * 硬编码文件名会过期，而镜像目录列表永远是最新的。
     * 目录结构：<镜像>/adoptium/<major>/jre/<arch>/<os>/
     *
     * @param versionPath 形如 "17/jre/x64/windows/"
     * @param os          Adoptium 操作系统标识（windows/mac/linux）
     * @return 文件名；镜像不可达或目录为空时返回 null
     */
    private String discoverFileNameFromMirrors(String versionPath, String os) {
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("OpenJDK\\d+U-jre_[A-Za-z0-9._\\-]+\\.zip");
        TreeSet<String> found = new TreeSet<>();

        for (String mirror : JRE_MIRRORS) {
            String dirUrl = mirror + "/" + versionPath;
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(6, TimeUnit.SECONDS)
                        .build();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(dirUrl)
                        .addHeader("User-Agent", com.mclauncher.network.NetworkConstants.USER_AGENT)
                        .build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        logger.debug("镜像目录不存在: {}", dirUrl);
                        continue;
                    }
                    String html = response.body().string();
                    java.util.regex.Matcher m = pattern.matcher(html);
                    while (m.find()) {
                        found.add(m.group());
                    }
                }
                if (!found.isEmpty()) {
                    break; // 第一个能列出文件的镜像即可
                }
            } catch (Exception e) {
                logger.debug("读取镜像目录失败 {}: {}", dirUrl, e.getMessage());
            }
        }

        if (found.isEmpty()) {
            return null;
        }

        // 只保留匹配当前 os 的包（文件名形如 ..._windows_... / ..._mac_... / ..._linux_...）
        List<String> osMatched = new ArrayList<>();
        for (String f : found) {
            if (f.contains("_" + os + "_")) {
                osMatched.add(f);
            }
        }
        Collection<String> candidates = osMatched.isEmpty() ? found : osMatched;

        // Adoptium 版本号补零对齐，字典序最大的基本就是最新版
        String best = null;
        for (String f : candidates) {
            if (best == null || f.compareTo(best) > 0) {
                best = f;
            }
        }
        return best;
    }

    /**
     * 根据版本号和架构返回已知的 JRE ZIP 文件名
     *
     * 数据来源：Adoptium Releases API 的已知发布版本。
     * 这些文件名需要定期更新（参考 https://api.adoptium.net/v3/assets/release/latest），
     * 过期时会先被 discoverFileNameFromMirrors 的镜像目录列表兜底。
     */
    private String getKnownJreFileName(int majorVersion, String arch, String os) {
        // 硬编码文件名只覆盖 Windows（本启动器主要平台）；
        //
        // 注意：这些版本号**必须与镜像上的实际文件一致**，否则会 404。
        // 曾因版本号过期导致 Java 8/21 下载全线失败（只有 17 能下）。
        // 修改前务必先用 HEAD 请求验证：
        //   https://mirror.nju.edu.cn/adoptium/<ver>/jre/x64/windows/<file>
        // 若镜像上文件名变了，下面的 discoverFileNameFromMirrors 会自动兜底。
        // 其它平台交给镜像目录发现 / Adoptium API
        if (!"windows".equals(os)) {
            return null;
        }

        // ---- Java 8（LTS，MC 1.12 及以下需要）----
        if (majorVersion == 8) {
            if ("aarch64".equals(arch)) {
                // Temurin 不提供 Windows ARM64 的 Java 8
                return null;
            }
            if ("x86-32".equals(arch)) {
                return "OpenJDK8U-jre_x86-32_windows_hotspot_8u504b01.zip";
            }
            // x64 默认
            return "OpenJDK8U-jre_x64_windows_hotspot_8u504b01.zip";
        }

        // ---- Java 17（LTS，MC 1.17~1.20.4 需要）----
        if (majorVersion == 17) {
            if ("aarch64".equals(arch)) {
                return "OpenJDK17U-jre_aarch64_windows_hotspot_17.0.13_11.zip";
            }
            if ("x86-32".equals(arch)) {
                // Temurin 不提供 Java 17 的 32 位 Windows 包
                return null;
            }
            return "OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip";
        }

        // ---- Java 21（LTS，MC 1.20.5+ 需要）----
        if (majorVersion == 21) {
            if ("aarch64".equals(arch)) {
                return "OpenJDK21U-jre_aarch64_windows_hotspot_21.0.7_2.zip";
            }
            if ("x86-32".equals(arch)) {
                // Temurin 不提供 Java 21 的 32 位 Windows 包
                return null;
            }
            return "OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip";
        }

        // 未知版本：使用镜像目录发现（API 查询会优先）
        return null;
    }

    /**
     * 解压 tar.gz（使用系统 tar 命令）
     */
    private void extractTarGz(Path archive, Path destDir) throws IOException {
        Files.createDirectories(destDir);

        List<String> command = new ArrayList<>();
        command.add("tar");
        command.add("-xzf");
        command.add(archive.toAbsolutePath().toString());
        command.add("-C");
        command.add(destDir.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            // 给足 5 分钟：JRE 压缩包约 45MB，慢磁盘上解压需要时间。
            // 但仍要有上限 —— 无超时的 waitFor 遇到挂起的 tar 会让整个
            // 下载流程永远卡住，界面无法恢复。
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("tar 解压超时（超过 5 分钟）");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("tar 解压失败，退出码: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("解压被中断", e);
        }
    }

    /**
     * 在解压目录中查找 Java 可执行文件
     */
    private Path findJavaExecutable(Path extractDir) {
        String exeName = PlatformUtils.isWindows() ? "java.exe" : "java";

        try {
            return Files.walk(extractDir)
                    .filter(path -> path.getFileName().toString().equals(exeName))
                    .filter(path -> path.getParent() != null &&
                                    path.getParent().getFileName().toString().equals("bin"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.error("查找 Java 可执行文件失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 Adoptium 的操作系统标识
     */
    private String getAdoptiumOs() {
        if (PlatformUtils.isWindows()) return "windows";
        if (PlatformUtils.isMacOS()) return "mac";
        return "linux";
    }

    /**
     * 获取 Adoptium 的架构标识
     *
     * 返回 Adoptium API / 镜像目录的标准命名：
     * x64 / x86-32 / aarch64（注意 32 位是 "x86-32"，不是 "x32"）
     */
    private String getAdoptiumArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        if (arch.contains("64")) {
            return "x64";
        }
        return "x86-32";
    }

    /**
     * 镜像目录用的架构名
     *
     * 与 getAdoptiumArch() 的区别：**镜像上的目录名是 x32**，
     * 而 Adoptium API 的参数是 x86-32。两者不能混用 ——
     * 实测南大镜像上 /x86-32/ 返回 404，只有 /x32/ 可用。
     */
    private String getMirrorArch() {
        String a = getAdoptiumArch();
        if ("x86-32".equals(a)) {
            return "x32";
        }
        return a;
    }

    /**
     * 当前 JVM 是 32 位还是 64 位
     *
     * 判断依据是 os.arch —— 注意这反映的是**运行本程序的 JVM**，
     * 不是操作系统本身。32 位 JVM 跑在 64 位 Windows 上时
     * os.arch 仍是 "x86"，此时我们照样下 32 位 JRE，
     * 与当前 JVM 保持一致（避免混用导致加载 native 库失败）。
     */
    private String getBitness() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("64") || arch.contains("aarch64")) {
            return "64";
        }
        return "32";
    }

    /**
     * 查 Liberica 已知的 JRE 文件名
     *
     * @return {version, filename}，找不到返回 null
     */
    private String[] libericaKnown(int majorVersion) {
        String bit = getBitness();
        for (String[] row : LIBERICA_KNOWN) {
            if (row[0].equals(String.valueOf(majorVersion)) && row[1].equals(bit)) {
                return new String[]{row[2], row[3]};
            }
        }
        return null;
    }

    /**
     * Zulu FX JRE 的下载地址（含 JavaFX）
     *
     * @return 完整的 zip 地址，无对应版本时返回 null
     */
    private String zuluUrl(int majorVersion) {
        String bit = getBitness();
        for (String[] row : ZULU_KNOWN) {
            if (row[0].equals(String.valueOf(majorVersion)) && row[1].equals(bit)) {
                return ZULU_CDN + "/" + row[2];
            }
        }
        return null;
    }

    /**
     * Liberica JRE 的下载地址
     */
    private String libericaUrl(int majorVersion) {
        String[] known = libericaKnown(majorVersion);
        if (known == null) {
            return null;
        }
        return LIBERICA_CDN + "/" + known[0] + "/" + known[1];
    }

    /**
     * 获取启动器 Java 目录
     */
    public Path getJavaDir() {
        return javaDir;
    }

    /**
     * 验证 Java 是否可用
     */
    public boolean validateJava(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();

            // 带超时：损坏/被拦截的 java.exe 会永久挂起
            boolean valid;
            try {
                valid = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                        && process.exitValue() == 0;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                valid = false;
            }
            if (!valid) {
                process.destroyForcibly();
            }

            if (valid && line != null) {
                logger.info("Java 验证成功: {}", line);
            }

            return valid;
        } catch (Exception e) {
            logger.warn("Java 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 关闭下载器
     */
    public void shutdown() {
        downloader.shutdown();
    }

    /**
     * 下载回调接口
     */
    public interface DownloadCallback {
        void onStatus(String status);
        void onProgress(double percent, long downloaded, long total);
    }
}