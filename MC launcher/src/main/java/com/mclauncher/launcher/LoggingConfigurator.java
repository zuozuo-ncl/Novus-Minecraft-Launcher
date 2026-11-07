package com.mclauncher.launcher;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 日志目录配置
 * 必须在**任何 Logger 被创建之前**调用 —— logback 是在第一次
 */
public final class LoggingConfigurator {

    /**
     * 系统属性名，供 logback.xml 引用。
     * 注意：名字用 "mc.launcher.logdir"（带点的标准属性名）。
     */
    private static final String PROP_LOG_DIR = "mc.launcher.logdir";

    private LoggingConfigurator() {}

    /**
     * 初始化日志目录（幂等，可重复调用）
     *
     * @return 实际使用的日志目录
     */
    public static Path configure() {
        Path logDir = resolveLogDir();

        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            // 目录建不出来时退回临时目录，保证日志功能不中断
            try {
                logDir = Files.createTempDirectory("mc-launcher-logs");
            } catch (IOException ignored) {
                logDir = Path.of(System.getProperty("java.io.tmpdir", "."));
            }
        }

        // logback.xml 里用 ${mc.launcher.logdir} 读取
        System.setProperty(PROP_LOG_DIR, logDir.toAbsolutePath().toString());

        return logDir;
    }

    /**
     * 日志目录 = 启动器数据目录 / logs
     * 注意：这里**刻意不去调用 ConfigManager**。
     */
    private static Path resolveLogDir() {
        try {
            Path home = resolveLauncherHome();
            return home.resolve(".mc_launcher").resolve("logs");
        } catch (Exception e) {
            return Path.of(System.getProperty("java.io.tmpdir", "."), "mc-launcher-logs");
        }
    }

    /**
     * 轻量版「启动器主目录」解析（不依赖 ConfigManager）
     */
    private static Path resolveLauncherHome() {
        // 1) 显式覆盖
        String override = System.getProperty("mc.launcher.home");
        if (override == null || override.isBlank()) {
            override = System.getenv("MC_LAUNCHER_HOME");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        // 2) 程序自身位置
        try {
            var uri = LoggingConfigurator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path p = Path.of(uri);

            if (java.nio.file.Files.isRegularFile(p)) {
                Path parent = p.getParent();
                if (parent != null) {
                    // 从 target/ 内的 JAR 运行时上溯到项目根
                    Path root = findProjectRoot(parent);
                    if (root != null && parent.getFileName() != null
                            && "target".equals(parent.getFileName().toString())) {
                        return root;
                    }
                    return parent;
                }
            } else {
                Path root = findProjectRoot(p);
                if (root != null) {
                    return root;
                }
            }
        } catch (Exception ignored) {
        }

        // 3) 兜底
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * 向上查找含 pom.xml 或 .git 的目录（项目根）
     */
    private static Path findProjectRoot(Path start) {
        Path cursor = start;
        for (int i = 0; i < 8 && cursor != null; i++) {
            if (java.nio.file.Files.exists(cursor.resolve("pom.xml"))
                    || java.nio.file.Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /**
     * 获取当前日志文件路径（供 UI 提供「打开日志」入口）
     */
    public static Path getLogFile() {
        String dir = System.getProperty(PROP_LOG_DIR);
        Path base = dir != null ? Path.of(dir) : resolveLogDir();
        return base.resolve("launcher.log");
    }
}