package com.mclauncher.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 游戏目录条目
 *
 * 代表一个用户添加的「Minecraft 游戏目录」。
 *
 * ===== 路径语义（重要）=====
 *
 * 用户选 D:\Minecraft 后：
 *
 *   D:\Minecraft\                  ← getRootPath()，游戏文件的落地位置
 *     ├── versions\                ← 游戏版本（原版 + Forge/Fabric）
 *     ├── libraries\               ← 依赖库
 *     ├── assets\                  ← 资源文件
 *     ├── mods\                    ← 模组
 *     ├── saves\                   ← 存档
 *     └── .mc_launcher\            ← getLauncherDataPath()，隐藏
 *           ├── cache\                   下载缓存
 *           └── logs\                    启动器日志
 *
 * 设计要点：
 * - **游戏文件直接放在根目录下**，与 PCL/HMCL 一致，
 *   也能直接兼容用户已有的 .minecraft 整合包。
 * - `.mc_launcher` 只用于存放启动器的辅助数据（缓存、日志），
 *   它会被标记为隐藏，且**不作为目录管理列表的条目**出现。
 * - 启动器自身的配置（账号、Java 路径等）不在这里，
 *   而是存放在启动器程序目录。
 */
public class GameDirectory {

    /** 隐藏文件夹名（存放启动器辅助数据：缓存、日志） */
    public static final String DATA_FOLDER_NAME = ".mc_launcher";

    private String id;
    private String name;
    private String rootPath;
    private long createdAt;
    private long lastUsedAt;

    public GameDirectory() {
        // 供 Gson 反序列化使用
    }

    public GameDirectory(String id, String name, String rootPath) {
        this.id = id;
        this.name = name;
        this.rootPath = rootPath;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedAt = this.createdAt;
    }

    // ==================== 路径 ====================

    /**
     * 用户选定的游戏根目录 —— 游戏文件直接放这里
     */
    public Path getRootPath() {
        return Paths.get(rootPath);
    }

    /**
     * 启动器辅助数据目录（隐藏）：<根目录>/.mc_launcher
     *
     * 只放缓存、日志等启动器自己的东西，不放游戏文件。
     */
    public Path getLauncherDataPath() {
        return getRootPath().resolve(DATA_FOLDER_NAME);
    }

    /**
     * 下载缓存目录
     */
    public Path getCacheDir() {
        return getLauncherDataPath().resolve("cache");
    }

    /**
     * 日志目录
     */
    public Path getLogsDir() {
        return getLauncherDataPath().resolve("logs");
    }

    // ---------- 以下均为「游戏文件」路径，直接位于根目录下 ----------

    /** 版本目录：<根>/versions */
    public Path getVersionsDir() {
        return getRootPath().resolve("versions");
    }

    /** 库文件目录：<根>/libraries */
    public Path getLibrariesDir() {
        return getRootPath().resolve("libraries");
    }

    /** 资源目录：<根>/assets */
    public Path getAssetsDir() {
        return getRootPath().resolve("assets");
    }

    /** 模组目录：<根>/mods */
    public Path getModsDir() {
        return getRootPath().resolve("mods");
    }

    /** 存档目录：<根>/saves */
    public Path getSavesDir() {
        return getRootPath().resolve("saves");
    }

    /** 光影目录：<根>/shaderpacks */
    public Path getShaderPacksDir() {
        return getRootPath().resolve("shaderpacks");
    }

    /** 原理图目录：<根>/schematics */
    public Path getSchematicsDir() {
        return getRootPath().resolve("schematics");
    }

    /** 某个版本的 natives 目录 */
    public Path getNativeDir(String versionId) {
        return getVersionsDir().resolve(versionId).resolve("natives");
    }

    // ==================== 校验 ====================

    /**
     * 目录条目是否可用（路径非空）
     */
    public boolean isValid() {
        return rootPath != null && !rootPath.isBlank();
    }

    /**
     * 根目录当前是否真实存在
     */
    /**
     * 根目录当前是否真实存在
     */
    public boolean existsOnDisk() {
        try {
            return java.nio.file.Files.exists(getRootPath());
        } catch (Exception e) {
            return false;
        }
    }

    public void touch() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    // ==================== Getter / Setter ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRootPathString() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(long lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    /**
     * 列表展示用的名称（未命名时退回路径末段）
     */
    public String getDisplayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        try {
            Path p = getRootPath();
            Path fn = p.getFileName();
            return fn != null ? fn.toString() : rootPath;
        } catch (Exception e) {
            return rootPath;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GameDirectory other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return getDisplayName() + "  [" + rootPath + "]";
    }
}