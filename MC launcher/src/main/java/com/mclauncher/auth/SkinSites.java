package com.mclauncher.auth;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 皮肤站注册表 */
public final class SkinSites {

    private SkinSites() {}

    /** 一个皮肤站 */
    public static final class Site {
        public final String id;
        public final String name;
        /** Yggdrasil API 根，例如 https://littleskin.cn/api/yggdrasil */
        public final String apiRoot;
        /** 站点主页（用于提示用户去上传皮肤） */
        public final String homepage;
        /** 域名修正表（错拼 -> 正确），可为空 */
        public final String[][] domainFixes;

        Site(String id, String name, String apiRoot, String homepage, String[][] domainFixes) {
            this.id = id;
            this.name = name;
            this.apiRoot = apiRoot;
            this.homepage = homepage;
            this.domainFixes = domainFixes;
        }
    }

    /** 已知站点（顺序即尝试顺序：国内站放前面） */
    public static final Site LITTLESKIN = new Site(
            "littleskin", "LittleSkin",
            "https://littleskin.cn/api/yggdrasil",
            "https://littleskin.cn",
            new String[][]{{"litleskin.cn", "littleskin.cn"}});

    public static final Site BLESSING = new Site(
            "blessing", "Blessing Skin",
            "https://skin.prinzeugen.net/api/yggdrasil",
            "https://skin.prinzeugen.net",
            null);

    /** 默认尝试顺序 */
    public static final Site[] DEFAULTS = {LITTLESKIN, BLESSING};

    /** 原生支持加载器皮肤的站点（不吃 Crafatar 兜底） */
    private static final Map<String, Site> BY_HOST = new HashMap<>();

    static {
        for (Site s : DEFAULTS) {
            BY_HOST.put(hostOf(s.apiRoot), s);
        }
    }

    /** 按域名找已知站点 */
    public static Site forApiRoot(String apiRoot) {
        return apiRoot == null ? null : BY_HOST.get(hostOf(apiRoot));
    }

    /**
     * 尝试把任意 URL 当作 Yggdrasil API 根
     * <li>否则 → https://host/api/yggdrasil</li>
     */
    public static String guessApiRoot(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        int idx = u.indexOf("/api/yggdrasil");
        if (idx > 0) {
            return trimSlash(u.substring(0, idx + "/api/yggdrasil".length()));
        }
        // 只有域名（可能带路径，如 https://mc.foo.com/skin）时，取 origin
        int schemeEnd = u.indexOf("://") + 3;
        int pathStart = u.indexOf('/', schemeEnd);
        String origin = pathStart > 0 ? u.substring(0, pathStart) : u;
        return trimSlash(origin) + "/api/yggdrasil";
    }

    /** 取站点主页（用于「去上传皮肤」的链接） */
    public static String homepageOf(String apiRoot) {
        if (apiRoot == null) {
            return null;
        }
        int idx = apiRoot.indexOf("/api/yggdrasil");
        return idx > 0 ? apiRoot.substring(0, idx) : apiRoot;
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(trimSlash(url)).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 给设置页用的简要说明 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Site s : DEFAULTS) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(s.name);
        }
        return sb.toString();
    }

    /** 工具：安全地 base64 解码（供测试/调试） */
    static String decodeB64(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}