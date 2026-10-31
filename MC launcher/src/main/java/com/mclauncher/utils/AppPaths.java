package com.mclauncher.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppPaths {

    private static final String DIR_NAME = "NCL_Launcher";

    private static final Logger logger = LoggerFactory.getLogger(AppPaths.class);

    private AppPaths() {}

    private static final String LEGACY_DIR_NAME = "MC_Launcher";

    private static volatile boolean migrationTried = false;

    public static Path appDataDir() {
        Path dir = resolveDir();
        tryMigrateLegacy(dir);
        return dir;
    }

    private static Path resolveDir() {

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, DIR_NAME);
        }

        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase().contains("win");
            if (windows) {

                return Paths.get(home, "AppData", "Roaming", DIR_NAME);
            }
            return Paths.get(home, "." + DIR_NAME.toLowerCase());
        }

        return Paths.get(DIR_NAME);
    }

    private static void tryMigrateLegacy(Path newDir) {
        if (migrationTried) {
            return;
        }
        synchronized (AppPaths.class) {
            if (migrationTried) {
                return;
            }
            migrationTried = true;

            try {
                if (Files.exists(newDir)) {
                    return;         
                }
                Path parent = newDir.getParent();
                if (parent == null) {
                    return;
                }
                Path oldDir = parent.resolve(LEGACY_DIR_NAME);
                if (!Files.isDirectory(oldDir)) {
                    return;         
                }

                logger.info("检测到旧数据目录，开始迁移: {} -> {}", oldDir, newDir);
                copyTree(oldDir, newDir);
                logger.info("旧数据迁移完成（旧目录保留未删除）");

            } catch (Exception e) {
                logger.warn("旧数据迁移失败（将使用全新目录）: {}", e.getMessage());
            }
        }
    }

    private static void copyTree(Path from, Path to) throws java.io.IOException {
        Files.walk(from).forEach(src -> {
            try {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException e) {

                logger.debug("迁移跳过 {}: {}", src, e.getMessage());
            }
        });
    }
}
