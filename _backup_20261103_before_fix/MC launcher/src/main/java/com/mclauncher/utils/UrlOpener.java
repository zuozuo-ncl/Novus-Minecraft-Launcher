package com.mclauncher.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;

/**
 * 用系统默认浏览器打开链接
 *
 * 集中处理，避免每个页面各写一份（原来 AccountPage 里有一份私有实现，
 * 其它页面需要时只能复制粘贴）。
 *
 * 失败不抛出：打不开浏览器不该让调用方的流程中断 —— 用户仍可手动复制链接。
 */
public final class UrlOpener {

    private static final Logger logger = LoggerFactory.getLogger(UrlOpener.class);

    private UrlOpener() {}

    /**
     * 打开链接
     *
     * @return true 表示已成功交给浏览器
     */
    public static boolean open(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            // 优先用 AWT Desktop（跨平台，且不会弹出控制台窗口）
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                logger.info("已在浏览器中打开: {}", url);
                return true;
            }
        } catch (Exception e) {
            logger.warn("用 Desktop 打开链接失败，尝试调系统命令: {}", e.getMessage());
        }

        // 兜底：直接调系统命令。
        // Windows 上 rundll32 是最稳的方式（不会像 `start` 那样依赖 shell）。
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            logger.info("已通过系统命令打开: {}", url);
            return true;
        } catch (Exception e) {
            logger.warn("打开链接失败（用户可手动复制）: {} - {}", url, e.getMessage());
            return false;
        }
    }
}