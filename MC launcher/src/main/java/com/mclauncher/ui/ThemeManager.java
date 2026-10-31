package com.mclauncher.ui;

import com.mclauncher.config.ConfigManager;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    private static final String CSS_PATH_DARK = "/css/launcher.css";
    private static final String CSS_PATH_LIGHT = "/css/launcher-light.css";

    private static final String CSS_NAME_DARK = "launcher.css";
    private static final String CSS_NAME_LIGHT = "launcher-light.css";

    private ThemeManager() {}

    public static void apply(Scene scene, String theme) {
        if (scene == null) {
            return;
        }

        

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

    public static void applyFromConfig(Scene scene, ConfigManager config) {
        apply(scene, config.getTheme());
    }

    public static boolean isLight(String theme) {
        return THEME_LIGHT.equalsIgnoreCase(theme);
    }

    public static String currentStylesheet() {
        String path = null;

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

        if (path == null) {
            path = isLight(currentTheme) ? CSS_PATH_LIGHT : CSS_PATH_DARK;
        }

        var url = ThemeManager.class.getResource(path);
        if (url == null) {
            url = ThemeManager.class.getResource(CSS_PATH_DARK);
        }
        return url == null ? "" : url.toExternalForm();
    }

    private static volatile String currentTheme = THEME_DARK;
}
