package com.mclauncher.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 工具类集合
 * 提供文件操作、哈希计算、路径处理等通用功能
 */
public class FileUtils {

    private FileUtils() {}

    /**
     * 复制文件
     */
    public static void copyFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * 移动文件
     */
    public static void moveFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 原子写文本文件：先写同目录临时文件，再 rename 覆盖目标。
     *
     * 直接 writeString 覆盖时，进程在写到一半被杀 / 断电会留下截断的 JSON，
     * 下次启动读取失败 —— 配置文件和账号文件都经不起这个。
     */
    public static void writeAtomic(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        // 临时文件名必须唯一：固定 ".tmp" 时两个线程同时写会交错成非法内容。
        // 用「.tmp.<随机后缀>」后，各自完整写出，最后 rename 覆盖是原子的。
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp."
                + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL));
        Files.writeString(tmp, content, java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 理论上同目录不会走到这里；跨卷或特殊文件系统降级为普通替换
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 确保目录存在
     */
    public static Path ensureDirectory(Path dir) throws IOException {
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * 删除文件或目录（递归）
     */
    public static void delete(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    delete(child);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * 计算文件的 SHA-256 哈希值
     */
    public static String sha256(Path file) throws IOException {
        return hash(file, "SHA-256");
    }

    /**
     * 计算文件的 SHA-1 哈希值
     * Minecraft 的版本 JSON 里库文件和资源文件用的都是 SHA-1
     */
    public static String sha1(Path file) throws IOException {
        return hash(file, "SHA-1");
    }

    /**
     * 计算文件哈希
     */
    public static String hash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " algorithm not available", e);
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 解析内存大小字符串（如 "4G", "2048M", "1024"）为字节数
     */
    public static long parseMemorySize(String sizeStr) {
        sizeStr = sizeStr.trim().toUpperCase();
        long value;
        String unit = "";

        int i = 0;
        while (i < sizeStr.length() && Character.isDigit(sizeStr.charAt(i))) {
            i++;
        }
        value = Long.parseLong(sizeStr.substring(0, i));
        unit = sizeStr.substring(i).trim();

        switch (unit) {
            case "G":
            case "GB":
                return value * 1024 * 1024 * 1024;
            case "M":
            case "MB":
                return value * 1024 * 1024;
            case "K":
            case "KB":
                return value * 1024;
            default:
                return value;
        }
    }

    /**
     * 格式化字节数为可读字符串
     */
    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    /**
     * 格式化字节数为整数（用于进度显示）
     */
    public static String formatFileSizeShort(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f K", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f M", bytes / (1024.0 * 1024));
        return String.format("%.2f G", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 检查文件是否存在且可访问
     */
    public static boolean isAccessible(Path path) {
        return path != null && Files.exists(path) && Files.isReadable(path);
    }

    /**
     * 规范化路径（处理空格和特殊字符）
     */
    public static String normalizePath(Path path) {
        if (path == null) return "";
        String pathStr = path.toString();
        // 如果路径包含空格，需要用引号包裹
        if (pathStr.contains(" ") && !pathStr.startsWith("\"")) {
            return "\"" + pathStr + "\"";
        }
        return pathStr;
    }

    /**
     * 获取文件的最后修改时间（毫秒时间戳）
     */
    public static long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 检查两个文件是否相同（通过比较哈希值）
     */
    public static boolean areFilesEqual(Path file1, Path file2) throws IOException {
        if (!Files.exists(file1) || !Files.exists(file2)) {
            return false;
        }
        return sha256(file1).equals(sha256(file2));
    }

    /**
     * 递归列出目录下所有文件
     */
    public static java.util.List<Path> listFilesRecursively(Path dir) throws IOException {
        java.util.ArrayList<Path> result = new java.util.ArrayList<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return result;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(result::add);
        }
        return result;
    }

    /**
     * 创建临时文件
     */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(prefix, suffix);
    }

    /**
     * 创建临时目录
     */
    public static Path createTempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }
}
