package com.mclauncher.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

public final class L {

    private static final Logger logger = LoggerFactory.getLogger(L.class);

    public enum Lang {
        ZH_CN("zh_CN", "简体中文"),
        EN_US("en_US", "English");

        public final String code;
        public final String displayName;

        Lang(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public static Lang fromCode(String code) {
            for (Lang l : values()) {
                if (l.code.equalsIgnoreCase(code)) {
                    return l;
                }
            }
            return ZH_CN;
        }
    }

    private static volatile Lang current = Lang.ZH_CN;

    private static final java.util.Map<String, Properties> BUNDLES = new java.util.HashMap<>();

    private static final java.util.Map<String, String> OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private L() {}

    public static String t(String key) {

        
        if (key == null) {
            return "";
        }
        java.util.Map<String, String> ov = OVERRIDES;
        if (ov != null) {
            String o = ov.get(key);
            if (o != null) {
                return o;
            }
        }
        return lookup(key);
    }

    private static String lookup(String key) {
        java.util.Map<String, Properties> bundles = BUNDLES;
        if (bundles == null) {

            
            Properties early = readBundle(current);
            if (early == null) {
                return key;
            }
            Properties e = early;
            String v = e.getProperty(key);
            return v != null && !v.isBlank() ? v : key;
        }
        Properties p = bundles.get(current.code);
        if (p != null) {
            String s = p.getProperty(key);
            if (s != null && !s.isBlank()) {
                return unescapeNewlines(s);
            }
        }

        Properties zh = bundles.get(Lang.ZH_CN.code);
        if (zh != null && current != Lang.ZH_CN) {
            String s = zh.getProperty(key);
            if (s != null && !s.isBlank()) {
                return unescapeNewlines(s);
            }
        }
        logger.debug("缺少文案: {}", key);
        return key;
    }

    public static String t(String key, Object... args) {
        String s = t(key);
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                s = s.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
            }
        }
        return s;
    }

    private static String unescapeNewlines(String s) {
        if (s == null || s.indexOf(BS_SLASH) < 0) {
            return s;
        }
        return s.replace(String.valueOf(BS_SLASH) + "n", "\n")
                .replace(String.valueOf(BS_SLASH) + "t", "\t");
    }

    private static final char BS_SLASH = 92;

    public static Lang lang() {
        return current;
    }

    public static Lang detectSystemLanguage() {
        Locale l = Locale.getDefault();
        if (l != null && "zh".equalsIgnoreCase(l.getLanguage())) {
            return Lang.ZH_CN;
        }
        return Lang.EN_US;
    }

    public static boolean load(Lang lang) {
        if (BUNDLES == null) {
            return false;
        }
        if (BUNDLES.containsKey(lang.code)) {
            return true;
        }
        Properties p = readBundle(lang);
        if (p == null) {
            return false;
        }
        BUNDLES.put(lang.code, p);
        return true;
    }

    private static Properties readBundle(Lang lang) {
        String path = "/i18n/messages_" + lang.code + ".properties";
        try (InputStream in = L.class.getResourceAsStream(path)) {
            if (in == null) {
                logger.warn("文案文件不存在: {}", path);
                return null;
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (Exception e) {
            logger.error("读取文案失败 {}: {}", path, e.getMessage());
            return null;
        }
    }

    public static void setLang(Lang lang) {
        if (lang == null || lang == current) {
            return;
        }
        load(lang);
        current = lang;
        logger.info("界面语言切换为: {}", lang.displayName);
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (Exception e) {
                logger.warn("语言切换回调异常: {}", e.getMessage());
            }
        }
    }

    public static void setLangQuiet(Lang lang) {
        if (lang == null) {
            return;
        }
        load(lang);
        current = lang;
    }

    public static void addListener(Runnable r) {
        if (r != null) {
            LISTENERS.add(r);
        }
    }

    public static void setOverride(String key, String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            OVERRIDES.remove(key);
        } else {
            OVERRIDES.put(key, value);
        }
    }

    public static List<String> keys() {
        Properties p = BUNDLES.get(current.code);
        return p == null ? new ArrayList<>() : new ArrayList<>(p.stringPropertyNames());
    }

    static {
        load(Lang.ZH_CN);
        load(Lang.EN_US);
    }
}
