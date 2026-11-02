package com.mclauncher.network;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BMCLAPI 客户端
 * 提供对 BMCLAPI 接口的访问
 *
 * BMCLAPI 文档: https://bmclapidoc.bangbang93.com/
 */
public class BMCLApiClient {

    private static final Logger logger = LoggerFactory.getLogger(BMCLApiClient.class);

    // BMCLAPI 基础 URL
    private static final String BMCL_BASE_URL = "https://bmclapi2.bangbang93.com";
    // 官方源基础 URL
    private static final String OFFICIAL_MANIFEST_URL = "https://launchermeta.mojang.com";

    // 版本清单路径
    private static final String BMCL_VERSION_MANIFEST = "/mc/game/version_manifest.json";
    private static final String OFFICIAL_VERSION_MANIFEST = "/mc/game/version_manifest.json";

    private final HttpClientUtils httpClient;
    private final Gson gson;
    private final boolean useBMCL;
    private final Map<String, String> urlCache = new ConcurrentHashMap<>();

    public BMCLApiClient() {
        this(true);
    }

    public BMCLApiClient(boolean useBMCL) {
        this.httpClient = new HttpClientUtils();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.useBMCL = useBMCL;
        logger.info("BMCLAPI 客户端初始化，使用镜像源: {}", useBMCL ? "BMCLAPI" : "官方源");
    }

    /**
     * 获取版本清单
     */
    public VersionManifest getVersionManifest() throws IOException {
        String url = getBaseUrl() + "/mc/game/version_manifest.json";
        logger.info("获取版本清单: {}", url);

        String json = httpClient.get(url);
        VersionManifest manifest = gson.fromJson(json, VersionManifest.class);

        if (manifest != null && manifest.getVersions() != null) {
            logger.info("获取到 {} 个版本", manifest.getVersions().size());
        }

        return manifest;
    }

    /**
     * 获取指定版本的详细信息 JSON
     */
    public String getVersionJson(String versionId) throws IOException {
        String url = getBaseUrl() + "/mc/game/version/" + versionId + "/json";
        logger.info("获取版本 JSON: {}", url);
        return httpClient.get(url);
    }

    /**
     * 获取指定版本的详细 JSON（从版本清单的 URL）
     */
    public String getVersionDetailJson(String versionUrl) throws IOException {
        // 将官方 URL 转换为镜像 URL
        String mirrorUrl = convertToMirror(versionUrl);
        logger.debug("获取版本详情: {}", mirrorUrl);
        return httpClient.get(mirrorUrl);
    }

    /**
     * 获取客户端 JAR 下载 URL
     */
    public String getClientDownloadUrl(String versionId) {
        return getBaseUrl() + "/mc/game/version/" + versionId + "/client";
    }

    /**
     * 获取资源文件下载 URL
     *
     * 两个源的路径结构：
     * - BMCLAPI ：/assets/<hash前两位>/<hash>
     *   （注意不是 /mc/assets，那个路径会 404）
     * - 官方源  ：resources.download.minecraft.net/<hash前两位>/<hash>
     */
    public String getAssetDownloadUrl(String hash, String size) {
        String prefix = hash.substring(0, 2);
        if (useBMCL) {
            return BMCL_BASE_URL + "/assets/" + prefix + "/" + hash;
        }
        return "https://resources.download.minecraft.net/" + prefix + "/" + hash;
    }

    /**
     * 获取库文件下载 URL
     *
     * 注意两个源的结构不同：
     * - BMCLAPI ：/maven/{path}          （镜像了 libraries.minecraft.net）
     * - 官方源  ：libraries.minecraft.net/{path}
     *
     * 若官方模式下误用 /maven/ 前缀会得到 404。
     */
    public String getLibraryDownloadUrl(String path) {
        if (useBMCL) {
            return BMCL_BASE_URL + "/maven/" + path;
        }
        return "https://libraries.minecraft.net/" + path;
    }

    /**
     * 获取 Fabric 版本列表
     */
    public List<String> getFabricVersions(String gameVersion) throws IOException {
        // 依据 BMCLAPI 文档：https://meta.fabricmc.net -> /fabric-meta
        String url = getFabricMetaBase() + "/v2/versions/loader/" + gameVersion;
        logger.info("获取 Fabric 版本列表: {}", url);

        String json = httpClient.get(url);
        Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> list = gson.fromJson(json, type);

        List<String> versions = new ArrayList<>();
        if (list != null) {
            for (Map<String, Object> item : list) {
                Object loader = item.get("loader");
                if (loader instanceof Map) {
                    Object version = ((Map<?, ?>) loader).get("version");
                    if (version != null) {
                        versions.add(version.toString());
                    }
                }
            }
        }
        return versions;
    }

    /**
     * 获取 Fabric 加载器信息
     */
    public String getFabricLoaderJson(String gameVersion, String loaderVersion) throws IOException {
        String url = getFabricMetaBase() + "/v2/versions/loader/" +
                     gameVersion + "/" + loaderVersion + "/profile/json";
        logger.info("获取 Fabric 配置: {}", url);
        return httpClient.get(url);
    }

    /**
     * 获取 Forge 版本列表
     *
     * 重要：Forge / OptiFine 的「版本列表」API **只有 BMCLAPI 提供**，
     * Mojang 官方源没有（https://launchermeta.mojang.com/forge/... 会 404）。
     * 因此这里固定使用 BMCLAPI，与用户选择的「下载源」无关 ——
     * 下载源只影响游戏文件从哪拉，不影响元数据查询。
     */
    public List<String> getForgeVersions(String gameVersion) throws IOException {
        String url = BMCL_BASE_URL + "/forge/minecraft/" + gameVersion;
        logger.info("获取 Forge 版本列表: {}", url);

        try {
            String json = httpClient.get(url);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            List<String> versions = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("version")) {
                    versions.add(obj.get("version").getAsString());
                }
            }
            return versions;
        } catch (Exception e) {
            logger.warn("获取 Forge 版本失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 获取 OptiFine 版本列表
     *
     * 接口返回的是**对象数组**，每项形如：
     *   { "mcversion": "1.20.1", "type": "HD_U", "patch": "I5",
     *     "filename": "OptiFine_1.20.1_HD_U_I5.jar", "forge": "Forge 47.0.35" }
     *
     * 用于展示与下载的版本标识是 type + "_" + patch，例如 "HD_U_I5"。
     * （原实现把它当成字符串数组解析，所以永远得到空列表。）
     *
     * 同 Forge：该 API 只有 BMCLAPI 提供，固定走 BMCLAPI。
     */
    public List<String> getOptifineVersions(String gameVersion) throws IOException {
        String url = BMCL_BASE_URL + "/optifine/" + gameVersion;
        logger.info("获取 OptiFine 版本列表: {}", url);

        try {
            String json = httpClient.get(url);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();

            List<String> versions = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    JsonObject o = element.getAsJsonObject();
                    String type = o.has("type") ? o.get("type").getAsString() : "";
                    String patch = o.has("patch") ? o.get("patch").getAsString() : "";
                    if (!type.isBlank() && !patch.isBlank()) {
                        versions.add(type + "_" + patch);
                    }
                } else if (element.isJsonPrimitive()) {
                    // 兼容可能存在的纯字符串形式
                    versions.add(element.getAsString());
                }
            }
            return versions;
        } catch (Exception e) {
            logger.warn("获取 OptiFine 版本失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 将官方 URL 转换为 BMCLAPI 镜像 URL
     *
     * 重要：BMCLAPI 镜像的是「保持原始路径」的资源服务器，
     * 例如 https://piston-data.mojang.com/v1/objects/<sha1>/client.jar
     *   -> https://bmclapi2.bangbang93.com/v1/objects/<sha1>/client.jar
     *
     * 因此这里只替换域名，绝不能改动路径，否则会得到 404。
     */
    public String convertToMirror(String officialUrl) {
        if (!useBMCL || officialUrl == null || officialUrl.isBlank()) {
            return officialUrl;
        }

        String cached = urlCache.get(officialUrl);
        if (cached != null) {
            return cached;
        }

        String mirrorUrl = officialUrl;

        // 资源类主机：直接换域名，路径原样保留
        String[][] hostMappings = {
                // launchermeta / piston 系列
                {"https://launchermeta.mojang.com", BMCL_BASE_URL},
                {"http://launchermeta.mojang.com", BMCL_BASE_URL},
                {"https://piston-data.mojang.com", BMCL_BASE_URL},
                {"http://piston-data.mojang.com", BMCL_BASE_URL},
                {"https://piston-meta.mojang.com", BMCL_BASE_URL},
                {"http://piston-meta.mojang.com", BMCL_BASE_URL},
                // 资源文件（assets objects）：官方路径是 /<前2位>/<hash>，
                // BMCLAPI 对应路径是 /assets/<前2位>/<hash>
                {"https://resources.download.minecraft.net", BMCL_BASE_URL + "/assets"},
                {"http://resources.download.minecraft.net", BMCL_BASE_URL + "/assets"},
                // 库文件
                {"https://libraries.minecraft.net", BMCL_BASE_URL + "/maven"},
                {"http://libraries.minecraft.net", BMCL_BASE_URL + "/maven"},
        };

        for (String[] mapping : hostMappings) {
            String from = mapping[0];
            if (officialUrl.startsWith(from)) {
                String preserved = officialUrl.substring(from.length());
                mirrorUrl = mapping[1] + preserved;
                break;
            }
        }

        urlCache.put(officialUrl, mirrorUrl);
        return mirrorUrl;
    }

    /**
     * 获取基础 URL
     */
    public String getBaseUrl() {
        return useBMCL ? BMCL_BASE_URL : OFFICIAL_MANIFEST_URL;
    }

    /**
     * 获取 Fabric Meta 服务的基础 URL
     *
     * 说明：Fabric 的元数据 API 在官方站（meta.fabricmc.net）和 BMCLAPI 镜像
     * （/fabric-meta）都可用。这里统一返回 BMCLAPI 镜像，
     * 原因是：
     * 1. 国内访问 fabricmc.net 可能很慢或超时
     * 2. 让「加载器元数据」与用户选择的下载源解耦，行为更可预期
     */
    public String getFabricMetaBase() {
        return BMCL_BASE_URL + "/fabric-meta";
    }

    /**
     * 获取 Fabric 官方 Meta 地址（备用/回退用）
     */
    public String getFabricMetaBaseOfficial() {
        return "https://meta.fabricmc.net";
    }

    /**
     * 获取 Fabric Maven 仓库的基础 URL
     *
     * 依据 BMCLAPI 文档，https://maven.fabricmc.net 对应 /maven
     */
    public String getFabricMavenBase() {
        return useBMCL ? BMCL_BASE_URL + "/maven" : "https://maven.fabricmc.net";
    }

    /**
     * 获取 Forge Maven 仓库的基础 URL
     *
     * 依据 BMCLAPI 文档，https://files.minecraftforge.net/maven 对应 /maven
     */
    public String getForgeMavenBase() {
        return useBMCL ? BMCL_BASE_URL + "/maven" : "https://maven.minecraftforge.net";
    }

    /**
     * 是否使用 BMCLAPI
     */
    public boolean isUseBMCL() {
        return useBMCL;
    }

    /**
     * 获取资源文件索引
     */
    public String getAssetIndex(String assetIndexUrl) throws IOException {
        String mirrorUrl = convertToMirror(assetIndexUrl);
        return httpClient.get(mirrorUrl);
    }

    // ==================== 数据模型 ====================

    /**
     * 版本清单
     */
    public static class VersionManifest {
        private Latest latest;
        private List<Version> versions;

        public Latest getLatest() { return latest; }
        public void setLatest(Latest latest) { this.latest = latest; }
        public List<Version> getVersions() { return versions; }
        public void setVersions(List<Version> versions) { this.versions = versions; }

        public static class Latest {
            private String release;
            private String snapshot;

            public String getRelease() { return release; }
            public void setRelease(String release) { this.release = release; }
            public String getSnapshot() { return snapshot; }
            public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
        }

        public static class Version {
            private String id;
            private String type;
            private String url;
            private String time;
            private String releaseTime;

            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
            public String getType() { return type; }
            public void setType(String type) { this.type = type; }
            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public String getTime() { return time; }
            public void setTime(String time) { this.time = time; }
            public String getReleaseTime() { return releaseTime; }
            public void setReleaseTime(String releaseTime) { this.releaseTime = releaseTime; }

            @Override
            public String toString() {
                return id + " (" + type + ")";
            }
        }
    }
}