package com.mclauncher.auth;

import com.mclauncher.i18n.L;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultSkins {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSkins.class);

    public static final class Preset {

        public final String key;

        public final String displayName;

        public final String description;

        public final String resource;

        Preset(String key, String displayName, String description, String resource) {
            this.key = key;
            this.displayName = displayName;
            this.description = description;
            this.resource = resource;
        }

        public String getValue() {
            return KEY_PREFIX + key;
        }
    }

    public static final String KEY_PREFIX = "default:";

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        add("steve", L.t("s_2c7854eed8"), L.t("s_5773650000"), "wide_steve.png");
        add("alex", L.t("s_c38b7e40ce"), L.t("s_116e59820d"), "wide_alex.png");
        add("ari", "Ari", L.t("s_2840e2d297"), "wide_ari.png");
        add("efe", "Efe", L.t("s_af2bcfc84a"), "wide_efe.png");
        add("kai", "Kai", L.t("s_f575b38490"), "wide_kai.png");
        add("makena", "Makena", L.t("s_2621e619b1"), "wide_makena.png");
        add("noor", "Noor", L.t("s_2f6fe82e8d"), "wide_noor.png");
        add("sunny", "Sunny", L.t("s_1d245b1f38"), "wide_sunny.png");
        add("zuri", "Zuri", L.t("s_24ea95084d"), "wide_zuri.png");
    }

    private static void add(String key, String name, String desc, String file) {
        PRESETS.put(key, new Preset(key, name, desc, "/skins/default/" + file));
    }

    private DefaultSkins() {}

    public static List<Preset> all() {
        return Collections.unmodifiableList(new ArrayList<>(PRESETS.values()));
    }

    public static boolean isDefault(String skinSite) {
        return skinSite != null && skinSite.startsWith(KEY_PREFIX)
                && PRESETS.containsKey(skinSite.substring(KEY_PREFIX.length()));
    }

    public static Preset get(String skinSite) {
        if (!isDefault(skinSite)) {
            return null;
        }
        return PRESETS.get(skinSite.substring(KEY_PREFIX.length()));
    }

    public static Preset getByKey(String key) {
        return PRESETS.get(key);
    }

    public static Image load(Preset preset) {
        if (preset == null) {
            return null;
        }
        synchronized (CACHE) {
            Image cached = CACHE.get(preset.key);
            if (cached != null) {
                return cached;
            }
        }
        try (InputStream in = DefaultSkins.class.getResourceAsStream(preset.resource)) {
            if (in == null) {
                logger.warn("内置皮肤资源缺失: {}", preset.resource);
                return null;
            }
            Image img = new Image(in);
            if (img.isError() || img.getWidth() <= 0) {
                logger.warn("内置皮肤解码失败: {}", preset.resource);
                return null;
            }
            synchronized (CACHE) {
                CACHE.put(preset.key, img);
            }
            return img;
        } catch (Exception e) {
            logger.warn("读取内置皮肤失败 {}: {}", preset.resource, e.getMessage());
            return null;
        }
    }

    private static final Map<String, Image> CACHE = new LinkedHashMap<>();
}
