package com.mclauncher.launcher;

import com.mclauncher.i18n.L;
import com.mclauncher.ui.MainWindow;
import com.mclauncher.update.VersionManifest;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LauncherApp extends Application {

    

    
    static {
        LoggingConfigurator.configure();
    }

    private static final Logger logger = LoggerFactory.getLogger(LauncherApp.class);

    private MainWindow mainWindow;

    @Override
    public void start(Stage primaryStage) {

        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("未捕获异常", throwable);
        });

        mainWindow = new MainWindow();
        mainWindow.show(primaryStage);

        

        

        markVersionHealthy();

        if (rollbackMessage != null) {
            showRollbackNotice(rollbackMessage);
            rollbackMessage = null;
        }
    }

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

    private void markVersionHealthy() {
        try {
            VersionManifest m = VersionManifest.load();

            m.syncWithDisk();

            String running = com.mclauncher.config.ConfigManager
                    .staticLauncherVersion();
            if (running == null || running.isBlank()) {
                logger.warn("无法确定当前版本号，跳过可用性标记");
                return;
            }

            

            com.mclauncher.launcher.JarIntegrity.confirmRunning(m, running);

            VersionManifest.writeStartupOk();

            
            com.mclauncher.launcher.JarIntegrity.registerSelf(running);

        } catch (Exception e) {
            logger.warn("标记版本可用性失败（不影响使用）: {}", e.getMessage());
        }
    }

    @Override
    public void init() {
        logger.info("初始化启动器...");

        

        checkPreviousAttempt();
    }

    private void checkPreviousAttempt() {
        try {
            VersionManifest m = VersionManifest.load();

            if (!m.hasUnfinishedAttempt()) {
                return;     
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

    private String rollbackMessage;

    @Override
    public void stop() {
        logger.info("关闭启动器...");

        

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
