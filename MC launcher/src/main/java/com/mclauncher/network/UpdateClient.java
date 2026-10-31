package com.mclauncher.network;

import com.mclauncher.i18n.L;
import com.mclauncher.utils.FileUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UpdateClient {

    private static final Logger logger = LoggerFactory.getLogger(UpdateClient.class);

    private static final String[] API_BASES = {
            "https://version-interface.us.ci",                    
            "http://frp.xueyuanpang.dpdns.org:37666",             
    };

    private volatile String activeBase = API_BASES[0];

    private volatile UpdateInfo lastUpdate;

    private static final String JAR_PREFIX = "https://3984235.xyz/";

    private final HttpClientUtils http;

    public UpdateClient() {
        this.http = new HttpClientUtils();
    }

    public static class UpdateInfo {

        public boolean updateAvailable;

        public String latestVersion;

        public String currentVersion;

        public java.util.List<String> allVersions = new java.util.ArrayList<>();

        public String latestSha256;

        public final java.util.Map<String, String> sha256ByVersion = new java.util.HashMap<>();

        public String describe() {
            if (updateAvailable) {
                return L.t("s_bdeaca9059") + latestVersion + L.t("s_c7b01cbc21") + currentVersion + "）";
            }
            return L.t("s_dcf5866577") + currentVersion + "）";
        }
    }

    public UpdateInfo checkUpdate(String currentVersion) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("currentVersion", currentVersion);

        logger.info("检查更新: 当前版本 {}", currentVersion);

        String response = postWithFallback("/api/check-update", body);
        logger.debug("更新接口响应: {}", response);
        boolean fromHttps = activeBase != null && activeBase.startsWith("https://");

        JsonObject json;
        try {
            json = JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("更新接口返回的不是有效 JSON: " + e.getMessage(), e);
        }

        UpdateInfo info = new UpdateInfo();
        info.updateAvailable = json.has("updateAvailable")
                && json.get("updateAvailable").getAsBoolean();

        if (json.has("latestVersion") && !json.get("latestVersion").isJsonNull()) {
            info.latestVersion = json.get("latestVersion").getAsString();
        }

        if (json.has("currentVersion") && !json.get("currentVersion").isJsonNull()) {
            info.currentVersion = json.get("currentVersion").getAsString();
        } else {
            info.currentVersion = currentVersion;
        }

        if (json.has("allVersions") && json.get("allVersions").isJsonArray()) {
            for (var e : json.getAsJsonArray("allVersions")) {
                info.allVersions.add(e.getAsString());
            }
        }

        
        if (fromHttps) {
            if (json.has("sha256") && !json.get("sha256").isJsonNull()
                    && json.get("sha256").isJsonPrimitive()) {
                String h = json.get("sha256").getAsString();
                if (isSha256Hex(h)) {
                    info.latestSha256 = h.toLowerCase();
                }
            }
            if (json.has("files") && json.get("files").isJsonArray()) {
                for (var e : json.getAsJsonArray("files")) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    var o = e.getAsJsonObject();
                    if (o.has("version") && o.has("sha256")
                            && o.get("sha256").isJsonPrimitive()
                            && isSha256Hex(o.get("sha256").getAsString())) {
                        info.sha256ByVersion.put(o.get("version").getAsString(),
                                o.get("sha256").getAsString().toLowerCase());
                    }
                }
            }
        } else {
            logger.info("更新接口响应来自明文地址，忽略其中的哈希字段: {}", activeBase);
        }

        lastUpdate = info;
        logger.info("更新检查结果: {}", info.describe());
        return info;
    }

    public String queryLatestVersion() throws IOException {
        String response = getWithFallback("/api/versions");

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        if (json.has("latest") && !json.get("latest").isJsonNull()) {
            return json.get("latest").getAsString();
        }
        return null;
    }

    public UpdateInfo getLastUpdate() {
        return lastUpdate;
    }

    private static boolean isSha256Hex(String s) {
        return s != null && s.matches("[0-9a-fA-F]{64}");
    }

    private String postWithFallback(String path, JsonObject body) throws IOException {
        IOException last = null;

        String first = activeBase;
        for (String base : orderedBases(first)) {
            String url = base + path;
            try {
                String resp = http.postJson(url, body);
                if (activeBase == null || !activeBase.equals(base)) {
                    logger.info("版本接口切换到: {}", base);
                    activeBase = base;
                }
                return resp;
            } catch (Exception e) {
                last = new IOException(base + L.t("s_660f74f42e") + e.getMessage(), e);
                logger.warn("接口地址不可用，尝试下一个: {} ({})", base, e.getMessage());
            }
        }

        throw last != null ? last : new IOException("没有可用的版本接口地址");
    }

    private String getWithFallback(String path) throws IOException {
        IOException last = null;

        for (String base : orderedBases(activeBase)) {
            String url = base + path;
            try {
                String resp = http.get(url);
                if (activeBase == null || !activeBase.equals(base)) {
                    logger.info("版本接口切换到: {}", base);
                    activeBase = base;
                }
                return resp;
            } catch (Exception e) {
                last = new IOException(base + L.t("s_660f74f42e") + e.getMessage(), e);
                logger.warn("接口地址不可用，尝试下一个: {} ({})", base, e.getMessage());
            }
        }

        throw last != null ? last : new IOException("没有可用的版本接口地址");
    }

    private String[] orderedBases(String preferred) {
        if (preferred == null || preferred.isBlank()) {
            return API_BASES;
        }
        String[] out = new String[API_BASES.length];
        out[0] = preferred;
        int i = 1;
        for (String b : API_BASES) {
            if (!b.equals(preferred) && i < out.length) {
                out[i++] = b;
            }
        }

        for (; i < out.length; i++) {
            out[i] = API_BASES[i];
        }
        return out;
    }

    public String jarUrl(String version) {
        return JAR_PREFIX + "mc-launcher-" + version + ".jar";
    }

    public Path downloadJar(Path appDataDir, String version, Downloader.DownloadListener listener)
            throws IOException {
        return downloadJar(appDataDir, version, null, listener);
    }

    public Path installVersion(Path appDataDir, String version, String expectSha256,
                               com.mclauncher.update.VersionManifest manifest,
                               java.util.Map<String, String> trustedExtra,
                               Downloader.DownloadListener listener) throws IOException {
        Path jar = downloadJar(appDataDir, version, expectSha256, listener);

        java.util.Map<String, String> extra = new java.util.LinkedHashMap<>();
        if (trustedExtra != null) {
            extra.putAll(trustedExtra);
        }
        if (expectSha256 != null) {
            extra.put(version, expectSha256);
        } else {
            try {
                extra.put(version, FileUtils.sha256(jar));
            } catch (IOException ignored) {

            }
        }

        if (manifest != null) {
            manifest.recordDownloaded(version, jar.getFileName().toString(), extra);
        }
        return jar;
    }

    public Path downloadJar(Path appDataDir, String version, String expectedSha256,
                            Downloader.DownloadListener listener)
            throws IOException {

        if (version == null || version.isBlank()
                || !version.matches("[0-9.\\-]+")) {
            throw new IOException("版本号不合法: " + version);
        }

        String fileName = "mc-launcher-" + version + ".jar";
        Path target = appDataDir.resolve(fileName);

        if (Files.exists(target) && Files.size(target) > 0) {
            if (expectedSha256 == null
                    || expectedSha256.equalsIgnoreCase(FileUtils.sha256(target))) {
                logger.info("该版本的 jar 已存在且校验通过，跳过下载: {}", target);
                if (listener != null) {
                    listener.onComplete(target);
                }
                return target;
            }
            logger.warn("已存在的 jar 与官方哈希不符，删除后重新下载: {}", target);
            Files.deleteIfExists(target);
        }

        Files.createDirectories(appDataDir);

        String url = jarUrl(version);
        logger.info("下载新版本: {}", url);

        Downloader downloader = new Downloader();
        try {
            Downloader.DownloadResult r = downloader.download(url, target, listener);
            if (!r.isSuccess()) {
                throw new IOException("下载失败: " + r.getErrorMessage());
            }
            if (expectedSha256 != null) {
                String actual = FileUtils.sha256(target);
                if (!expectedSha256.equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(target);
                    throw new IOException("下载内容校验失败：SHA-256 与服务器公布的不一致，文件已删除");
                }
                logger.info("下载内容 SHA-256 校验通过: {}", target.getFileName());
            } else {
                logger.info("服务器未提供该版本哈希，下载后未做内容校验: {}", target.getFileName());
            }
            return target;
        } finally {
            downloader.shutdown();
        }
    }

    public static Path getAppDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {

            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, "NCL_Launcher");
    }
}
