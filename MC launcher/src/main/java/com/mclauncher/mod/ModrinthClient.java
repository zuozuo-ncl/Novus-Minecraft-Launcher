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

public class ModrinthClient {

    private static final Logger logger = LoggerFactory.getLogger(ModrinthClient.class);

    private static final String OFFICIAL_API_BASE = "https://api.modrinth.com/v2";

    private static final String MIRROR_API_BASE = "https://mod.mcimirror.top/modrinth/v2";

    private static final String OFFICIAL_CDN_BASE = "https://cdn.modrinth.com";

    private static final String MIRROR_FILE_BASE = "https://mod.mcimirror.top";

    private static final int MAX_LIMIT = 100;

    private final OkHttpClient httpClient;
    private final Gson gson;

    private static final java.util.Map<String, String> RAW_ICON_CACHE
            = new java.util.concurrent.ConcurrentHashMap<>();

    private final boolean useMirrorApi;

    private final boolean useMirrorFiles;

    public ModrinthClient() {

        this(false, true);
    }

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

                                .header("User-Agent", NetworkConstants.USER_AGENT)
                                .header("Accept", "application/json")
                                .build()))
                .build();

        logger.info("Modrinth 客户端初始化：API 源={}，文件源={}",
                useMirrorApi ? "国内镜像" : "官方",
                useMirrorFiles ? "国内镜像" : "官方");
    }

    private String apiBase() {
        return useMirrorApi ? MIRROR_API_BASE : OFFICIAL_API_BASE;
    }

    public byte[] downloadImageBytes(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        

        

        

        

        
        if (url.toLowerCase().endsWith(".webp")) {
            byte[] raw = fetchRawIcon(url);
            if (raw != null && isDecodable(raw)) {
                return raw;
            }

        }

        String mirrored = toMirrorFileUrl(url);
        boolean sameUrl = mirrored.equals(url);

        byte[] fromMirror = fetchImage(mirrored);
        if (fromMirror != null) {

            
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

        

        byte[] raw = fetchRawIcon(url);
        if (raw != null && isDecodable(raw)) {
            logger.debug("图标改用原始格式: {}", url);
            return raw;
        }
        return null;
    }

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
                RAW_ICON_CACHE.put(projectId, rawUrl);   
            }
        }
        if (rawUrl == null) {
            return null;
        }

        

        

        
        return fetchImage(RAW_ICON_CLIENT, rawUrl);
    }

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

    private String queryRawIconUrl(String projectId) {
        try {

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

    private static final OkHttpClient IMAGE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final OkHttpClient RAW_ICON_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private boolean looksLikeImage(byte[] b, String contentType) {
        if (b == null || b.length < 12) {
            return false;
        }

        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return true;
        }

        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return true;
        }

        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return true;
        }

        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return true;
        }

        if (b[0] == 'B' && b[1] == 'M') {
            return true;
        }

        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return !isHtml(b);
        }
        return false;
    }

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

    public SearchResult search(String query, String gameVersion, String loader,
                               int offset, int limit) throws IOException {
        return search(query, gameVersion, loader, offset, limit, null);
    }

    public SearchResult search(String query, String gameVersion, String loader,
                               int offset, int limit, String index) throws IOException {
        return search(query, gameVersion, loader, offset, limit, index, "mod");
    }

    public SearchResult search(String query, String gameVersion, String loader,
                               int offset, int limit, String index,
                               String projectType) throws IOException {
        StringBuilder url = new StringBuilder(apiBase()).append("/search");

        List<String> params = new ArrayList<>();
        params.add("query=" + encode(query == null ? "" : query));
        params.add("offset=" + Math.max(0, offset));
        params.add("limit=" + Math.min(Math.max(1, limit), MAX_LIMIT));

        if (index != null && !index.isBlank()) {
            params.add("index=" + encode(index));
        }

        

        List<String> facetGroups = new ArrayList<>();

        if (loader != null && !loader.isBlank()) {
            facetGroups.add("[\"categories:" + loader.toLowerCase() + "\"]");
        }
        if (gameVersion != null && !gameVersion.isBlank()) {
            facetGroups.add("[\"versions:" + gameVersion + "\"]");
        }

        facetGroups.add("[\"project_type:" + projectType + "\"]");

        if (!facetGroups.isEmpty()) {
            params.add("facets=" + encode("[" + String.join(",", facetGroups) + "]"));
        }

        url.append("?").append(String.join("&", params));

        String body = get(url.toString());
        return parseSearchResult(body);
    }

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

            if (hit.has("display_categories")) {
                for (var c : hit.getAsJsonArray("display_categories")) {
                    String cat = c.getAsString();
                    if (isLoaderName(cat)) {
                        p.getLoaders().add(cat);
                    }
                }
            }

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

    private boolean isLoaderName(String category) {
        return switch (category.toLowerCase()) {
            case "fabric", "forge", "neoforge", "quilt", "liteloader", "rift",
                 "modloader", "risugamis-mods" -> true;
            default -> false;
        };
    }

    public List<ModVersion> getVersions(String projectId, String gameVersion, String loader)
            throws IOException {
        StringBuilder url = new StringBuilder(apiBase())
                .append("/project/").append(encodePath(projectId)).append("/version");

        List<String> params = new ArrayList<>();

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

    public List<ModVersion> getLatestVersions(String projectId, int limit) throws IOException {
        String url = apiBase() + "/project/" + encodePath(projectId)
                + "/version?limit=" + Math.max(1, Math.min(limit, 10));
        return parseVersions(get(url));
    }

    public static final int VERSION_PAGE_SIZE = 100;

    public boolean fetchVersionsPaged(String projectId, int firstPageSize,
                                      java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<List<ModVersion>> onPage)
            throws IOException {
        int offset = 0;
        int limit = Math.max(1, Math.min(firstPageSize, VERSION_PAGE_SIZE));
        int fetched = 0;
        while (true) {

            if (cancelled != null && cancelled.getAsBoolean()) {
                return false;
            }
            String url = apiBase() + "/project/" + encodePath(projectId)
                    + "/version?limit=" + limit + "&offset=" + offset;
            List<ModVersion> page = parseVersions(get(url));
            onPage.accept(page);
            fetched += page.size();
            if (page.size() < limit) {

                

                List<ModVersion> all = parseVersions(get(apiBase() + "/project/"
                        + encodePath(projectId) + "/version"));
                if (all.size() > fetched) {
                    onPage.accept(all);
                }
                return true;
            }
            offset = fetched;
            limit = VERSION_PAGE_SIZE;   
        }
    }

    private List<ModVersion> parseVersions(String json) throws IOException {
        List<ModVersion> versions = new ArrayList<>();

        JsonArray array;
        try {
            var parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {

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

    public ModVersion getVersion(String versionId) throws IOException {
        String body = get(apiBase() + "/version/" + encodePath(versionId));
        List<ModVersion> parsed = parseVersions("[" + body + "]");
        if (parsed.isEmpty()) {
            throw new IOException("版本 " + versionId + " 解析失败");
        }
        return parsed.get(0);
    }

    public JsonObject getProject(String projectId) throws IOException {
        return JsonParser.parseString(
                get(apiBase() + "/project/" + encodePath(projectId))).getAsJsonObject();
    }

    private String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {

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

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

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

    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

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

        public String toDisplayString() {
            return versionNumber + "  [" + getLoaderDisplay() + "]  " + getGameVersionDisplay()
                    + (versionType != null && !"release".equals(versionType)
                        ? "  (" + versionType + ")" : "");
        }
    }

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
