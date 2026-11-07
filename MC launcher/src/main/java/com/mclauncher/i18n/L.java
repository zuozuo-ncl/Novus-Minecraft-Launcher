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

/**
 * 界面文案
 * <h3>为什么不用 ResourceBundle</h3>
 * 用法：{@code L.t("download.search")}，带参数时 {@code L.t("error.http", 404)}。
 */
public final class L {

    private static final Logger logger = LoggerFactory.getLogger(L.class);

    /** 支持的语言 */
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

    /** 语言码 -> 文案表 */
    private static final java.util.Map<String, Properties> BUNDLES = new java.util.HashMap<>();

    /** 来自配置的自定义覆盖（可放服务端下发的文案） */
    private static final java.util.Map<String, String> OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private L() {}

    /** 取一条文案 */
    public static String t(String key) {
        // 静态初始化顺序：Lang 枚举的构造可能早于本类的字段初始化，
        // 那期间 OVERRIDES/BUNDLES 还是 null —— 必须容忍。
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
            // 静态初始化尚未完成（别的类在初始化期间就调用了 t()）。
            // 直接同步读取，不依赖静态块的执行顺序。
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
        // 回退到中文：宁可显示中文，也不要显示 key
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

    /**
     * 带参数的文案，{@code {0}} {@code {1}} 会被替换
     */
    public static String t(String key, Object... args) {
        String s = t(key);
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                s = s.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
            }
        }
        return s;
    }

    /** 把文案里的字面 \\n 转成真实换行 */
    private static String unescapeNewlines(String s) {
        if (s == null || s.indexOf(BS_SLASH) < 0) {
            return s;
        }
        return s.replace(String.valueOf(BS_SLASH) + "n", "\n")
                .replace(String.valueOf(BS_SLASH) + "t", "\t");
    }

    /** 单个反斜杠字符（避免在源码里写转义，容易出错） */
    private static final char BS_SLASH = 92;

    /** 当前语言 */
    public static Lang lang() {
        return current;
    }

    /** 按系统区域猜一个默认语言 */
    public static Lang detectSystemLanguage() {
        Locale l = Locale.getDefault();
        if (l != null && "zh".equalsIgnoreCase(l.getLanguage())) {
            return Lang.ZH_CN;
        }
        return Lang.EN_US;
    }

    /**
     * 加载某语言的文案表
     *
     * @return 是否加载成功
     */
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
        fillMissingFromOtherLanguages(lang, p);
        BUNDLES.put(lang.code, p);
        return true;
    }

    /** 非中文语言缺少的键用简体中文补齐。 */
    private static void fillMissingFromOtherLanguages(Lang lang, Properties p) {
        if (lang == Lang.ZH_CN) {
            return;
        }
        Properties zh = readBundle(Lang.ZH_CN);
        if (zh == null) {
            return;
        }
        for (String key : zh.stringPropertyNames()) {
            if (!p.containsKey(key)) {
                p.setProperty(key, zh.getProperty(key));
            }
        }
    }

    /** 从 classpath 读一份文案表；失败返回 null */
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

    /**
     * 切换语言
     *
     * 会通知所有监听者重建界面。
     */
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

    /**
     * 启动时设置语言，不触发界面重建回调
     *
     * 界面还没构建好，此时通知监听者没有意义。
     */
    public static void setLangQuiet(Lang lang) {
        if (lang == null) {
            return;
        }
        load(lang);
        current = lang;
    }

    /** 注册「语言切换」回调 */
    public static void addListener(Runnable r) {
        if (r != null) {
            LISTENERS.add(r);
        }
    }

    /** 设置自定义覆盖（例如服务端下发的文案） */
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

    /** 全部已加载的键（调试用） */
    public static List<String> keys() {
        Properties p = BUNDLES.get(current.code);
        return p == null ? new ArrayList<>() : new ArrayList<>(p.stringPropertyNames());
    }

    static {
        load(Lang.ZH_CN);
        load(Lang.EN_US);
    }
}