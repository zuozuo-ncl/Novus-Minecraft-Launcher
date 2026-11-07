package com.mclauncher.network;


import com.mclauncher.i18n.L;
import com.mclauncher.utils.FileUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 版本更新客户端
 * 注意：下载下来的新版本会放到「启动器数据目录」，与旧版本共存，
 */
public class UpdateClient {

    private static final Logger logger = LoggerFactory.getLogger(UpdateClient.class);

    /**
     * 版本管理服务地址列表（按优先级排列）
     * 为什么要多个：主接口曾多次因中转服务不稳定而连不上，
     */
    private static final String[] API_BASES = {
            "https://version-interface.us.ci",                    // 主力
            // 备用地址必须也是 HTTPS：明文 HTTP 后备会让中间人直接决定
            // 「下载哪个版本、期望是什么哈希」（哈希又只认 HTTPS 来源），
            // 等同把更新通道的信任根交给了网络路径上的任何人。
            // 备用走 frp 隧道：隧道出口是 HTTPS（TLS 在隧道边缘终结，
            // 映射到本服务的 5003 端口），因此同样满足「必须 HTTPS」的要求。
            "https://api.rxfmprc89.nyat.app:57294",               // 备用（HTTPS，frp 隧道）
    };

    /** 当前实际生效的地址（首次成功后记住，避免每次都从头试） */
    private volatile String activeBase = API_BASES[0];

    /** 最近一次 checkUpdate 的完整结果（含版本列表与哈希），供事务写清单/信任表用 */
    private volatile UpdateInfo lastUpdate;

    /** jar 下载地址前缀 */
    private static final String JAR_PREFIX = "https://3984235.xyz/";

    private final HttpClientUtils http;

    public UpdateClient() {
        this.http = new HttpClientUtils();
    }

    // ==================== 数据模型 ====================

    /**
     * 更新检查结果
     */
    public static class UpdateInfo {
        /** 是否有新版本 */
        public boolean updateAvailable;
        /** 服务器上的最新版本号（无更新时为 null/空） */
        public String latestVersion;
        /** 当前版本号 */
        public String currentVersion;
        /** 服务器上的全部版本（可选） */
        public java.util.List<String> allVersions = new java.util.ArrayList<>();

        /** 最新版本 jar 的官方 SHA-256（服务器给了才有；来自 HTTPS 接口） */
        public String latestSha256;

        /** 版本号 -> SHA-256（服务器给了才有；来自 HTTPS 接口） */
        public final java.util.Map<String, String> sha256ByVersion = new java.util.HashMap<>();

        /** 面向用户的描述 */
        public String describe() {
            if (updateAvailable) {
                return L.t("s_bdeaca9059") + latestVersion + L.t("s_c7b01cbc21") + currentVersion + "）";
            }
            return L.t("s_dcf5866577") + currentVersion + "）";
        }
    }

    // ==================== 接口 ====================

    /** 检查更新 */
    public UpdateInfo checkUpdate(String currentVersion) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("currentVersion", currentVersion);

        logger.info("检查更新: 当前版本 {}", currentVersion);

        // 多地址尝试：主力失败就换备用
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

        // latestVersion 可能是 null
        if (json.has("latestVersion") && !json.get("latestVersion").isJsonNull()) {
            info.latestVersion = json.get("latestVersion").getAsString();
        }

        // currentVersion 字段名在不同分支下可能不同，两个都试
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

        // 哈希只认 HTTPS 响应的：备用地址是明文 HTTP，从那里拿哈希等于让中间人
        // 同时控制「下载哪个文件」和「期望是什么哈希」，校验就失去意义了。
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

    /**
     * 获取服务器上的最新版本号
     */
    public String queryLatestVersion() throws IOException {
        String response = getWithFallback("/api/versions");

        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        if (json.has("latest") && !json.get("latest").isJsonNull()) {
            return json.get("latest").getAsString();
        }
        return null;
    }

    /** 最近一次 checkUpdate 的结果（可能为 null） */
    public UpdateInfo getLastUpdate() {
        return lastUpdate;
    }

    /** 是否为 64 位十六进制 */
    private static boolean isSha256Hex(String s) {
        return s != null && s.matches("[0-9a-fA-F]{64}");
    }

    // ==================== 多地址容错 ====================

    /**
     * POST，失败时依次尝试其它地址
     *
     * 成功的地址会被记住，后续请求优先用它，避免每次都从主力开始试。
     */
    private String postWithFallback(String path, JsonObject body) throws IOException {
        IOException last = null;

        // 先用上次成功的地址（若与主力不同，也先试它）
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

    /**
     * GET，失败时依次尝试其它地址
     */
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

    /**
     * 生成尝试顺序：优先用传入的地址，其余按原顺序补齐
     *
     * 这样「上次成功的地址」会被放到最前面，常见情况下一次就命中。
     */
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
        // 极端情况下（preferred 不在列表里）剩下的位置保持原顺序
        for (; i < out.length; i++) {
            out[i] = API_BASES[i];
        }
        return out;
    }

    /**
     * jar 的下载地址
     */
    public String jarUrl(String version) {
        return JAR_PREFIX + "mc-launcher-" + version + ".jar";
    }

    /** 对应 jar 的官方 .sha256 文件地址（发布脚本会上传；404 时由调用方跳过） */
    public String jarShaUrl(String version) {
        return JAR_PREFIX + "mc-launcher-" + version + ".jar.sha256";
    }

    /**
     * 没有随接口下发的哈希时，去文件服务器取同目录的 .sha256。
     * 注意这是「弱兜底」：文件和哈希同域，只能防止下载过程损坏/被临时替换，
     */
    public String fetchOfficialSha256(String version) {
        if (version == null || !version.matches("[0-9.\\-]+")) {
            return null;
        }
        try {
            String body = http.get(jarShaUrl(version));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([0-9a-fA-F]{64})").matcher(body);
            if (m.find()) {
                return m.group(1).toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Exception e) {
            logger.info("取不到官方 .sha256（{}）: {}", version, e.getMessage());
        }
        return null;
    }

    /** 下载指定版本的 jar 到数据目录（保留旧版本，不删除） */
    public Path downloadJar(Path appDataDir, String version, Downloader.DownloadListener listener)
            throws IOException {
        return downloadJar(appDataDir, version, null, listener);
    }

    /** 安装新版本（事务式） */
    public Path installVersion(Path appDataDir, String version, String expectSha256,
                               com.mclauncher.update.VersionManifest manifest,
                               java.util.Map<String, String> trustedExtra,
                               Downloader.DownloadListener listener) throws IOException {
        Path jar = downloadJar(appDataDir, version, expectSha256, listener);

        // 官方哈希没有时，用「刚下载并已校验的内容」自己算一个，一起登记
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
                // 算不出来就不登记，引导器会按「无锚点」放行一次
            }
        }

        if (manifest != null) {
            manifest.recordDownloaded(version, jar.getFileName().toString(), extra);
        }
        return jar;
    }

    /** 下载指定版本的 jar，可选地校验 SHA-256 */
    public Path downloadJar(Path appDataDir, String version, String expectedSha256,
                            Downloader.DownloadListener listener)
            throws IOException {

        if (version == null || version.isBlank()
                || !version.matches("[0-9.\\-]+")) {
            throw new IOException("版本号不合法: " + version);
        }

        String fileName = "mc-launcher-" + version + ".jar";
        Path target = appDataDir.resolve(fileName);

        // 已存在：有期望哈希时必须校验（不符删掉重下），没有哈希时沿用「非空即用」
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
                // 没有哈希时不能盲信：CDN / 反代返回 404 页面或错误 JSON 时
                // 状态码可能是 200。这里做两层弱校验：
                //   · 必须是 ZIP；
                //   · 解压后必须能打开、含主清单（Entry-Point），排除「假 jar」。
                // 想更严格可以再加签名，但引导器侧仍会按信任表最终把关。
                if (!isZipFile(target) || !looksLikeLauncherJar(target)) {
                    Files.deleteIfExists(target);
                    throw new IOException("下载到的文件不是有效的启动器 jar（可能是错误页面），已删除");
                }
                logger.warn("服务器未提供该版本哈希，只做了格式校验（建议服务端补登记 sha256）: {}",
                        target.getFileName());
            }
            return target;
        } finally {
            downloader.shutdown();
        }
    }

    /** 文件开头是否为 ZIP 魔数（PK / PK / PK） */
    private static boolean looksLikeLauncherJar(Path f) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f.toFile())) {
            java.util.zip.ZipEntry mf = zf.getEntry("META-INF/MANIFEST.MF");
            if (mf == null) {
                return false;
            }
            String text = new String(zf.getInputStream(mf).readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            return text.contains("Main-Class");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isZipFile(Path f) {
        try (InputStream in = Files.newInputStream(f)) {
            byte[] h = in.readNBytes(4);
            return h.length == 4 && h[0] == 'P' && h[1] == 'K'
                    && ((h[2] == 3 && h[3] == 4) || (h[2] == 5 && h[3] == 6)
                        || (h[2] == 7 && h[3] == 8));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 获取启动器数据目录（与 C++ 引导器一致：%APPDATA%\NCL_Launcher）
     */
    public static Path getAppDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            // 非 Windows 或环境变量缺失时退回用户主目录
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, "NCL_Launcher");
    }
}