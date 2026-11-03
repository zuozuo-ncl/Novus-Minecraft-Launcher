package com.mclauncher.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 压缩文件工具类
 * 提供 ZIP 解压和打包功能
 */
public class ZipUtils {

    private static final Logger logger = LoggerFactory.getLogger(ZipUtils.class);
    private static final int BUFFER_SIZE = 8192;

    /**
     * 解压 ZIP 文件到目标目录
     *
     * @param zipFile   ZIP 文件路径
     * @param destDir   目标目录
     * @param overwrite 是否覆盖已存在的文件
     */
    public static void unzip(Path zipFile, Path destDir, boolean overwrite) throws IOException {
        if (!Files.exists(zipFile)) {
            throw new FileNotFoundException("ZIP 文件不存在: " + zipFile);
        }

        Files.createDirectories(destDir);

        long totalUnzipped = 0;
        try (ZipInputStream zis = new ZipInputStream(
                Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = destDir.resolve(entry.getName()).normalize();

                // 安全检查：防止路径遍历攻击
                Path destAbs = destDir.toAbsolutePath().normalize();
                if (!entryPath.toAbsolutePath().normalize().startsWith(destAbs)) {
                    logger.warn("跳过不安全的路径: {}", entry.getName());
                    zis.closeEntry();
                    continue;
                }
                // 已存在的父目录是符号链接/junction 时同样可能写到外面
                if (crossesLink(destAbs, entryPath.toAbsolutePath().normalize())) {
                    logger.warn("跳过经过外部链接的路径: {}", entry.getName());
                    zis.closeEntry();
                    continue;
                }
                // 本机唯一的解压上限：防止压缩炸弹把磁盘写满
                totalUnzipped += entry.getSize() > 0 ? entry.getSize() : 0;
                if (totalUnzipped > MAX_UNZIPPED_BYTES) {
                    throw new IOException("解压体积超过上限（可能的压缩炸弹）: " + totalUnzipped);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // 确保父目录存在
                    Files.createDirectories(entryPath.getParent());

                    // 检查是否需要覆盖
                    if (Files.exists(entryPath) && !overwrite) {
                        logger.debug("跳过已存在的文件: {}", entryPath);
                        zis.closeEntry();
                        continue;
                    }

                    // 解压文件
                    try (BufferedOutputStream bos = new BufferedOutputStream(
                            Files.newOutputStream(entryPath))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            bos.write(buffer, 0, bytesRead);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        logger.info("解压完成: {} -> {}", zipFile, destDir);
    }

    /**
     * 解压 ZIP 文件到目标目录（覆盖模式）
     */
    /** 单个压缩包解压后的总大小上限（16 GB） */
    private static final long MAX_UNZIPPED_BYTES = 16L * 1024 * 1024 * 1024;

    private static boolean crossesLink(Path base, Path target) {
        Path cur = base;
        for (Path part : base.relativize(target)) {
            cur = cur.resolve(part);
            try {
                if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cur)) {
                    return !cur.toRealPath().startsWith(base);
                }
            } catch (IOException e) {
                return true;   // 判断不了就当作不安全
            }
        }
        return false;
    }

    public static void unzip(Path zipFile, Path destDir) throws IOException {
        unzip(zipFile, destDir, true);
    }

    /**
     * 解压 ZIP 文件的某个条目
     *
     * @param zipFile      ZIP 文件路径
     * @param entryName    要解压的条目名称
     * @param destDir      目标目录
     */
    public static void unzipEntry(Path zipFile, String entryName, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    Path outputPath = destDir.resolve(entryName);
                    Files.createDirectories(outputPath.getParent());
                    try (BufferedOutputStream bos = new BufferedOutputStream(
                            Files.newOutputStream(outputPath))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            bos.write(buffer, 0, bytesRead);
                        }
                    }
                    break;
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 打包目录为 ZIP 文件
     *
     * @param sourceDir 源目录
     * @param destZip   目标 ZIP 文件
     */
    public static void zipDirectory(Path sourceDir, Path destZip) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("源路径必须是目录: " + sourceDir);
        }

        Files.createDirectories(destZip.getParent());

        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(destZip))) {

            Files.walk(sourceDir)
                 .filter(path -> !Files.isDirectory(path))
                 .forEach(path -> {
                     try {
                         String entryName = sourceDir.relativize(path).toString();
                         zos.putNextEntry(new ZipEntry(entryName));
                         Files.copy(path, zos);
                         zos.closeEntry();
                     } catch (IOException e) {
                         logger.error("打包文件失败: {}", path, e);
                     }
                 });
        }

        logger.info("打包完成: {} -> {}", sourceDir, destZip);
    }

    /**
     * 检查是否是有效的 ZIP 文件
     */
    public static boolean isValidZip(Path zipFile) {
        if (!Files.exists(zipFile)) {
            return false;
        }
        try (ZipInputStream zis = new ZipInputStream(
                Files.newInputStream(zipFile))) {
            return zis.getNextEntry() != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 列出 ZIP 文件中的所有条目
     */
    public static List<String> listZipEntries(Path zipFile) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(
                Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.add(entry.getName());
                zis.closeEntry();
            }
        }
        return entries;
    }

    /**
     * 解压 ZIP 文件并返回解压后的目录
     * 用于临时解压场景
     */
    public static Path unzipToTemp(Path zipFile, String prefix) throws IOException {
        Path tempDir = Files.createTempDirectory(prefix + "_");
        unzip(zipFile, tempDir);
        return tempDir;
    }
}
