package com.mclauncher.mod;


import com.mclauncher.i18n.L;
import com.mclauncher.network.Downloader;
import com.mclauncher.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组下载器
 *
 * 职责：把 Modrinth 的版本文件下载到游戏目录的 mods 文件夹。
 *
 * 复用现有的 {@link Downloader}，因此自动获得
 * 断点续传、重试、退避等能力。
 *
 * 注意：本阶段不处理模组依赖。要装前置模组请手动安装。
 */
public class ModDownloader {

    private static final Logger logger = LoggerFactory.getLogger(ModDownloader.class);

    /** Modrinth 文件的连接超时。
     * 部分网络环境下 cdn.modrinth.com 不可达，用较短超时可快速失败并回退，
     * 而不是让用户等上几分钟。 */
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Downloader downloader;
    private final ModrinthClient modrinthClient;

    /**
     * @param modrinthClient 用于把 cdn.modrinth.com 的文件 URL 改写到国内镜像；
     *                       传 null 则直接使用原始 URL
     */
    public ModDownloader(ModrinthClient modrinthClient) {
        this.downloader = new Downloader(CONNECT_TIMEOUT_MS);
        this.modrinthClient = modrinthClient;
    }

    public ModDownloader() {
        this(null);
    }

    /**
     * 下载模组文件
     *
     * @param gameDir   游戏目录（mods 会建在此目录下）
     * @param version   选定的模组版本
     * @param listener  进度回调（可为 null）
     * @return 下载结果
     */
    public DownloadResult downloadMod(Path gameDir, ModrinthClient.ModVersion version,
                                      ProgressListener listener) {
        ModrinthClient.ModFile file = version.getPrimaryFile();

        if (file == null || file.getUrl() == null || file.getUrl().isBlank()) {
            String msg = L.t("s_e4f69e056c");
            logger.error(msg);
            if (listener != null) {
                listener.onError(msg);
            }
            return new DownloadResult(false, null, msg);
        }

        // 确保 mods 目录存在
        Path modsDir = gameDir.resolve("mods");
        try {
            Files.createDirectories(modsDir);
        } catch (IOException e) {
            String msg = L.t("s_dca339355e") + e.getMessage();
            logger.error(msg, e);
            if (listener != null) {
                listener.onError(msg);
            }
            return new DownloadResult(false, null, msg);
        }

        // 文件名可能包含 URL 编码字符（如 +），这里做个安全化处理
        String filename = sanitizeFilename(file.getFilename());
        if (filename.isBlank()) {
            filename = "mod-" + version.getId() + ".jar";
        }

        Path target = modsDir.resolve(filename);

        // 决定下载地址：优先走镜像，失败后回退到原始 URL
        String originalUrl = file.getUrl();
        String mirrorUrl = modrinthClient != null
                ? modrinthClient.toMirrorFileUrl(originalUrl)
                : originalUrl;

        logger.info("下载模组: {} -> {}", version.getVersionNumber(), target);
        logger.debug("下载地址: {}", mirrorUrl);

        if (listener != null) {
            listener.onStart(filename);
        }

        // 已存在且校验通过 -> 直接当作成功
        boolean alreadyExists;
        long existingSize;
        try {
            alreadyExists = Files.exists(target);
            existingSize = alreadyExists ? Files.size(target) : 0;
        } catch (IOException e) {
            alreadyExists = false;
            existingSize = 0;
        }

        if (alreadyExists && existingSize == file.getSize() && file.getSize() > 0) {
            try {
                if (file.getSha1() == null || file.getSha1().isBlank()
                        || file.getSha1().equalsIgnoreCase(FileUtils.sha1(target))) {
                    logger.info("模组文件已存在且校验通过，跳过下载: {}", filename);
                    if (listener != null) {
                        listener.onComplete(target);
                    }
                    return new DownloadResult(true, target, null);
                }
                logger.warn("已存在的模组文件校验失败，重新下载");
                Files.deleteIfExists(target);
            } catch (IOException e) {
                logger.debug("校验已存在文件失败: {}", e.getMessage());
            }
        }

        // 执行下载（Downloader 自带重试与断点续传）
        Downloader.DownloadResult result = downloader.download(mirrorUrl, target,
                new Downloader.DownloadListener() {
                    @Override
                    public void onStart() {
                    }

                    @Override
                    public void onProgress(long downloaded, long total, double percent) {
                        if (listener != null) {
                            listener.onProgress(downloaded, total, percent);
                        }
                    }

                    @Override
                    public void onComplete(Path destination) {
                    }

                    @Override
                    public void onError(Exception e) {
                    }
                });

        // 镜像失败时回退到原始 URL（例如镜像未代理该文件）
        if (!result.isSuccess() && !mirrorUrl.equals(originalUrl)) {
            logger.warn("镜像下载失败，回退原始地址: {}", originalUrl);
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                logger.debug("清理失败文件出错: {}", e.getMessage());
            }

            result = downloader.download(originalUrl, target,
                    new Downloader.DownloadListener() {
                        @Override
                        public void onStart() {
                        }

                        @Override
                        public void onProgress(long downloaded, long total, double percent) {
                            if (listener != null) {
                                listener.onProgress(downloaded, total, percent);
                            }
                        }

                        @Override
                        public void onComplete(Path destination) {
                        }

                        @Override
                        public void onError(Exception e) {
                        }
                    });
        }

        if (!result.isSuccess()) {
            String msg = result.getErrorMessage();
            logger.error("模组下载失败: {}", msg);
            if (listener != null) {
                listener.onError(msg);
            }
            return new DownloadResult(false, target, msg);
        }

        // SHA-1 校验（Modrinth 同时提供 sha1 / sha512，这里用 sha1 与现有工具对齐）
        if (file.getSha1() != null && !file.getSha1().isBlank()) {
            try {
                String actual = FileUtils.sha1(target);
                if (!file.getSha1().equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(target);
                    String msg = L.t("s_22ce8f1cbb");
                    logger.error("{}: {}", msg, filename);
                    if (listener != null) {
                        listener.onError(msg);
                    }
                    return new DownloadResult(false, target, msg);
                }
                logger.info("模组校验通过: {}", filename);
            } catch (IOException e) {
                logger.warn("校验模组文件失败: {}", e.getMessage());
            }
        }

        logger.info("模组下载完成: {}", target);
        if (listener != null) {
            listener.onComplete(target);
        }
        return new DownloadResult(true, target, null);
    }

    /**
     * 安全化文件名，去掉路径分隔符等非法字符
     * （Modrinth 的文件名一般没问题，但做防御性处理）
     */
    private String sanitizeFilename(String name) {
        if (name == null) {
            return "";
        }
        // 去掉可能被用于路径穿越的字符
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // 防止 ../ 之类的相对路径
        if (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_");
        }
        return cleaned;
    }

    /**
     * 关闭下载器
     */
    public void shutdown() {
        downloader.shutdown();
    }

    /**
     * 进度回调
     */
    public interface ProgressListener {
        void onStart(String filename);
        void onProgress(long downloaded, long total, double percent);
        void onComplete(Path destination);
        void onError(String message);
    }

    /**
     * 下载结果
     */
    public static class DownloadResult {
        private final boolean success;
        private final Path path;
        private final String errorMessage;

        public DownloadResult(boolean success, Path path, String errorMessage) {
            this.success = success;
            this.path = path;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public Path getPath() { return path; }
        public String getErrorMessage() { return errorMessage; }
    }
}