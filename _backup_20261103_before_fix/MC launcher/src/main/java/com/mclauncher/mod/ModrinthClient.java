package com.mclauncher.mod;


import com.mclauncher.i18n.L;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.network.NetworkConstants;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Modrinth API 客户端
 *
 * 文档: https://docs.modrinth.com/api/
 *
 * 说明：
 * 本阶段只实现「搜索 -> 选版本 -> 下载 jar」。
 * 不处理依赖关系解析与整合包安装。
 *
 * ===== 关于镜像 (mod.mcimirror.top) =====
 * Modrinth 官方域名 api.modrinth.com 在部分网络环境下可访问，
 * 但 cdn.modrinth.com（文件下载）常被阻断。
 * 因此这里统一走国内镜像 mod.mcimirror.top：
 *   - API   : https://mod.mcimirror.top/modrinth/v2/...
 *   - 文件  : https://mod.mcimirror.top/data/{project_id}/versions/{version_id}/{file_name}
 *
 * 该镜像只镜像 API；文件端点会 302 重定向到实际源。
 * 若重定向目标不可达，可通过 setUseMirror(false) 切回官方源。
 *
 * ===== 关于 User-Agent =====
 * Modrinth 要求所有请求携带可识别身份的 UA，
 * 格式为 `启动器名/版本号 (联系方式)`。
 */
public class ModrinthClient {

    private static final Logger logger = LoggerFactory.getLogger(ModrinthClient.class);

    /** 官方 API 基址 */
    private static final String OFFICIAL_API_BASE = "https://api.modrinth.com/v2";
    /** 镜像 API 基址 */
    private static final String MIRROR_API_BASE = "https://mod.mcimirror.top/modrinth/v2";

    /** 官方文件 CDN 基址（用于把 URL 改写到镜像） */
    private static final String OFFICIAL_CDN_BASE = "https://cdn.modrinth.com";
    /** 镜像文件基址 */
    private static final String MIRROR_FILE_BASE = "https://mod.mcimirror.top";

    /** 单次请求的最大返回条数（Modrinth 上限为 100） */
    private static final int MAX_LIMIT = 100;

    private final OkHttpClient httpClient;
    private final Gson gson;

    /**
     * projectId -> raw_icon_url（值为 null 表示「查过但没有」）
     *
     * JavaFX 解不了 WebP，热门模组的图标都需要走一次原始格式查询，
     * 缓存可以避免列表滚动时反复请求 API。
     */
    private static final java.util.Map<String, String> RAW_ICON_CACHE
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * API 是否走国内镜像
     *
     * 实测结论（2026-10-24）：
     *   同一份 190KB 数据 — 镜像 8.1 秒，官方源 1.2 秒
     *   同一份 536KB 版本列表 — 镜像 17.1 秒，官方源 2.0 秒
     * 即**镜像对 API 响应体做了限速（约 23KB/s，官方约 150KB/s）**。
     * 因此 API 默认走官方源。
     *
     * 若某网络环境下 api.modrinth.com 不可达，可置为 true 切回镜像。
     */
    private final boolean useMirrorApi;

    /**
     * 文件/图标是否走国内镜像
     *
     * 文件 CDN（cdn.modrinth.com）在国内**不可达**（实测 curl 返回 000），
     * 而镜像的文件服务很快（图标 0.3-0.9 秒）。因此文件始终走镜像。
     */
    private final boolean useMirrorFiles;

    public ModrinthClient() {
        // 默认：API 官方源 + 文件镜像源
        this(false, true);
    }

    /** 兼容旧签名：同时控制 API 与文件 */
    public ModrinthClient(boolean useMirror) {
        this(useMirror, useMirror);
    }

    public ModrinthClient(boolean useMirrorApi, boolean useMirrorFiles) {
        this.useMirrorApi = useMirrorApi;
        this.useMirrorFiles = useMirrorFiles;
        this.gson = new Gson();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(NetworkConstants.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(NetworkConstants.API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                // Modrinth 要求带可识别身份的 UA
                                .header("User-Agent", NetworkConstants.USER_AGENT)
                                .header("Accept", "application/json")
                                .build()))
                .build();

        logger.info("Modrinth 客户端初始化：API 源={}，文件源={}",
                useMirrorApi ? "国内镜像" : "官方",
                useMirrorFiles ? "国内镜像" : "官方");
    }

    /**
     * 获取 API 基址
     */
    private String apiBase() {
        return useMirrorApi ? MIRROR_API_BASE : OFFICIAL_API_BASE;
    }

    /**
     * 把文件 URL 改写到镜像
     *
     * 镜像返回的 files[].url 仍指向 cdn.modrinth.com，
     * 必须改写为 mod.mcimirror.top/data/... 才能走镜像。
     *
     * 官方格式: https://cdn.modrinth.com/data/{project}/versions/{version}/{file}
     * 镜像格式: https://mod.mcimirror.top/data/{project}/versions/{version}/{file}
     */
    /**
     * 下载图片字节（用于模组图标）
     *
     * 为什么不用 JavaFX 的 Image(url) 直接加载：
     * 实测 JavaFX 内部用 HttpsURLConnection 直连，在部分网络环境下
     * 会 Connection timed out（而 OkHttp/curl 能正常访问）。
     * 因此统一走这里的 OkHttp（已配好 UA 与重定向），
     * 拿到字节后再交给 JavaFX 解码。
     *
     * @param url 图标 URL（会自动改写到镜像）
     * @return 图片字节；失败返回 null
     */
    public byte[] downloadImageBytes(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        // 依次尝试：镜像改写后的地址 -> 原始地址
        //
        // 为什么要两个都试：镜像站可能已停服（实测 mod.mcimirror.top
        // 会返回 HTTP 200 + 一个 HTML「服务不可用」页面）。
        // 但**只在网络层失败时**才回退 —— 若镜像明确回了「不是图片」
        // 的内容，说明它在正常工作、只是这个资源没有，再试原始地址
        // 只会白等一轮超时（原始 CDN 在国内同样不通）。
        // 快速通道：URL 已经是 .webp 时**直接跳过首次下载**。
        // JavaFX 没有 WebP 解码器，下下来也用不了；每张图省掉一次
        // 下载 + 一次「查原始地址」的往返。实测这是热门模组图标慢的主因。
        if (url.toLowerCase().endsWith(".webp")) {
            byte[] raw = fetchRawIcon(url);
            if (raw != null && isDecodable(raw)) {
                return raw;
            }
            // 原始图也拿不到时，仍尝试原 URL（极少数项目 raw_icon_url 也是 webp）
        }

        String mirrored = toMirrorFileUrl(url);
        boolean sameUrl = mirrored.equals(url);

        byte[] fromMirror = fetchImage(mirrored);
        if (fromMirror != null) {
            // 关键：校验能不能解码。镜像确实有这张图，但如果它是 JavaFX 解不了的格式
            // （典型：WebP），直接返回等于「下载成功、界面空白」—— 必须继续找替代格式。
            if (isDecodable(fromMirror)) {
                return fromMirror;
            }
            logger.debug("图标格式不支持 JPEG/PNG 解码器，尝试原始格式: {}", url);
        }
        if (sameUrl) {
            return null;
        }

        if (!sameUrl) {
            logger.debug("镜像未取到图标，尝试原始地址: {}", url);
            byte[] fromOrigin = fetchImage(url);
            if (fromOrigin != null && isDecodable(fromOrigin)) {
                logger.info("图标已改用原始源: {}", url);
                return fromOrigin;
            }
        }

        // 3) 原始格式兜底：Modrinth 的 icon_url 是压缩过的 _96.webp 缩略图，
        //    而 /v2/projects 的 raw_icon_url 指向作者上传的原始图片（PNG / JPEG）。
        //    JavaFX 解不了 WebP，所以这一步才是热门模组图标能显示的关键。
        byte[] raw = fetchRawIcon(url);
        if (raw != null && isDecodable(raw)) {
            logger.debug("图标改用原始格式: {}", url);
            return raw;
        }
        return null;
    }

    /**
     * 取作者的原始上传图标
     *
     * 只在需要时（下载到的图解码不了）调用，而且**每个项目只查一次**，
     * 结果不论成败都记进 RAW_ICON_CACHE —— 列表滚动会反复请求同一批图标。
     *
     * @param iconUrl 形如 https://cdn.modrinth.com/data/{projectId}/{hash}_96.webp
     * @return PNG/JPEG 字节；拿不到返回 null
     */
    private byte[] fetchRawIcon(String iconUrl) {
        String projectId = projectIdOf(iconUrl);
        if (projectId == null) {
            return null;
        }

        String rawUrl;
        synchronized (RAW_ICON_CACHE) {
            if (RAW_ICON_CACHE.containsKey(projectId)) {
                rawUrl = RAW_ICON_CACHE.get(projectId);
            } else {
                rawUrl = queryRawIconUrl(projectId);
                RAW_ICON_CACHE.put(projectId, rawUrl);   // null 也缓存，避免反复请求
            }
        }
        if (rawUrl == null) {
            return null;
        }

        // 原始图是作者上传的完整尺寸，实测 200KB~350KB。
        // 必须用宽超时的客户端：IMAGE_CLIENT 的 5 秒读超时对它们来说太短，
        // 会被判成失败（表现为「图标一直是占位字母」）。
        //
        // 直接走官方 CDN：镜像对原始图路径一律返回 567（实测），
        // 试镜像只会多等一次往返。
        return fetchImage(RAW_ICON_CLIENT, rawUrl);
    }

    /** 从 CDN 图标 URL 里取出 projectId */
    private String projectIdOf(String iconUrl) {
        if (iconUrl == null) {
            return null;
        }
        int i = iconUrl.indexOf("/data/");
        if (i < 0) {
            return null;
        }
        int start = i + "/data/".length();
        int end = iconUrl.indexOf('/', start);
        return end > start ? iconUrl.substring(start, end) : null;
    }

    /**
     * 查项目的 raw_icon_url
     *
     * 用 /v2/projects?ids=[...] 批量接口，单次只查一个也能用；
     * 失败返回 null（调用方会退回首字母占位图）。
     */
    private String queryRawIconUrl(String projectId) {
        try {
            // ids 是 JSON 数组，整体做一次 URL 编码
            String url = apiBase() + "/projects?ids=" + java.net.URLEncoder.encode(
                    "[\"" + projectId + "\"]", StandardCharsets.UTF_8);
            String body = get(url);
            if (body == null || !body.trim().startsWith("[")) {
                return null;
            }
            for (var e : JsonParser.parseString(body).getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject p = e.getAsJsonObject();
                String raw = optString(p, "raw_icon_url");
                if (!raw.isBlank()) {
                    return raw;
                }
            }
        } catch (Exception e) {
            logger.debug("查询原始图标失败 {}: {}", projectId, e.getMessage());
        }
        return null;
    }

    /**
     * 判断字节流能否被 JavaFX 解码
     *
     * 实测 JavaFX 17 **没有 WebP 解码器**（抛 "No loader for image data"），
     * 而 Modrinth 的 icon_url 绝大多数是 WebP。这里只放行 PNG/JPEG/GIF/BMP。
     *
     * 在后台线程调用，线程安全。
     */
    private boolean isDecodable(byte[] b) {
        if (b == null || b.length < 8) {
            return false;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return true;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return true;
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return true;
        }
        return b[0] == 'B' && b[1] == 'M';
    }

    /**
     * 下载图片并校验内容确实是图片
     *
     * 关键：**不能只看 HTTP 状态码**。
     * 实测镜像站在停服期间返回的是 200 + HTML 错误页，
     * 只看 isSuccessful() 会把 HTML 当成图片交给 JavaFX 解码，
     * 结果是既没图标、也刷不出错误日志。
     *
     * 用独立的短超时客户端：图标是列表里的小图，
     * 等 20 秒才失败会让整个列表卡住不动。3 秒拿不到就当没有。
     */
    private byte[] fetchImage(String target) {
        return fetchImage(IMAGE_CLIENT, target);
    }

    private byte[] fetchImage(OkHttpClient client, String target) {
        try {
            Request request = new Request.Builder()
                    .url(target)
                    .header("User-Agent", NetworkConstants.USER_AGENT)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    logger.debug("图标下载失败: HTTP {} - {}", response.code(), target);
                    return null;
                }

                String contentType = response.header("Content-Type", "");
                byte[] bytes = response.body().bytes();

                if (!looksLikeImage(bytes, contentType)) {
                    logger.debug("响应不是图片（可能是错误页）: type={} len={} - {}",
                            contentType, bytes.length, target);
                    return null;
                }
                return bytes;
            }
        } catch (Exception e) {
            logger.debug("图标下载异常: {} - {}", target, e.getMessage());
            return null;
        }
    }

    /**
     * 图标专用 HTTP 客户端
     *
     * 与主客户端分开的原因：
     *   - 超时要短（3 秒）—— 图标失败不该拖慢列表渲染
     *   - Accept 要 image/*，不能沿用主客户端的 application/json
     */
    private static final OkHttpClient IMAGE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    /**
     * 原始图标专用客户端
     *
     * 原始图是作者上传的完整尺寸（实测最大 350KB+），国内直连官方 CDN 时
     * 单张可能要 10 秒。给它单独放宽超时，并复用连接 ——
     * 图标是一批一批出现的，连接复用能省掉每张一次的 TLS 握手。
     */
    private static final OkHttpClient RAW_ICON_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    /**
     * 判断字节流是否真的是图片
     *
     * 查文件头（magic number），这是最可靠的判断 ——
     * Content-Type 可能是错的或缺失，但文件头骗不了人。
     */
    private boolean looksLikeImage(byte[] b, String contentType) {
        if (b == null || b.length < 12) {
            return false;
        }
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return true;
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return true;
        }
        // GIF: 47 49 46 38
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return true;
        }
        // WebP: "RIFF"...."WEBP"
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return true;
        }
        // BMP: 42 4D
        if (b[0] == 'B' && b[1] == 'M') {
            return true;
        }
        // 兜底：Content-Type 明确说是图片，且内容不是 HTML
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return !isHtml(b);
        }
        return false;
    }

    /** 内容开头是否是 HTML（错误页的典型特征） */
    private boolean isHtml(byte[] b) {
        int n = Math.min(b.length, 64);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (b[i] & 0xFF));
        }
        String head = sb.toString().trim().toLowerCase();
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head");
    }

    public String toMirrorFileUrl(String originalUrl) {
        if (!useMirrorFiles || originalUrl == null || originalUrl.isBlank()) {
            return originalUrl;
        }

        if (originalUrl.startsWith(OFFICIAL_CDN_BASE)) {
            String mirrored = MIRROR_FILE_BASE + originalUrl.substring(OFFICIAL_CDN_BASE.length());
            logger.debug("文件 URL 改写为镜像: {}", mirrored);
            return mirrored;
        }

        return originalUrl;
    }

    // ==================== 搜索 ====================

    /**
     * 搜索模组
     *
     * @param query        关键词（可为空，表示按条件浏览）
     * @param gameVersion  游戏版本筛选（如 "1.20.1"，可为 null）
     * @param loader       加载器筛选（如 "fabric" / "forge"，可为 null）
     * @param offset       分页偏移
     * @param limit        返回条数（上限 100）
     */
    public SearchResult search(String query, String gameVersion, String loader,
                               int offset, int limit) throws IOException {
        return search(query, gameVersion, loader, offset, limit, null);
    }

    /**
     * 搜索模组（可指定排序方式）
     *
     * @param index 排序方式，Modrinth 支持：
     *              relevance（相关度，默认）/ downloads（下载量）/ follows（关注数）/
     *              newest（最新）/ updated（最近更新）
     *              传 null 使用默认（relevance）
     */
    public SearchResult search(String query, String gameVersion, String loader,
                               int offset, int limit, String index) throws IOException {
        return search(query, gameVersion, loader, offset, limit, index, "mod");
    }

    /**
     * 搜索指定类型的项目
     *
     * @param projectType Modrinth 的 project_type：mod / modpack / resourcepack / shader / datapack
     */
    public SearchResult search(String query, String gameVersion, String loader,
                               int offset, int limit, String index,
                               String projectType) throws IOException {
        StringBuilder url = new StringBuilder(apiBase()).append("/search");

        List<String> params = new ArrayList<>();
        params.add("query=" + encode(query == null ? "" : query));
        params.add("offset=" + Math.max(0, offset));
        params.add("limit=" + Math.min(Math.max(1, limit), MAX_LIMIT));

        // 排序方式：空查询时用 downloads 才能得到「热门榜」
        if (index != null && !index.isBlank()) {
            params.add("index=" + encode(index));
        }

        // facets 用于按分类/版本过滤。
        // 语法：二维 JSON 数组，外层数组内各元素之间是 AND，元素内部各值是 OR。
        // 例如 [["categories:fabric"],["versions:1.20.1"]]
        List<String> facetGroups = new ArrayList<>();

        if (loader != null && !loader.isBlank()) {
            facetGroups.add("[\"categories:" + loader.toLowerCase() + "\"]");
        }
        if (gameVersion != null && !gameVersion.isBlank()) {
            facetGroups.add("[\"versions:" + gameVersion + "\"]");
        }
        // 只搜指定类型（排除资源包等）
        facetGroups.add("[\"project_type:" + projectType + "\"]");

        if (!facetGroups.isEmpty()) {
            params.add("facets=" + encode("[" + String.join(",", facetGroups) + "]"));
        }

        url.append("?").append(String.join("&", params));

        String body = get(url.toString());
        return parseSearchResult(body);
    }

    /**
     * 解析搜索结果
     * 注：搜索结果的 hits 里没有 loaders 字段，
     * 加载器信息体现在 display_categories 中。
     */
    private SearchResult parseSearchResult(String json) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("解析搜索结果失败: " + e.getMessage(), e);
        }

        SearchResult result = new SearchResult();
        result.setTotalHits(root.has("total_hits") ? root.get("total_hits").getAsInt() : 0);
        result.setOffset(root.has("offset") ? root.get("offset").getAsInt() : 0);
        result.setLimit(root.has("limit") ? root.get("limit").getAsInt() : 0);

        List<ModProject> projects = new ArrayList<>();
        JsonArray hits = root.has("hits") ? root.getAsJsonArray("hits") : new JsonArray();

        for (var element : hits) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject hit = element.getAsJsonObject();

            ModProject p = new ModProject();
            p.setProjectId(optString(hit, "project_id"));
            p.setSlug(optString(hit, "slug"));
            p.setTitle(optString(hit, "title"));
            p.setDescription(optString(hit, "description"));
            p.setIconUrl(optString(hit, "icon_url"));
            p.setAuthor(optString(hit, "author"));
            p.setDownloads(hit.has("downloads") ? hit.get("downloads").getAsLong() : 0);
            p.setLatestVersionId(optString(hit, "latest_version"));

            // 支持的加载器：从 display_categories 里提取
            if (hit.has("display_categories")) {
                for (var c : hit.getAsJsonArray("display_categories")) {
                    String cat = c.getAsString();
                    if (isLoaderName(cat)) {
                        p.getLoaders().add(cat);
                    }
                }
            }

            // 支持的游戏版本
            if (hit.has("versions")) {
                for (var v : hit.getAsJsonArray("versions")) {
                    p.getGameVersions().add(v.getAsString());
                }
            }

            projects.add(p);
        }

        result.setProjects(projects);
        logger.info("搜索到 {} 个结果（总计 {}）", projects.size(), result.getTotalHits());
        return result;
    }

    /**
     * 判断分类名是否为加载器名（而非功能分类如 optimization）
     */
    private boolean isLoaderName(String category) {
        return switch (category.toLowerCase()) {
            case "fabric", "forge", "neoforge", "quilt", "liteloader", "rift",
                 "modloader", "risugamis-mods" -> true;
            default -> false;
        };
    }

    // ==================== 版本 ====================

    /**
     * 获取模组的版本列表
     *
     * @param projectId    项目 ID 或 slug
     * @param gameVersion  游戏版本筛选（可为 null）
     * @param loader       加载器筛选（可为 null）
     */
    public List<ModVersion> getVersions(String projectId, String gameVersion, String loader)
            throws IOException {
        StringBuilder url = new StringBuilder(apiBase())
                .append("/project/").append(encodePath(projectId)).append("/version");

        List<String> params = new ArrayList<>();

        // 注意：版本接口用 loaders / game_versions 数组参数，不是 facets
        if (loader != null && !loader.isBlank()) {
            params.add("loaders=" + encode("[\"" + loader.toLowerCase() + "\"]"));
        }
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.add("game_versions=" + encode("[\"" + gameVersion + "\"]"));
        }

        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }

        String body = get(url.toString());
        return parseVersions(body);
    }

    /**
     * 只取最新的 N 个版本（首屏用）
     *
     * 完整版本列表对热门模组有 1MB 以上、要十几秒；
     * `?limit=1` 只返回最新一条，几百毫秒就能让界面有内容。
     */
    public List<ModVersion> getLatestVersions(String projectId, int limit) throws IOException {
        String url = apiBase() + "/project/" + encodePath(projectId)
                + "/version?limit=" + Math.max(1, Math.min(limit, 10));
        return parseVersions(get(url));
    }

    /** 版本列表单页条数上限（Modrinth 接口上限是 100） */
    public static final int VERSION_PAGE_SIZE = 100;

    /**
     * 分页拉取项目的完整版本列表
     *
     * 之前一次性取全量：热门项目 258 个版本、530KB，整段解析要好几秒，
     * 期间界面只有「正在加载完整版本列表」。现在按页拉，每页到了就回调一次，
     * 界面逐步补全；同时用 offset 探测是否还有下一页。
     *
     * @param firstPageSize 第一页条数（首屏要快就传小值）
     * @param onPage 每拉到一页就回调（在调用线程执行，调用方自己切到 FX 线程）
     * @return 是否全部拉完（false 表示中途失败，但已回调的部分有效）
     */
    public boolean fetchVersionsPaged(String projectId, int firstPageSize,
                                      java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<List<ModVersion>> onPage)
            throws IOException {
        int offset = 0;
        int limit = Math.max(1, Math.min(firstPageSize, VERSION_PAGE_SIZE));
        int fetched = 0;
        while (true) {
            // 每页开始前检查取消：玩家关掉窗口后不该再把剩下的几百个版本下完
            if (cancelled != null && cancelled.getAsBoolean()) {
                return false;
            }
            String url = apiBase() + "/project/" + encodePath(projectId)
                    + "/version?limit=" + limit + "&offset=" + offset;
            List<ModVersion> page = parseVersions(get(url));
            onPage.accept(page);
            fetched += page.size();
            if (page.size() < limit) {
                // 最后一页不满。但如果服务端分页本身把数据漏了（实测 Sodium：258 个版本，
                // offset=160&limit=100 只回 94 条，而 offset=250 仍有数据），
                // 直接停会静默少掉几十个版本。用一次全量请求核对，多出来就整体补发。
                List<ModVersion> all = parseVersions(get(apiBase() + "/project/"
                        + encodePath(projectId) + "/version"));
                if (all.size() > fetched) {
                    onPage.accept(all);
                }
                return true;
            }
            offset = fetched;
            limit = VERSION_PAGE_SIZE;   // 后续统一 100 条一页
        }
    }

    /**
     * 解析版本列表
     */
    private List<ModVersion> parseVersions(String json) throws IOException {
        List<ModVersion> versions = new ArrayList<>();

        JsonArray array;
        try {
            var parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                // 出错时接口可能返回对象（含 error 字段）
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("error")) {
                    throw new IOException("Modrinth 返回错误: "
                            + parsed.getAsJsonObject().get("error").getAsString()
                            + " - "
                            + (parsed.getAsJsonObject().has("description")
                                ? parsed.getAsJsonObject().get("description").getAsString() : ""));
                }
                throw new IOException("版本列表格式异常");
            }
            array = parsed.getAsJsonArray();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解析版本列表失败: " + e.getMessage(), e);
        }

        for (var element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject v = element.getAsJsonObject();

            ModVersion ver = new ModVersion();
            ver.setId(optString(v, "id"));
            ver.setProjectId(optString(v, "project_id"));
            ver.setName(optString(v, "name"));
            ver.setVersionNumber(optString(v, "version_number"));
            ver.setVersionType(optString(v, "version_type"));
            ver.setDatePublished(optString(v, "date_published"));
            ver.setDownloads(v.has("downloads") ? v.get("downloads").getAsLong() : 0);

            if (v.has("loaders")) {
                for (var l : v.getAsJsonArray("loaders")) {
                    ver.getLoaders().add(l.getAsString());
                }
            }
            if (v.has("game_versions")) {
                for (var g : v.getAsJsonArray("game_versions")) {
                    ver.getGameVersions().add(g.getAsString());
                }
            }

            // 解析文件列表
            if (v.has("files")) {
                for (var f : v.getAsJsonArray("files")) {
                    if (!f.isJsonObject()) {
                        continue;
                    }
                    JsonObject fo = f.getAsJsonObject();

                    ModFile file = new ModFile();
                    file.setUrl(optString(fo, "url"));
                    file.setFilename(optString(fo, "filename"));
                    file.setPrimary(fo.has("primary") && fo.get("primary").getAsBoolean());
                    file.setSize(fo.has("size") ? fo.get("size").getAsLong() : 0);

                    // 哈希：同时提供 sha1 和 sha512
                    if (fo.has("hashes") && fo.get("hashes").isJsonObject()) {
                        JsonObject hashes = fo.getAsJsonObject("hashes");
                        if (hashes.has("sha1")) {
                            file.setSha1(hashes.get("sha1").getAsString());
                        }
                        if (hashes.has("sha512")) {
                            file.setSha512(hashes.get("sha512").getAsString());
                        }
                    }

                    ver.getFiles().add(file);
                }
            }

            versions.add(ver);
        }

        logger.info("获取到 {} 个版本", versions.size());
        return versions;
    }

    /**
     * 按 ID 取单个版本（含文件列表）
     *
     * 整合包安装需要：从搜索结果的 latest_version 直接拿到文件清单。
     */
    public ModVersion getVersion(String versionId) throws IOException {
        String body = get(apiBase() + "/version/" + encodePath(versionId));
        List<ModVersion> parsed = parseVersions("[" + body + "]");
        if (parsed.isEmpty()) {
            throw new IOException("版本 " + versionId + " 解析失败");
        }
        return parsed.get(0);
    }

    /**
     * 获取模组详情（当前界面暂未使用，保留供后续扩展）
     */
    public JsonObject getProject(String projectId) throws IOException {
        return JsonParser.parseString(
                get(apiBase() + "/project/" + encodePath(projectId))).getAsJsonObject();
    }

    // ==================== 内部工具 ====================

    /**
     * 执行 GET 请求
     */
    private String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                // 403 通常意味着 UA 不合规或被限流
                if (response.code() == 403) {
                    throw new IOException(
                            "Modrinth 拒绝了请求（403）。可能是 User-Agent 不合规或请求过于频繁。");
                }
                if (response.code() == 404) {
                    throw new IOException("未找到资源（404）");
                }
                if (response.code() == 429) {
                    throw new IOException("请求过于频繁（429），请稍后再试");
                }
                throw new IOException("Modrinth 请求失败: HTTP " + response.code()
                        + " - " + truncate(body, 200));
            }

            return body;
        }
    }

    /** URL 查询参数编码 */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** URL 路径段编码（保留字母数字和 -_.~，用于 slug） */
    private String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 关闭客户端
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ==================== 数据模型 ====================

    /** 搜索结果 */
    public static class SearchResult {
        private int totalHits;
        private int offset;
        private int limit;
        private List<ModProject> projects = new ArrayList<>();

        public int getTotalHits() { return totalHits; }
        public void setTotalHits(int totalHits) { this.totalHits = totalHits; }
        public int getOffset() { return offset; }
        public void setOffset(int offset) { this.offset = offset; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public List<ModProject> getProjects() { return projects; }
        public void setProjects(List<ModProject> projects) { this.projects = projects; }
    }

    /** 模组项目 */
    public static class ModProject {
        private String projectId;
        private String slug;
        private String title;
        private String description;
        private String iconUrl;
        private String author;
        private long downloads;
        private String latestVersionId;
        private final List<String> loaders = new ArrayList<>();
        private final List<String> gameVersions = new ArrayList<>();

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getIconUrl() { return iconUrl; }
        public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public long getDownloads() { return downloads; }
        public void setDownloads(long downloads) { this.downloads = downloads; }
        public String getLatestVersionId() { return latestVersionId; }
        public void setLatestVersionId(String v) { this.latestVersionId = v; }
        public List<String> getLoaders() { return loaders; }
        public List<String> getGameVersions() { return gameVersions; }

        /** 用于列表展示的文本 */
        public String toDisplayString() {
            StringBuilder sb = new StringBuilder();
            sb.append(title);
            if (!loaders.isEmpty()) {
                sb.append("  [").append(String.join("/", loaders)).append("]");
            }
            sb.append("\n    ").append(description == null ? "" :
                    (description.length() > 90 ? description.substring(0, 90) + "..." : description));
            sb.append(L.t("s_a344149992")).append(formatDownloads(downloads));
            return sb.toString();
        }

        private static String formatDownloads(long n) {
            if (n >= 100_000_000) return String.format(L.t("s_48be8f0bcb"), n / 100_000_000.0);
            if (n >= 10_000) return String.format(L.t("s_c8af81cb79"), n / 10_000.0);
            return String.valueOf(n);
        }
    }

    /** 模组版本 */
    public static class ModVersion {
        private String id;
        private String projectId;
        private String name;
        private String versionNumber;
        private String versionType;
        private String datePublished;
        private long downloads;
        private final List<String> loaders = new ArrayList<>();
        private final List<String> gameVersions = new ArrayList<>();
        private final List<ModFile> files = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersionNumber() { return versionNumber; }
        public void setVersionNumber(String v) { this.versionNumber = v; }
        public String getVersionType() { return versionType; }
        public void setVersionType(String v) { this.versionType = v; }
        public String getDatePublished() { return datePublished; }
        public void setDatePublished(String v) { this.datePublished = v; }
        public long getDownloads() { return downloads; }
        public void setDownloads(long d) { this.downloads = d; }
        public List<String> getLoaders() { return loaders; }
        public List<String> getGameVersions() { return gameVersions; }
        public List<ModFile> getFiles() { return files; }

        /**
         * 获取主文件（primary=true 的那个），没有则取第一个
         */
        public ModFile getPrimaryFile() {
            for (ModFile f : files) {
                if (f.isPrimary()) {
                    return f;
                }
            }
            return files.isEmpty() ? null : files.get(0);
        }

        public String getGameVersionDisplay() {
            return gameVersions.isEmpty() ? "?" : String.join(", ", gameVersions);
        }

        public String getLoaderDisplay() {
            return loaders.isEmpty() ? "?" : String.join("/", loaders);
        }

        /** 用于列表展示的文本 */
        public String toDisplayString() {
            return versionNumber + "  [" + getLoaderDisplay() + "]  " + getGameVersionDisplay()
                    + (versionType != null && !"release".equals(versionType)
                        ? "  (" + versionType + ")" : "");
        }
    }

    /** 模组文件 */
    public static class ModFile {
        private String url;
        private String filename;
        private boolean primary;
        private long size;
        private String sha1;
        private String sha512;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public boolean isPrimary() { return primary; }
        public void setPrimary(boolean primary) { this.primary = primary; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getSha1() { return sha1; }
        public void setSha1(String sha1) { this.sha1 = sha1; }
        public String getSha512() { return sha512; }
        public void setSha512(String sha512) { this.sha512 = sha512; }
    }
}