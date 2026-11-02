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

/**
 * 依赖引导器
 * 负责在首次运行时检查并下载必要的依赖
 *
 * 对应模块 1：项目骨架与本地配置管理
 */
public class DependencyBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(DependencyBootstrap.class);

    /**
     * 启动器自身的数据目录名
     *
     * 注意与「游戏数据目录」区分：
     * - 本目录（启动器目录）：存放启动器配置、账号、Java 运行时、缓存
     *   位置 = 启动器程序所在目录/.mc_launcher
     * - 游戏数据目录：存放版本、库、资源、存档、模组
     *   位置 = 用户选定的游戏根目录/.mc_launcher
     *
     * 两者同名是历史原因（都叫 .mc_launcher），但它们位于不同父目录下，
     * 不会冲突。游戏数据目录由 GameDirectory 负责管理。
     */
    public static final String LAUNCHER_DIR = ".mc_launcher";

    // 必要的子目录（启动器自身需要的）
    private static final String[] REQUIRED_DIRS = {
            "cache",
            "cache/downloads",
            "java",
            "logs"
    };

    // BMCLAPI 基础 URL
    private static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com";

    private final Path launcherDir;
    private final ConfigManager config;
    private final Downloader downloader;

    private Consumer<String> statusCallback;

    public DependencyBootstrap(ConfigManager config) {
        this.config = config;
        // 锚定在「启动器程序所在目录」，不要用 CWD（否则不同工作目录下
        // 会解析出不同的位置，导致配置/缓存「丢失」）
        this.launcherDir = ConfigManager.getConfigDir();
        this.downloader = new Downloader();
    }

    /**
     * 设置状态回调
     */
    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    /**
     * 执行引导流程
     *
     * @return 是否成功
     */
    public boolean bootstrap() {
        try {
            reportStatus(L.t("s_bb0749c752"));

            // 1. 创建必要的目录结构
            createDirectoryStructure();

            // 2. 创建版本标记文件
            createVersionMarker();

            // 3. 检查并创建默认配置
            ensureDefaultConfig();

            // 4. 检查启动器自身依赖
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

    /**
     * 创建目录结构
     */
    private void createDirectoryStructure() throws IOException {
        // 用统一的工具方法创建并隐藏，保证跨平台行为一致
        DirectoryUtils.ensureHiddenTree(launcherDir, REQUIRED_DIRS);
        logger.info("启动器目录: {} (隐藏={})",
                launcherDir, DirectoryUtils.isHidden(launcherDir));
    }

    /**
     * 创建版本标记文件
     */
    private void createVersionMarker() throws IOException {
        Path markerFile = launcherDir.resolve("launcher.version");
        String currentVersion = config.getLauncherVersion();

        if (!Files.exists(markerFile) ||
            !Files.readString(markerFile, StandardCharsets.UTF_8).trim().equals(currentVersion)) {
            Files.writeString(markerFile, currentVersion, StandardCharsets.UTF_8);
            logger.info("更新启动器版本标记: {}", currentVersion);
        }
    }

    /**
     * 确保配置文件存在
     *
     * 配置的创建与读写**完全由 ConfigManager 负责**（它使用锚定在
     * 启动器程序目录的绝对路径）。这里只做一个「兜底触发」，
     * 不直接操作文件。
     *
     * 历史教训：此前这里会用相对路径读一份 config.json、
     * 再用绝对路径 + REPLACE_EXISTING 覆盖另一份，
     * 导致进程工作目录变化时用户设置被默认值覆盖
     * （表现为「重启后设置全部消失」）。已移除该逻辑。
     */
    private void ensureDefaultConfig() {
        // ConfigManager 的构造函数在文件不存在时已自动写入默认配置，
        // 这里只需确保内存中的配置落盘一次即可。
        config.forceSave();
        logger.debug("配置文件路径: {}", ConfigManager.getConfigPath());
    }

    /**
     * 检查启动器依赖
     *
     * 这里检查的是启动器运行所需的本地库文件。
     * 由于我们使用 Fat JAR 打包，大部分依赖已经包含在内。
     * 这里检查的是一些运行时的辅助文件。
     */
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

    /**
     * 获取必需的依赖列表
     *
     * 注意：这些是启动器运行时的辅助资源。
     * 主要的 Java 库已经打包在 Fat JAR 中。
     */
    private List<DependencyInfo> getRequiredDependencies() {
        List<DependencyInfo> deps = new ArrayList<>();

        // 启动器图标（可选，不影响功能）
        // deps.add(new DependencyInfo("launcher_icon", "icons/icon.png", ...));

        // 当前版本没有必需的运行时依赖需要下载
        // 所有必要的库都已打包在 Fat JAR 中
        // 这里保留结构，方便后续添加

        return deps;
    }

    /**
     * 下载依赖
     */
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

    /**
     * 验证依赖完整性
     */
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

    /**
     * 获取启动器目录
     */
    public Path getLauncherDir() {
        return launcherDir;
    }

    /**
     * 报告状态
     */
    private void reportStatus(String status) {
        logger.debug("状态: {}", status);
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }

    /**
     * 关闭
     */
    public void shutdown() {
        downloader.shutdown();
    }

    /**
     * 依赖信息
     */
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