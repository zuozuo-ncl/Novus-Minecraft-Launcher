package com.mclauncher;

import com.mclauncher.launcher.LauncherApp;
import com.mclauncher.launcher.LoggingConfigurator;
import javafx.application.Application;

/**
 * Fat JAR 启动入口
 * 为什么需要这个类？
 */
public class Main {

    public static void main(String[] args) {
        // 必须先配置日志目录，再让任何 Logger 被创建 ——
        // logback 在首次 getLogger() 时完成初始化，之后再设系统属性就无效了。
        // 这样日志会落在「启动器目录/.mc_launcher/logs」，而不是进程工作目录。
        LoggingConfigurator.configure();

        Application.launch(LauncherApp.class, args);
    }
}