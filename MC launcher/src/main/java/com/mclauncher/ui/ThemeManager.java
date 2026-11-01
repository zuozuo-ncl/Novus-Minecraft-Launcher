package com.mclauncher.ui;

import com.mclauncher.config.ConfigManager;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主题管理
 *
 * 采用「双 CSS 文件切换」方案：
 *   - 深色：/css/launcher.css
 *   - 浅色：/css/launcher-light.css
 *
 * 切换时把旧的 stylesheet 移除、加入新的，界面立即生效（无需重启）。
 *
 * 注意：内嵌弹窗（ModalHost）是主窗口 Scene 内的节点，
 * 因此主题切换会自动覆盖到弹窗，不需要单独处理。
 */
public final class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    /** CSS 资源路径（注意与上面的「主题名」区分，二者不可混用比较） */
    private static final String CSS_PATH_DARK = "/css/launcher.css";
    private static final String CSS_PATH_LIGHT = "/css/launcher-light.css";

    /** 用于识别「本启动器的样式表」的文件名（清旧样式时用） */
    private static final String CSS_NAME_DARK = "launcher.css";
    private static final String CSS_NAME_LIGHT = "launcher-light.css";

    private ThemeManager() {}

    /**
     * 应用主题到指定 Scene
     *
     * @param theme 主题名（THEME_DARK / THEME_LIGHT），不是 CSS 路径
     */
    public static void apply(Scene scene, String theme) {
        if (scene == null) {
            return;
        }

        // 修复：这里曾误用 CSS 路径常量与主题名比较（CSS_LIGHT.equals(theme)
        // 即 "/css/launcher-light.css".equals("light")），永远为 false，
        // 导致无论选什么主题都只会应用深色。
        boolean light = isLight(theme);
        String cssPath = light ? CSS_PATH_LIGHT : CSS_PATH_DARK;

        try {
            var url = ThemeManager.class.getResource(cssPath);
            if (url == null) {
                logger.warn("主题 CSS 不存在: {}，回退到深色", cssPath);
                url = ThemeManager.class.getResource(CSS_PATH_DARK);
                if (url == null) {
                    return;
                }
                light = false;
            }

            // 先移除本启动器的旧样式表（精确匹配文件名，避免互相包含导致误删）
            scene.getStylesheets().removeIf(s -> {
                String lower = s.toLowerCase();
                return lower.endsWith(CSS_NAME_DARK) || lower.endsWith(CSS_NAME_LIGHT);
            });
            scene.getStylesheets().add(url.toExternalForm());

            currentTheme = light ? THEME_LIGHT : THEME_DARK;

            logger.info("已应用{}主题", light ? "浅色" : "深色");

        } catch (Exception e) {
            logger.error("应用主题失败: {}", e.getMessage());
        }
    }

    /**
     * 按配置应用主题
     */
    public static void applyFromConfig(Scene scene, ConfigManager config) {
        apply(scene, config.getTheme());
    }

    public static boolean isLight(String theme) {
        return THEME_LIGHT.equalsIgnoreCase(theme);
    }

    /**
     * 当前应使用的样式表 URL
     *
     * 给 Alert / TextInputDialog 这类**独立 Dialog**用：
     * 它们自带 DialogPane，不会自动继承主窗口 Scene 的样式表，
     * 必须手动 add。此前这些对话框硬编码 /css/launcher.css，
     * 于是选浅色时提示框仍是深色。
     *
     * 取值优先级：
     *   1. 当前显示的主窗口 Scene（最可靠 —— 用户看到的主题就是它）
     *   2. 最近一次 apply() 记录的主题（主窗口尚未显示时的兜底）
     *   3. 深色（默认）
     *
     * 走第 1 条还有个附带好处：连没调用 apply() 的独立窗口
     * （如 Java 下载进度小窗）也能跟着当前主题走。
     */
    public static String currentStylesheet() {
        String path = null;

        // 1) 从当前显示的主窗口 Scene 反查
        try {
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                if (!w.isShowing()) {
                    continue;
                }
                javafx.scene.Scene sc = w.getScene();
                if (sc == null) {
                    continue;
                }
                for (String sheet : sc.getStylesheets()) {
                    String lower = sheet.toLowerCase();
                    if (lower.endsWith(CSS_NAME_LIGHT)) {
                        path = CSS_PATH_LIGHT;
                        break;
                    }
                    if (lower.endsWith(CSS_NAME_DARK)) {
                        path = CSS_PATH_DARK;
                    }
                }
                if (path != null) {
                    break;
                }
            }
        } catch (Exception e) {
            logger.debug("反查当前主题失败，回退到记录值: {}", e.getMessage());
        }

        // 2) 兜底：最近一次 apply() 的主题
        if (path == null) {
            path = isLight(currentTheme) ? CSS_PATH_LIGHT : CSS_PATH_DARK;
        }

        var url = ThemeManager.class.getResource(path);
        if (url == null) {
            url = ThemeManager.class.getResource(CSS_PATH_DARK);
        }
        return url == null ? "" : url.toExternalForm();
    }

    /** 记住最近一次应用的主题，供独立对话框查询 */
    private static volatile String currentTheme = THEME_DARK;
}