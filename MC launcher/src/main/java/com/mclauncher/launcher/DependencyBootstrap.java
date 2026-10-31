package com.mclauncher.launcher;

import com.mclauncher.i18n.L;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.network.Downloader;
import com.mclauncher.utils.DirectoryUtils;
import com.mclauncher.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class DependencyBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(DependencyBootstrap.class);

    public static final String LAUNCHER_DIR = ".mc_launcher";

    private static final String[] REQUIRED_DIRS = {
            "cache",
            "cache/downloads",
            "java",
            "logs"
    };

    private static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com";

    private final Path launcherDir;
    private final ConfigManager config;
    private final Downloader downloader;

    private Consumer<String> statusCallback;

    public DependencyBootstrap(ConfigManager config) {
        this.config = config;

        
        this.launcherDir = ConfigManager.getConfigDir();
        this.downloader = new Downloader();
    }

    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    public boolean bootstrap() {
        try {
            reportStatus(L.t("s_bb0749c752"));

            createDirectoryStructure();

            createVersionMarker();

            ensureDefaultConfig();

            reportStatus(L.t("s_7e3000a645"));
            checkLauncherDependencies();

            reportStatus(L.t("s_5b3cf9c293"));
            logger.info("依赖引导完成");
            return true;

        } catch (Exception e) {
            logger.error("依赖引导失败: {}", e.getMessage(), e);
            reportStatus("初始化失败: " + e.getMessage());
            return false;
        }
    }

    private void createDirectoryStructure() throws IOException {

        DirectoryUtils.ensureHiddenTree(launcherDir, REQUIRED_DIRS);
        logger.info("启动器目录: {} (隐藏={})",
                launcherDir, DirectoryUtils.isHidden(launcherDir));
    }

    private void createVersionMarker() throws IOException {
        Path markerFile = launcherDir.resolve("launcher.version");
        String currentVersion = config.getLauncherVersion();

        if (!Files.exists(markerFile) ||
            !Files.readString(markerFile, StandardCharsets.UTF_8).trim().equals(currentVersion)) {
            Files.writeString(markerFile, currentVersion, StandardCharsets.UTF_8);
            logger.info("更新启动器版本标记: {}", currentVersion);
        }
    }

    private void ensureDefaultConfig() {

        
        config.forceSave();
        logger.debug("配置文件路径: {}", ConfigManager.getConfigPath());
    }

    private void checkLauncherDependencies() throws IOException {
        List<DependencyInfo> dependencies = getRequiredDependencies();

        List<DependencyInfo> missing = new ArrayList<>();

        for (DependencyInfo dep : dependencies) {
            Path targetPath = launcherDir.resolve(dep.path);
            if (!Files.exists(targetPath)) {
                missing.add(dep);
            }
        }

        if (missing.isEmpty()) {
            logger.info("所有启动器依赖已就绪");
            return;
        }

        logger.info("发现 {} 个缺失的依赖，开始下载", missing.size());
        reportStatus("正在下载 " + missing.size() + " 个缺失的依赖...");

        int failed = 0;
        for (DependencyInfo dep : missing) {
            try {
                downloadDependency(dep);
            } catch (Exception e) {
                logger.error("依赖下载失败: {} - {}", dep.name, e.getMessage());
                failed++;
            }
        }

        if (failed > 0) {
            logger.warn("{} 个依赖下载失败，可能影响部分功能", failed);
        }
    }

    private List<DependencyInfo> getRequiredDependencies() {
        List<DependencyInfo> deps = new ArrayList<>();

        

        

        return deps;
    }

    private void downloadDependency(DependencyInfo dep) throws IOException {
        Path targetPath = launcherDir.resolve(dep.path);
        Files.createDirectories(targetPath.getParent());

        logger.info("下载依赖: {} -> {}", dep.name, dep.url);

        Downloader.DownloadResult result = downloader.download(dep.url, targetPath,
                new Downloader.DownloadListener() {
                    @Override
                    public void onProgress(long downloaded, long total, double percent) {
                        reportStatus(String.format(L.t("s_9772584a76"), dep.name, percent));
                    }

                    @Override
                    public void onComplete(Path destination) {
                        logger.info("依赖下载完成: {}", dep.name);
                    }

                    @Override
                    public void onError(Exception e) {
                        logger.error("依赖下载失败: {}", e.getMessage());
                    }

                    @Override
                    public void onStart() {}
                });

        if (!result.isSuccess()) {
            throw new IOException("下载失败: " + result.getErrorMessage());
        }
    }

    public boolean verifyDependencies() {
        for (DependencyInfo dep : getRequiredDependencies()) {
            Path targetPath = launcherDir.resolve(dep.path);
            if (!Files.exists(targetPath)) {
                return false;
            }
            if (dep.sha256 != null && !dep.sha256.isEmpty()) {
                try {
                    String actual = FileUtils.sha256(targetPath);
                    if (!actual.equalsIgnoreCase(dep.sha256)) {
                        logger.warn("依赖哈希不匹配: {}", dep.name);
                        return false;
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    public Path getLauncherDir() {
        return launcherDir;
    }

    private void reportStatus(String status) {
        logger.debug("状态: {}", status);
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }

    public void shutdown() {
        downloader.shutdown();
    }

    private static class DependencyInfo {
        final String name;
        final String path;
        final String url;
        final String sha256;

        DependencyInfo(String name, String path, String url, String sha256) {
            this.name = name;
            this.path = path;
            this.url = url;
            this.sha256 = sha256;
        }

        DependencyInfo(String name, String path, String url) {
            this(name, path, url, null);
        }
    }
}
