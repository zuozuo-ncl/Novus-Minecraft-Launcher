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

    public String installFabric(String gameVersion, String loaderVersion) throws IOException {
        logger.info("开始安装 Fabric: {}-{}", gameVersion, loaderVersion);

        String fabricJson = apiClient.getFabricLoaderJson(gameVersion, loaderVersion);

        JsonObject fabricObj = JsonParser.parseString(fabricJson).getAsJsonObject();

        fabricObj.addProperty("inheritsFrom", gameVersion);

        String versionId = gameVersion + "-fabric" + loaderVersion;

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        Path jsonPath = versionDir.resolve(versionId + ".json");
        String prettyJson = gson.toJson(fabricObj);
        Files.writeString(jsonPath, prettyJson, StandardCharsets.UTF_8);

        logger.info("Fabric 安装完成: {}", versionId);
        logger.info("版本 JSON: {}", jsonPath);

        downloadFabricLibraries(fabricObj);

        return versionId;
    }

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

            if (Files.exists(libPath) && Files.size(libPath) > 0) {
                if (expectedSha1 == null || expectedSha1.isBlank()
                        || expectedSha1.equalsIgnoreCase(FileUtils.sha1(libPath))) {
                    ok++;
                    continue;
                }
                logger.warn("Fabric 库校验失败，重新下载: {}", name);
                Files.deleteIfExists(libPath);
            }

            String mirrorUrl = apiClient.getFabricMavenBase() + "/" + mavenPath;
            logger.debug("下载 Fabric 库: {} <- {}", name, mirrorUrl);
            Downloader.DownloadResult result = downloader.download(mirrorUrl, libPath, null);

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

    public String installForge(String gameVersion, String forgeVersion) throws IOException {
        logger.info("开始安装 Forge: {}-{}", gameVersion, forgeVersion);

        String fullVersion = gameVersion + "-" + forgeVersion;

        String versionId = fullVersion + "-forge";

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        

        
        String mavenPath = "net/minecraftforge/forge/" + fullVersion
                + "/forge-" + fullVersion + "-installer.jar";

        Path installerPath = versionDir.resolve("forge-installer.jar");

        if (!Files.exists(installerPath) || Files.size(installerPath) < 100_000) {

            String mirrorUrl = apiClient.getLibraryDownloadUrl(mavenPath);
            logger.info("下载 Forge 安装器: {}", mirrorUrl);

            Downloader.DownloadResult result = downloader.download(mirrorUrl, installerPath, null);

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

        logger.info("从安装器提取版本定义...");
        JsonObject forgeJson = extractVersionJsonFromInstaller(installerPath);

        if (forgeJson == null) {
            throw new IOException(
                    "无法从 Forge 安装器中提取 version.json，安装器可能已损坏或格式不受支持");
        }

        
        forgeJson.addProperty("id", versionId);
        if (!forgeJson.has("inheritsFrom")) {
            forgeJson.addProperty("inheritsFrom", gameVersion);
        } else {
            forgeJson.addProperty("inheritsFrom", gameVersion);
        }

        Path jsonPath = versionDir.resolve(versionId + ".json");
        Files.writeString(jsonPath, gson.toJson(forgeJson), StandardCharsets.UTF_8);
        logger.info("Forge 版本 JSON 已保存: {}", jsonPath);

        int libCount = downloadForgeLibraries(forgeJson);
        logger.info("已下载 {} 个 Forge 库文件", libCount);

        logger.info("Forge 安装完成: {} (mainClass={})",
                versionId, forgeJson.has("mainClass")
                        ? forgeJson.get("mainClass").getAsString() : L.t("s_d9c32a4c3d"));

        return versionId;
    }

    private JsonObject extractVersionJsonFromInstaller(Path installerJar) {

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

        try {
            String content = readEntryFromZip(installerJar, "install_profile.json");
            if (content != null && !content.isBlank()) {
                JsonObject profile = JsonParser.parseString(content).getAsJsonObject();

                if (profile.has("versionInfo")) {
                    logger.info("从 install_profile.json 的 versionInfo 读取版本定义");
                    return profile.getAsJsonObject("versionInfo");
                }

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

            if (lib.has("rules") && !isLibraryAllowedByRules(lib.getAsJsonArray("rules"))) {
                continue;
            }

            String mavenPath = parseMavenPath(name);
            Path libPath = gameDir.resolve("libraries").resolve(mavenPath);

            if (Files.exists(libPath) && libPath.toFile().length() > 0) {
                skipped++;
                continue;
            }

            String url = resolveLibraryUrl(lib, mavenPath);

            logger.debug("下载 Forge 库: {} <- {}", name, url);
            Downloader.DownloadResult result = downloader.download(url, libPath, null);

            if (result.isSuccess()) {
                downloaded++;
            } else {

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

    private String resolveLibraryUrl(JsonObject lib, String mavenPath) {
        String group = lib.get("name").getAsString().split(":")[0];

        if (group.startsWith("net.minecraftforge") || group.startsWith("cpw.mods")) {
            return apiClient.getLibraryDownloadUrl(mavenPath);
        }

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

        if (lib.has("url") && !lib.get("url").getAsString().isBlank()) {
            String base = lib.get("url").getAsString();
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            return base + mavenPath;
        }

        if (group.startsWith("org.spongepowered") || group.startsWith("org.ow2.asm")) {
            return "https://repo.spongepowered.org/repository/maven-public/" + mavenPath;
        }

        return apiClient.getLibraryDownloadUrl(mavenPath);
    }

    private String resolveFallbackUrl(JsonObject lib, String mavenPath) {
        String group = lib.get("name").getAsString().split(":")[0];

        if (group.startsWith("net.minecraftforge") || group.startsWith("cpw.mods")) {

            return "https://maven.minecraftforge.net/" + mavenPath;
        }

        if (group.startsWith("org.spongepowered") || group.startsWith("org.ow2.asm")) {
            return "https://repo1.maven.org/maven2/" + mavenPath;
        }

        return "https://repo1.maven.org/maven2/" + mavenPath;
    }

    private String getCurrentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    public String installOptifine(String gameVersion, String optifineVersion) throws IOException {
        logger.info("开始安装 OptiFine: {}-{}", gameVersion, optifineVersion);

        String versionId = gameVersion + "-OptiFine_" + optifineVersion;

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

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

    public JsonObject mergeVersionJson(String childVersionId) throws IOException {
        Path childJsonPath = gameDir.resolve("versions").resolve(childVersionId)
                                    .resolve(childVersionId + ".json");

        if (!Files.exists(childJsonPath)) {
            throw new IOException("版本 JSON 不存在: " + childJsonPath);
        }

        String childJsonStr = Files.readString(childJsonPath, StandardCharsets.UTF_8);
        JsonObject childJson = JsonParser.parseString(childJsonStr).getAsJsonObject();

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

        return mergeJson(parentJson, childJson);
    }

    private JsonObject mergeJson(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();

        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();

            if (key.equals("libraries")) {

                JsonArray parentLibs = merged.has("libraries")
                        ? merged.getAsJsonArray("libraries") : new JsonArray();
                JsonArray childLibs = value.getAsJsonArray();

                JsonArray combined = new JsonArray();
                Set<String> seenNames = new HashSet<>();

                for (JsonElement lib : childLibs) {
                    String name = lib.getAsJsonObject().get("name").getAsString();
                    if (seenNames.add(name)) {
                        combined.add(lib);
                    }
                }

                for (JsonElement lib : parentLibs) {
                    String name = lib.getAsJsonObject().get("name").getAsString();
                    if (seenNames.add(name)) {
                        combined.add(lib);
                    }
                }

                merged.add("libraries", combined);

            } else if (key.equals("arguments")) {

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

                continue;
            } else {

                merged.add(key, value);
            }
        }

        merged.remove("inheritsFrom");
        return merged;
    }

    public void shutdown() {
        downloader.shutdown();
    }
}
