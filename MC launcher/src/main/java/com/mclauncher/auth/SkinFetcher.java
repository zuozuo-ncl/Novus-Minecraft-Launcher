package com.mclauncher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/**
 * 皮肤获取
 * <h3>关于 LittleSkin 的域名坑</h3>
 */
public class SkinFetcher {

    private static final Logger logger = LoggerFactory.getLogger(SkinFetcher.class);

    private static final String UA = com.mclauncher.network.NetworkConstants.USER_AGENT;

    /** LittleSkin 的 Yggdrasil API 根 */
    private static final String LITTLESKIN_API = "https://littleskin.cn/api/yggdrasil";

    /** Mojang 官方会话服务器（只对正版 UUID 有效） */
    private static final String MOJANG_SESSION =
            "https://sessionserver.mojang.com/session/minecraft/profile";

    /** 已知的「错拼域名 → 正确域名」替换表（兜底，站点表里没有时使用） */
    private static final String[][] DOMAIN_FIXES = {
            {"litleskin.cn", "littleskin.cn"},
    };

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private final ThreadLocal<Account> currentAccount = new ThreadLocal<>();

    /**
     * 取账号在皮肤站登记的皮肤模型类型（只用缓存，不发请求）
     *
     * @return "slim"（纤细/Alex）或 "default"（经典/Steve）；未知返回 null
     */
    public String peekModel(Account account) {
        if (account == null) {
            return null;
        }
        synchronized (MODEL_CACHE) {
            return MODEL_CACHE.get(cacheKey(account));
        }
    }

    private static final java.util.Map<String, String> MODEL_CACHE = new java.util.HashMap<>();
    private static final java.util.Map<String, Image> CACHE = new java.util.HashMap<>();

    /** 「查过了，确实没有皮肤」的缓存：key -> 失效时间（毫秒） */
    private static final java.util.Map<String, Long> MISS_CACHE = new java.util.HashMap<>();
    private static final long MISS_TTL_MS = 5 * 60 * 1000L;


    // ==================== 对外接口 ====================

    /**
     * 预热：在后台线程里做一次真实请求
     * - 首次调用 {@code SSLSocketFactory.getDefault()} 加载 JSSE 安全提供者 ~1.1s
     */
    public static void warmUpAsync() {
        Thread t = new Thread(() -> {
            try {
                // 用真实的接口而不是首页：首页是 HTML，走的是另一个后端，
                // 预热首页并不能让 API 的 TLS 会话/HTTP2 连接就绪。
                Request req = new Request.Builder()
                        .url(LITTLESKIN_API + "/")
                        .header("User-Agent", UA)
                        .head()
                        .build();
                try (Response r = HTTP.newCall(req).execute()) {
                    logger.debug("皮肤站连接预热完成 ({}, {})", r.code(), r.protocol());
                }
            } catch (Exception e) {
                logger.debug("皮肤站连接预热失败（不影响使用）: {}", e.getMessage());
            }
        }, "skin-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 获取账号的皮肤原图（64x64 PNG）
     * 网络请求，**不要**在 FX 线程调用。
     */
    public Image fetchSkin(Account account) {
        if (account == null) {
            return null;
        }

        String key = cacheKey(account);
        synchronized (CACHE) {
            Image cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        synchronized (CACHE) {
            Long missUntil = MISS_CACHE.get(key);
            if (missUntil != null) {
                if (System.currentTimeMillis() < missUntil) {
                    return null;
                }
                MISS_CACHE.remove(key);
            }
        }

        Image img = null;

        long t0 = System.currentTimeMillis();
        currentAccount.set(account);

        // 玩家指定的皮肤站（设置 → 账号 → 皮肤站）优先。
        // "mojang" 表示直接走 Mojang 会话服务器（正版账号）；
        // 留空则用站点注册表里的默认顺序。
        String site = account.getSkinSite();
        // 显式指定了皮肤站就**只用**它：用户填了自定义站，却悄悄拿 LittleSkin 的结果，
        // 会让人以为自己的皮肤上传生效了。
        boolean explicit = site != null && !site.isBlank() && !"auto".equalsIgnoreCase(site);

        // 内置基础皮肤：纯本地资源，不需要网络
        DefaultSkins.Preset preset = DefaultSkins.get(site);
        if (preset != null) {
            Image local = DefaultSkins.load(preset);
            if (local != null) {
                synchronized (CACHE) {
                    CACHE.put(key, local);
                    MISS_CACHE.remove(key);
                    MODEL_CACHE.put(key, "default");
                }
                logger.debug("使用内置皮肤: {} -> {}", key, preset.key);
                return local;
            }
            // 资源缺失时退回默认流程（不要让账号因此没有头像）
            logger.warn("内置皮肤 {} 加载失败，改用在线获取", preset.key);
        }

        if (explicit && preset == null) {
            if ("mojang".equalsIgnoreCase(site) || "microsoft".equalsIgnoreCase(site)) {
                // Mojang 会话服务器只认正版 UUID（32 位十六进制）
                String uuid = account.getUuid();
                if (uuid != null && uuid.replace("-", "").matches("[0-9a-fA-F]{32}")
                        && account.getType() != Account.Type.OFFLINE) {
                    img = fetchProfileSkin(MOJANG_SESSION, uuid);
                }
            } else {
                String apiRoot = SkinSites.guessApiRoot(site);
                if (apiRoot != null) {
                    img = tryAllSources(apiRoot, lookupKey(account));
                }
            }
        }

        // 未指定时按账号类型走默认顺序
        if (!explicit && img == null) {
            if (account.getType() == Account.Type.OFFLINE) {
                // 离线账号的 uuid 是本地算出来的，皮肤站查不到 —— 直接按用户名查
                String name = account.getUsername();
                if (name != null && !name.isBlank()) {
                    img = tryDefaultSources(name);
                }
            } else {
                // 微软账号：先用 Mojang 官方（最准），再按用户名找国内皮肤站
                String uuid = account.getUuid();
                if (uuid != null && uuid.replace("-", "").matches("[0-9a-fA-F]{32}")) {
                    img = fetchProfileSkin(MOJANG_SESSION, uuid);
                }
                if (img == null) {
                    String name = account.getUsername();
                    if (name != null && !name.isBlank()) {
                        img = tryDefaultSources(name);
                    }
                }
            }
        }
        logger.info("皮肤请求总耗时 {}ms", System.currentTimeMillis() - t0);

        if (img != null) {
            synchronized (CACHE) {
                CACHE.put(key, img);
                MISS_CACHE.remove(key);
            }
            logger.info("已获取账号皮肤: {}（{}ms）", key, System.currentTimeMillis() - t0);
        } else {
            synchronized (CACHE) {
                MISS_CACHE.put(key, System.currentTimeMillis() + MISS_TTL_MS);
            }
            logger.debug("未获取到皮肤: {}（{}ms）", key, System.currentTimeMillis() - t0);
        }
        return img;
    }

    /**
     * 清掉某个账号的缓存，下次调用会重新请求
     *
     * 用于「刷新皮肤」按钮：玩家可能刚在皮肤站换了皮肤。
     */
    public static void invalidate(Account account) {
        if (account == null) {
            return;
        }
        // 必须与 cacheKey 完全一致，否则会清错 key（表现为「刷新皮肤」没反应）
        String key = cacheKey(account);
        synchronized (CACHE) {
            CACHE.remove(key);
            MISS_CACHE.remove(key);
            MODEL_CACHE.remove(key);
        }
    }

    /**
     * 只看缓存，不发请求（FX 线程安全）
     *
     * @return 缓存中的皮肤；没有则 null
     */
    public Image peekSkin(Account account) {
        if (account == null) {
            return null;
        }
        synchronized (CACHE) {
            return CACHE.get(cacheKey(account));
        }
    }

    /** 把皮肤图裁成「头部」并放大 —— 用于侧边栏显示的小头像 */
    public static Image headAvatar(Image skin, int size, boolean overlay) {
        if (skin == null) {
            return null;
        }

        int w = (int) skin.getWidth();
        int h = (int) skin.getHeight();
        PixelReader r = skin.getPixelReader();
        if (r == null || w < 64 || h < 32) {
            return null;
        }

        WritableImage out = new WritableImage(size, size);
        javafx.scene.image.PixelWriter wr = out.getPixelWriter();

        // 旧版 64x32 皮肤没有外层，overlay 自动失效
        boolean canOverlay = overlay && h >= 64;

        int src = 8;                 // 头部 8x8
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = src + x * 8 / size;
                int sy = src + y * 8 / size;

                // 先画基础层
                javafx.scene.paint.Color c = r.getColor(sx, sy);

                // 再叠外层（不透明才覆盖）
                if (canOverlay) {
                    javafx.scene.paint.Color o = r.getColor(40 + (sx - src), sy);
                    if (o != null && o.getOpacity() > 0.1) {
                        c = o;
                    }
                }
                wr.setColor(x, y, c == null ? javafx.scene.paint.Color.TRANSPARENT : c);
            }
        }
        return out;
    }

    // ==================== 内部实现 ====================

    private static String cacheKey(Account a) {
        // 离线 UUID 是按用户名算的，天然唯一；为空时退回用户名（字符串，避免 null）
        String u = a.getUuid();
        if (u != null && !u.isBlank()) {
            return u;
        }
        return String.valueOf(a.getUsername());
    }

    /**
     * 按站点注册表依次尝试默认皮肤站
     */
    private Image tryDefaultSources(String idOrName) {
        for (SkinSites.Site site : SkinSites.DEFAULTS) {
            Image img = tryAllSources(site.apiRoot, idOrName);
            if (img != null) {
                return img;
            }
        }
        return null;
    }

    /** 尝试一个皮肤站 */
    private Image tryAllSources(String apiRoot, String idOrName) {
        Account a = currentAccount.get();
        boolean forceLookup = a != null && a.getType() == Account.Type.OFFLINE;
        String uuid = resolveUuid(apiRoot, idOrName, forceLookup);
        if (uuid != null) {
            Image img = fetchProfileSkin(apiRoot, uuid);
            if (img != null) {
                return img;
            }
            // 账号在该站存在但没有皮肤：已知站点才去 Crafatar 找 Mojang 同名皮肤
            if (SkinSites.forApiRoot(apiRoot) != null) {
                return fetchFromCrafatar(uuid);
            }
            return null;
        }
        return null;
    }

    /** 用来查皮肤站的标识 */
    private String lookupKey(Account a) {
        return a.getType() == Account.Type.OFFLINE ? a.getUsername() : a.getUuid();
    }

    /** 用户名 → 皮肤站 UUID */
    private String resolveUuid(String apiRoot, String idOrName, boolean forceLookup) {
        if (!forceLookup && idOrName.replace("-", "").matches("[0-9a-fA-F]{32}")) {
            return idOrName;
        }
        try {
            String body = get(apiRoot + "/api/users/profiles/minecraft/" + idOrName);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            String id = o.has("id") ? o.get("id").getAsString() : null;
            return (id == null || id.isBlank()) ? null : id;
        } catch (Exception e) {
            logger.debug("UUID 查询失败: {}", e.getMessage());
            return null;
        }
    }

    /** 通过 Yggdrasil 协议取皮肤（LittleSkin / Blessing Skin 通用） */
    private Image fetchFromYggdrasil(String apiRoot, String idOrName) {
        String uuid = resolveUuid(apiRoot, idOrName, false);
        return uuid == null ? null : fetchProfileSkin(apiRoot, uuid);
    }

    /** UUID → profile → textures → 下载皮肤原图 */
    private Image fetchProfileSkin(String apiRoot, String uuid) {
        try {
            String body = get(apiRoot + "/sessionserver/session/minecraft/profile/" + uuid);
            if (body == null) {
                return null;
            }

            JsonObject profile = JsonParser.parseString(body).getAsJsonObject();
            JsonArray props = profile.has("properties")
                    ? profile.getAsJsonArray("properties") : null;
            if (props == null) {
                return null;
            }

            for (int i = 0; i < props.size(); i++) {
                JsonObject p = props.get(i).getAsJsonObject();
                if (!"textures".equals(p.has("name") ? p.get("name").getAsString() : "")) {
                    continue;
                }
                String b64 = p.get("value").getAsString();
                String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                JsonObject tex = JsonParser.parseString(decoded).getAsJsonObject();
                if (!tex.has("textures")) {
                    return null;
                }
                JsonObject skins = tex.getAsJsonObject("textures");
                if (!skins.has("SKIN")) {
                    return null;
                }
                JsonObject skinObj = skins.getAsJsonObject("SKIN");
                String url = skinObj.get("url").getAsString();

                // 模型类型："slim" = 纤细（Alex），缺省 = 经典（Steve）
                String model = skinObj.has("metadata")
                        && skinObj.getAsJsonObject("metadata").has("model")
                        ? skinObj.getAsJsonObject("metadata").get("model").getAsString()
                        : "default";
                synchronized (MODEL_CACHE) {
                    Account owner = currentAccount.get();
                    if (owner != null) {
                        MODEL_CACHE.put(cacheKey(owner), model);
                    }
                }

                url = fixDomain(url, apiRoot);
                byte[] bytes = getBytes(url);
                if (bytes == null || bytes.length < 100) {
                    return null;
                }
                return new Image(new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            logger.debug("Yggdrasil 皮肤获取失败 ({}): {}", apiRoot, e.getMessage());
        }
        return null;
    }

    /** 修正已知的错误域名 */
    private String fixDomain(String url, String apiRoot) {
        SkinSites.Site site = SkinSites.forApiRoot(apiRoot);
        String[][] fixes = site != null && site.domainFixes != null
                ? site.domainFixes : DOMAIN_FIXES;
        String fixed = url;
        for (String[] pair : fixes) {
            if (fixed.contains(pair[0])) {
                fixed = fixed.replace(pair[0], pair[1]);
                logger.debug("皮肤域名修正: {} -> {}", pair[0], pair[1]);
            }
        }
        return fixed;
    }

    /**
     * Crafatar：直接按 uuid/name 取渲染好的头像
     * 不用它的皮肤原图（/skins/），因为要自己裁；
     */
    /** Crafatar 专用客户端：短超时，它只是兜底 */
    private static final OkHttpClient CRAFATAR_HTTP = HTTP.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    private Image fetchFromCrafatar(String idOrName) {
        try {
            byte[] bytes = getBytes(CRAFATAR_HTTP,
                    "https://crafatar.com/skins/" + idOrName);
            if (bytes == null || bytes.length < 100) {
                return null;
            }
            return new Image(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            logger.debug("Crafatar 皮肤获取失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== HTTP ====================

    private String get(String url) {
        byte[] b = getBytes(url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String url) {
        return getBytes(HTTP, url);
    }

    private byte[] getBytes(OkHttpClient client, String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "image/png,application/json,*/*")
                    .get()
                    .build();
            try (Response r = client.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) {
                    return null;
                }
                byte[] b = r.body().bytes();
                // 防错误页：图片应以 PNG 头开始
                if (url.endsWith(".png") || r.header("Content-Type", "").startsWith("image/")) {
                    if (b.length < 8 || (b[0] & 0xFF) != 0x89) {
                        return null;
                    }
                }
                return b;
            }
        } catch (Exception e) {
            logger.debug("HTTP 请求失败 {}: {}", url, e.getMessage());
            return null;
        }
    }
}