package com.mclauncher.mod;

import com.mclauncher.i18n.L;
import com.mclauncher.network.Downloader;
import com.mclauncher.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModDownloader {

    private static final Logger logger = LoggerFactory.getLogger(ModDownloader.class);

    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Downloader downloader;
    private final ModrinthClient modrinthClient;

    public ModDownloader(ModrinthClient modrinthClient) {
        this.downloader = new Downloader(CONNECT_TIMEOUT_MS);
        this.modrinthClient = modrinthClient;
    }

    public ModDownloader() {
        this(null);
    }

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

        String filename = sanitizeFilename(file.getFilename());
        if (filename.isBlank()) {
            filename = "mod-" + version.getId() + ".jar";
        }

        Path target = modsDir.resolve(filename);

        String originalUrl = file.getUrl();
        String mirrorUrl = modrinthClient != null
                ? modrinthClient.toMirrorFileUrl(originalUrl)
                : originalUrl;

        logger.info("下载模组: {} -> {}", version.getVersionNumber(), target);
        logger.debug("下载地址: {}", mirrorUrl);

        if (listener != null) {
            listener.onStart(filename);
        }

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

    private String sanitizeFilename(String name) {
        if (name == null) {
            return "";
        }

        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();

        if (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "_");
        }
        return cleaned;
    }

    public void shutdown() {
        downloader.shutdown();
    }

    public interface ProgressListener {
        void onStart(String filename);
        void onProgress(long downloaded, long total, double percent);
        void onComplete(Path destination);
        void onError(String message);
    }

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
