package com.mclauncher.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;

public final class UrlOpener {

    private static final Logger logger = LoggerFactory.getLogger(UrlOpener.class);

    private UrlOpener() {}

    public static boolean open(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {

            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                logger.info("已在浏览器中打开: {}", url);
                return true;
            }
        } catch (Exception e) {
            logger.warn("用 Desktop 打开链接失败，尝试调系统命令: {}", e.getMessage());
        }

        
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
