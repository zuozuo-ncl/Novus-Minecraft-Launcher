package com.mclauncher.launcher;

import com.mclauncher.i18n.L;
import com.google.gson.*;
import com.mclauncher.auth.Account;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.game.ModLoaderInstaller;
import com.mclauncher.utils.JavaDetector;
import com.mclauncher.java.JavaRuntimeManager;
import com.mclauncher.utils.PlatformUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class GameLauncher {

    private static final Logger logger = LoggerFactory.getLogger(GameLauncher.class);

    private final ConfigManager config;

    private com.mclauncher.ui.component.ModalHost modalHost;

    private static final String LANG_ZH_CN = "zh_cn";

    private final Path gameDir;
    private final Path launcherDir;

    private Process gameProcess;
    private Consumer<String> logConsumer;
    private Consumer<Integer> exitConsumer;

    public GameLauncher(ConfigManager config, Path launcherDir) {
        this.config = config;
        this.launcherDir = launcherDir;
        this.gameDir = config.getGameDir();
    }

    public void setLogConsumer(Consumer<String> logConsumer) {
        this.logConsumer = logConsumer;
    }

    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    public void setExitConsumer(Consumer<Integer> exitConsumer) {
        this.exitConsumer = exitConsumer;
    }

    public boolean launch(String versionId, Account account, String javaPath) {
        try {
            log(L.t("s_5c89143955") + versionId + "...");

            JsonObject versionJson = loadVersionJson(versionId);
            if (versionJson == null) {
                logError(L.t("s_6033a948b6") + versionId);
                return false;
            }

            int requiredJavaVersion = parseRequiredJavaVersion(versionJson);
            log(L.t("s_393e769d23") + versionId + L.t("s_6090347260") + requiredJavaVersion + "+");

            String javaExe = resolveJavaPath(javaPath, requiredJavaVersion);
            if (javaExe == null) {
                logError(L.t("s_53b7691291") + requiredJavaVersion + L.t("s_65679c0155"));
                return false;
            }
            log(L.t("s_38e3640810") + javaExe);

            List<String> command = buildCommand(javaExe, versionJson, account, versionId);

            

            ensureChineseLanguage();

            log(L.t("s_d36f6195ea") + String.join(" ", maskSensitive(command)));

            return startProcess(command);

        } catch (Exception e) {
            logger.error("启动游戏失败", e);
            logError("启动失败: " + e.getMessage());
            return false;
        }
    }

    private int parseRequiredJavaVersion(JsonObject versionJson) {
        try {

            if (versionJson.has("javaVersion")) {
                JsonObject javaVer = versionJson.getAsJsonObject("javaVersion");
                if (javaVer.has("majorVersion")) {
                    int major = javaVer.get("majorVersion").getAsInt();
                    if (major >= 8) {
                        return major;
                    }
                }
            }

            
            if (versionJson.has("id")) {
                String id = versionJson.get("id").getAsString();

                if (id.matches("1\\.20\\.[5-9].*") || id.matches("1\\.(2[1-9]|[3-9]\\d).*")) {
                    return 21;
                }
                if (id.matches("1\\.1[7-9].*") || id.matches("1\\.20\\.[0-4].*")) {
                    return 17;
                }
                if (id.matches("1\\.1[6-9].*")) {
                    return 17; 
                }
            }
        } catch (Exception e) {
            logger.debug("解析 Java 版本要求失败，使用默认值 17: {}", e.getMessage());
        }
        return 17; 
    }

    private String resolveJavaPath(String configuredPath, int requiredVersion) {

        if (configuredPath != null && !configuredPath.isEmpty()) {
            if (validateJavaPath(configuredPath)) {

                if (checkJavaVersion(configuredPath, requiredVersion)) {
                    return configuredPath;
                }
                logger.warn("配置的 Java 版本不满足要求（需要 {}，当前 {}），将尝试自动寻找或下载",
                        requiredVersion, getJavaMajorVersion(configuredPath));
            }
            logger.warn("配置的 Java 路径无效: {}", configuredPath);
        }

        String configJavaPath = config.getJavaPath();
        if (configJavaPath != null && !configJavaPath.isEmpty()) {
            if (validateJavaPath(configJavaPath) && checkJavaVersion(configJavaPath, requiredVersion)) {
                return configJavaPath;
            }
        }

        

        

        

        

        try {
            JavaRuntimeManager mgr = new JavaRuntimeManager(launcherDir);
            try {
                mgr.detectInstalledJava();
                JavaDetector.JavaInfo best = pickBestJava(mgr.getAllJava(), requiredVersion);
                if (best != null) {
                    String path = best.getJavaExecutable();
                    logger.info("自动选择 Java: {} (版本 {} / {})",
                            path, best.getMajorVersion(), best.getArch());
                    return path;
                }
            } finally {

                
                mgr.shutdown();
            }
        } catch (Exception e) {
            logger.warn("检测启动器自带 Java 失败，退回系统检测: {}", e.getMessage());

            List<JavaDetector.JavaInfo> javaList = JavaDetector.detectJavaVersions();
            JavaDetector.JavaInfo best = pickBestJava(javaList, requiredVersion);
            if (best != null) {
                logger.info("自动选择 Java（系统检测）: {} (版本 {})",
                        best.getJavaExecutable(), best.getMajorVersion());
                return best.getJavaExecutable();
            }
        }

        if (config.isAutoDownloadJava()) {
            logger.info("未找到 Java {}+，尝试自动下载...", requiredVersion);
            try {
                JavaRuntimeManager mgr = new JavaRuntimeManager(launcherDir);
                Path downloadedJava;
                try {
                    downloadedJava = mgr.downloadJava(requiredVersion,
                            new JavaRuntimeManager.DownloadCallback() {
                                @Override
                                public void onStatus(String status) {
                                    log(status);
                                }

                                @Override
                                public void onProgress(double percent, long downloaded, long total) {

                                }
                            });
                } finally {

                    

                    mgr.shutdown();
                }
                if (downloadedJava != null && Files.exists(downloadedJava)) {
                    logger.info("自动下载 Java 成功: {}", downloadedJava);
                    return downloadedJava.toString();
                }
            } catch (Exception e) {
                logger.error("自动下载 Java 失败: {}", e.getMessage());
                logError("自动下载 Java 失败: " + e.getMessage());
            }
        }

        String currentJava = System.getProperty("java.home");
        if (currentJava != null) {
            Path javaExe = PlatformUtils.isWindows()
                    ? Paths.get(currentJava, "bin", "java.exe")
                    : Paths.get(currentJava, "bin", "java");
            if (Files.exists(javaExe) && checkJavaVersion(javaExe.toString(), requiredVersion)) {
                return javaExe.toString();
            }
        }

        
        if (modalHost != null) {
            return promptDownloadJava(requiredVersion);
        }

        return null;
    }

    private String promptDownloadJava(int requiredVersion) {
        if (modalHost == null) {
            return null;
        }

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Path> resultRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final boolean[] started = {false};

        javafx.application.Platform.runLater(() ->
                com.mclauncher.ui.component.MessageDialog.confirmOrCancel(modalHost,
                        L.t("s_9f2cc1aec8") + requiredVersion,
                        L.t("s_d581b77f04") + requiredVersion + "+",
                        L.t("s_bbebff84ed") + requiredVersion + L.t("s_2daee5f73c")
                                + L.t("s_c8ff8b2716"),
                        L.t("s_92f35590d5"), L.t("s_4d0b4688c7"),
                        () -> {
                            started[0] = true;
                            downloadJavaWithProgress(requiredVersion, resultRef, latch);
                        },
                        latch::countDown));

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        Path downloaded = resultRef.get();
        if (downloaded != null && Files.exists(downloaded)) {
            return downloaded.toString();
        }
        if (started[0]) {
            logError("Java " + requiredVersion + L.t("s_711a4caffc"));
        } else {
            log(L.t("s_3322f074c4") + requiredVersion);
        }
        return null;
    }

    private void downloadJavaWithProgress(int requiredVersion,
                                          java.util.concurrent.atomic.AtomicReference<Path> resultRef,
                                          java.util.concurrent.CountDownLatch latch) {
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(12);
        box.setPadding(new javafx.geometry.Insets(20, 20, 18, 20));
        box.setPrefWidth(520);

        javafx.scene.control.Label title = new javafx.scene.control.Label(
                L.t("s_806027bfdd") + requiredVersion + " JRE");
        title.getStyleClass().add("card-title");

        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.control.Label status = new javafx.scene.control.Label(L.t("s_abb2d4c496"));
        status.getStyleClass().add("text-secondary");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.layout.HBox footer = new javafx.scene.layout.HBox(10);
        footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        javafx.scene.control.Button closeBtn = new javafx.scene.control.Button(L.t("s_6c14bd7f6f"));
        closeBtn.getStyleClass().addAll("button", "button-primary");
        closeBtn.setVisible(false);
        closeBtn.setManaged(false);
        closeBtn.setOnAction(e -> modalHost.close());
        footer.getChildren().add(closeBtn);

        box.getChildren().addAll(title, bar, status, footer);

        modalHost.show(L.t("s_732c2382fe") + requiredVersion, box, null, false);

        Thread worker = new Thread(() -> {
            try {
                JavaRuntimeManager mgr = new JavaRuntimeManager(launcherDir);
                Path exe;
                try {
                    exe = mgr.downloadJava(requiredVersion, new JavaRuntimeManager.DownloadCallback() {
                    private long lastUi = 0;

                    @Override
                    public void onStatus(String s) {
                        log(s);
                        javafx.application.Platform.runLater(() -> status.setText(s));
                    }

                    @Override
                    public void onProgress(double percent, long done, long total) {

                        long now = System.currentTimeMillis();
                        if (now - lastUi < 120) {
                            return;
                        }
                        lastUi = now;
                        javafx.application.Platform.runLater(() -> {
                            bar.setProgress(percent / 100.0);
                            status.setText(String.format("%.1f%%  %s / %s", percent,
                                    com.mclauncher.utils.FileUtils.formatFileSizeShort(done),
                                    com.mclauncher.utils.FileUtils.formatFileSizeShort(total)));
                        });
                    }
                });
                } finally {

                    mgr.shutdown();
                }
                resultRef.set(exe);

                javafx.application.Platform.runLater(() -> {
                    bar.setProgress(1.0);
                    status.setText(L.t("s_2f2b6bd0b2") + exe);
                    modalHost.setClosable(true);
                    javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                            javafx.util.Duration.seconds(1.5));
                    delay.setOnFinished(e -> modalHost.close());
                    delay.play();
                });
            } catch (Exception e) {
                logger.error("自动下载 Java 失败", e);
                javafx.application.Platform.runLater(() -> {
                    bar.setProgress(0);
                    status.setText(L.t("s_9767bac218") + e.getMessage());
                    modalHost.setClosable(true);
                    closeBtn.setVisible(true);
                    closeBtn.setManaged(true);
                });
            } finally {
                latch.countDown();
            }
        }, "java-downloader");
        worker.setDaemon(true);
        worker.start();
    }

    private void startLaunchVerification(Process process) {
        if (modalHost == null) {
            return;
        }
        Thread t = new Thread(() -> verifyGameWindow(process), "game-window-verifier");
        t.setDaemon(true);
        t.start();
    }

    private void verifyGameWindow(Process process) {
        long pid = process.pid();

        int waitSeconds = 20;
        while (process.isAlive()) {
            for (int i = 0; i < waitSeconds && process.isAlive(); i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!process.isAlive()) {
                log(L.t("s_f3367a5c7e"));
                return;
            }
            if (hasMainWindow(pid)) {
                log(L.t("s_075f71154c"));
                return;
            }

            int choice = askLaunchStatus();
            if (choice == 0) {
                log(L.t("s_55d6d62ab7"));
                return;
            }
            if (choice == 1) {
                log(L.t("s_7ef37770c6"));
                javafx.application.Platform.runLater(() ->
                        com.mclauncher.ui.component.MessageDialog.error(modalHost,
                                L.t("s_b39be155ab"),
                                L.t("s_28573d908e")
                                        + L.t("s_b4298c9de7")
                                        + L.t("s_cc35d7a4f3")));
                return;
            }

            log(L.t("s_7a8160fcb6"));
            waitSeconds = 15;
        }
    }

    private boolean hasMainWindow(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue).MainWindowHandle");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line.trim());
                }
            }
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            String digits = sb.toString().replaceAll("\\D", "");
            return !digits.isEmpty() && Long.parseLong(digits) > 0;
        } catch (Exception e) {
            logger.debug("窗口检测失败: {}", e.getMessage());
            return false;
        }
    }

    private int askLaunchStatus() {
        final int[] choice = {2};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        javafx.application.Platform.runLater(() -> {
            javafx.scene.layout.VBox body = new javafx.scene.layout.VBox(8);
            body.setPadding(new javafx.geometry.Insets(16, 18, 14, 18));
            body.setMinWidth(340);
            body.setMaxWidth(480);

            javafx.scene.control.Label header = new javafx.scene.control.Label(L.t("s_d25d690b8d"));
            header.getStyleClass().add("modal-header-text");
            header.setWrapText(true);

            javafx.scene.control.Label msg = new javafx.scene.control.Label(
                    L.t("s_10878358fd"));
            msg.getStyleClass().add("modal-message-text");
            msg.setWrapText(true);

            javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(10);
            buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            buttons.setPadding(new javafx.geometry.Insets(4, 0, 0, 0));

            javafx.scene.control.Button okBtn = new javafx.scene.control.Button(L.t("s_4bfd9bda4f"));
            okBtn.getStyleClass().addAll("button", "button-primary");
            okBtn.setOnAction(e -> {
                choice[0] = 0;
                modalHost.close();
            });

            javafx.scene.control.Button failBtn = new javafx.scene.control.Button(L.t("s_82a8e717c6"));
            failBtn.getStyleClass().add("button");
            failBtn.setOnAction(e -> {
                choice[0] = 1;
                modalHost.close();
            });

            javafx.scene.control.Button waitBtn = new javafx.scene.control.Button(L.t("s_b1b4672773"));
            waitBtn.getStyleClass().add("button");
            waitBtn.setOnAction(e -> {
                choice[0] = 2;
                modalHost.close();
            });

            buttons.getChildren().addAll(okBtn, failBtn, waitBtn);
            body.getChildren().addAll(header, msg, buttons);

            modalHost.show(L.t("s_949d0dbcf5"), body, latch::countDown);
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 2;
        }
        return choice[0];
    }

    private JavaDetector.JavaInfo pickBestJava(
            List<JavaDetector.JavaInfo> list, int requiredVersion) {
        if (list == null || list.isEmpty()) {
            return null;
        }

        JavaDetector.JavaInfo best = null;
        for (JavaDetector.JavaInfo info : list) {
            if (info.getMajorVersion() < requiredVersion) {
                continue;
            }
            if (best == null) {
                best = info;
                continue;
            }

            boolean infoIs64 = is64Bit(info.getArch());
            boolean bestIs64 = is64Bit(best.getArch());

            if (infoIs64 && !bestIs64) {
                best = info;      
            } else if (infoIs64 == bestIs64
                    && info.getMajorVersion() > best.getMajorVersion()) {
                best = info;      
            }
        }
        return best;
    }

    private boolean is64Bit(String arch) {
        if (arch == null) {
            return true;    
        }
        String a = arch.toLowerCase();
        return a.contains("64") || a.contains("x64") || a.contains("amd64");
    }

    private boolean checkJavaVersion(String javaPath, int requiredVersion) {
        int major = getJavaMajorVersion(javaPath);
        return major >= requiredVersion;
    }

    private int getJavaMajorVersion(String javaPath) {
        try {
            Path path = Paths.get(javaPath);
            String javaExe;
            if (Files.isDirectory(path)) {
                javaExe = PlatformUtils.isWindows()
                        ? path.resolve("bin").resolve("java.exe").toString()
                        : path.resolve("bin").resolve("java").toString();
            } else {
                javaExe = javaPath;
            }

            

            String output = JavaDetector.runVersionCommand(javaExe, 10);
            if (output == null) {
                return 0;   
            }
            return parseMajorVersion(output);
        } catch (Exception e) {
            logger.debug("获取 Java 版本失败: {}", e.getMessage());
            return 0;
        }
    }

    private int parseMajorVersion(String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            return 0;
        }
        try {

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"?(\\d+)(?:\\.(\\d+))?");
            java.util.regex.Matcher matcher = pattern.matcher(versionString);
            if (matcher.find()) {
                int first = Integer.parseInt(matcher.group(1));
                if (first == 1 && matcher.group(2) != null) {
                    return Integer.parseInt(matcher.group(2)); 
                }
                return first; 
            }
        } catch (Exception e) {
            logger.debug("解析版本号失败: {}", e.getMessage());
        }
        return 0;
    }

    private boolean validateJavaPath(String javaPath) {
        try {
            Path path = Paths.get(javaPath);

            if (Files.isDirectory(path)) {
                Path javaExe = PlatformUtils.isWindows()
                        ? path.resolve("bin").resolve("java.exe")
                        : path.resolve("bin").resolve("java");
                return Files.exists(javaExe);
            }

            return Files.exists(path);
        } catch (Exception e) {
            return false;
        }
    }

    private JsonObject loadVersionJson(String versionId) throws IOException {
        Path versionJsonPath = gameDir.resolve("versions").resolve(versionId)
                                     .resolve(versionId + ".json");

        if (!Files.exists(versionJsonPath)) {
            logger.error("版本 JSON 不存在: {}", versionJsonPath);
            return null;
        }

        String jsonStr = Files.readString(versionJsonPath, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();

        if (json.has("inheritsFrom")) {
            String parentId = json.get("inheritsFrom").getAsString();
            logger.info("版本 {} 继承自 {}", versionId, parentId);

            Path parentJsonPath = gameDir.resolve("versions").resolve(parentId)
                                        .resolve(parentId + ".json");

            if (!Files.exists(parentJsonPath)) {
                logger.error("父版本 JSON 不存在: {}", parentJsonPath);
                return json;
            }

            String parentJsonStr = Files.readString(parentJsonPath, StandardCharsets.UTF_8);
            JsonObject parentJson = JsonParser.parseString(parentJsonStr).getAsJsonObject();

            ModLoaderInstaller installer = new ModLoaderInstaller(
                    new com.mclauncher.network.BMCLApiClient(), gameDir);
            try {
                json = installer.mergeVersionJson(versionId);
            } finally {
                installer.shutdown();
            }
        }

        return json;
    }

    private List<String> buildCommand(String javaExe, JsonObject versionJson,
                                      Account account, String versionId) throws IOException {

        List<String> command = new ArrayList<>();

        command.add(javaExe);

        String memoryMin = config.getMemoryMin();
        String memoryMax = config.getMemoryMax();
        command.add("-Xms" + memoryMin);
        command.add("-Xmx" + memoryMax);

        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");

        

        

        Map<String, String> variables = buildVariables(versionJson, account, versionId);

        Path nativesDir = gameDir.resolve("versions").resolve(versionId).resolve("natives");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        command.add("-Dminecraft.launcher.brand=Novus Minecraft Launcher");
        command.add("-Dminecraft.launcher.version=" + config.getLauncherVersion());

        List<Path> classpathEntries = buildClasspath(versionJson, versionId);
        if (classpathEntries.isEmpty()) {
            throw new IOException("无法构建 Classpath，缺少必要的库文件");
        }

        String classpathString = buildClasspathString(classpathEntries);

        
        variables.put("classpath", classpathString);

        
        List<String> jvmArgs = buildJvmArguments(versionJson, variables);
        if (!jvmArgs.isEmpty()) {
            logger.info("注入 {} 个自定义 JVM 参数", jvmArgs.size());
            command.addAll(jvmArgs);
        }

        command.add("-cp");
        command.add(classpathString);

        String mainClass = versionJson.has("mainClass")
                ? versionJson.get("mainClass").getAsString()
                : "net.minecraft.client.main.Main";
        command.add(mainClass);

        List<String> gameArgs = buildGameArguments(versionJson, account, versionId, variables);

        

        

        
        if (!containsArg(gameArgs, "--lang")) {
            gameArgs.add("--lang");
            gameArgs.add(LANG_ZH_CN);
        }

        command.addAll(gameArgs);

        return command;
    }

    private boolean containsArg(List<String> args, String option) {
        for (String a : args) {
            if (option.equalsIgnoreCase(a)) {
                return true;
            }
        }
        return false;
    }

    private String replaceUnresolvedPlaceholders(String input, String fallback) {
        if (input == null || !input.contains("${")) {
            return input;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            int start = input.indexOf("${", i);
            if (start < 0) {
                out.append(input, i, input.length());
                break;
            }
            int end = input.indexOf('}', start);
            if (end < 0) {
                out.append(input, i, input.length());
                break;
            }
            out.append(input, i, start);
            out.append(fallback);
            i = end + 1;
        }
        return out.toString();
    }

    private List<String> buildJvmArguments(JsonObject versionJson, Map<String, String> variables) {
        List<String> result = new ArrayList<>();

        if (!versionJson.has("arguments")) {
            return result;
        }

        JsonElement argumentsEl = versionJson.get("arguments");
        if (!argumentsEl.isJsonObject()) {
            return result;
        }

        JsonObject arguments = argumentsEl.getAsJsonObject();
        if (!arguments.has("jvm")) {
            return result;
        }

        JsonElement jvmEl = arguments.get("jvm");
        if (!jvmEl.isJsonArray()) {
            return result;
        }

        List<String> raw = new ArrayList<>();
        processArguments(jvmEl.getAsJsonArray(), variables, raw);

        

        

        

        

        

        

        
        java.util.Set<String> optionsWithValue = java.util.Set.of(
                "-cp", "-classpath", "-p", "--module-path",
                "--add-opens", "--add-exports", "--add-reads",
                "--patch-module", "--upgrade-module-path");

        for (int i = 0; i < raw.size(); i++) {
            String arg = raw.get(i);
            if (arg == null || arg.isBlank()) {
                continue;
            }

            String lower = arg.toLowerCase();

            if (optionsWithValue.contains(lower)) {
                i++;            
                continue;
            }

            if (lower.startsWith("-cp=") || lower.startsWith("-classpath=")) {
                continue;
            }

            if (lower.startsWith("-xmx") || lower.startsWith("-xms")) {
                continue;
            }
            if (lower.startsWith("-djava.library.path")) {
                continue;
            }
            if (lower.startsWith("-dfile.encoding") || lower.startsWith("-dstdout.encoding")
                    || lower.startsWith("-dstderr.encoding")) {
                continue;
            }
            if (lower.startsWith("-dminecraft.launcher.brand")
                    || lower.startsWith("-dminecraft.launcher.version")) {
                continue;
            }

            result.add(arg);
        }

        

        for (int i = 0; i < result.size(); i++) {
            String v = result.get(i);
            if (v.contains("${")) {
                for (Map.Entry<String, String> e : variables.entrySet()) {
                    v = v.replace("${" + e.getKey() + "}", e.getValue());
                }

                String natives = variables.getOrDefault("natives_directory", "");
                if (v.contains("${") && !natives.isEmpty()) {

                    v = replaceUnresolvedPlaceholders(v, natives);
                }
                result.set(i, v);
            }
        }

        return result;
    }

    private List<Path> buildClasspath(JsonObject versionJson, String versionId) throws IOException {
        List<Path> classpath = new ArrayList<>();

        Path versionJar = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        if (Files.exists(versionJar)) {
            classpath.add(versionJar);
        } else {

            if (versionJson.has("jar")) {
                String jarVersion = versionJson.get("jar").getAsString();
                Path parentJar = gameDir.resolve("versions").resolve(jarVersion)
                                       .resolve(jarVersion + ".jar");
                if (Files.exists(parentJar)) {
                    classpath.add(parentJar);
                }
            } else if (versionJson.has("inheritsFrom")) {
                String parentId = versionJson.get("inheritsFrom").getAsString();
                Path parentJar = gameDir.resolve("versions").resolve(parentId)
                                       .resolve(parentId + ".jar");
                if (Files.exists(parentJar)) {
                    classpath.add(parentJar);
                }
            }
        }

        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement element : libraries) {
                JsonObject lib = element.getAsJsonObject();

                if (!isLibraryAllowed(lib)) {
                    continue;
                }

                String name = lib.get("name").getAsString();
                String mavenPath = parseMavenPath(name);
                Path libPath = gameDir.resolve("libraries").resolve(mavenPath);

                if (Files.exists(libPath)) {
                    classpath.add(libPath);
                } else {
                    logger.debug("库文件不存在，跳过: {}", libPath);
                }
            }
        }

        return classpath;
    }

    private boolean isLibraryAllowed(JsonObject library) {
        if (!library.has("rules")) {
            return true;
        }

        boolean allowed = false;
        String currentOs = getCurrentOsName();

        for (JsonElement ruleElement : library.getAsJsonArray("rules")) {
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

    private String buildClasspathString(List<Path> entries) {
        String separator = PlatformUtils.isWindows() ? ";" : ":";
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(entries.get(i).toAbsolutePath().toString());
        }

        return sb.toString();
    }

    private List<String> buildGameArguments(JsonObject versionJson, Account account, String versionId,
                                            Map<String, String> variables)
            throws IOException {

        List<String> args = new ArrayList<>();

        if (versionJson.has("arguments")) {
            JsonElement argumentsEl = versionJson.get("arguments");
            if (argumentsEl.isJsonObject()) {
                JsonObject arguments = argumentsEl.getAsJsonObject();

                if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                    processArguments(arguments.getAsJsonArray("game"), variables, args);
                    return args;
                }
            }
        }

        if (versionJson.has("minecraftArguments")) {
            String mcArgs = versionJson.get("minecraftArguments").getAsString();
            for (String arg : mcArgs.split(" ")) {
                args.add(replaceVariables(arg, variables));
            }
            return args;
        }

        logger.warn("版本 JSON 中没有游戏参数，使用默认参数");
        args.add("--username"); args.add(account.getUsername());
        args.add("--version"); args.add(versionId);
        args.add("--gameDir"); args.add(gameDir.toAbsolutePath().toString());
        args.add("--assetsDir"); args.add(gameDir.resolve("assets").toAbsolutePath().toString());
        args.add("--assetIndex"); args.add(getAssetIndexId(versionJson));
        args.add("--uuid"); args.add(account.getFormattedUuid());
        args.add("--accessToken"); args.add(account.getAccessToken());
        args.add("--userType"); args.add(account.getType() == Account.Type.MICROSOFT ? "msa" : "legacy");
        args.add("--versionType"); args.add("release");

        return args;
    }

    private void processArguments(JsonArray argsArray, Map<String, String> variables,
                                 List<String> output) {
        for (JsonElement element : argsArray) {
            if (element.isJsonPrimitive()) {

                output.add(replaceVariables(element.getAsString(), variables));
            } else if (element.isJsonObject()) {

                JsonObject argObj = element.getAsJsonObject();

                if (argObj.has("rules") && !evaluateRules(argObj.getAsJsonArray("rules"))) {
                    continue;
                }

                if (argObj.has("value")) {
                    JsonElement value = argObj.get("value");

                    if (value.isJsonPrimitive()) {
                        output.add(replaceVariables(value.getAsString(), variables));
                    } else if (value.isJsonArray()) {
                        for (JsonElement v : value.getAsJsonArray()) {
                            output.add(replaceVariables(v.getAsString(), variables));
                        }
                    }
                }
            }
        }
    }

    private boolean evaluateRules(JsonArray rules) {
        boolean allowed = false;
        String currentOs = getCurrentOsName();
        String currentArch = getCurrentArch();
        Map<String, Boolean> features = getDefaultFeatures();

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

                if (matches && os.has("arch")) {
                    String arch = os.get("arch").getAsString();

                    if (arch.equals("x86") && currentArch.equals("x86_64")) {
                        matches = false;
                    }
                }
            }

            if (matches && rule.has("features")) {
                JsonObject featuresObj = rule.getAsJsonObject("features");
                for (Map.Entry<String, JsonElement> entry : featuresObj.entrySet()) {
                    String featureName = entry.getKey();
                    boolean required = entry.getValue().getAsBoolean();
                    boolean actual = features.getOrDefault(featureName, false);

                    if (required != actual) {
                        matches = false;
                        break;
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

    private Map<String, Boolean> getDefaultFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", false);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);
        return features;
    }

    private Map<String, String> buildVariables(JsonObject versionJson, Account account,
                                               String versionId) {
        Map<String, String> vars = new HashMap<>();

        vars.put("auth_player_name", account.getUsername());
        vars.put("auth_uuid", account.getFormattedUuid());
        vars.put("auth_access_token", account.getAccessToken());
        vars.put("auth_session", account.getAccessToken());
        vars.put("auth_xuid", account.getXuid() != null ? account.getXuid() : "");
        vars.put("user_type", account.getType() == Account.Type.MICROSOFT ? "msa" : "legacy");
        vars.put("clientid", account.getClientId() != null ? account.getClientId() : "");

        vars.put("game_directory", gameDir.toAbsolutePath().toString());
        vars.put("assets_root", gameDir.resolve("assets").toAbsolutePath().toString());
        vars.put("assets_index_name", getAssetIndexId(versionJson));
        vars.put("game_assets", gameDir.resolve("assets").resolve("virtual").resolve("legacy")
                                     .toAbsolutePath().toString());

        vars.put("version_name", versionId);
        vars.put("version_type", versionJson.has("type")
                ? versionJson.get("type").getAsString() : "release");

        vars.put("launcher_name", "NCL");
        vars.put("launcher_version", config.getLauncherVersion());

        vars.put("library_directory", gameDir.resolve("libraries").toAbsolutePath().toString());
        vars.put("classpath_separator", PlatformUtils.isWindows() ? ";" : ":");

        

        vars.put("natives_directory",
                gameDir.resolve("versions").resolve(versionId).resolve("natives")
                        .toAbsolutePath().toString());

        return vars;
    }

    private String replaceVariables(String input, Map<String, String> variables) {
        if (input == null) {
            return "";
        }

        String result = input;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, entry.getValue());
            }
        }

        return result;
    }

    private String getAssetIndexId(JsonObject versionJson) {
        if (versionJson.has("assetIndex")) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            if (assetIndex.has("id")) {
                return assetIndex.get("id").getAsString();
            }
        }
        if (versionJson.has("assets")) {
            return versionJson.get("assets").getAsString();
        }
        return "legacy";
    }

    private String getCurrentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    private String getCurrentArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("64")) {
            return "x86_64";
        }
        return "x86";
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

    private void ensureChineseLanguage() {
        Path optionsFile = gameDir.resolve("options.txt");
        if (!Files.exists(optionsFile)) {
            return;     
        }

        try {
            List<String> lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            boolean found = false;
            boolean changed = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("lang:")) {
                    found = true;

                    

                    String want = "lang:" + LANG_ZH_CN;
                    if (!want.equalsIgnoreCase(line.trim())) {
                        lines.set(i, want);
                        changed = true;
                    }
                    break;
                }
            }

            if (!found) {
                lines.add("lang:" + LANG_ZH_CN);
                changed = true;
            }

            if (changed) {
                Files.write(optionsFile, lines, StandardCharsets.UTF_8);
                logger.info("已将游戏语言设置为简体中文");
            }

        } catch (Exception e) {

            logger.warn("设置游戏语言失败（不影响启动）: {}", e.getMessage());
        }
    }

    private boolean startProcess(List<String> command) throws IOException {

        ProcessBuilder pb = new ProcessBuilder(command);

        pb.directory(gameDir.toFile());

        pb.redirectErrorStream(true);

        

        Path nativesDir = gameDir.resolve("natives");
        Files.createDirectories(nativesDir);

        logger.info("启动游戏进程...");
        log("正在启动游戏进程...");

        gameProcess = pb.start();

        final Process process = gameProcess;
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log(L.t("s_a1992b5637") + line);
                }
            } catch (IOException e) {
                logger.debug("读取游戏输出结束: {}", e.getMessage());
            }
        }, "game-output-reader");
        outputThread.setDaemon(true);
        outputThread.start();

        Thread exitThread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                logger.info("游戏进程退出，退出码: {}", exitCode);

                if (exitConsumer != null) {
                    exitConsumer.accept(exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "game-exit-watcher");
        exitThread.setDaemon(true);
        exitThread.start();

        logger.info("游戏已启动，PID: {}", gameProcess.pid());
        log("游戏已启动 (PID: " + gameProcess.pid() + ")");

        startLaunchVerification(process);

        return true;
    }

    public void killProcess() {
        if (gameProcess != null && gameProcess.isAlive()) {
            logger.info("终止游戏进程");
            gameProcess.destroy();

            try {
                if (!gameProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    gameProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                gameProcess.destroyForcibly();
            }
        }
    }

    public boolean isRunning() {
        return gameProcess != null && gameProcess.isAlive();
    }

    public long getPid() {
        return gameProcess != null ? gameProcess.pid() : -1;
    }

    private List<String> maskSensitive(List<String> command) {
        List<String> masked = new ArrayList<>();
        boolean maskNext = false;

        for (String arg : command) {
            if (maskNext) {
                masked.add("***");
                maskNext = false;
                continue;
            }

            if (arg.equals("--accessToken") || arg.equals("--auth_session")) {
                masked.add(arg);
                maskNext = true;
                continue;
            }

            masked.add(arg);
        }

        return masked;
    }

    private void log(String message) {
        logger.info(message);
        if (logConsumer != null) {
            logConsumer.accept(message);
        }
    }

    private void logError(String message) {
        logger.error(message);
        if (logConsumer != null) {
            logConsumer.accept("[错误] " + message);
        }
    }
}
