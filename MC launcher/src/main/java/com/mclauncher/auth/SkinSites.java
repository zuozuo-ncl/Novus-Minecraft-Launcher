package com.mclauncher.auth;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class SkinSites {

    private SkinSites() {}

    public static final class Site {
        public final String id;
        public final String name;

        public final String apiRoot;

        public final String homepage;

        public final String[][] domainFixes;

        Site(String id, String name, String apiRoot, String homepage, String[][] domainFixes) {
            this.id = id;
            this.name = name;
            this.apiRoot = apiRoot;
            this.homepage = homepage;
            this.domainFixes = domainFixes;
        }
    }

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

    public static final Site[] DEFAULTS = {LITTLESKIN, BLESSING};

    private static final Map<String, Site> BY_HOST = new HashMap<>();

    static {
        for (Site s : DEFAULTS) {
            BY_HOST.put(hostOf(s.apiRoot), s);
        }
    }

    public static Site forApiRoot(String apiRoot) {
        return apiRoot == null ? null : BY_HOST.get(hostOf(apiRoot));
    }

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

        int schemeEnd = u.indexOf("://") + 3;
        int pathStart = u.indexOf('/', schemeEnd);
        String origin = pathStart > 0 ? u.substring(0, pathStart) : u;
        return trimSlash(origin) + "/api/yggdrasil";
    }

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

    static String decodeB64(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
