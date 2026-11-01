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

/**
 * 内置基础皮肤
 *
 * Minecraft 自带 9 个官方默认皮肤（Steve、Alex 等 9 位角色）。
 * 玩家没有上传自己的皮肤、或想换回官方造型时，可以直接选其中一套。
 *
 * <h3>图片来源</h3>
 * 纹理取自官方 1.21.4 客户端的
 * {@code assets/minecraft/textures/entity/player/slim/*.png}
 * （slim 与 wide 两套模型下同名皮肤的**纹理内容完全相同**，只差渲染模型），
 * 已打包进启动器的 {@code /skins/default/} 资源目录 —— 断网也能显示。
 *
 * <h3>只影响显示</h3>
 * 本类只决定「启动器里看到什么皮肤」。进入服务器后看到的仍是账号在
 * 皮肤站/Mojang 上登记的皮肤 —— 这是 Minecraft 的机制，启动器无法单方面修改。
 * {@link #getValue()} 存储的 key 会在设置页明文说明这一点。
 */
public final class DefaultSkins {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSkins.class);

    /** 一个内置预设 */
    public static final class Preset {
        /** 存储用的稳定 key，例如 "steve" */
        public final String key;
        /** 界面显示名 */
        public final String displayName;
        /** 角色介绍（一句话） */
        public final String description;
        /** 资源路径 */
        public final String resource;

        Preset(String key, String displayName, String description, String resource) {
            this.key = key;
            this.displayName = displayName;
            this.description = description;
            this.resource = resource;
        }

        /** 存储值：{@link #KEY_PREFIX} + key */
        public String getValue() {
            return KEY_PREFIX + key;
        }
    }

    /** 账号 skinSite 字段里表示「内置默认皮肤」的前缀 */
    public static final String KEY_PREFIX = "default:";

    /** 按界面顺序排列的内置皮肤 */
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

    /** 全部内置皮肤（不可修改） */
    public static List<Preset> all() {
        return Collections.unmodifiableList(new ArrayList<>(PRESETS.values()));
    }

    /**
     * 判断一个 skinSite 值是否为内置皮肤
     */
    public static boolean isDefault(String skinSite) {
        return skinSite != null && skinSite.startsWith(KEY_PREFIX)
                && PRESETS.containsKey(skinSite.substring(KEY_PREFIX.length()));
    }

    /**
     * 按 skinSite 值取预设
     *
     * @return 不认识或不是内置皮肤时返回 null
     */
    public static Preset get(String skinSite) {
        if (!isDefault(skinSite)) {
            return null;
        }
        return PRESETS.get(skinSite.substring(KEY_PREFIX.length()));
    }

    public static Preset getByKey(String key) {
        return PRESETS.get(key);
    }

    /**
     * 加载内置皮肤图
     *
     * 优先从类路径读（打包进 jar 里）；没有时返回 null，调用方退回首字母占位。
     * 结果缓存 —— 同一张图只解码一次。
     *
     * @return 64x64 皮肤图；失败返回 null
     */
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