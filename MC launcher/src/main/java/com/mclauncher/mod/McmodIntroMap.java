package com.mclauncher.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class McmodIntroMap {

    private static final Logger logger = LoggerFactory.getLogger(McmodIntroMap.class);

    private static final String[] MAP_URLS = {
            "https://3984235.xyz/mcmod_intros.json",
            "https://version-interface.us.ci/mcmod_intros.json",
    };

    private static final String CACHE_NAME = "mcmod_intros.json";

    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)

            

            

            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(java.time.Duration.ofSeconds(75))
            .followRedirects(true)
            .build();

    private final Gson gson = new Gson();

    private final Map<String, Entry> byName = new HashMap<>();

    private final Map<String, Entry> byNorm = new HashMap<>();

    private volatile boolean loaded = false;

    private final java.util.List<Runnable> loadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static volatile McmodIntroMap shared;

    public static McmodIntroMap prewarmAsync() {
        McmodIntroMap m = shared;
        if (m == null) {
            synchronized (McmodIntroMap.class) {
                if (shared == null) {
                    shared = new McmodIntroMap();
                    shared.loadAsync(com.mclauncher.utils.AppPaths.appDataDir());
                }
                m = shared;
            }
        } else if (!m.isLoaded()) {

            m.loadAsync(com.mclauncher.utils.AppPaths.appDataDir());
        }
        return m;
    }

    public static McmodIntroMap shared() {
        return shared;
    }

    public static class Entry {
        public Integer id;
        public String cn;      
        public String en;      
        public String intro;   

        public java.util.List<String> keys;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int size() {
        return byName.size();
    }

    public void onLoaded(Runnable r) {
        if (r == null) {
            return;
        }
        if (loaded) {
            r.run();
            return;
        }
        loadListeners.add(r);

        if (loaded) {
            loadListeners.remove(r);
            r.run();
        }
    }

    private void notifyLoaded() {
        for (Runnable r : loadListeners) {
            try {
                r.run();
            } catch (Exception e) {
                logger.warn("简介表加载回调异常: {}", e.getMessage());
            }
        }
        loadListeners.clear();
    }

    public void loadAsync(Path cacheDir) {
        new Thread(() -> {
            try {
                Path cache = cacheDir.resolve(CACHE_NAME);

                if (Files.isRegularFile(cache)) {
                    long age = System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis();
                    if (age < CACHE_TTL_MS) {
                        if (parse(Files.readString(cache, StandardCharsets.UTF_8))) {
                            logger.info("MC 百科映射已从缓存加载: {} 条", byName.size());
                            notifyLoaded();
                            return;
                        }
                    }
                }

                String json = fetchAny();
                if (json != null && parse(json)) {
                    try {
                        Files.createDirectories(cacheDir);
                        Files.writeString(cache, json, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        logger.debug("映射表缓存写入失败: {}", e.getMessage());
                    }
                    logger.info("MC 百科映射已更新: {} 条", byName.size());
                    notifyLoaded();
                    return;
                }

                if (Files.isRegularFile(cache)) {
                    if (parse(Files.readString(cache, StandardCharsets.UTF_8))) {
                        logger.info("映射表拉取失败，使用过期缓存: {} 条", byName.size());
                        notifyLoaded();
                        return;
                    }
                }
                logger.info("MC 百科映射不可用，模组将显示英文简介");

            } catch (Exception e) {
                logger.debug("加载 MC 百科映射失败: {}", e.getMessage());
            }
        }, "mcmod-intro-loader").start();
    }

    private String fetchAny() {
        for (String url : MAP_URLS) {
            long t0 = System.currentTimeMillis();
            String json = fetch(url);
            if (json != null) {
                logger.info("映射表下载完成: {} ({}ms, {} 字符)",
                        hostOf(url), System.currentTimeMillis() - t0, json.length());
                return json;
            }
            logger.warn("映射表地址不可用，尝试下一个: {}", hostOf(url));
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private String fetch(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", com.mclauncher.network.NetworkConstants.USER_AGENT)

                    .header("Accept-Encoding", "gzip")
                    .get()
                    .build();
            try (Response r = HTTP.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) {
                    logger.debug("映射表下载失败: HTTP {} - {}", r.code(), url);
                    return null;
                }
                return r.body().string();
            }
        } catch (Exception e) {
            logger.debug("映射表下载失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    private boolean parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject entries = root.getAsJsonObject("entries");
            if (entries == null) {
                return false;
            }

            Map<String, Entry> tmp = new HashMap<>();
            for (String key : entries.keySet()) {
                try {
                    Entry e = gson.fromJson(entries.get(key), Entry.class);
                    if (e == null || e.intro == null || e.intro.isBlank()) {
                        continue;
                    }
                    tmp.put(key.toLowerCase(), e);

                    
                    if (e.keys != null) {
                        for (String alias : e.keys) {
                            if (alias != null && !alias.isBlank()) {
                                tmp.putIfAbsent(alias.toLowerCase(), e);
                            }
                        }
                    }
                } catch (Exception ignored) {

                }
            }

            if (tmp.isEmpty()) {
                return false;
            }

            Map<String, Entry> norm = new HashMap<>();
            for (Map.Entry<String, Entry> kv : tmp.entrySet()) {
                String n = normalize(kv.getKey());
                if (!n.isEmpty()) {
                    norm.putIfAbsent(n, kv.getValue());
                }
            }

            synchronized (this) {
                byName.clear();
                byName.putAll(tmp);
                byNorm.clear();
                byNorm.putAll(norm);
                loaded = true;
            }
            return true;

        } catch (Exception e) {
            logger.debug("映射表解析失败: {}", e.getMessage());
            return false;
        }
    }

    public String lookupIntro(String title, String slug) {
        if (!loaded) {
            return null;
        }

        Entry e = find(title);
        if (e == null) {
            e = find(slug);
        }
        return e == null ? null : e.intro;
    }

    public String lookupCnName(String title, String slug) {
        if (!loaded) {
            return null;
        }
        Entry e = find(title);
        if (e == null) {
            e = find(slug);
        }
        return e == null ? null : e.cn;
    }

    private Entry find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        Entry e = byName.get(name.toLowerCase().trim());
        if (e != null) {
            return e;
        }

        return byNorm.get(normalize(name));
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[\\s\\-_]+", "");
    }
}
