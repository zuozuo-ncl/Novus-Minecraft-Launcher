package com.mclauncher.ui;

import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 窗口图标
 * 为什么不只用一张大图：Windows 任务栏要 16/32，标题栏在 150% 缩放下要 48/64，
 */
public final class AppIcon {

    private static final Logger logger = LoggerFactory.getLogger(AppIcon.class);

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    /** 读到的图标列表（只读一次，进程内复用） */
    private static List<javafx.scene.image.Image> cached;

    private AppIcon() {}

    /** 给窗口设置图标；失败时只记日志，不影响启动 */
    public static void apply(Stage stage) {
        if (stage == null) {
            return;
        }
        List<javafx.scene.image.Image> icons = load();
        if (!icons.isEmpty()) {
            stage.getIcons().setAll(icons);
        }
    }

    private static synchronized List<javafx.scene.image.Image> load() {
        if (cached != null) {
            return cached;
        }
        List<javafx.scene.image.Image> out = new ArrayList<>();
        for (int size : SIZES) {
            String path = "/ui/icon-" + size + ".png";
            try (InputStream in = AppIcon.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                var img = new javafx.scene.image.Image(in);
                if (!img.isError()) {
                    out.add(img);
                }
            } catch (Exception e) {
                logger.debug("读取窗口图标 {} 失败: {}", path, e.getMessage());
            }
        }
        if (out.isEmpty()) {
            logger.warn("没有找到任何窗口图标资源（/ui/icon-*.png）");
        }
        cached = out;
        return out;
    }
}