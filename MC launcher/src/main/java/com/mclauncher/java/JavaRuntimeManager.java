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

public class JavaRuntimeManager {

    private static final Logger logger = LoggerFactory.getLogger(JavaRuntimeManager.class);

    private static final String ADOPTIUM_API = "https://api.adoptium.net/v3";

    

    

    private static final String[] JRE_MIRRORS = {
            "https://mirror.nju.edu.cn/adoptium",      
            "https://mirrors.huaweicloud.com/adoptium", 
            "https://mirrors.ustc.edu.cn/adoptium"      
    };

    private static final String LIBERICA_CDN = "https://download.bell-sw.com/java";

    private static final String ZULU_CDN = "https://cdn.azul.com/zulu/bin";

    private static final String[][] ZULU_KNOWN = {

            {"8",  "32", "zulu8.96.0.205-ca-fx-jre8.0.504-win_i686.zip"},
            {"8",  "64", "zulu8.96.0.205-ca-fx-jre8.0.504-win_x64.zip"},

            {"17", "32", "zulu17.68.203-ca-fx-jre17.0.20.1-win_i686.zip"},
            {"17", "64", "zulu17.68.203-ca-fx-jre17.0.20.1-win_x64.zip"},

            {"21", "64", "zulu21.44.17-ca-fx-jre21.0.8-win_x64.zip"},
    };

    private static final String[][] LIBERICA_KNOWN = {

            {"8",  "32", "8u513+7",   "bellsoft-jre8u513+7-windows-i586.zip"},
            {"8",  "64", "8u513+7",   "bellsoft-jre8u513+7-windows-amd64.zip"},

            {"17", "32", "17.0.9+11",  "bellsoft-jre17.0.9+11-windows-i586.zip"},
            {"17", "64", "17.0.9+11",  "bellsoft-jre17.0.9+11-windows-amd64.zip"},

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

    public List<JavaDetector.JavaInfo> detectInstalledJava() {
        logger.info("开始检测系统 Java...");
        detectedJava.clear();
        detectedJava.addAll(JavaDetector.detectJavaVersions());

        for (JavaDetector.JavaInfo info : detectedJava) {
            logger.info("检测到 Java: {} (版本 {})", info.getPath(), info.getMajorVersion());
        }

        detectLauncherJava();

        return new ArrayList<>(detectedJava);
    }

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

    public List<JavaDetector.JavaInfo> getAllJava() {
        return new ArrayList<>(detectedJava);
    }

    public JavaDetector.JavaInfo findJava(int requiredVersion) {

        for (JavaDetector.JavaInfo info : detectedJava) {
            if (info.getPath().startsWith(javaDir.toString()) &&
                info.getMajorVersion() >= requiredVersion) {
                return info;
            }
        }

        return JavaDetector.findJavaByVersion(detectedJava, requiredVersion);
    }

    public boolean needDownloadJava(int requiredVersion) {
        return findJava(requiredVersion) == null;
    }

    public Path downloadJava(int majorVersion, DownloadCallback callback) throws IOException {
        logger.info("开始下载 Java {}", majorVersion);

        String os = getAdoptiumOs();

        String arch = getAdoptiumArch();

        String mirrorArch = getMirrorArch();

        
        String versionPath = majorVersion + "/jre/" + mirrorArch + "/" + os + "/";

        

        
        String fileName = null;
        String downloadUrl = null;
        long fileSize = 0;

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

        

        if (fileName == null && downloadUrl == null
                && zuluUrl(majorVersion) == null
                && libericaUrl(majorVersion) == null) {
            throw new IOException("无法确定 Java " + majorVersion
                    + " 下载地址：Liberica / 国内镜像 / Adoptium API 均不可用");
        }

        
        List<String> candidateUrls = new ArrayList<>();

        

        

        

        

        String zulu = zuluUrl(majorVersion);
        if (zulu != null) {
            candidateUrls.add(zulu);
            logger.info("Zulu FX 候选（含 JavaFX）: {}", zulu);
        }

        

        
        String libUrl = libericaUrl(majorVersion);
        if (libUrl != null) {
            candidateUrls.add(libUrl);
            logger.info("Liberica 候选: {}", libUrl);
        }

        if (fileName != null) {
            String relPath = versionPath + fileName;
            for (String mirror : JRE_MIRRORS) {
                candidateUrls.add(mirror + "/" + relPath);
            }
        }

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
                downloadUrl = url; 
                logger.info("下载成功，使用源: {}", url);
                break;
            }

            lastError = new IOException(result.getErrorMessage());
            logger.warn("该源失败: {}", result.getErrorMessage());
        }

        if (downloadUrl == null || !Files.exists(downloadPath) || Files.size(downloadPath) == 0) {
            throw new IOException("所有下载源均失败: " + (lastError != null ? lastError.getMessage() : "未知错误"));
        }

        if (callback != null) {
            callback.onStatus(L.t("s_434123e873"));
        }

        Path extractDir = javaDir.resolve("jre-" + majorVersion);

        if (downloadPath.toString().endsWith(".zip")) {
            ZipUtils.unzip(downloadPath, extractDir);
        } else if (downloadPath.toString().endsWith(".tar.gz") || downloadPath.toString().endsWith(".tar")) {
            extractTarGz(downloadPath, extractDir);
        }

        Path javaExe = findJavaExecutable(extractDir);
        if (javaExe == null) {
            throw new IOException("无法找到解压后的 Java 可执行文件");
        }

        if (!PlatformUtils.isWindows()) {
            try {
                PlatformUtils.setExecutable(javaExe);
            } catch (IOException e) {
                logger.warn("设置执行权限失败: {}", e.getMessage());
            }
        }

        try {
            Files.deleteIfExists(downloadPath);
        } catch (IOException e) {
            logger.debug("删除安装包失败: {}", e.getMessage());
        }

        logger.info("Java {} 安装完成: {}", majorVersion, javaExe);

        if (callback != null) {
            callback.onStatus("Java " + majorVersion + " 安装完成");
        }

        detectInstalledJava();

        return javaExe;
    }

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
                    break; 
                }
            } catch (Exception e) {
                logger.debug("读取镜像目录失败 {}: {}", dirUrl, e.getMessage());
            }
        }

        if (found.isEmpty()) {
            return null;
        }

        List<String> osMatched = new ArrayList<>();
        for (String f : found) {
            if (f.contains("_" + os + "_")) {
                osMatched.add(f);
            }
        }
        Collection<String> candidates = osMatched.isEmpty() ? found : osMatched;

        String best = null;
        for (String f : candidates) {
            if (best == null || f.compareTo(best) > 0) {
                best = f;
            }
        }
        return best;
    }

    private String getKnownJreFileName(int majorVersion, String arch, String os) {

        

        

        

        
        if (!"windows".equals(os)) {
            return null;
        }

        if (majorVersion == 8) {
            if ("aarch64".equals(arch)) {

                return null;
            }
            if ("x86-32".equals(arch)) {
                return "OpenJDK8U-jre_x86-32_windows_hotspot_8u504b01.zip";
            }

            return "OpenJDK8U-jre_x64_windows_hotspot_8u504b01.zip";
        }

        if (majorVersion == 17) {
            if ("aarch64".equals(arch)) {
                return "OpenJDK17U-jre_aarch64_windows_hotspot_17.0.13_11.zip";
            }
            if ("x86-32".equals(arch)) {

                return null;
            }
            return "OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip";
        }

        if (majorVersion == 21) {
            if ("aarch64".equals(arch)) {
                return "OpenJDK21U-jre_aarch64_windows_hotspot_21.0.7_2.zip";
            }
            if ("x86-32".equals(arch)) {

                return null;
            }
            return "OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip";
        }

        return null;
    }

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

    private String getAdoptiumOs() {
        if (PlatformUtils.isWindows()) return "windows";
        if (PlatformUtils.isMacOS()) return "mac";
        return "linux";
    }

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

    private String getMirrorArch() {
        String a = getAdoptiumArch();
        if ("x86-32".equals(a)) {
            return "x32";
        }
        return a;
    }

    private String getBitness() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("64") || arch.contains("aarch64")) {
            return "64";
        }
        return "32";
    }

    private String[] libericaKnown(int majorVersion) {
        String bit = getBitness();
        for (String[] row : LIBERICA_KNOWN) {
            if (row[0].equals(String.valueOf(majorVersion)) && row[1].equals(bit)) {
                return new String[]{row[2], row[3]};
            }
        }
        return null;
    }

    private String zuluUrl(int majorVersion) {
        String bit = getBitness();
        for (String[] row : ZULU_KNOWN) {
            if (row[0].equals(String.valueOf(majorVersion)) && row[1].equals(bit)) {
                return ZULU_CDN + "/" + row[2];
            }
        }
        return null;
    }

    private String libericaUrl(int majorVersion) {
        String[] known = libericaKnown(majorVersion);
        if (known == null) {
            return null;
        }
        return LIBERICA_CDN + "/" + known[0] + "/" + known[1];
    }

    public Path getJavaDir() {
        return javaDir;
    }

    public boolean validateJava(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();

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

    public void shutdown() {
        downloader.shutdown();
    }

    public interface DownloadCallback {
        void onStatus(String status);
        void onProgress(double percent, long downloaded, long total);
    }
}
