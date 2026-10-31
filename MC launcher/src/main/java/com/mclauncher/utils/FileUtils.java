package com.mclauncher.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileUtils {

    private FileUtils() {}

    public static void copyFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    public static void moveFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path ensureDirectory(Path dir) throws IOException {
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

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

    public static String sha256(Path file) throws IOException {
        return hash(file, "SHA-256");
    }

    public static String sha1(Path file) throws IOException {
        return hash(file, "SHA-1");
    }

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

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

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

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static String formatFileSizeShort(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f K", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f M", bytes / (1024.0 * 1024));
        return String.format("%.2f G", bytes / (1024.0 * 1024 * 1024));
    }

    public static boolean isAccessible(Path path) {
        return path != null && Files.exists(path) && Files.isReadable(path);
    }

    public static String normalizePath(Path path) {
        if (path == null) return "";
        String pathStr = path.toString();

        if (pathStr.contains(" ") && !pathStr.startsWith("\"")) {
            return "\"" + pathStr + "\"";
        }
        return pathStr;
    }

    public static long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static boolean areFilesEqual(Path file1, Path file2) throws IOException {
        if (!Files.exists(file1) || !Files.exists(file2)) {
            return false;
        }
        return sha256(file1).equals(sha256(file2));
    }

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

    public static Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(prefix, suffix);
    }

    public static Path createTempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }
}
