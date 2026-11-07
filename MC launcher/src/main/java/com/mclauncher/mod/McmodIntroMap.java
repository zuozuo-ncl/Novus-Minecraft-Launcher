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

/**
 * MC 百科中文简介映射
 * <h3>为什么不直接查 MC 百科</h3>
 */
public class McmodIntroMap {

    private static final Logger logger = LoggerFactory.getLogger(McmodIntroMap.class);

    /** 映射表地址（按优先级排列） */
    private static final String[] MAP_URLS = {
            "https://3984235.xyz/mcmod_intros.json",
            "https://version-interface.us.ci/mcmod_intros.json",
    };

    /** 缓存文件（放在启动器的数据目录） */
    private static final String CACHE_NAME = "mcmod_intros.json";

    /** 缓存有效期：1 天 */
    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;

    /**
     * 简介表专用客户端
     * 但给足余量更稳妥 —— 否则慢速连接会被中途掐断，玩家看到的就是英文。
     */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // 实测：慢网络（50~160KB/s）下 gzip 版 1.58MB 要 35~50 秒。
            // 60 秒太贴近实测耗时，网络稍差就会中途被掐断 -> 玩家永远看到英文。
            // 主地址（COS）实测 1 秒；备份地址慢到 50 秒。
            // 单次调用上限 75 秒：主地址连不上时 8 秒就失败切走，
            // 备份地址哪怕慢也能在 75 秒内给出结果，不会无限拖住界面。
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(java.time.Duration.ofSeconds(75))
            .followRedirects(true)
            .build();

    private final Gson gson = new Gson();

    /** 英文名（小写） -> 条目 */
    private final Map<String, Entry> byName = new HashMap<>();

    /** 归一化名（去空格/连字符/下划线、小写） -> 条目 */
    private final Map<String, Entry> byNorm = new HashMap<>();

    /** 是否已成功加载 */
    private volatile boolean loaded = false;

    /**
     * 加载完成回调
     * 「必须手动点开一次才变中文」。加载完成后统一通知一次。
     */
    private final java.util.List<Runnable> loadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 进程内的共享实例
     * 启动时预热和下载页共用同一个实例，否则会下载/解析两份数据，
     */
    private static volatile McmodIntroMap shared;

    /**
     * 启动预热
     * @return 共享实例（调用方应直接使用它，不要自己 new）
     */
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
            // 已经创建但没加载（理论上不会走到），补一次
            m.loadAsync(com.mclauncher.utils.AppPaths.appDataDir());
        }
        return m;
    }

    /** 取共享实例；尚未预热时返回 null */
    public static McmodIntroMap shared() {
        return shared;
    }


    /** 一条映射 */
    public static class Entry {
        public Integer id;
        public String cn;      // 中文名
        public String en;      // 英文名
        public String intro;   // 中文简介

        /**
         * 别名列表（slug / 标题 / 各种写法）
         * 启动器从 Modrinth 拿到的可能是任意一种写法，必须都能命中。
         */
        public java.util.List<String> keys;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * 索引条目数（含别名）
     *
     * 注意：一个模组的所有写法都在里面，所以这个数大于模组数。
     */
    public int size() {
        return byName.size();
    }

    /** 注册「加载完成」回调 */
    public void onLoaded(Runnable r) {
        if (r == null) {
            return;
        }
        if (loaded) {
            r.run();
            return;
        }
        loadListeners.add(r);
        // 注册期间刚好加载完了：补一次
        if (loaded) {
            loadListeners.remove(r);
            r.run();
        }
    }

    /** 通知所有等待者（加载线程调用） */
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

                // 1) 缓存还新鲜 -> 直接用
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

                // 2) 拉新
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

                // 3) 拉取失败 -> 退回旧缓存（哪怕过期）
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

    /** 依次尝试各地址下载映射表 */
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

    /** 下载映射表；失败返回 null */
    private String fetch(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", com.mclauncher.network.NetworkConstants.USER_AGENT)
                    // OkHttp 默认就会带 gzip 并自动解压；显式写出便于调试抓包
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

    /** 解析映射 JSON */
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
                    // 别名也建索引：数据已去重，一个模组只存一条，
                    // slug / 标题 / 变体都在 keys 里。
                    if (e.keys != null) {
                        for (String alias : e.keys) {
                            if (alias != null && !alias.isBlank()) {
                                tmp.putIfAbsent(alias.toLowerCase(), e);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 单条格式错误不影响其它条目
                }
            }

            if (tmp.isEmpty()) {
                return false;
            }
            // 建归一化索引
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

    /**
     * 按模组的英文名/标题查中文简介
     * 不做模糊匹配 —— 宁可显示英文，也不要把别的模组简介安错。
     */
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

    /** 查中文名 */
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

        // 归一化索引查找（O(1)）
        return byNorm.get(normalize(name));
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[\\s\\-_]+", "");
    }
}