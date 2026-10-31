package com.mclauncher.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class GameDirectory {

    public static final String DATA_FOLDER_NAME = ".mc_launcher";

    private String id;
    private String name;
    private String rootPath;
    private long createdAt;
    private long lastUsedAt;

    public GameDirectory() {

    }

    public GameDirectory(String id, String name, String rootPath) {
        this.id = id;
        this.name = name;
        this.rootPath = rootPath;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedAt = this.createdAt;
    }

    public Path getRootPath() {
        return Paths.get(rootPath);
    }

    public Path getLauncherDataPath() {
        return getRootPath().resolve(DATA_FOLDER_NAME);
    }

    public Path getCacheDir() {
        return getLauncherDataPath().resolve("cache");
    }

    public Path getLogsDir() {
        return getLauncherDataPath().resolve("logs");
    }

    public Path getVersionsDir() {
        return getRootPath().resolve("versions");
    }

    public Path getLibrariesDir() {
        return getRootPath().resolve("libraries");
    }

    public Path getAssetsDir() {
        return getRootPath().resolve("assets");
    }

    public Path getModsDir() {
        return getRootPath().resolve("mods");
    }

    public Path getSavesDir() {
        return getRootPath().resolve("saves");
    }

    public Path getShaderPacksDir() {
        return getRootPath().resolve("shaderpacks");
    }

    public Path getSchematicsDir() {
        return getRootPath().resolve("schematics");
    }

    public Path getNativeDir(String versionId) {
        return getVersionsDir().resolve(versionId).resolve("natives");
    }

    public boolean isValid() {
        return rootPath != null && !rootPath.isBlank();
    }

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
