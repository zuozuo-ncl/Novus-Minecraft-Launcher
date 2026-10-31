package com.mclauncher.utils;

import com.mclauncher.i18n.L;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class DirectoryUtils {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryUtils.class);

    private DirectoryUtils() {}

    public static Path ensureHiddenDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        hide(dir);
        return dir;
    }

    public static Path ensureDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    public static boolean hide(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }

        try {
            if (PlatformUtils.isWindows()) {
                DosFileAttributeView view =
                        Files.getFileAttributeView(path, DosFileAttributeView.class);
                if (view != null) {
                    DosFileAttributes attrs = view.readAttributes();
                    if (!attrs.isHidden()) {
                        view.setHidden(true);
                        logger.debug("已设置隐藏属性: {}", path);
                    }
                    return true;
                }
            } else {

                String name = path.getFileName() != null ? path.getFileName().toString() : "";
                return name.startsWith(".");
            }
        } catch (Exception e) {

            logger.debug("设置隐藏属性失败（不影响功能）: {} - {}", path, e.getMessage());
        }
        return false;
    }

    public static boolean unhide(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        try {
            if (PlatformUtils.isWindows()) {
                DosFileAttributeView view =
                        Files.getFileAttributeView(path, DosFileAttributeView.class);
                if (view != null) {
                    view.setHidden(false);
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("取消隐藏失败: {}", e.getMessage());
        }
        return false;
    }

    public static void ensureHiddenTree(Path base, String... subDirs) throws IOException {
        ensureHiddenDirectory(base);
        for (String sub : subDirs) {
            Path p = base.resolve(sub);
            Files.createDirectories(p);
        }
    }

    public static boolean isHidden(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        try {
            if (PlatformUtils.isWindows()) {
                DosFileAttributes attrs = Files.readAttributes(path, DosFileAttributes.class);
                return attrs.isHidden();
            }
            String name = path.getFileName() != null ? path.getFileName().toString() : "";
            return name.startsWith(".");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isWritable(Path dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (!Files.isDirectory(dir)) {
                return false;
            }

            Path probe = dir.resolve(".mc_write_test_" + System.nanoTime());
            Files.createFile(probe);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static long calculateSize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        final long[] total = {0};
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(
                        Path file, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            logger.debug("计算目录大小失败: {}", e.getMessage());
        }
        return total[0];
    }

    public static String validateRootDirectory(Path root) {
        if (root == null) {
            return L.t("s_255b9d09fc");
        }
        if (!Files.exists(root)) {
            return L.t("s_6d97c12d55");
        }
        if (!Files.isDirectory(root)) {
            return L.t("s_cf5378e327");
        }
        if (!Files.isReadable(root)) {
            return L.t("s_fe3b41dc75");
        }
        if (!isWritable(root)) {
            return L.t("s_9edc83b159");
        }
        return null;
    }

    public static boolean isDataFolder(Path p) {
        if (p == null || p.getFileName() == null) {
            return false;
        }
        return com.mclauncher.config.GameDirectory.DATA_FOLDER_NAME
                .equals(p.getFileName().toString());
    }
}
