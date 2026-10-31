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

public class SkinFetcher {

    private static final Logger logger = LoggerFactory.getLogger(SkinFetcher.class);

    private static final String UA = com.mclauncher.network.NetworkConstants.USER_AGENT;

    private static final String LITTLESKIN_API = "https://littleskin.cn/api/yggdrasil";

    private static final String MOJANG_SESSION =
            "https://sessionserver.mojang.com/session/minecraft/profile";

    private static final String[][] DOMAIN_FIXES = {
            {"litleskin.cn", "littleskin.cn"},
    };

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private final ThreadLocal<Account> currentAccount = new ThreadLocal<>();

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

    private static final java.util.Map<String, Long> MISS_CACHE = new java.util.HashMap<>();
    private static final long MISS_TTL_MS = 5 * 60 * 1000L;

    public static void warmUpAsync() {
        Thread t = new Thread(() -> {
            try {

                
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

        

        String site = account.getSkinSite();

        
        boolean explicit = site != null && !site.isBlank() && !"auto".equalsIgnoreCase(site);

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

            logger.warn("内置皮肤 {} 加载失败，改用在线获取", preset.key);
        }

        if (explicit && preset == null) {
            if ("mojang".equalsIgnoreCase(site) || "microsoft".equalsIgnoreCase(site)) {

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

        if (!explicit && img == null) {
            if (account.getType() == Account.Type.OFFLINE) {

                String name = account.getUsername();
                if (name != null && !name.isBlank()) {
                    img = tryDefaultSources(name);
                }
            } else {

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

    public static void invalidate(Account account) {
        if (account == null) {
            return;
        }

        String key = cacheKey(account);
        synchronized (CACHE) {
            CACHE.remove(key);
            MISS_CACHE.remove(key);
            MODEL_CACHE.remove(key);
        }
    }

    public Image peekSkin(Account account) {
        if (account == null) {
            return null;
        }
        synchronized (CACHE) {
            return CACHE.get(cacheKey(account));
        }
    }

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

        boolean canOverlay = overlay && h >= 64;

        int src = 8;                 
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = src + x * 8 / size;
                int sy = src + y * 8 / size;

                javafx.scene.paint.Color c = r.getColor(sx, sy);

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

    private static String cacheKey(Account a) {

        String u = a.getUuid();
        if (u != null && !u.isBlank()) {
            return u;
        }
        return String.valueOf(a.getUsername());
    }

    private Image tryDefaultSources(String idOrName) {
        for (SkinSites.Site site : SkinSites.DEFAULTS) {
            Image img = tryAllSources(site.apiRoot, idOrName);
            if (img != null) {
                return img;
            }
        }
        return null;
    }

    private Image tryAllSources(String apiRoot, String idOrName) {
        Account a = currentAccount.get();
        boolean forceLookup = a != null && a.getType() == Account.Type.OFFLINE;
        String uuid = resolveUuid(apiRoot, idOrName, forceLookup);
        if (uuid != null) {
            Image img = fetchProfileSkin(apiRoot, uuid);
            if (img != null) {
                return img;
            }

            if (SkinSites.forApiRoot(apiRoot) != null) {
                return fetchFromCrafatar(uuid);
            }
            return null;
        }
        return null;
    }

    private String lookupKey(Account a) {
        return a.getType() == Account.Type.OFFLINE ? a.getUsername() : a.getUuid();
    }

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

    private Image fetchFromYggdrasil(String apiRoot, String idOrName) {
        String uuid = resolveUuid(apiRoot, idOrName, false);
        return uuid == null ? null : fetchProfileSkin(apiRoot, uuid);
    }

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
