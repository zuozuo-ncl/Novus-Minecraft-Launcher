package com.mclauncher.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动器数据目录定位
 *
 * 统一处理 %APPDATA%\NCL_Launcher 的解析。
 *
 * 为什么要集中处理：{@code System.getenv("APPDATA")} 在某些环境下会返回 null
 * （例如被引导器以不完整的环境块拉起、或运行在服务/沙箱环境里），
 * 直接交给 {@code Paths.get(null, "NCL_Launcher")} 会抛 NullPointerException，
 * 表现为「启动器一打开就崩」。这里做多级兜底。
 */
public final class AppPaths {

    /** 数据目录名（与 C++ 引导器保持一致） */
    private static final String DIR_NAME = "NCL_Launcher";

    private static final Logger logger = LoggerFactory.getLogger(AppPaths.class);

    private AppPaths() {}

    /**
     * 启动器改名前的旧数据目录名
     *
     * 启动器从「MC Launcher」改名为「Novus Minecraft Launcher」时，
     * 数据目录也从 %APPDATA%\MC_Launcher 改成了 %APPDATA%\NCL_Launcher。
     * 首次运行时会把旧目录的内容迁移过来，老用户无感知。
     */
    private static final String LEGACY_DIR_NAME = "MC_Launcher";

    /** 迁移只尝试一次（避免每次调用都检查） */
    private static volatile boolean migrationTried = false;

    /**
     * 启动器数据目录：%APPDATA%\NCL_Launcher
     *
     * 取值顺序：
     *   1. 环境变量 APPDATA
     *   2. 系统属性 user.home 下的 AppData\Roaming（Windows 默认位置）
     *   3. user.home 直接拼目录名（最后的兜底，保证永不返回 null）
     *
     * 首次调用时会顺带做旧目录迁移 —— 引导器通常已经做过了，
     * 但直接 java -jar 运行时不经过引导器，这里需要兜底。
     */
    public static Path appDataDir() {
        Path dir = resolveDir();
        tryMigrateLegacy(dir);
        return dir;
    }

    private static Path resolveDir() {
        // 1) 环境变量
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, DIR_NAME);
        }

        // 2) 用户主目录
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase().contains("win");
            if (windows) {
                // Windows 上 APPDATA 缺失时，按标准布局推导
                return Paths.get(home, "AppData", "Roaming", DIR_NAME);
            }
            return Paths.get(home, "." + DIR_NAME.toLowerCase());
        }

        // 3) 最后兜底：相对当前工作目录，至少不会崩
        return Paths.get(DIR_NAME);
    }

    /**
     * 从旧目录迁移数据（只做一次）
     *
     * 触发条件：新目录不存在、旧目录存在。
     * 迁移失败不影响使用 —— 大不了重新下载 JRE 和 jar。
     *
     * 旧目录**不删除**：万一迁移有问题，用户还能手动找回。
     * 磁盘占用可以接受（主要是 jar 和 JRE），且用户可自行清理。
     */
    private static void tryMigrateLegacy(Path newDir) {
        if (migrationTried) {
            return;
        }
        synchronized (AppPaths.class) {
            if (migrationTried) {
                return;
            }
            migrationTried = true;

            try {
                if (Files.exists(newDir)) {
                    return;         // 新目录已存在，无需迁移
                }
                Path parent = newDir.getParent();
                if (parent == null) {
                    return;
                }
                Path oldDir = parent.resolve(LEGACY_DIR_NAME);
                if (!Files.isDirectory(oldDir)) {
                    return;         // 没有旧目录（全新安装）
                }

                logger.info("检测到旧数据目录，开始迁移: {} -> {}", oldDir, newDir);
                copyTree(oldDir, newDir);
                logger.info("旧数据迁移完成（旧目录保留未删除）");

            } catch (Exception e) {
                logger.warn("旧数据迁移失败（将使用全新目录）: {}", e.getMessage());
            }
        }
    }

    /** 递归复制目录 */
    private static void copyTree(Path from, Path to) throws java.io.IOException {
        Files.walk(from).forEach(src -> {
            try {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException e) {
                // 单个文件失败不影响整体（比如被占用的 jar）
                logger.debug("迁移跳过 {}: {}", src, e.getMessage());
            }
        });
    }
}