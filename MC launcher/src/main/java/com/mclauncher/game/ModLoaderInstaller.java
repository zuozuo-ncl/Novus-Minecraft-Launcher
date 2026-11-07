package com.mclauncher.game;


import com.mclauncher.i18n.L;
import com.google.gson.*;
import com.mclauncher.network.BMCLApiClient;
import com.mclauncher.network.Downloader;
import com.mclauncher.utils.FileUtils;
import com.mclauncher.utils.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 模组加载器安装器
 * 处理 Fabric、Forge、OptiFine 的安装
 */
public class ModLoaderInstaller {

    private static final Logger logger = LoggerFactory.getLogger(ModLoaderInstaller.class);

    private final BMCLApiClient apiClient;
    private final Downloader downloader;
    private final Path gameDir;
    private final Gson gson;

    public ModLoaderInstaller(BMCLApiClient apiClient, Path gameDir) {
        this.apiClient = apiClient;
        this.gameDir = gameDir;
        this.downloader = new Downloader();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /** 安装 Fabric */
    public String installFabric(String gameVersion, String loaderVersion) throws IOException {
        logger.info("开始安装 Fabric: {}-{}", gameVersion, loaderVersion);

        // 1. 获取 Fabric profile JSON
        String fabricJson = apiClient.getFabricLoaderJson(gameVersion, loaderVersion);

        // 2. 解析并修改
        JsonObject fabricObj = JsonParser.parseString(fabricJson).getAsJsonObject();

        // 3. 设置 inheritsFrom
        fabricObj.addProperty("inheritsFrom", gameVersion);

        // 4. 生成版本 ID
        String versionId = gameVersion + "-fabric" + loaderVersion;

        // 5. 创建版本目录
        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        // 6. 保存 JSON
        Path jsonPath = versionDir.resolve(versionId + ".json");
        String prettyJson = gson.toJson(fabricObj);
        Files.writeString(jsonPath, prettyJson, StandardCharsets.UTF_8);

        logger.info("Fabric 安装完成: {}", versionId);
        logger.info("版本 JSON: {}", jsonPath);

        // 7. 下载 Fabric 库文件
        downloadFabricLibraries(fabricObj);

        return versionId;
    }

    /** 下载 Fabric 的库文件 */
    private int downloadFabricLibraries(JsonObject fabricObj) throws IOException {
        JsonArray libraries = fabricObj.getAsJsonArray("libraries");
        if (libraries == null) {
            return 0;
        }

        int ok = 0;

        for (JsonElement element : libraries) {
            JsonObject lib = element.getAsJsonObject();
            String name = lib.get("name").getAsString();
            String mavenPath = parseMavenPath(name);
            Path libPath = gameDir.resolve("libraries").resolve(mavenPath);

            // 取期望的 SHA-1（如果有）
            String expectedSha1 = null;
            if (lib.has("downloads")) {
                JsonElement dEl = lib.get("downloads");
                if (dEl.isJsonObject()) {
                    JsonElement aEl = dEl.getAsJsonObject().get("artifact");
                    if (aEl != null && aEl.isJsonObject()) {
                        JsonObject artifact = aEl.getAsJsonObject();
                        if (artifact.has("sha1")) {
                            expectedSha1 = artifact.get("sha1").getAsString();
                        }
                    }
                }
            }

            // 已存在且校验通过 -> 跳过
            if (Files.exists(libPath) && Files.size(libPath) > 0) {
                if (expectedSha1 == null || expectedSha1.isBlank()
                        || expectedSha1.equalsIgnoreCase(FileUtils.sha1(libPath))) {
                    ok++;
                    continue;
                }
                logger.warn("Fabric 库校验失败，重新下载: {}", name);
                Files.deleteIfExists(libPath);
            }

            // 优先用 BMCLAPI 镜像（Fabric Maven 也镜像在 /maven 下）
            String mirrorUrl = apiClient.getFabricMavenBase() + "/" + mavenPath;
            logger.debug("下载 Fabric 库: {} <- {}", name, mirrorUrl);
            Downloader.DownloadResult result = downloader.download(mirrorUrl, libPath, null);

            // 校验；失败则回退官方 Maven
            boolean valid = result.isSuccess() && Files.exists(libPath)
                    && (expectedSha1 == null || expectedSha1.isBlank()
                        || expectedSha1.equalsIgnoreCase(FileUtils.sha1(libPath)));

            if (!valid) {
                String officialUrl = "https://maven.fabricmc.net/" + mavenPath;
                logger.debug("Fabric 库镜像失败，回退官方源: {}", name);
                Files.deleteIfExists(libPath);
                result = downloader.download(officialUrl, libPath, null);

                valid = result.isSuccess() && Files.exists(libPath)
                        && (expectedSha1 == null || expectedSha1.isBlank()
                            || expectedSha1.equalsIgnoreCase(FileUtils.sha1(libPath)));
            }

            if (valid) {
                ok++;
            } else {
                logger.warn("Fabric 库下载失败: {}", name);
                Files.deleteIfExists(libPath);
            }
        }

        logger.info("Fabric 库文件就绪: {} 个", ok);
        return ok;
    }

    /**
     * 解析 Maven 坐标为路径
     */
    private String parseMavenPath(String name) {
        String[] parts = name.split(":");
        if (parts.length >= 3) {
            String group = parts[0].replace(".", "/");
            String artifact = parts[1];
            String version = parts[2];

            if (version.contains("@")) {
                version = version.split("@")[0];
            }

            if (parts.length >= 4) {
                String classifier = parts[3];
                return group + "/" + artifact + "/" + version + "/" +
                       artifact + "-" + version + "-" + classifier + ".jar";
            }

            return group + "/" + artifact + "/" + version + "/" +
                   artifact + "-" + version + ".jar";
        }
        return name;
    }

    /**
     * 安装 Forge
     * 注意：现代 Forge（1.13+）的库是从 Maven 仓库下载的普通 JAR，
     */
    public String installForge(String gameVersion, String forgeVersion) throws IOException {
        logger.info("开始安装 Forge: {}-{}", gameVersion, forgeVersion);

        // Forge 的完整版本号格式：1.20.1-47.0.1
        String fullVersion = gameVersion + "-" + forgeVersion;
        // 版本目录名使用 Forge 官方格式，便于与其它启动器互通
        String versionId = fullVersion + "-forge";

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        // 1. 下载 Forge 安装器
        //    按照 BMCLAPI 文档，Forge 文件路径规则为：
        //      /maven/net/minecraftforge/forge/{mcversion}-{version}/forge-{mcversion}-{version}-{category}.{format}
        //    该地址会 302 重定向到实际文件服务器，Downloader 已开启重定向跟随。
        String mavenPath = "net/minecraftforge/forge/" + fullVersion
                + "/forge-" + fullVersion + "-installer.jar";

        Path installerPath = versionDir.resolve("forge-installer.jar");

        if (!Files.exists(installerPath) || Files.size(installerPath) < 100_000) {
            // 优先走 BMCLAPI 镜像（国内速度快）
            String mirrorUrl = apiClient.getLibraryDownloadUrl(mavenPath);
            logger.info("下载 Forge 安装器: {}", mirrorUrl);

            Downloader.DownloadResult result = downloader.download(mirrorUrl, installerPath, null);

            // 镜像失败时回退到官方 Maven
            if (!result.isSuccess() || Files.size(installerPath) < 100_000) {
                String officialUrl = "https://maven.minecraftforge.net/" + mavenPath;
                logger.warn("BMCLAPI 下载失败，回退官方源: {}", officialUrl);
                result = downloader.download(officialUrl, installerPath, null);

                if (!result.isSuccess()) {
                    throw new IOException("Forge 安装器下载失败: " + result.getErrorMessage());
                }
            }
        } else {
            logger.info("Forge 安装器已存在，跳过下载");
        }

        // 2. 从安装器中提取 version.json（这是 Forge 提供的成品版本定义）
        logger.info("从安装器提取版本定义...");
        JsonObject forgeJson = extractVersionJsonFromInstaller(installerPath);

        if (forgeJson == null) {
            throw new IOException(
                    "无法从 Forge 安装器中提取 version.json，安装器可能已损坏或格式不受支持");
        }

        // 3. 改写关键字段
        //    id 必须与目录名一致，否则启动器找不到版本
        forgeJson.addProperty("id", versionId);
        if (!forgeJson.has("inheritsFrom")) {
            forgeJson.addProperty("inheritsFrom", gameVersion);
        } else {
            forgeJson.addProperty("inheritsFrom", gameVersion);
        }

        // 4. 保存版本 JSON
        Path jsonPath = versionDir.resolve(versionId + ".json");
        Files.writeString(jsonPath, gson.toJson(forgeJson), StandardCharsets.UTF_8);
        logger.info("Forge 版本 JSON 已保存: {}", jsonPath);

        // 5. 下载 Forge 的库文件
        int libCount = downloadForgeLibraries(forgeJson);
        logger.info("已下载 {} 个 Forge 库文件", libCount);

        logger.info("Forge 安装完成: {} (mainClass={})",
                versionId, forgeJson.has("mainClass")
                        ? forgeJson.get("mainClass").getAsString() : L.t("s_d9c32a4c3d"));

        return versionId;
    }

    /** 从 Forge 安装器 JAR 中提取 version.json */
    private JsonObject extractVersionJsonFromInstaller(Path installerJar) {
        // 优先读取 version.json（现代 Forge）
        try {
            String content = readEntryFromZip(installerJar, "version.json");
            if (content != null && !content.isBlank()) {
                JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                if (obj.has("mainClass") || obj.has("libraries")) {
                    logger.info("从 version.json 读取版本定义成功");
                    return obj;
                }
            }
        } catch (Exception e) {
            logger.debug("读取 version.json 失败: {}", e.getMessage());
        }

        // 回退：从 install_profile.json 的 versionInfo 字段读取（老版本 Forge）
        try {
            String content = readEntryFromZip(installerJar, "install_profile.json");
            if (content != null && !content.isBlank()) {
                JsonObject profile = JsonParser.parseString(content).getAsJsonObject();

                if (profile.has("versionInfo")) {
                    logger.info("从 install_profile.json 的 versionInfo 读取版本定义");
                    return profile.getAsJsonObject("versionInfo");
                }

                // 还有一种形式：json 字段直接内嵌完整 JSON 字符串
                if (profile.has("json") && profile.get("json").isJsonPrimitive()) {
                    String embedded = profile.get("json").getAsString();
                    if (embedded.startsWith("{")) {
                        logger.info("从 install_profile.json 的 json 字段读取版本定义");
                        return JsonParser.parseString(embedded).getAsJsonObject();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("读取 install_profile.json 失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从 ZIP/JAR 中读取指定条目的文本内容
     */
    private String readEntryFromZip(Path zipPath, String entryName) throws IOException {
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zipPath.toFile())) {
            java.util.zip.ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (java.io.InputStream is = zipFile.getInputStream(entry)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** 下载 Forge 版本定义中列出的所有库文件 */
    private int downloadForgeLibraries(JsonObject forgeJson) {
        JsonArray libraries = forgeJson.getAsJsonArray("libraries");
        if (libraries == null || libraries.isEmpty()) {
            return 0;
        }

        int downloaded = 0;
        int skipped = 0;

        for (JsonElement element : libraries) {
            JsonObject lib = element.getAsJsonObject();
            String name = lib.has("name") ? lib.get("name").getAsString() : null;
            if (name == null) {
                continue;
            }

            // 检查平台规则
            if (lib.has("rules") && !isLibraryAllowedByRules(lib.getAsJsonArray("rules"))) {
                continue;
            }

            String mavenPath = parseMavenPath(name);
            Path libPath = gameDir.resolve("libraries").resolve(mavenPath);

            if (Files.exists(libPath) && libPath.toFile().length() > 0) {
                skipped++;
                continue;
            }

            // 推断下载地址：优先用库自带的 url，否则按来源猜测
            String url = resolveLibraryUrl(lib, mavenPath);

            logger.debug("下载 Forge 库: {} <- {}", name, url);
            Downloader.DownloadResult result = downloader.download(url, libPath, null);

            if (result.isSuccess()) {
                downloaded++;
            } else {
                // 尝试备用仓库
                String fallback = resolveFallbackUrl(lib, mavenPath);
                if (fallback != null && !fallback.equals(url)) {
                    Downloader.DownloadResult retry = downloader.download(fallback, libPath, null);
                    if (retry.isSuccess()) {
                        downloaded++;
                        continue;
                    }
                }
                logger.warn("Forge 库下载失败: {}", name);
            }
        }

        logger.info("Forge 库文件：新下载 {} 个，已存在 {} 个", downloaded, skipped);
        return downloaded;
    }

    /**
     * 判断库是否适用于当前平台（复用规则求值逻辑）
     */
    private boolean isLibraryAllowedByRules(JsonArray rules) {
        boolean allowed = false;
        String currentOs = getCurrentOsName();

        for (JsonElement ruleElement : rules) {
            JsonObject rule = ruleElement.getAsJsonObject();
            boolean matches = true;

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) {
                    String osName = os.get("name").getAsString();
                    if (!osName.equalsIgnoreCase(currentOs)) {
                        matches = false;
                    }
                }
            }

            if (matches) {
                String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
                allowed = "allow".equals(action);
            }
        }

        return allowed;
    }

    /** 解析库的下载地址 */
    private String resolveLibraryUrl(JsonObject lib, String mavenPath) {
        String group = lib.get("name").getAsString().split(":")[0];

        // Forge 相关：走 BMCLAPI 的 /maven 镜像
        if (group.startsWith("net.minecraftforge") || group.startsWith("cpw.mods")) {
            return apiClient.getLibraryDownloadUrl(mavenPath);
        }

        // 其它库：优先使用库自带的官网 URL
        if (lib.has("downloads")) {
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads.has("artifact")) {
                JsonElement artifactEl = downloads.get("artifact");
                if (artifactEl.isJsonObject()) {
                    JsonObject artifact = artifactEl.getAsJsonObject();
                    if (artifact.has("url")) {
                        String url = artifact.get("url").getAsString();
                        if (!url.isBlank()) {
                            return apiClient.convertToMirror(url);
                        }
                    }
                }
            }
        }

        // 库自带的 url 字段（老格式）
        if (lib.has("url") && !lib.get("url").getAsString().isBlank()) {
            String base = lib.get("url").getAsString();
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            return base + mavenPath;
        }

        // 按坐标推断仓库
        if (group.startsWith("org.spongepowered") || group.startsWith("org.ow2.asm")) {
            return "https://repo.spongepowered.org/repository/maven-public/" + mavenPath;
        }

        // 默认走 BMCLAPI 的 Maven 镜像（它同时镜像了 Maven Central 内容）
        return apiClient.getLibraryDownloadUrl(mavenPath);
    }

    /**
     * 备用下载地址（主地址失败时尝试）
     */
    private String resolveFallbackUrl(JsonObject lib, String mavenPath) {
        String group = lib.get("name").getAsString().split(":")[0];

        if (group.startsWith("net.minecraftforge") || group.startsWith("cpw.mods")) {
            // 回退到 Forge 官方 Maven
            return "https://maven.minecraftforge.net/" + mavenPath;
        }

        if (group.startsWith("org.spongepowered") || group.startsWith("org.ow2.asm")) {
            return "https://repo1.maven.org/maven2/" + mavenPath;
        }

        // 回退到 Maven Central
        return "https://repo1.maven.org/maven2/" + mavenPath;
    }

    /**
     * 获取当前操作系统名称（Minecraft 格式）
     */
    private String getCurrentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    /** 安装 OptiFine */
    public String installOptifine(String gameVersion, String optifineVersion) throws IOException {
        logger.info("开始安装 OptiFine: {}-{}", gameVersion, optifineVersion);

        String versionId = gameVersion + "-OptiFine_" + optifineVersion;

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        // 下载 OptiFine 安装器
        String downloadUrl = apiClient.getBaseUrl() + "/optifine/" +
                             gameVersion + "/" + optifineVersion + "/download";

        Path installerPath = versionDir.resolve("OptiFine_installer.jar");
        logger.info("下载 OptiFine 安装器: {}", downloadUrl);

        Downloader.DownloadResult result = downloader.download(downloadUrl, installerPath, null);
        if (!result.isSuccess()) {
            throw new IOException("OptiFine 安装器下载失败: " + result.getErrorMessage());
        }

        logger.info("OptiFine 下载完成: {}", installerPath);
        logger.warn("注意: OptiFine 需要在游戏中手动安装或运行安装器");

        return versionId;
    }

    /** 合并版本 JSON（处理继承关系） */
    public JsonObject mergeVersionJson(String childVersionId) throws IOException {
        Path childJsonPath = gameDir.resolve("versions").resolve(childVersionId)
                                    .resolve(childVersionId + ".json");

        if (!Files.exists(childJsonPath)) {
            throw new IOException("版本 JSON 不存在: " + childJsonPath);
        }

        String childJsonStr = Files.readString(childJsonPath, StandardCharsets.UTF_8);
        JsonObject childJson = JsonParser.parseString(childJsonStr).getAsJsonObject();

        // 检查是否有继承
        if (!childJson.has("inheritsFrom")) {
            return childJson;
        }

        String parentVersionId = childJson.get("inheritsFrom").getAsString();
        Path parentJsonPath = gameDir.resolve("versions").resolve(parentVersionId)
                                    .resolve(parentVersionId + ".json");

        if (!Files.exists(parentJsonPath)) {
            throw new IOException("父版本 JSON 不存在: " + parentJsonPath);
        }

        String parentJsonStr = Files.readString(parentJsonPath, StandardCharsets.UTF_8);
        JsonObject parentJson = JsonParser.parseString(parentJsonStr).getAsJsonObject();

        // 合并
        return mergeJson(parentJson, childJson);
    }

    /**
     * 合并两个版本 JSON
     * 子版本的属性覆盖父版本
     */
    private JsonObject mergeJson(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();

        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();

            if (key.equals("libraries")) {
                // 合并库文件列表
                JsonArray parentLibs = merged.has("libraries")
                        ? merged.getAsJsonArray("libraries") : new JsonArray();
                JsonArray childLibs = value.getAsJsonArray();

                JsonArray combined = new JsonArray();
                Set<String> seenNames = new HashSet<>();

                // 先添加子版本的库（优先级高）
                for (JsonElement lib : childLibs) {
                    String name = lib.getAsJsonObject().get("name").getAsString();
                    if (seenNames.add(name)) {
                        combined.add(lib);
                    }
                }

                // 再添加父版本的库
                for (JsonElement lib : parentLibs) {
                    String name = lib.getAsJsonObject().get("name").getAsString();
                    if (seenNames.add(name)) {
                        combined.add(lib);
                    }
                }

                merged.add("libraries", combined);

            } else if (key.equals("arguments")) {
                // 合并参数
                JsonObject parentArgs = merged.has("arguments")
                        ? merged.getAsJsonObject("arguments") : new JsonObject();
                JsonObject childArgs = value.getAsJsonObject();

                JsonObject combinedArgs = new JsonObject();

                if (parentArgs.has("game") || childArgs.has("game")) {
                    JsonArray gameArgs = new JsonArray();
                    if (parentArgs.has("game")) {
                        gameArgs.addAll(parentArgs.getAsJsonArray("game"));
                    }
                    if (childArgs.has("game")) {
                        gameArgs.addAll(childArgs.getAsJsonArray("game"));
                    }
                    combinedArgs.add("game", gameArgs);
                }

                if (parentArgs.has("jvm") || childArgs.has("jvm")) {
                    JsonArray jvmArgs = new JsonArray();
                    if (childArgs.has("jvm")) {
                        jvmArgs.addAll(childArgs.getAsJsonArray("jvm"));
                    }
                    if (parentArgs.has("jvm")) {
                        jvmArgs.addAll(parentArgs.getAsJsonArray("jvm"));
                    }
                    combinedArgs.add("jvm", jvmArgs);
                }

                merged.add("arguments", combinedArgs);

            } else if (key.equals("inheritsFrom")) {
                // 跳过
                continue;
            } else {
                // 直接覆盖
                merged.add(key, value);
            }
        }

        merged.remove("inheritsFrom");
        return merged;
    }

    /**
     * 关闭下载器
     */
    public void shutdown() {
        downloader.shutdown();
    }
}