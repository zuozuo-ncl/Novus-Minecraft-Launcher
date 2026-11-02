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

/**
 * 配置管理器
 * 负责启动器配置的加载、保存和访问
 * 配置存储在 .mc_launcher/config.json 中
 */
public class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    // 配置目录名（位于启动器程序所在目录下）
    private static final String CONFIG_DIR = ".mc_launcher";
    private static final String CONFIG_FILE = "config.json";

    /**
     * 启动器配置文件的绝对路径
     *
     * 重要：这里必须解析成**绝对路径**，且锚定在「启动器程序所在目录」，
     * 而不是依赖进程的当前工作目录（CWD）。
     *
     * 原因是此前用相对路径 `Paths.get(".mc_launcher", "config.json")` 时，
     * 只要用户以不同的工作目录启动启动器（快捷方式的「起始位置」不同、
     * 从别的目录执行 jar 等），读写的就是**不同的 config.json**，
     * 表现为「重启后目录列表等设置全部消失」。
     */
    private static final Path CONFIG_PATH = resolveConfigPath();

    /**
     * 获取配置文件的绝对路径（供其它组件复用，避免各自解析出不同位置）
     */
    public static Path getConfigPath() {
        return CONFIG_PATH;
    }

    /**
     * 获取配置目录的绝对路径
     */
    public static Path getConfigDir() {
        return CONFIG_PATH.getParent();
    }

    /**
     * 解析配置文件的绝对路径
     *
     * 定位逻辑：
     * 1. 优先用 JAR/class 文件所在目录（启动器安装位置，最稳定）
     * 2. 开发环境（从 target/classes 运行）时退回项目根目录
     * 3. 都拿不到时退回 user.home，保证一定有可写位置
     */
    private static Path resolveConfigPath() {
        Path base = launcherHome();
        return base.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }

    /**
     * 启动器程序所在目录
     */
    public static Path launcherHome() {
        // 1) 显式覆盖优先：便于用户把数据放到任意位置
        //    -Dmc.launcher.home=D:\data  或  环境变量 MC_LAUNCHER_HOME
        String override = System.getProperty("mc.launcher.home");
        if (override == null || override.isBlank()) {
            override = System.getenv("MC_LAUNCHER_HOME");
        }
        if (override != null && !override.isBlank()) {
            logger.info("使用显式指定的启动器数据目录: {}", override);
            return Paths.get(override);
        }

        // 2) 定位程序自身位置
        try {
            var uri = ConfigManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path p = Paths.get(uri);

            if (java.nio.file.Files.isRegularFile(p)) {
                // 从 JAR 运行：数据放在 JAR 同级目录。
                // 注意若 JAR 位于 target/ 下（本地构建后直接运行），
                // 会被 mvn clean 清掉 —— 因此这种情况下继续往上找项目根。
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
                // 开发环境（target/classes 或 build/classes）：用项目根目录，
                // 绝不能落在 target/ 里（会被 clean 删除）
                Path projectRoot = findProjectRoot(p);
                if (projectRoot != null) {
                    logger.info("开发环境运行，启动器数据目录: {}", projectRoot);
                    return projectRoot;
                }
            }
        } catch (Exception e) {
            logger.debug("定位程序目录失败: {}", e.getMessage());
        }

        // 3) 最终回退：%APPDATA%\NCL_Launcher（与 C++ 引导器保持一致）
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path mcLauncherDir = Paths.get(appData, "NCL_Launcher");
            logger.info("使用 APPDATA 目录作为启动器数据目录: {}", mcLauncherDir);
            return mcLauncherDir;
        }

        // 4) 最后回退：用户主目录（一定可写）
        Path home = Paths.get(System.getProperty("user.home"));
        logger.warn("无法定位启动器目录，使用用户主目录: {}", home);
        return home;
    }

    /**
     * 从给定路径向上查找项目根（含 pom.xml 或 .git 的目录）
     *
     * @return 项目根路径；找不到返回 null
     */
    private static Path findProjectRoot(Path start) {
        Path cursor = start;
        // 最多上溯 8 层，足以覆盖 target/classes、build/classes/java/main 等结构
        for (int i = 0; i < 8 && cursor != null; i++) {
            if (java.nio.file.Files.exists(cursor.resolve("pom.xml"))
                    || java.nio.file.Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    // 默认配置值
    private static final String DEFAULT_DOWNLOAD_DIR = ".";
    private static final String DEFAULT_GAME_DIR = "minecraft";
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    // 配置数据
    private Map<String, Object> config;
    private Gson gson;
    private boolean modified = false;

    /**
     * 构造函数：加载配置
     */
    public ConfigManager() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        this.config = new HashMap<>();
        loadConfig();
    }

    /**
     * 加载配置
     */
    private void loadConfig() {
        try {
            // 确保配置目录存在
            Files.createDirectories(CONFIG_PATH.getParent());

            if (Files.exists(CONFIG_PATH)) {
                // 读取现有配置
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> loaded = gson.fromJson(json, type);

                // Gson 对 "null" 内容会返回 null，需要防御
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
                // 初始化默认配置
                initDefaultConfig();
                saveConfig();
                logger.info("创建默认配置: {}", CONFIG_PATH);
            }
        } catch (Exception e) {
            // 注意：这里捕获所有异常（不仅是 IOException）。
            // Gson 解析失败会抛 JsonSyntaxException（RuntimeException），
            // 若漏掉会导致启动崩溃。
            logger.error("加载配置失败: {}", e.getMessage());

            // 关键：**不要**无条件用默认值覆盖用户的配置文件 ——
            // 那样一次解析失败就会让用户丢失所有设置。
            // 只有确认原文件已安全备份走，才写默认值；
            // 备份失败时保留内存中的默认配置但不落盘，
            // 让用户还有机会手工修复原文件。
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

    /**
     * 为缺失的键补上默认值（不覆盖已有值）
     *
     * 这样新增配置项时，旧配置文件也能平滑升级，
     * 而不会因为缺少某个键而出现 NPE。
     */
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

        // ---- 平滑迁移：缩小默认窗口尺寸 ----
        //
        // 早期默认 1024x768，对笔记本（1366x768）来说太大了。
        // 缩小到 860x600 后大部分屏幕都能完整显示。
        //
        // 注意：只迁移「从未被用户主动改过的旧默认值」。
        // 如果用户已经手动改过尺寸，尊重他的选择。
        migrateItem("windowWidth",  1024.0, 860);
        migrateItem("windowWidth",  1024,    860);
        migrateItem("windowHeight", 768.0,   600);
        migrateItem("windowHeight", 768,     600);
    }

    /**
     * 如果配置项的当前值等于 oldValue（Gson 可能存成 Double 或整数），
     * 就更新为新值。用于平滑迁移旧默认值，不影响用户已修改的设置。
     */
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

    /**
     * 备份损坏的配置文件（解析失败时调用）
     *
     * @return true 表示已成功备份（原文件已移走，可以安全写默认值）；
     *         false 表示备份失败（此时**不应**覆盖原文件）
     */
    private boolean backupBrokenConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                // 文件不存在，无需备份，直接写默认值即可
                return true;
            }

            Path backup = CONFIG_PATH.resolveSibling(
                    CONFIG_FILE + ".broken." + System.currentTimeMillis());

            // 用 REPLACE_EXISTING：避免同一毫秒内二次失败导致
            // FileAlreadyExistsException（那样会让备份静默失败）
            Files.move(CONFIG_PATH, backup, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("原配置文件已损坏，已备份到: {}", backup);
            return true;

        } catch (Exception e) {
            // 用 WARN 而不是 DEBUG：这是用户需要知道的问题
            // （logback 默认输出 INFO 及以上，DEBUG 用户看不到）
            logger.warn("备份损坏的配置文件失败: {}，将保留原文件不动", e.getMessage());
            return false;
        }
    }

    /**
     * 初始化默认配置
     */
    /**
     * 清掉历史遗留的 launcherVersion 配置项
     *
     * 早期版本把启动器版本号写进了 config.json。升级后旧值会一直留着，
     * 导致程序自称旧版本、更新检测反复提示同一个"新版本"。
     * 版本号现在从 Manifest / 资源文件读取，配置里这一项是多余的，
     * 加载时顺手删除（下次保存配置时就不再写回磁盘）。
     */
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
        // 实测：BMCLAPI 对连接数限流较严（并发高时返回 429 并长时间降速），
        // 而官方源带宽更充足。因此默认使用官方源，用户可自行切换到镜像。
        config.put("sourceType", "official");  // official 或 bmcl
        config.put("gameVersion", "");
        // 注意：这里**不再**写入 launcherVersion。
        // 版本号是构建产物的属性（见 getLauncherVersion()），
        // 存进配置文件会让升级后的程序一直自称旧版本。
        // 旧版本留下的这个键会在加载时被清掉，见 removeStaleVersionKey()。
        // 窗口默认尺寸：860x600。
        // 早期是 1024x768，在 1366x768 的笔记本上几乎占满整屏。
        config.put("windowWidth", 860);
        config.put("windowHeight", 600);
        config.put("lastAccountId", "");
        config.put("autoDownloadJava", true);
        config.put("memoryMax", "4G");
        config.put("memoryMin", "1G");

        // 游戏目录列表（用户可添加多个）与当前激活的那个
        config.put("gameDirectories", new ArrayList<>());
        config.put("activeDirectoryId", "");
        // 界面主题：light（默认）/ dark
        config.put("theme", "light");
    }

    /**
     * 保存配置
     */
    public void saveConfig() {
        try {
            String json = gson.toJson(config);
            // 原子写：避免写到一半被中断时留下截断的 JSON，导致下次启动配置全丢
            com.mclauncher.utils.FileUtils.writeAtomic(CONFIG_PATH, json);
            modified = false;
            logger.debug("配置已保存");
        } catch (IOException e) {
            logger.error("保存配置失败: {}", e.getMessage());
        }
    }

    /**
     * 获取配置值
     */
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

    /**
     * 设置配置值
     */
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

    /**
     * 如果配置被修改过，保存配置
     */
    public void autoSave() {
        if (modified) {
            saveConfig();
        }
    }

    /**
     * 强制保存配置
     */
    public void forceSave() {
        saveConfig();
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取下载目录（启动器缓存/Java 运行时的存放位置）
     */
    public Path getDownloadDir() {
        String dir = getString("downloadDir", DEFAULT_DOWNLOAD_DIR);
        if (dir.isEmpty() || dir.equals(".")) {
            return Paths.get("");
        }
        return Paths.get(dir);
    }

    /**
     * 获取「游戏数据目录」—— 所有游戏相关文件的落地位置
     *
     * 语义：**直接返回用户选定的根目录**（如 D:\Minecraft）。
     * 版本 / 库 / 资源 / 模组 / 存档都直接放在该目录下，
     * 这是 PCL、HMCL 等主流启动器的通行做法，
     * 也能与用户已下载的整合包、手动安装的 .minecraft 兼容。
     *
     * 注意：启动器自身的配置另存于「启动器程序目录/.mc_launcher」，
     * 与游戏数据分开放置，互不影响。
     */
    public Path getGameDir() {
        GameDirectory active = getActiveDirectory();
        if (active != null && active.isValid()) {
            return active.getRootPath();
        }

        // 尚未配置任何游戏目录时的回退：
        // 使用「启动器所在目录/.minecraft」，保证功能不中断
        return getDownloadDir().toAbsolutePath().resolve("minecraft");
    }

    // ==================== 游戏目录列表管理 ====================

    /**
     * 获取全部游戏目录
     */
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

    /**
     * 把配置里的原始 Map 还原成 GameDirectory
     * （因为 Gson 反序列化后是 Map，不是强类型对象）
     */
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

    /**
     * 保存游戏目录列表
     */
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

    /**
     * 添加一个游戏目录
     *
     * @return 新添加的目录；若已存在同样路径则返回 null
     */
    public GameDirectory addGameDirectory(String rootPath, String name) {
        List<GameDirectory> dirs = getGameDirectories();

        // 去重：同一路径不重复添加
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

        // 第一个目录自动设为激活
        if (getActiveDirectoryId() == null || getActiveDirectoryId().isBlank()) {
            setActiveDirectoryId(d.getId());
        }
        return d;
    }

    /**
     * 删除一个游戏目录
     *
     * 只从列表移除，**不会**删除磁盘上的文件（避免误删用户数据）。
     */
    public boolean removeGameDirectory(String id) {
        List<GameDirectory> dirs = getGameDirectories();
        boolean removed = dirs.removeIf(d -> d.getId() != null && d.getId().equals(id));
        if (!removed) {
            return false;
        }
        saveGameDirectories(dirs);

        // 若删的是当前激活目录，自动切换到第一个
        if (id.equals(getActiveDirectoryId())) {
            setActiveDirectoryId(dirs.isEmpty() ? "" : dirs.get(0).getId());
        }
        return true;
    }

    /**
     * 重命名目录显示名
     */
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

    /**
     * 获取当前激活的目录
     */
    public GameDirectory getActiveDirectory() {
        String activeId = getActiveDirectoryId();
        if (activeId == null || activeId.isBlank()) {
            // 没设置时退回第一个
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

    /**
     * 设置当前激活目录（单选）
     */
    public void setActiveDirectoryId(String id) {
        config.put("activeDirectoryId", id == null ? "" : id);
        modified = true;
        saveConfig();

        // 记录使用时间
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

    /**
     * 是否有任何已配置的游戏目录
     */
    public boolean hasGameDirectory() {
        return !getGameDirectories().isEmpty();
    }

    /**
     * 获取 JVM 最大内存
     */
    public String getMemoryMax() {
        return getString("memoryMax", "4G");
    }

    /**
     * 获取 JVM 最小内存
     */
    public String getMemoryMin() {
        return getString("memoryMin", "1G");
    }

    /**
     * 设置内存配置
     */
    public void setMemoryConfig(String min, String max) {
        setString("memoryMin", min);
        setString("memoryMax", max);
    }

    /**
     * 获取 API 源
     */
    public String getSourceType() {
        return getString("sourceType", "official");
    }

    /**
     * 界面主题：light（默认）/ dark
     */
    public String getTheme() {
        return getString("theme", "light");
    }

    public void setTheme(String theme) {
        setString("theme", theme);
    }

    /**
     * 设置 API 源
     */
    public void setSourceType(String sourceType) {
        setString("sourceType", sourceType);
    }

    /**
     * 获取最后使用的账号 ID
     */
    public String getLastAccountId() {
        return getString("lastAccountId", "");
    }

    /**
     * 设置最后使用的账号 ID
     */
    public void setLastAccountId(String accountId) {
        setString("lastAccountId", accountId);
    }

    /**
     * 获取 JVM 路径
     */
    public String getJavaPath() {
        return getString("javaPath", "");
    }

    /**
     * 设置 JVM 路径
     */
    public void setJavaPath(String javaPath) {
        setString("javaPath", javaPath);
        setBoolean("javaAutoDetect", javaPath.isEmpty());
    }

    /**
     * 检查是否启用自动检测 Java
     */
    public boolean isAutoDetectJava() {
        return getBoolean("javaAutoDetect", true);
    }

    /**
     * 检查是否启用自动下载 Java
     */
    public boolean isAutoDownloadJava() {
        return getBoolean("autoDownloadJava", true);
    }

    /**
     * 设置是否自动下载 Java
     */
    public void setAutoDownloadJava(boolean autoDownload) {
        setBoolean("autoDownloadJava", autoDownload);
    }

    /**
     * 获取 Java 版本要求
     */
    public String getJavaVersion() {
        return getString("javaVersion", "17");
    }

    /**
     * 设置 Java 版本要求
     */
    public void setJavaVersion(String version) {
        setString("javaVersion", version);
    }

    /**
     * 获取窗口尺寸
     */
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

    /**
     * 获取游戏版本
     */
    public String getGameVersion() {
        return getString("gameVersion", "");
    }

    /**
     * 设置游戏版本
     */
    public void setGameVersion(String version) {
        setString("gameVersion", version);
    }

    /**
     * 启动器版本号
     *
     * 优先从 JAR 的 Manifest 读取（Maven 打包时由 pom 的 <version> 写入），
     * 读不到再退回资源文件，最后才用硬编码兜底。
     *
     * 为什么**不读配置文件**：
     * 版本号是构建产物的属性，不是用户设置。早先把它存进 config.json 后
     * 出过问题 —— 升级到新版本时，旧配置里仍留着上一次的版本号，
     * 于是程序自称旧版本，更新检测便不断提示"发现新版本"，
     * 而用户跑的恰恰已经是最新的。
     *
     * setLauncherVersion() 保留只是为了不破坏既有调用方，
     * 写入的值不会再影响本方法。
     */
    public String getLauncherVersion() {
        return staticLauncherVersion();
    }

    /**
     * 静态版本查询
     *
     * 不依赖 ConfigManager 实例 —— 启动极早期（还没加载配置）或
     * 只想知道「我在跑哪个版本」的场合用它，避免触发整个配置加载流程。
     */
    public static String staticLauncherVersion() {
        // 1) Manifest（Maven Jar Plugin 默认写入 Implementation-Version）
        String fromManifest = readVersionFromManifest();
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest;
        }

        // 2) 资源文件（打包时由 maven-resources-plugin 过滤写入）
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

        // 3) 兜底：与 pom 的 <version> 保持一致
        return "1.0.3";
    }

    /** 从所属 JAR 的 Manifest 里取 Implementation-Version */
    private static String readVersionFromManifest() {
        try {
            java.security.CodeSource cs = ConfigManager.class
                    .getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            java.nio.file.Path self = java.nio.file.Paths.get(cs.getLocation().toURI());
            // 只在真 JAR 里读；IDE 里跑的是 target/classes 目录，没有 Manifest
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

    /**
     * 更新启动器版本（兼容保留）
     *
     * @deprecated 版本号现在由构建产物决定，写入配置不再有意义
     */
    @Deprecated
    public void setLauncherVersion(String version) {
        setString("launcherVersion", version);
    }

    /**
     * 清除配置
     */
    public void clear() {
        config.clear();
        modified = true;
    }

    /**
     * 重置为默认配置
     */
    public void reset() {
        config.clear();
        initDefaultConfig();
        saveConfig();
        logger.info("配置已重置为默认值");
    }
}
