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

/**
 * 目录工具类
 *
 * 主要负责创建「隐藏文件夹」并保证跨平台行为正确。
 */
public final class DirectoryUtils {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryUtils.class);

    private DirectoryUtils() {}

    /** 确保目录存在，并尝试将其标记为隐藏 */
    public static Path ensureHiddenDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        hide(dir);
        return dir;
    }

    /**
     * 确保目录存在（不隐藏）
     */
    public static Path ensureDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /** 把目录标记为隐藏 */
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
                // Unix 系：用文件名以 "." 开头实现隐藏即可
                String name = path.getFileName() != null ? path.getFileName().toString() : "";
                return name.startsWith(".");
            }
        } catch (Exception e) {
            // 某些文件系统（如部分网络盘、FAT）不支持该属性
            logger.debug("设置隐藏属性失败（不影响功能）: {} - {}", path, e.getMessage());
        }
        return false;
    }

    /**
     * 取消隐藏
     */
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

    /**
     * 递归确保目录树存在，并把「最外层」标记为隐藏
     *
     * 适用于一次性准备 versions / libraries / assets 等结构。
     */
    public static void ensureHiddenTree(Path base, String... subDirs) throws IOException {
        ensureHiddenDirectory(base);
        for (String sub : subDirs) {
            Path p = base.resolve(sub);
            Files.createDirectories(p);
        }
    }

    /**
     * 检查目录是否被标记为隐藏
     */
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

    /**
     * 目录是否可写（用于校验用户选的目录是否可用）
     */
    public static boolean isWritable(Path dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (!Files.isDirectory(dir)) {
                return false;
            }
            // 尝试创建一个临时文件来验证可写性
            Path probe = dir.resolve(".mc_write_test_" + System.nanoTime());
            Files.createFile(probe);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 统计目录占用空间（字节）
     *
     * 用于在目录管理界面展示各目录的大小。
     */
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

    /**
     * 校验用户选择的路径是否可作为游戏根目录
     *
     * @return null 表示可用；否则返回错误说明
     */
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

    /**
     * 判断给定目录是否为某个游戏根目录的数据文件夹
     */
    public static boolean isDataFolder(Path p) {
        if (p == null || p.getFileName() == null) {
            return false;
        }
        return com.mclauncher.config.GameDirectory.DATA_FOLDER_NAME
                .equals(p.getFileName().toString());
    }
}