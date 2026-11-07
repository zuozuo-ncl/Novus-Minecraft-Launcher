package com.mclauncher.launcher;

import com.mclauncher.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

/** 启动器自身的完整性登记（供 C++ 引导器校验） */
public final class JarIntegrity {

    private static final Logger logger = LoggerFactory.getLogger(JarIntegrity.class);

    public static final String TRUSTED_FILE = "trusted-hashes.json";
    public static final String SELECTED_FILE = "selected.json";

    private JarIntegrity() {}

    /**
     * 当前运行中的 jar；在 IDE / 源码目录里运行时返回 null
     */
    public static Path selfJar() {
        try {
            CodeSource cs = JarIntegrity.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path self = Paths.get(cs.getLocation().toURI());
            return Files.isRegularFile(self) ? self : null;
        } catch (Exception e) {
            logger.debug("无法定位自身 jar: {}", e.getMessage());
            return null;
        }
    }

    /** 启动器数据目录：与 C++ 引导器一致（%APPDATA%\NCL_Launcher） */
    public static Path appDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, "NCL_Launcher");
    }

    /** 记录「自己就是这个 jar」，并把哈希登记到本机信任清单。 */
    public static void registerSelf(String version) {
        try {
            Path self = selfJar();
            if (self == null) {
                logger.debug("源码目录运行，跳过自身完整性登记");
                return;
            }
            Path dir = appDataDir();
            Files.createDirectories(dir);

            String sha = FileUtils.sha256(self);
            long size = Files.size(self);
            String ver = version != null && !version.isBlank() ? version : inferVersion(self);

            // 1) selected.json
            String json = "{\n"
                    + "  \"version\": \"" + jsonEscape(ver) + "\",\n"
                    + "  \"path\": \"" + jsonEscape(self.toAbsolutePath().toString().replace('\\', '/')) + "\",\n"
                    + "  \"size\": " + size + ",\n"
                    + "  \"sha256\": \"" + sha + "\"\n"
                    + "}\n";
            FileUtils.writeAtomic(dir.resolve(SELECTED_FILE), json);

            // 2) trusted-hashes.json（读旧内容 -> 覆盖该版本 -> 原子写）
            Path trustFile = dir.resolve(TRUSTED_FILE);
            String old = "";
            try {
                if (Files.exists(trustFile)) {
                    old = Files.readString(trustFile, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // 旧文件坏了就当空的
            }
            java.util.Map<String, String> map = parseTrusted(old);
            if (!sha.equalsIgnoreCase(map.getOrDefault(ver, ""))) {
                map.put(ver, sha);
                FileUtils.writeAtomic(trustFile, toJson(map));
                logger.info("已登记本机信任: {} = {}", ver, sha);
            }
            logger.debug("selected.json 已更新: {} ({} 字节)", self, size);
        } catch (Exception e) {
            // 登记失败不影响使用：引导器会退回官方 .sha256 / 内置表 / 放行无锚点文件
            logger.warn("登记自身完整性失败（不影响使用）: {}", e.getMessage());
        }
    }

    /** 粗略从文件名推断版本（mc-launcher-1.0.2.jar） */
    private static String inferVersion(Path jar) {
        String name = jar.getFileName().toString();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("mc-launcher-(\\d+(?:\\.\\d+){0,3})(?:-|\\.jar$)")
                .matcher(name);
        return m.find() ? m.group(1) : "unknown";
    }

    /**
     * 启动成功后：确认「当前正在运行的版本」可用，并把它设为当前版本。
     * 别处），所以必须先把 current 指向运行中的版本、再确认 —— 反过来会把
     */
    public static void confirmRunning(com.mclauncher.update.VersionManifest m, String version) {
        if (m == null || version == null || version.isBlank()) {
            return;
        }
        try {
            m.beginAttempt(version);
            m.markGood();
        } catch (Exception e) {
            logger.warn("确认运行版本失败（不影响使用）: {}", e.getMessage());
        }
    }

    /** 旧接口：修复历史版本清单里「明明没跑过却被标成待验证」的状态 */
    public static void healManifest(com.mclauncher.update.VersionManifest m) {
        confirmRunning(m, com.mclauncher.config.ConfigManager.staticLauncherVersion());
    }

    // ---------- 极简 JSON（只处理扁平字符串映射） ----------

    private static java.util.Map<String, String> parseTrusted(String json) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                .matcher(json == null ? "" : json);
        while (m.find()) {
            map.put(m.group(1), m.group(2).toLowerCase());
        }
        return map;
    }

    private static String toJson(java.util.Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  \"").append(jsonEscape(e.getKey())).append("\": \"")
                    .append(e.getValue()).append("\"");
        }
        return sb.append("\n}\n").toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
