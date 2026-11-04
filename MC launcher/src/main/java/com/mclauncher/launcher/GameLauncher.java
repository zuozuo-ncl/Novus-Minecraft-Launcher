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
import java.util.function.BooleanSupplier;

/**
 * 游戏启动器核心
 * 负责拼装启动参数并启动游戏进程
 */
public class GameLauncher {

    private static final Logger logger = LoggerFactory.getLogger(GameLauncher.class);

    private final ConfigManager config;

    /** 内嵌弹窗宿主（由 HomePage 注入，用于 Java 下载进度弹窗） */
    private com.mclauncher.ui.component.ModalHost modalHost;
    /**
     * 简体中文的语言代码
     *
     * 必须用**小写** zh_cn —— 这是 Minecraft 的资源文件命名
     * （assets 里是 minecraft/lang/zh_cn.json），游戏按此 key 查找语言。
     * 写成 zh_CN 会查不到文件而回退英文，这是之前「启动游戏是英文」的原因。
     */
    private static final String LANG_ZH_CN = "zh_cn";

    /**
     * 当前游戏目录。
     *
     * 不能像早先那样在构造时固定成 config.getGameDir()：那时启动器刚起来，
     * 用户还没在「目录管理」里选目录，拿到的是默认的数据目录下 minecraft 子目录。
     * 之后用户添加/切换目录，启动游戏仍然去旧路径找版本 JSON，
     * 表现为「版本 JSON 不存在 …\NCL_Launcher\minecraft\versions…」。
     * 现在每次启动游戏都按当前配置重新解析。
     */
    private volatile Path gameDir;
    private final Path launcherDir;

    private Process gameProcess;
    private Consumer<String> logConsumer;
    private Consumer<Integer> exitConsumer;

    /**
     * MC 本地服务器「有玩家连上来」的通知（VPN 联机用）
     *
     * 参数：(玩家编号, 该连接在完成本地服务器握手前就已经收到的原始字节)。
     * VPN 转发必须先写回这些字节（MC 握手 + login start 可能早于转发建立），
     * 否则 MC 客户端会一直卡在「正在登录」。
     */
    private PlayerJoinHandler playerJoinConsumer;

    /** 玩家接入回调：玩家编号、已读到的原始字节、本机连接 */
    @FunctionalInterface
    public interface PlayerJoinHandler {
        void onPlayerJoin(int id, byte[] head, java.net.Socket sock);
    }

    /**
     * 是否允许把本机 MC 局域网的连接交给 VPN 转发。
     *
     * 只在本机确实开了 VPN 房主时返回 true —— 否则任何人扫一下这台机器
     * 的 MC 端口都能进来；也避免在没开 VPN 时浪费一个转发线程。
     */
    private BooleanSupplier vpnForwardingGate;

    /** VPN 转发线程池（懒创建，随游戏进程退出时关闭） */
    private java.util.concurrent.ExecutorService vpnForwardPool;
    private volatile java.net.ServerSocket vpnForwarder;
    private volatile int vpnPeerSeq = 0;

    public GameLauncher(ConfigManager config, Path launcherDir) {
        this.config = config;
        this.launcherDir = launcherDir;
    }

    /**
     * 设置日志输出回调
     */
    public void setLogConsumer(Consumer<String> logConsumer) {
        this.logConsumer = logConsumer;
    }

    /**
     * 设置进程退出回调
     */
    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    public void setExitConsumer(Consumer<Integer> exitConsumer) {
        this.exitConsumer = exitConsumer;
    }

    /**
     * 设置「玩家接入」回调（VPN 联机用）
     */
    public void setPlayerJoinConsumer(PlayerJoinHandler c) {
        this.playerJoinConsumer = c;
    }

    /**
     * 设置 VPN 转发开关：只有本机开了 VPN 房主时才允许接受转发连接
     */
    public void setVpnForwardingGate(BooleanSupplier gate) {
        this.vpnForwardingGate = gate;
    }

    /**
     * 启动游戏
     *
     * @param versionId 版本 ID
     * @param account   账号
     * @param javaPath  Java 路径（null 表示自动检测）
     * @return 是否启动成功
     */
    public boolean launch(String versionId, Account account, String javaPath) {
        return launch(versionId, account, javaPath, null);
    }

    /**
     * 启动游戏（可注入账号刷新回调）
     *
     * @param refresher 可选：账号的 MC 令牌过期时用它换取新账号；为 null 时跳过刷新
     */
    public boolean launch(String versionId, Account account, String javaPath,
                          java.util.function.UnaryOperator<Account> refresher) {
        try {
            // 每次启动都按当前配置解析游戏目录：用户可能刚在界面里切换过目录
            this.gameDir = config.getGameDir();
            logger.info("游戏目录: {}", gameDir);
            if (account != null && refresher != null
                    && account.getType() == Account.Type.MICROSOFT && account.isTokenExpired()) {
                try {
                    log("登录令牌已过期，正在刷新…");
                    Account fresh = refresher.apply(account);
                    if (fresh != null) {
                        account = fresh;
                    }
                } catch (Exception e) {
                    logger.warn("刷新登录令牌失败，仍按原账号启动: {}", e.getMessage());
                    log("刷新登录令牌失败：" + e.getMessage());
                }
            }
            log(L.t("s_5c89143955") + versionId + "...");

            // 1. 先加载版本 JSON，获取 Java 版本要求
            JsonObject versionJson = loadVersionJson(versionId);
            if (versionJson == null) {
                logError(L.t("s_6033a948b6") + versionId);
                return false;
            }

            // 从版本 JSON 解析 Java 版本要求（默认 17）
            int requiredJavaVersion = parseRequiredJavaVersion(versionJson);
            log(L.t("s_393e769d23") + versionId + L.t("s_6090347260") + requiredJavaVersion + "+");

            // 2. 确定 Java 路径（含版本检查与自动下载）
            String javaExe = resolveJavaPath(javaPath, requiredJavaVersion);
            if (javaExe == null) {
                logError(L.t("s_53b7691291") + requiredJavaVersion + L.t("s_65679c0155"));
                return false;
            }
            log(L.t("s_38e3640810") + javaExe);

            // 3. 构建启动命令
            List<String> command = buildCommand(javaExe, versionJson, account, versionId);

            // 3.5 确保游戏语言为简体中文
            //     --lang 只在首次生成 options.txt 时生效；
            //     已有 options.txt 的情况下必须直接改文件，否则仍是英文。
            ensureChineseLanguage();

            // 4. 打印完整命令（调试用）
            log(L.t("s_d36f6195ea") + String.join(" ", maskSensitive(command)));

            // 5. 启动进程
            return startProcess(command);

        } catch (Exception e) {
            logger.error("启动游戏失败", e);
            logError("启动失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从版本 JSON 解析所需 Java 版本
     * 新版本格式：arguments.jvm 中或顶层 javaVersion.component/majorVersion
     * 旧版本无此字段，默认返回 17
     */
    private int parseRequiredJavaVersion(JsonObject versionJson) {
        try {
            // 新版本格式（1.17+）：顶层 javaVersion 对象
            if (versionJson.has("javaVersion")) {
                JsonObject javaVer = versionJson.getAsJsonObject("javaVersion");
                if (javaVer.has("majorVersion")) {
                    int major = javaVer.get("majorVersion").getAsInt();
                    if (major >= 8) {
                        return major;
                    }
                }
            }
            // 兼容旧版本：通过版本 ID 推断
            // 1.17+ 需要 Java 17，1.18+ 需要 Java 17，1.20.5+ 需要 Java 21
            if (versionJson.has("id")) {
                String id = versionJson.get("id").getAsString();
                // 简单的版本号解析
                if (id.matches("1\\.20\\.[5-9].*") || id.matches("1\\.(2[1-9]|[3-9]\\d).*")) {
                    return 21;
                }
                if (id.matches("1\\.1[7-9].*") || id.matches("1\\.20\\.[0-4].*")) {
                    return 17;
                }
                // 1.16 只需要 Java 16，但统一用 17；1.17-1.19 已由上面分支覆盖。
                // 早期这里正则是 1.1[6-9]，与上面重叠且永不生效
                if (id.matches("1\\.16(\\..*)?")) {
                    return 17;
                }
            }
        } catch (Exception e) {
            logger.debug("解析 Java 版本要求失败，使用默认值 17: {}", e.getMessage());
        }
        return 17; // 默认 Java 17
    }

    /**
     * 确定 Java 路径（含版本检查与自动下载）
     *
     * @param configuredPath    手动指定的路径
     * @param requiredVersion   版本要求的最低 Java 主版本号
     * @return Java 可执行文件路径，找不到返回 null
     */
    private String resolveJavaPath(String configuredPath, int requiredVersion) {
        // 1. 如果配置了路径，优先使用
        if (configuredPath != null && !configuredPath.isEmpty()) {
            if (validateJavaPath(configuredPath)) {
                // 额外检查版本是否满足要求
                if (checkJavaVersion(configuredPath, requiredVersion)) {
                    return configuredPath;
                }
                logger.warn("配置的 Java 版本不满足要求（需要 {}，当前 {}），将尝试自动寻找或下载",
                        requiredVersion, getJavaMajorVersion(configuredPath));
            }
            logger.warn("配置的 Java 路径无效: {}", configuredPath);
        }

        // 2. 检查配置中的 Java 路径
        String configJavaPath = config.getJavaPath();
        if (configJavaPath != null && !configJavaPath.isEmpty()) {
            if (validateJavaPath(configJavaPath) && checkJavaVersion(configJavaPath, requiredVersion)) {
                return configJavaPath;
            }
        }

        // 3. 自动检测可用的 Java —— **必须包含启动器自己下载的 JRE**
        //
        // 关键：这里用 JavaRuntimeManager.detectInstalledJava() 而不是静态的
        // JavaDetector.detectJavaVersions()。后者只扫 JAVA_HOME / 当前 JVM /
        // 系统常见路径，**不扫 <launcherDir>/java**，而自动下载的 JRE 恰好装在
        // 那里。早期用静态方法导致：第一次下载 JRE 成功 → 第二次启动又找不到
        // → 每次启动都重新下载 40MB。没装 Java 的玩家会反复被要求下载。
        //
        // detectInstalledJava() 内部 = 系统检测 + 启动器 java 目录检测。
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
                // 必须关闭：它会创建线程池与 OkHttp 连接池，
                // 不关会泄漏约 20 个非守护线程，还会拖住 JVM 退出。
                mgr.shutdown();
            }
        } catch (Exception e) {
            logger.warn("检测启动器自带 Java 失败，退回系统检测: {}", e.getMessage());

            // 兜底：即使上面失败，也用静态检测再试一次
            List<JavaDetector.JavaInfo> javaList = JavaDetector.detectJavaVersions();
            JavaDetector.JavaInfo best = pickBestJava(javaList, requiredVersion);
            if (best != null) {
                logger.info("自动选择 Java（系统检测）: {} (版本 {})",
                        best.getJavaExecutable(), best.getMajorVersion());
                return best.getJavaExecutable();
            }
        }

        // 4. 未找到满足版本要求的 Java，尝试自动下载（如果启用了自动下载）
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
                                    // 可选：更新进度 UI
                                }
                            });
                } finally {
                    // 必须关闭：JavaRuntimeManager 内部建了线程池与 OkHttp
                    // 连接池（约 20 个非守护线程）。不关会持续累积，
                    // 并且这些线程会让 JVM 无法正常退出。
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

        // 5. 使用当前 JVM（最后兜底）
        String currentJava = System.getProperty("java.home");
        if (currentJava != null) {
            Path javaExe = PlatformUtils.isWindows()
                    ? Paths.get(currentJava, "bin", "java.exe")
                    : Paths.get(currentJava, "bin", "java");
            if (Files.exists(javaExe) && checkJavaVersion(javaExe.toString(), requiredVersion)) {
                return javaExe.toString();
            }
        }

        // 6. 触发弹窗下载（如果 modalHost 已注入）
        // 这样玩家能看到进度，而不是静默失败
        if (modalHost != null) {
            return promptDownloadJava(requiredVersion);
        }

        return null;
    }

    /**
     * 弹窗下载 Java 并返回路径
     *
     * 本方法运行在启动线程（HomePage 的 CompletableFuture）上，可以安全阻塞；
     * 弹窗和进度条切到 JavaFX 线程显示，下载本身在后台线程执行，
     * 用户点「取消」则放弃下载返回 null。
     */
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
                            try {
                                downloadJavaWithProgress(requiredVersion, resultRef, latch);
                            } catch (RuntimeException ex) {
                                // 弹窗本身出问题时也要放行等待线程，否则启动永久卡住
                                logger.error("Java 下载面板异常", ex);
                                latch.countDown();
                            }
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

    /**
     * 带进度条的 Java 下载
     *
     * 必须在 JavaFX 线程上调用（confirmOrCancel 的回调即 FX 线程）：
     * 先弹出不可关闭的进度面板，再把真正的下载放到后台线程。
     */
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

        // closable=false：下载途中不能点 ✕ / 遮罩关掉，避免误以为关掉就等于取消
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
                        // 节流：下载回调很频繁，避免把 FX 线程刷爆
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
                    // 关闭线程池与连接池，否则每次下载都会泄漏约 20 个线程
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
                // 确认分支里若弹窗/线程启动本身抛异常，latch 永不清零，
                // 启动线程就会永久阻塞（表现为点了开始后一直卡在「正在准备」）
                latch.countDown();
            }
        }, "java-downloader");
        worker.setDaemon(true);
        worker.start();
    }

    // ==================== 启动后窗口检测 ====================

    /**
     * 启动后检测游戏窗口是否出现
     *
     * 等待一段时间后检查游戏进程是否创建了主窗口：
     * - 检测到窗口 → 记录日志，正常结束（不打扰玩家）
     * - 进程中途退出 → 交给已有的退出监听处理
     * - 超时仍未检测到 → 弹窗询问玩家（已正常打开 / 出了问题 / 再等等）
     */
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
        // 首次等待久一点：给游戏加载资源、初始化 LWJGL 留时间
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

            // 检测失败 → 弹窗询问玩家
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
            // 2 = 再等等
            log(L.t("s_7a8160fcb6"));
            waitSeconds = 15;
        }
    }

    /**
     * 查询指定进程是否拥有主窗口（Windows）
     *
     * 通过 PowerShell 的 MainWindowHandle 判断，0 表示还没有可见窗口。
     */
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

    /**
     * 弹窗询问玩家启动状态（三选一）
     *
     * @return 0=已正常打开 1=出了问题 2=再等等（ESC / 点遮罩关闭也算「再等等」）
     */
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

            // 关闭（含 ESC / 点遮罩）时放行；若没点过按钮则维持「再等等」
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


    /**
     * 从候选列表里挑最合适的 Java
     *
     * 选择规则（按优先级）：
     *   1. 必须满足主版本要求
     *   2. **优先 64 位** —— 32 位 JVM 堆上限约 1.5G，
     *      用户分配 4G 内存时会直接 OOM 起不来，这类失败很难排查
     *   3. 同为 64 位时取版本号最高的（兼容性更好）
     *
     * 早期直接用 findJavaByVersion 取「第一个满足的」，既不区分架构、
     * 顺序也取决于扫描顺序，混装 32/64 位 Java 的机器上会随机失败。
     */
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
            // 启动器是 32 位进程时，它拉起的游戏也只能用 32 位 JVM
            if (!PlatformUtils.is64BitJvm() && infoIs64) {
                continue;
            }

            if (infoIs64 && !bestIs64) {
                best = info;      // 64 位优先
            } else if (infoIs64 == bestIs64
                    && info.getMajorVersion() > best.getMajorVersion()) {
                best = info;      // 同架构取版本高的
            }
        }
        return best;
    }

    /** 是否 64 位架构 */
    private boolean is64Bit(String arch) {
        if (arch == null) {
            return true;    // 未知时当作可用，避免误排除
        }
        String a = arch.toLowerCase();
        return a.contains("64") || a.contains("x64") || a.contains("amd64");
    }

    /**
     * 检查指定 Java 路径的版本是否满足要求
     */
    private boolean checkJavaVersion(String javaPath, int requiredVersion) {
        int major = getJavaMajorVersion(javaPath);
        return major >= requiredVersion;
    }

    /**
     * 获取 Java 主版本号（执行 java -version 解析）
     */
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
            // java -version 的输出在独立线程里读，主线程只管计时。
            // 以前是先在当前线程读完输出、再 waitFor 超时：读循环本身没有超时，
            // java.exe 挂着不输出也不退出时会永久卡在这里（启动线程卡死）。
            String output = JavaDetector.runVersionCommand(javaExe, 10);
            if (output == null) {
                return 0;   // 超时 / 无法启动，视为无法获取版本
            }
            return parseMajorVersion(output);
        } catch (Exception e) {
            logger.debug("获取 Java 版本失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 从版本字符串解析主版本号
     * 例如："1.8.0_292" -> 8, "17.0.1" -> 17, "21.0.2" -> 21
     */
    private int parseMajorVersion(String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            return 0;
        }
        try {
            // 匹配 "1.x.x" 或 "x.x.x" 格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"?(\\d+)(?:\\.(\\d+))?");
            java.util.regex.Matcher matcher = pattern.matcher(versionString);
            if (matcher.find()) {
                int first = Integer.parseInt(matcher.group(1));
                if (first == 1 && matcher.group(2) != null) {
                    return Integer.parseInt(matcher.group(2)); // 1.8 -> 8
                }
                return first; // 17, 21 等
            }
        } catch (Exception e) {
            logger.debug("解析版本号失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 验证 Java 路径
     */
    private boolean validateJavaPath(String javaPath) {
        try {
            Path path = Paths.get(javaPath);

            // 如果是目录，尝试找 bin/java
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

    /**
     * 加载版本 JSON（处理继承）
     */
    private JsonObject loadVersionJson(String versionId) throws IOException {
        Path versionJsonPath = gameDir.resolve("versions").resolve(versionId)
                                     .resolve(versionId + ".json");

        if (!Files.exists(versionJsonPath)) {
            logger.error("版本 JSON 不存在: {}", versionJsonPath);
            return null;
        }

        String jsonStr = Files.readString(versionJsonPath, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();

        // 处理继承关系
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

    /**
     * 构建启动命令
     * 关键：使用 List 数组，避免路径空格问题
     */
    private List<String> buildCommand(String javaExe, JsonObject versionJson,
                                      Account account, String versionId) throws IOException {

        List<String> command = new ArrayList<>();

        // Java 可执行文件
        command.add(javaExe);

        // 内存参数
        String memoryMin = config.getMemoryMin();
        String memoryMax = config.getMemoryMax();
        command.add("-Xms" + memoryMin);
        command.add("-Xmx" + memoryMax);

        // 编码参数
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");

        // 注意：不要添加 "-Dos.name=Windows 10" 这类**含空格**的系统属性。
        // JVM 解析 -D 时会在空格处截断，结果是 "-Dos.name=Windows" 与
        // 孤立的 "10" 两个参数，"10" 会被当成主类/参数，导致启动失败。
        // 现代 Minecraft 也不依赖伪造的 os.name。

        // 变量映射（JVM 参数和游戏参数都要用）
        Map<String, String> variables = buildVariables(versionJson, account, versionId);

        // Natives 路径
        Path nativesDir = gameDir.resolve("versions").resolve(versionId).resolve("natives");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        // 资源目录
        command.add("-Dminecraft.launcher.brand=Novus Minecraft Launcher");
        command.add("-Dminecraft.launcher.version=" + config.getLauncherVersion());

        // Classpath
        List<Path> classpathEntries = buildClasspath(versionJson, versionId);
        if (classpathEntries.isEmpty()) {
            throw new IOException("无法构建 Classpath，缺少必要的库文件");
        }

        String classpathString = buildClasspathString(classpathEntries);

        // Forge 等模组加载器的 JVM 参数里会引用 ${classpath}，
        // 因此需要在处理 JVM 参数前把它放进变量表
        variables.put("classpath", classpathString);

        // 注入版本自定义的 JVM 参数（Forge 的 -p / --add-modules / --add-opens 等）
        // 注意：必须在 -cp 之前插入，否则 module-path 相关参数位置不对
        List<String> jvmArgs = buildJvmArguments(versionJson, variables);
        if (!jvmArgs.isEmpty()) {
            logger.info("注入 {} 个自定义 JVM 参数", jvmArgs.size());
            command.addAll(jvmArgs);
        }

        command.add("-cp");
        command.add(classpathString);

        // 主类
        String mainClass = versionJson.has("mainClass")
                ? versionJson.get("mainClass").getAsString()
                : "net.minecraft.client.main.Main";
        command.add(mainClass);

        // 游戏参数
        List<String> gameArgs = buildGameArguments(versionJson, account, versionId, variables);

        // 统一追加界面语言：如果不指定，游戏会跟随系统区域，
        // 在某些系统上会显示英文。这里固定为简体中文。
        //
        // 注意语言代码必须**小写** zh_cn。
        // 游戏是按这个 key 去 assets 里找语言文件的，实际文件是
        // minecraft/lang/zh_cn.json；写成 zh_CN 会找不到并回退到英文。
        if (!containsArg(gameArgs, "--lang")) {
            gameArgs.add("--lang");
            gameArgs.add(LANG_ZH_CN);
        }

        command.addAll(gameArgs);

        return command;
    }

    /**
     * 判断参数列表中是否已存在某个选项
     */
    private boolean containsArg(List<String> args, String option) {
        for (String a : args) {
            if (option.equalsIgnoreCase(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建版本自定义的 JVM 参数
     *
     * 原版 Minecraft 的 JVM 参数通常为空，但 Forge / Fabric 等模组加载器
     * 会在这里定义 module-path、--add-opens、-DignoreList 等关键参数，
     * 缺少这些参数会导致模组加载器无法启动。
     */
    /**
     * 把字符串里未解析的 ${xxx} 占位符替换为兜底值
     *
     * 模组加载器有时会引入启动器不知道的变量（如 Fabric 的 ${natives_directory}）。
     * 留着会让 JVM 收到字面量，导致运行时找不到路径。
     */
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

        // 复用与游戏参数相同的规则求值逻辑
        List<String> raw = new ArrayList<>();
        processArguments(jvmEl.getAsJsonArray(), variables, raw);

        // 过滤掉启动器自己管理的参数，避免重复或冲突：
        // - classpath 相关：由启动器统一构建
        // - 内存参数：由设置界面控制
        // - 编码 / natives：启动器已自行处理
        // 逐项过滤，注意「带值的选项」必须**连同它的值一起跳过**。
        //
        // 之前的 bug：只跳过了 "-cp" 本身，它后面的那一长串 classpath
        // 被当成普通参数保留下来，于是命令行里出现两个 classpath 相邻：
        //     ... -cp <启动器classpath> <Fabric的classpath> 主类 ...
        // JVM 就把那串 classpath 当成了主类名，报
        //     「找不到或无法加载主类 E:\...sm-9.10.1.jar;...」
        //
        // 需要「跳过值」的选项：-cp / -classpath / -p / --module-path
        //                      --add-opens / --add-exports / --add-reads
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

            // 带值的选项：本身跳过，且把它的值也跳过
            if (optionsWithValue.contains(lower)) {
                i++;            // 跳过紧跟的值
                continue;
            }
            // 形如 -cp=xxx 的写法
            if (lower.startsWith("-cp=") || lower.startsWith("-classpath=")) {
                continue;
            }

            // 启动器自行管理的参数，不重复添加
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

        // 修掉未替换的占位符（例如 Fabric 的 ${natives_directory}）
        // 未替换的占位符会让 JVM 收到形如 "-Djna.tmpdir=${natives_directory}" 的
        // 字面量，虽然不致命，但会造成运行时路径错误。
        for (int i = 0; i < result.size(); i++) {
            String v = result.get(i);
            if (v.contains("${")) {
                for (Map.Entry<String, String> e : variables.entrySet()) {
                    v = v.replace("${" + e.getKey() + "}", e.getValue());
                }
                // 仍有未替换的（模组加载器自定义变量）——用 natives 目录兜底
                String natives = variables.getOrDefault("natives_directory", "");
                if (v.contains("${") && !natives.isEmpty()) {
                    // 用字符串处理而非正则，避免转义问题
                    v = replaceUnresolvedPlaceholders(v, natives);
                }
                result.set(i, v);
            }
        }

        return result;
    }

    /**
     * 构建 Classpath
     */
    private List<Path> buildClasspath(JsonObject versionJson, String versionId) throws IOException {
        List<Path> classpath = new ArrayList<>();

        // 1. 添加版本 JAR
        Path versionJar = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        if (Files.exists(versionJar)) {
            classpath.add(versionJar);
        } else {
            // 检查父版本的 JAR（继承情况）
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

        // 2. 添加库文件
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement element : libraries) {
                JsonObject lib = element.getAsJsonObject();

                // 检查规则
                if (!isLibraryAllowed(lib)) {
                    continue;
                }

                String name = lib.get("name").getAsString();
                String mavenPath = parseMavenPath(name);
                Path libPath = gameDir.resolve("libraries").resolve(mavenPath);

                if (Files.exists(libPath)) {
                    classpath.add(libPath);
                } else {
                    // 缺库会让游戏起不来且报错晦涩，必须让用户看到并提示「重新下载」
                    logger.warn("库文件不存在，已跳过: {}", libPath);
                    if (logConsumer != null) {
                        log("缺少库文件：" + libPath.getFileName()
                                + "（请在「下载」页重新安装该版本）");
                    }
                }
            }
        }

        return classpath;
    }

    /**
     * 检查库文件是否应该被加载
     */
    private boolean isLibraryAllowed(JsonObject library) {
        if (!library.has("rules") || !library.get("rules").isJsonArray()) {
            return true;
        }
        JsonArray rules = library.getAsJsonArray("rules");
        if (rules.size() == 0) {
            return true;   // 空 rules 数组 = 无条件允许
        }

        // 默认拒绝；依次应用规则，最后一条匹配的规则决定结果
        boolean allowed = false;
        String currentOs = getCurrentOsName();
        String osArch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean is64 = osArch.contains("64") || osArch.equals("aarch64");

        for (JsonElement ruleElement : rules) {
            if (!ruleElement.isJsonObject()) {
                continue;
            }
            JsonObject rule = ruleElement.getAsJsonObject();
            boolean matches = true;

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equalsIgnoreCase(currentOs)) {
                    matches = false;
                }
                // os.arch：官方是 x86 / arm64；早期启动器会忽略它，arm64 机器上会拉错库
                if (matches && os.has("arch")) {
                    String want = os.get("arch").getAsString().toLowerCase(java.util.Locale.ROOT);
                    boolean ok;
                    switch (want) {
                        case "x86", "i386", "i686", "32", "x32" -> ok = !is64;
                        case "x86_64", "amd64", "x64", "arm64", "aarch64", "64" -> ok = is64;
                        default -> ok = true;
                    }
                    if (!ok) {
                        matches = false;
                    }
                }
                if (matches && os.has("version")) {
                    matches = versionPatternMatches(os.get("version").getAsString(),
                            System.getProperty("os.version", ""));
                }
            }

            if (matches && rule.has("features")) {
                JsonObject feats = rule.getAsJsonObject("features");
                // 启动器不支持任何 features（is_demo_user / has_custom_resolution 等）
                if (feats.size() > 0) {
                    matches = false;
                }
            }

            if (matches) {
                String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
                allowed = "allow".equals(action);
            }
        }

        return allowed;
    }

    /** os.version 规则：支持 ^前缀、$ 后缀、* 通配 */
    private static boolean versionPatternMatches(String pattern, String version) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        boolean anchoredStart = pattern.startsWith("^");
        boolean anchoredEnd = pattern.endsWith("$");
        String core = pattern;
        core = (anchoredStart ? core.substring(1) : core);
        core = (anchoredEnd ? core.substring(0, core.length() - 1) : core);
        StringBuilder re = new StringBuilder();
        int last = 0;
        for (int k = 0; k < core.length(); k++) {
            if (core.charAt(k) == '*') {
                re.append(java.util.regex.Pattern.quote(core.substring(last, k))).append(".*");
                last = k + 1;
            }
        }
        re.append(java.util.regex.Pattern.quote(core.substring(last)));
        if (anchoredStart) {
            re.insert(0, "^");
        }
        if (anchoredEnd) {
            re.append("$");
        }
        try {
            return java.util.regex.Pattern.compile(re.toString()).matcher(version).find();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 构建 Classpath 字符串
     * 使用系统路径分隔符
     */
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

    /**
     * 构建游戏参数
     */
    private List<String> buildGameArguments(JsonObject versionJson, Account account, String versionId,
                                            Map<String, String> variables)
            throws IOException {

        List<String> args = new ArrayList<>();

        // 如果有 arguments.game，使用它
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

        // 回退到旧版 minecraftArguments 格式
        if (versionJson.has("minecraftArguments")) {
            String mcArgs = versionJson.get("minecraftArguments").getAsString();
            for (String arg : mcArgs.split(" ")) {
                args.add(replaceVariables(arg, variables));
            }
            return args;
        }

        // 完全没有参数，使用默认
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

    /**
     * 处理参数数组（新格式，包含规则）
     */
    private void processArguments(JsonArray argsArray, Map<String, String> variables,
                                 List<String> output) {
        for (JsonElement element : argsArray) {
            if (element.isJsonPrimitive()) {
                // 简单字符串参数
                output.add(replaceVariables(element.getAsString(), variables));
            } else if (element.isJsonObject()) {
                // 带规则的对象参数
                JsonObject argObj = element.getAsJsonObject();

                // 检查规则
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

    /**
     * 评估规则
     */
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
                    // Minecraft 使用 x86 表示 32 位，这里简化处理
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

    /**
     * 获取默认特性
     */
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

    /**
     * 构建变量映射
     */
    private Map<String, String> buildVariables(JsonObject versionJson, Account account,
                                               String versionId) {
        Map<String, String> vars = new HashMap<>();

        // 账号相关
        vars.put("auth_player_name", account.getUsername());
        vars.put("auth_uuid", account.getFormattedUuid());
        vars.put("auth_access_token", account.getAccessToken());
        vars.put("auth_session", account.getAccessToken());
        vars.put("auth_xuid", account.getXuid() != null ? account.getXuid() : "");
        vars.put("user_type", account.getType() == Account.Type.MICROSOFT ? "msa" : "legacy");
        vars.put("clientid", account.getClientId() != null ? account.getClientId() : "");

        // 路径相关
        vars.put("game_directory", gameDir.toAbsolutePath().toString());
        vars.put("assets_root", gameDir.resolve("assets").toAbsolutePath().toString());
        vars.put("assets_index_name", getAssetIndexId(versionJson));
        vars.put("game_assets", gameDir.resolve("assets").resolve("virtual").resolve("legacy")
                                     .toAbsolutePath().toString());

        // 版本相关
        vars.put("version_name", versionId);
        vars.put("version_type", versionJson.has("type")
                ? versionJson.get("type").getAsString() : "release");

        // 启动器相关
        vars.put("launcher_name", "NCL");
        vars.put("launcher_version", config.getLauncherVersion());

        // 库目录（Forge 等需要）
        vars.put("library_directory", gameDir.resolve("libraries").toAbsolutePath().toString());
        vars.put("classpath_separator", PlatformUtils.isWindows() ? ";" : ":");

        // Natives 目录：Fabric / Forge 的 JVM 参数会引用 ${natives_directory}
        // （如 -Djna.tmpdir=${natives_directory}），不提供会导致这些
        // 系统属性里残留未替换的占位符字面量。
        vars.put("natives_directory",
                gameDir.resolve("versions").resolve(versionId).resolve("natives")
                        .toAbsolutePath().toString());

        return vars;
    }

    /**
     * 替换变量
     * 支持 ${variable} 格式
     */
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

    /**
     * 获取资源索引 ID
     */
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

    /**
     * 获取当前操作系统名称
     */
    private String getCurrentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    /**
     * 获取当前架构
     */
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
     * 确保游戏界面语言为简体中文
     *
     * 为什么要单独处理 options.txt：
     * 启动参数里的 --lang 只在游戏**首次生成** options.txt 时被写入，
     * 之后游戏只读 options.txt。因此如果用户已经玩过（已有 options.txt），
     * 单靠 --lang 不会改变语言，必须直接修改该文件。
     *
     * 语言代码必须小写 zh_cn：游戏保存 options.txt 时会把它转成小写，
     * 而资源文件名也是小写的 zh_cn.json。曾经写成 zh_CN，
     * 既导致游戏找不到语言文件（显示英文），又造成每次启动都反复改写文件。
     *
     * 行为：
     * - options.txt 不存在 -> 什么都不做（--lang 参数会在首次启动时生效）
     * - 已存在且 lang 不是 zh_cn -> 改成 zh_cn，其余内容原样保留
     */
    private void ensureChineseLanguage() {
        Path optionsFile = gameDir.resolve("options.txt");
        if (!Files.exists(optionsFile)) {
            return;     // 首次启动由 --lang 负责
        }

        try {
            List<String> lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            boolean found = false;
            boolean changed = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("lang:")) {
                    found = true;
                    // 忽略大小写比较：游戏保存时会写成小写 zh_cn，
                    // 不做忽略大小写的话每次启动都会判定为「需要改」，
                    // 于是反复写盘、且写出的值游戏并不认。
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
            // 语言设置失败不应阻止游戏启动
            logger.warn("设置游戏语言失败（不影响启动）: {}", e.getMessage());
        }
    }

    // ==================== VPN 转发 ====================

    /**
     * 启动 VPN 转发监听。
     *
     * MC 的「对局域网开放」会让客户端自动连回本机 gamePort（Java 里报给客户端的
     * 就是那个端口）。VPN 玩家（10.77.0.x）经由中转发来的 IPv4 包让我们替他们
     * 连 127.0.0.1:gamePort。但本机 MC 已经占住 gamePort，所以这里换个端口监听，
     * 再把连接转发给 MC，并通过回调把「玩家编号 + 已收到的原始字节」交给 VPN 层。
     *
     * 端口占用/被防火墙拦截都不应影响正常启动游戏 —— 失败只记日志。
     */
    private void startVpnForwarder(int gamePort) {
        if (vpnForwarder != null) {
            return;
        }
        gamePort = Math.max(0, Math.min(gamePort, 65535));
        // 已经开着 VPN 房主时，优先用 VPN 会话里「实际空出来的端口」；
        // 否则用局域网端口（MC 自己占着时下面 bind 会失败，只记日志）
        try {
            com.mclauncher.multiplayer.vpn.VpnSession host =
                    com.mclauncher.multiplayer.vpn.VpnSession.activeHost();
            if (host != null && host.forwardGatewayPort() > 0) {
                gamePort = host.forwardGatewayPort();
            }
        } catch (Throwable ignored) {
            // 不影响正常启动
        }
        try {
            java.net.ServerSocket ss = new java.net.ServerSocket();
            ss.setReuseAddress(true);
            // 只监听回环：外部扫描不到；VPN 包只经由我们自己的转发进入
            int port = gamePort > 0 ? gamePort : 0;   // 0 = 让系统分配
            ss.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 50);
            ss.setSoTimeout(100);
            vpnForwarder = ss;
            Thread t = new Thread(() -> acceptVpnPeers(ss), "vpn-local-forward");
            t.setDaemon(true);
            t.start();
            logger.info("VPN 本地转发已在 127.0.0.1:{} 监听（转发到 MC {}）", ss.getLocalPort(), gamePort);
        } catch (Exception e) {
            logger.info("VPN 本地转发未启动（不影响游戏本体）: {}", e.getMessage());
        }
    }

    private void acceptVpnPeers(java.net.ServerSocket ss) {
        while (true) {
            try {
                java.net.Socket client = ss.accept();
                client.setSoTimeout(3000);
                client.setTcpNoDelay(true);
                // 先读「已经到达的字节」：MC 握手 + login 可能在转发建立前就到了
                byte[] head = readAvailable(client);
                client.setSoTimeout(0);
                if (vpnForwardingGate != null && !vpnForwardingGate.getAsBoolean()) {
                    logger.info("收到 VPN 转发但本机未开 VPN 房主，已断开");
                    client.close();
                    continue;
                }
                if (playerJoinConsumer == null) {
                    client.close();
                    continue;
                }
                int id = ++vpnPeerSeq;
                java.util.concurrent.ExecutorService pool;
                synchronized (this) {
                    if (vpnForwardPool == null || vpnForwardPool.isShutdown()) {
                        vpnForwardPool = java.util.concurrent.Executors.newCachedThreadPool(r -> {
                            Thread th = new Thread(r, "vpn-peer-" + id);
                            th.setDaemon(true);
                            return th;
                        });
                    }
                    pool = vpnForwardPool;
                }
                final java.net.Socket sock = client;
                final byte[] headCopy = head;
                pool.submit(() -> playerJoinConsumer.onPlayerJoin(id, headCopy, sock));
                logger.info("VPN 玩家 #{} 接入本地转发（预读 {} 字节）", id, head.length);
            } catch (java.net.SocketTimeoutException ignored) {
                // 只是用来定期检查是否该退出
            } catch (Exception e) {
                if (ss.isClosed()) {
                    return;
                }
                logger.debug("VPN 转发 accept 结束: {}", e.getMessage());
                return;
            }
            if (ss.isClosed()) {
                return;
            }
        }
    }

    /** 读出 socket 里当前已经到达的字节（最多等约 80ms 不再有新数据） */
    private static byte[] readAvailable(java.net.Socket s) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            java.io.InputStream in = s.getInputStream();
            long idleUntil = System.currentTimeMillis() + 80;
            while (System.currentTimeMillis() < idleUntil) {
                if (in.available() <= 0) {
                    Thread.sleep(5);
                    continue;
                }
                int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                if (n <= 0) break;
                bos.write(buf, 0, n);
                if (bos.size() > 64 * 1024) break;
                idleUntil = System.currentTimeMillis() + 30;
            }
        } catch (Exception ignored) {
            // 没有数据也正常
        }
        return bos.toByteArray();
    }

    /** 关闭 VPN 转发监听（游戏结束时调用） */
    private void stopVpnForwarder() {
        java.net.ServerSocket ss = vpnForwarder;
        vpnForwarder = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 启动进程
     *
     * 关键点：
     * 1. 使用 ProcessBuilder 的 List 构造器，避免路径空格问题
     * 2. 设置工作目录为游戏目录
     * 3. 重定向输出到日志回调
     */
    private boolean startProcess(List<String> command) throws IOException {
        // 使用 List 形式的命令，避免路径空格问题
        ProcessBuilder pb = new ProcessBuilder(command);

        // 设置工作目录
        pb.directory(gameDir.toFile());

        // 合并错误流到标准输出
        pb.redirectErrorStream(true);

        // 隐藏控制台窗口（Windows）
        // 注意：Java 没有直接隐藏控制台的 API，
        // 但因为我们是从 GUI 启动的，且使用 javaw 启动启动器，所以不会弹出控制台

        // 确保 Natives 目录存在
        Path nativesDir = gameDir.resolve("natives");
        Files.createDirectories(nativesDir);

        logger.info("启动游戏进程...");
        log("正在启动游戏进程...");

        // VPN 联机：让虚拟局域网的玩家能连进本机 MC 服务器
        // （转发口 = 局域网端口 + 1，见 VpnSession.FORWARD_OFFSET）
        startVpnForwarder(config.getVpnForwardPort());

        // 启动进程
        gameProcess = pb.start();

        // 读取输出
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

        // 监听进程退出
        Thread exitThread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                logger.info("游戏进程退出，退出码: {}", exitCode);

                // 游戏结束：停掉 VPN 本地转发监听
                stopVpnForwarder();

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

        // 启动后检测游戏窗口；超时未检测到则弹窗询问玩家（三选一）
        startLaunchVerification(process);

        return true;
    }

    /**
     * 结束游戏进程
     */
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

    /**
     * 检查游戏是否在运行
     */
    public boolean isRunning() {
        return gameProcess != null && gameProcess.isAlive();
    }

    /**
     * 获取进程 PID
     */
    public long getPid() {
        return gameProcess != null ? gameProcess.pid() : -1;
    }

    /**
     * 隐藏敏感信息（用于日志）
     */
    private List<String> maskSensitive(List<String> command) {
        List<String> masked = new ArrayList<>();
        boolean maskNext = false;

        for (String arg : command) {
            if (maskNext) {
                masked.add("***");
                maskNext = false;
                continue;
            }

            // 独立参数形式：--accessToken xxx
            if (arg.equals("--accessToken") || arg.equals("--auth_session")) {
                masked.add(arg);
                maskNext = true;
                continue;
            }
            // 等号形式：--accessToken=xxx；JVM 属性形式：-Dauth_access_token=xxx
            // 这两处以前漏了，会把 token 明文打进日志
            String lower = arg.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("--accesstoken=") || lower.startsWith("--auth_session=")
                    || lower.startsWith("-dauth_access_token=") || lower.startsWith("-dauth_session=")
                    || lower.startsWith("-daccessToken=".toLowerCase(java.util.Locale.ROOT))) {
                int eq = arg.indexOf('=');
                masked.add(arg.substring(0, eq + 1) + "***");
                continue;
            }

            masked.add(arg);
        }

        return masked;
    }

    /**
     * 输出日志
     */
    private void log(String message) {
        logger.info(message);
        if (logConsumer != null) {
            logConsumer.accept(message);
        }
    }

    /**
     * 输出错误日志
     */
    private void logError(String message) {
        logger.error(message);
        if (logConsumer != null) {
            logConsumer.accept("[错误] " + message);
        }
    }
}