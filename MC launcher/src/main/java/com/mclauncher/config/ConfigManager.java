package com.mclauncher.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    private static final String CONFIG_DIR = ".mc_launcher";
    private static final String CONFIG_FILE = "config.json";

    private static final Path CONFIG_PATH = resolveConfigPath();

    public static Path getConfigPath() {
        return CONFIG_PATH;
    }

    public static Path getConfigDir() {
        return CONFIG_PATH.getParent();
    }

    private static Path resolveConfigPath() {
        Path base = launcherHome();
        return base.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }

    public static Path launcherHome() {

        
        String override = System.getProperty("mc.launcher.home");
        if (override == null || override.isBlank()) {
            override = System.getenv("MC_LAUNCHER_HOME");
        }
        if (override != null && !override.isBlank()) {
            logger.info("使用显式指定的启动器数据目录: {}", override);
            return Paths.get(override);
        }

        try {
            var uri = ConfigManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path p = Paths.get(uri);

            if (java.nio.file.Files.isRegularFile(p)) {

                

                Path parent = p.getParent();
                if (parent != null) {
                    Path projectRoot = findProjectRoot(parent);
                    if (projectRoot != null && parent.getFileName() != null
                            && "target".equals(parent.getFileName().toString())) {
                        logger.info("检测到从 target/ 内的 JAR 运行，"
                                + "数据目录上移到项目根: {}", projectRoot);
                        return projectRoot;
                    }
                    return parent;
                }
            } else {

                
                Path projectRoot = findProjectRoot(p);
                if (projectRoot != null) {
                    logger.info("开发环境运行，启动器数据目录: {}", projectRoot);
                    return projectRoot;
                }
            }
        } catch (Exception e) {
            logger.debug("定位程序目录失败: {}", e.getMessage());
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path mcLauncherDir = Paths.get(appData, "NCL_Launcher");
            logger.info("使用 APPDATA 目录作为启动器数据目录: {}", mcLauncherDir);
            return mcLauncherDir;
        }

        Path home = Paths.get(System.getProperty("user.home"));
        logger.warn("无法定位启动器目录，使用用户主目录: {}", home);
        return home;
    }

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

    private static final String DEFAULT_DOWNLOAD_DIR = ".";
    private static final String DEFAULT_GAME_DIR = "minecraft";
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    private Map<String, Object> config;
    private Gson gson;
    private boolean modified = false;

    public ConfigManager() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        this.config = new HashMap<>();
        loadConfig();
    }

    private void loadConfig() {
        try {

            Files.createDirectories(CONFIG_PATH.getParent());

            if (Files.exists(CONFIG_PATH)) {

                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> loaded = gson.fromJson(json, type);

                if (loaded != null) {
                    this.config = loaded;
                    removeStaleVersionKey();
                    backfillMissingDefaults();
                    logger.info("配置加载成功: {}（{} 项）",
                            CONFIG_PATH, this.config.size());
                } else {
                    logger.warn("配置文件内容为空，使用默认配置");
                    initDefaultConfig();
                    saveConfig();
                }
            } else {

                initDefaultConfig();
                saveConfig();
                logger.info("创建默认配置: {}", CONFIG_PATH);
            }
        } catch (Exception e) {

            

            logger.error("加载配置失败: {}", e.getMessage());

            

            

            if (backupBrokenConfig()) {
                initDefaultConfig();
                saveConfig();
                logger.info("已写入默认配置: {}", CONFIG_PATH);
            } else {
                initDefaultConfig();
                logger.warn("为避免覆盖可能可修复的配置，本次不回写 {}；"
                        + "当前会话使用默认值", CONFIG_PATH);
            }
        }
    }

    private void backfillMissingDefaults() {
        Map<String, Object> defaults = new HashMap<>();
        Map<String, Object> snapshot = this.config;

        this.config = defaults;
        initDefaultConfig();
        Map<String, Object> defaultValues = this.config;

        this.config = snapshot;
        for (Map.Entry<String, Object> e : defaultValues.entrySet()) {
            this.config.putIfAbsent(e.getKey(), e.getValue());
        }

        

        

        

        migrateItem("windowWidth",  1024.0, 860);
        migrateItem("windowWidth",  1024,    860);
        migrateItem("windowHeight", 768.0,   600);
        migrateItem("windowHeight", 768,     600);
    }

    private void migrateItem(String key, double oldValue, double newValue) {
        Object v = config.get(key);
        if (v == null) {
            return;
        }
        double d;
        if (v instanceof Number) {
            d = ((Number) v).doubleValue();
        } else {
            return;
        }
        if (Math.abs(d - oldValue) < 0.5) {
            config.put(key, newValue);
        }
    }

    private boolean backupBrokenConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) {

                return true;
            }

            Path backup = CONFIG_PATH.resolveSibling(
                    CONFIG_FILE + ".broken." + System.currentTimeMillis());

            
            Files.move(CONFIG_PATH, backup, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("原配置文件已损坏，已备份到: {}", backup);
            return true;

        } catch (Exception e) {

            
            logger.warn("备份损坏的配置文件失败: {}，将保留原文件不动", e.getMessage());
            return false;
        }
    }

    private void removeStaleVersionKey() {
        if (config.remove("launcherVersion") != null) {
            logger.info("已清除配置中过时的 launcherVersion（版本号改由构建产物提供）");
        }
    }

    private void initDefaultConfig() {
        config.put("downloadDir", DEFAULT_DOWNLOAD_DIR);
        config.put("gameDir", DEFAULT_GAME_DIR);
        config.put("javaPath", "");
        config.put("javaAutoDetect", true);
        config.put("javaVersion", "17");

        
        config.put("sourceType", "official");  
        config.put("gameVersion", "");

        

        

        
        config.put("windowWidth", 860);
        config.put("windowHeight", 600);
        config.put("lastAccountId", "");
        config.put("autoDownloadJava", true);
        config.put("memoryMax", "4G");
        config.put("memoryMin", "1G");

        config.put("gameDirectories", new ArrayList<>());
        config.put("activeDirectoryId", "");

        config.put("theme", "light");
    }

    public void saveConfig() {
        try {
            String json = gson.toJson(config);

            com.mclauncher.utils.FileUtils.writeAtomic(CONFIG_PATH, json);
            modified = false;
            logger.debug("配置已保存");
        } catch (IOException e) {
            logger.error("保存配置失败: {}", e.getMessage());
        }
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public String getString(String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }

    public double getDouble(String key, double defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    public void setString(String key, String value) {
        config.put(key, value);
        modified = true;
    }

    public void setInt(String key, int value) {
        config.put(key, value);
        modified = true;
    }

    public void setDouble(String key, double value) {
        config.put(key, value);
        modified = true;
    }

    public void setBoolean(String key, boolean value) {
        config.put(key, value);
        modified = true;
    }

    public void autoSave() {
        if (modified) {
            saveConfig();
        }
    }

    public void forceSave() {
        saveConfig();
    }

    public Path getDownloadDir() {
        String dir = getString("downloadDir", DEFAULT_DOWNLOAD_DIR);
        if (dir.isEmpty() || dir.equals(".")) {
            return Paths.get("");
        }
        return Paths.get(dir);
    }

    public Path getGameDir() {
        GameDirectory active = getActiveDirectory();
        if (active != null && active.isValid()) {
            return active.getRootPath();
        }

        
        return getDownloadDir().toAbsolutePath().resolve("minecraft");
    }

    @SuppressWarnings("unchecked")
    public List<GameDirectory> getGameDirectories() {
        Object raw = config.get("gameDirectories");
        if (!(raw instanceof List)) {
            return new ArrayList<>();
        }

        List<GameDirectory> result = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            GameDirectory d = toGameDirectory(item);
            if (d != null) {
                result.add(d);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private GameDirectory toGameDirectory(Object item) {
        if (item instanceof GameDirectory gd) {
            return gd;
        }
        if (!(item instanceof Map)) {
            return null;
        }

        Map<String, Object> m = (Map<String, Object>) item;
        GameDirectory d = new GameDirectory();
        d.setId(str(m.get("id")));
        d.setName(str(m.get("name")));
        d.setRootPath(str(m.get("rootPath")));
        d.setCreatedAt(num(m.get("createdAt")));
        d.setLastUsedAt(num(m.get("lastUsedAt")));

        return d.isValid() ? d : null;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    public void saveGameDirectories(List<GameDirectory> dirs) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (GameDirectory d : dirs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("rootPath", d.getRootPathString());
            m.put("createdAt", d.getCreatedAt());
            m.put("lastUsedAt", d.getLastUsedAt());
            raw.add(m);
        }
        config.put("gameDirectories", raw);
        modified = true;
        saveConfig();
    }

    public GameDirectory addGameDirectory(String rootPath, String name) {
        List<GameDirectory> dirs = getGameDirectories();

        for (GameDirectory d : dirs) {
            if (d.getRootPathString() != null
                    && d.getRootPathString().equalsIgnoreCase(rootPath)) {
                return null;
            }
        }

        GameDirectory d = new GameDirectory(
                java.util.UUID.randomUUID().toString(),
                name,
                rootPath);

        dirs.add(d);
        saveGameDirectories(dirs);

        if (getActiveDirectoryId() == null || getActiveDirectoryId().isBlank()) {
            setActiveDirectoryId(d.getId());
        }
        return d;
    }

    public boolean removeGameDirectory(String id) {
        List<GameDirectory> dirs = getGameDirectories();
        boolean removed = dirs.removeIf(d -> d.getId() != null && d.getId().equals(id));
        if (!removed) {
            return false;
        }
        saveGameDirectories(dirs);

        if (id.equals(getActiveDirectoryId())) {
            setActiveDirectoryId(dirs.isEmpty() ? "" : dirs.get(0).getId());
        }
        return true;
    }

    public void renameGameDirectory(String id, String newName) {
        List<GameDirectory> dirs = getGameDirectories();
        for (GameDirectory d : dirs) {
            if (d.getId() != null && d.getId().equals(id)) {
                d.setName(newName);
                break;
            }
        }
        saveGameDirectories(dirs);
    }

    public GameDirectory getActiveDirectory() {
        String activeId = getActiveDirectoryId();
        if (activeId == null || activeId.isBlank()) {

            List<GameDirectory> dirs = getGameDirectories();
            return dirs.isEmpty() ? null : dirs.get(0);
        }
        for (GameDirectory d : getGameDirectories()) {
            if (activeId.equals(d.getId())) {
                return d;
            }
        }
        List<GameDirectory> dirs = getGameDirectories();
        return dirs.isEmpty() ? null : dirs.get(0);
    }

    public void setActiveDirectoryId(String id) {
        config.put("activeDirectoryId", id == null ? "" : id);
        modified = true;
        saveConfig();

        List<GameDirectory> dirs = getGameDirectories();
        for (GameDirectory d : dirs) {
            if (d.getId() != null && d.getId().equals(id)) {
                d.touch();
            }
        }
        saveGameDirectories(dirs);
    }

    public String getActiveDirectoryId() {
        return getString("activeDirectoryId", "");
    }

    public boolean hasGameDirectory() {
        return !getGameDirectories().isEmpty();
    }

    public String getMemoryMax() {
        return getString("memoryMax", "4G");
    }

    public String getMemoryMin() {
        return getString("memoryMin", "1G");
    }

    public void setMemoryConfig(String min, String max) {
        setString("memoryMin", min);
        setString("memoryMax", max);
    }

    public String getSourceType() {
        return getString("sourceType", "official");
    }

    public String getTheme() {
        return getString("theme", "light");
    }

    public void setTheme(String theme) {
        setString("theme", theme);
    }

    public void setSourceType(String sourceType) {
        setString("sourceType", sourceType);
    }

    public String getLastAccountId() {
        return getString("lastAccountId", "");
    }

    public void setLastAccountId(String accountId) {
        setString("lastAccountId", accountId);
    }

    public String getJavaPath() {
        return getString("javaPath", "");
    }

    public void setJavaPath(String javaPath) {
        setString("javaPath", javaPath);
        setBoolean("javaAutoDetect", javaPath.isEmpty());
    }

    public boolean isAutoDetectJava() {
        return getBoolean("javaAutoDetect", true);
    }

    public boolean isAutoDownloadJava() {
        return getBoolean("autoDownloadJava", true);
    }

    public void setAutoDownloadJava(boolean autoDownload) {
        setBoolean("autoDownloadJava", autoDownload);
    }

    public String getJavaVersion() {
        return getString("javaVersion", "17");
    }

    public void setJavaVersion(String version) {
        setString("javaVersion", version);
    }

    public int getWindowWidth() {
        return getInt("windowWidth", 860);
    }

    public int getWindowHeight() {
        return getInt("windowHeight", 600);
    }

    public void setWindowSize(int width, int height) {
        setInt("windowWidth", width);
        setInt("windowHeight", height);
    }

    public String getGameVersion() {
        return getString("gameVersion", "");
    }

    public void setGameVersion(String version) {
        setString("gameVersion", version);
    }

    public String getLauncherVersion() {
        return staticLauncherVersion();
    }

    public static String staticLauncherVersion() {

        String fromManifest = readVersionFromManifest();
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest;
        }

        try (java.io.InputStream in = ConfigManager.class
                .getResourceAsStream("/launcher-version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Exception e) {
            logger.debug("读取 launcher-version.txt 失败: {}", e.getMessage());
        }

        return "1.0.2";
    }

    private static String readVersionFromManifest() {
        try {
            java.security.CodeSource cs = ConfigManager.class
                    .getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            java.nio.file.Path self = java.nio.file.Paths.get(cs.getLocation().toURI());

            if (!java.nio.file.Files.isRegularFile(self)) {
                return null;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(self.toFile())) {
                return jar.getManifest().getMainAttributes()
                        .getValue("Implementation-Version");
            }
        } catch (Exception e) {
            logger.debug("从 Manifest 读取版本失败: {}", e.getMessage());
            return null;
        }
    }

    @Deprecated
    public void setLauncherVersion(String version) {
        setString("launcherVersion", version);
    }

    public void clear() {
        config.clear();
        modified = true;
    }

    public void reset() {
        config.clear();
        initDefaultConfig();
        saveConfig();
        logger.info("配置已重置为默认值");
    }
}
