package com.mclauncher.launcher;


import com.mclauncher.i18n.L;
import com.mclauncher.ui.MainWindow;
import com.mclauncher.update.VersionManifest;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动器主入口类
 * 负责初始化 JavaFX 并启动主窗口
 */
public class LauncherApp extends Application {

    // 静态初始化块：必须在本类的静态 logger 字段创建**之前**执行，
    // 否则 logback 会先用默认配置初始化，日志就落到工作目录去了。
    // 这样即使用户直接从 IDE 运行 LauncherApp（绕过 Main 类），
    // 日志路径依然是正确的。
    static {
        LoggingConfigurator.configure();
    }

    private static final Logger logger = LoggerFactory.getLogger(LauncherApp.class);

    private MainWindow mainWindow;

    @Override
    public void start(Stage primaryStage) {
        // 设置全局 JavaFX 错误处理器
        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("未捕获异常", throwable);
        });

        // 启动主窗口
        mainWindow = new MainWindow();
        mainWindow.show(primaryStage);

        // 界面已经显示出来了 —— 说明这个版本能正常运行。
        // 在这里（而不是更早的 init()）标记「可用」是有意为之：
        // 只有窗口真正显示，才能证明 UI 初始化没有崩溃。
        // 若在 init() 里标记，一个「能进 main 但 UI 起不来」的坏版本
        // 也会被记录成可用，回退机制就失效了。
        markVersionHealthy();

        // 若本次发生了回退，告诉用户（否则他会疑惑版本怎么变了）
        if (rollbackMessage != null) {
            showRollbackNotice(rollbackMessage);
            rollbackMessage = null;
        }
    }

    /**
     * 展示回退提示
     *
     * 用内嵌弹窗而非系统对话框，与启动器整体风格一致。
     * 延迟一拍调用：主窗口刚 show 出来，立即叠弹窗可能被抢焦点。
     */
    private void showRollbackNotice(String text) {
        javafx.application.Platform.runLater(() -> {
            try {
                var host = mainWindow.getModalHost();
                if (host == null) {
                    return;
                }
                com.mclauncher.ui.component.MessageDialog.warn(
                        host, L.t("s_ec289ce1c5"), text);
            } catch (Exception e) {
                logger.warn("显示回退提示失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 把当前运行的版本标记为「已验证可用」，并告知引导器「已经正常起来了」
     *
     * 失败不抛出：这只影响回退机制的准确性，不该阻止启动器运行。
     */
    private void markVersionHealthy() {
        try {
            VersionManifest m = VersionManifest.load();

            // 补记磁盘上存在但清单未收录的版本（含用户手动拷进来的）
            m.syncWithDisk();

            String running = com.mclauncher.config.ConfigManager
                    .staticLauncherVersion();
            if (running == null || running.isBlank()) {
                logger.warn("无法确定当前版本号，跳过可用性标记");
                return;
            }

            // 每次正常启动都走一遍 beginAttempt + markGood：
            // 既完成「试用 -> 确认」，也顺带把 current / lastGood
            // 刷新成当前版本 —— 比如用户手动换了 jar，清单要跟着更新。
            com.mclauncher.launcher.JarIntegrity.confirmRunning(m, running);

            // 通知引导器：本次启动是成功的（供它在等待期间判断）
            VersionManifest.writeStartupOk();

            // 把自己的 jar 哈希登记为本机信任锚：引导器下次启动时用它校验。
            // 必须在「界面已经显示」之后做 —— 能跑到这里才说明这份 jar 能正常用。
            com.mclauncher.launcher.JarIntegrity.registerSelf(running);

        } catch (Exception e) {
            logger.warn("标记版本可用性失败（不影响使用）: {}", e.getMessage());
        }
    }

    @Override
    public void init() {
        logger.info("初始化启动器...");

        // 启动前先检查「上次是不是没走完就挂了」。
        // 必须在 start() 之前做：若上个版本有问题，要在界面起来之前
        // 就决定好要回退到哪个版本，并提示用户。
        checkPreviousAttempt();
    }

    /**
     * 检查上次启动是否异常中断，必要时回退并提示
     *
     * 判定逻辑见 {@link VersionManifest#hasUnfinishedAttempt()}：
     * 上次启动时写了「正在试用」标记，但界面没能显示出来（没走到
     * markGood），所以标记还留着 —— 说明那个版本有问题。
     */
    private void checkPreviousAttempt() {
        try {
            VersionManifest m = VersionManifest.load();

            if (!m.hasUnfinishedAttempt()) {
                return;     // 上次正常结束（或首次运行），无需处理
            }

            String failed = m.current;
            String target = m.rollback();

            if (target != null) {
                logger.warn("检测到版本 {} 上次启动失败，已回退到 {}", failed, target);
            } else {
                logger.error("检测到版本 {} 上次启动失败，但没有可回退的版本", failed);
            }

            this.rollbackMessage = buildRollbackMessage(failed, target);

        } catch (Exception e) {
            logger.warn("检查上次启动状态失败（不影响使用）: {}", e.getMessage());
        }
    }

    private static String buildRollbackMessage(String failed, String target) {
        if (target == null) {
            return L.t("s_0eab2f9a07") + failed + L.t("s_71d0960599");
        }
        return L.t("s_0eab2f9a07") + failed + L.t("s_46f85410a2") + target + L.t("s_14e52e2c3e");
    }

    /** 需要向用户展示的回退提示（start() 里弹窗用） */
    private String rollbackMessage;

    @Override
    public void stop() {
        logger.info("关闭启动器...");

        // 清掉启动成功标记：留着的话，下次引导器等 3 秒时会看到
        // 一个「PID 属于上次那个已退出进程」的陈旧标记。
        // 引导器本身会用 PID 核对，这里清掉是为了更干净。
        try {
            com.mclauncher.update.VersionManifest.clearStartupOk();
        } catch (Exception e) {
            logger.debug("清理启动标记失败: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}