package com.mclauncher.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动器数据目录定位
 * 为什么要集中处理：{@code System.getenv("APPDATA")} 在某些环境下会返回 null
 */
public final class AppPaths {

    /** 数据目录名（与 C++ 引导器保持一致） */
    private static final String DIR_NAME = "NCL_Launcher";

    private AppPaths() {}

    public static Path appDataDir() {
        return resolveDir();
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
}
