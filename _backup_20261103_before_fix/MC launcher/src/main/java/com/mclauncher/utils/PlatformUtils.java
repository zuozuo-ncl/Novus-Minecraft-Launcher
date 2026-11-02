package com.mclauncher.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * 跨平台工具类
 * 提供平台相关的辅助方法
 */
public class PlatformUtils {

    /**
     * 检查是否在 Windows 系统上运行
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 检查是否在 macOS 系统上运行
     */
    public static boolean isMacOS() {
        return System.getProperty("os.name").toLowerCase().contains("mac") ||
               System.getProperty("os.name").toLowerCase().contains("darwin");
    }

    /**
     * 检查是否在 Linux 系统上运行
     */
    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    /**
     * 获取适合当前平台的文件分隔符
     */
    public static String getFileSeparator() {
        return isWindows() ? "\\" : "/";
    }

    /**
     * 设置文件可执行权限（Unix/Linux/macOS）
     * Windows 上是空操作
     *
     * @param path 可执行文件路径
     * @throws IOException 设置失败时抛出
     */
    public static void setExecutable(Path path) throws IOException {
        if (isWindows()) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(path));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（如某些 Windows 挂载），忽略
            path.toFile().setExecutable(true, false);
        }
    }

    /**
     * 获取当前系统架构标识
     *
     * @return "x64" / "x86" / "aarch64" / 未知时返回原始 os.arch
     */
    public static String getArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return normalizeArch(arch);
    }

    /**
     * 归一化架构名
     */
    public static String normalizeArch(String arch) {
        if (arch == null || arch.isEmpty()) {
            return "x64";
        }
        switch (arch) {
            case "amd64":
            case "x86_64":
                return "x64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x32";
            case "aarch64":
            case "arm64":
                return "aarch64";
            case "arm":
                return "arm";
            default:
                return arch;
        }
    }

    /**
     * 获取系统临时目录
     */
    public static Path getTempDir() {
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }
}
