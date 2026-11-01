package com.mclauncher;

import com.mclauncher.launcher.LauncherApp;
import com.mclauncher.launcher.LoggingConfigurator;
import javafx.application.Application;

/**
 * Fat JAR 启动入口
 *
 * 为什么需要这个类？
 * JavaFX 的 java -jar 启动机制有个限制：如果 JAR 的 Main-Class 直接继承
 * javafx.application.Application，JVM 会检查 JavaFX 运行时是否在 module-path 上，
 * 而 Fat JAR 里 JavaFX 是以普通 classpath JAR 形式打包的，因此会报
 * "缺少 JavaFX 运行时组件"。
 *
 * 解决办法：用一个不继承 Application 的普通类作为 Main-Class，
 * 由它在 main 方法里调用 Application.launch()，这样 JavaFX 就会
 * 从 classpath 正常加载。
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