package com.mclauncher.ui.page;


import com.mclauncher.i18n.L;
import com.mclauncher.auth.Account;
import com.mclauncher.auth.AccountManager;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.config.GameDirectory;
import com.mclauncher.launcher.GameLauncher;
import com.mclauncher.java.JavaRuntimeManager;
import com.mclauncher.utils.JavaDetector;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 主页
 *
 * 内容：公告 + 启动游戏按钮
 * 玩家进入启动器后看到的第一屏。
 */
public class HomePage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(HomePage.class);

    private final ConfigManager config;
    private final AccountManager accountManager;
    private final GameLauncher gameLauncher;
    private final Consumer<String> statusConsumer;
    private final Consumer<String> logConsumer;

    /** 内嵌弹窗宿主（由 MainWindow 注入，用于更新提示等） */
    private com.mclauncher.ui.component.ModalHost modalHost;

    /** 是否已做过启动更新检测（每次运行只查一次，避免切回主页反复弹窗） */
    private boolean updateChecked = false;

    private BorderPane root;
    private ComboBox<String> versionCombo;
    private ComboBox<String> accountCombo;
    private Button launchButton;
    private Label versionInfoLabel;
    private Label accountInfoLabel;
    private TextArea logArea;
    private VBox logPanel;
    private Label hintLabel;
    private Label stateLabel;

    /** 版本列表（下拉框展示的是格式化文本，这里保存原始 ID） */
    private List<String> versionIds = new java.util.ArrayList<>();

    public HomePage(ConfigManager config, AccountManager accountManager,
                    GameLauncher gameLauncher,
                    Consumer<String> statusConsumer,
                    Consumer<String> logConsumer) {
        this.config = config;
        this.accountManager = accountManager;
        this.gameLauncher = gameLauncher;
        this.statusConsumer = statusConsumer;
        this.logConsumer = logConsumer;
    }

    @Override
    public String getTitle() {
        return L.t("s_25c408b30e");
    }

    @Override
    public Region getContent() {
        if (root == null) {
            root = buildUI();
        }
        return root;
    }

    /** 由 MainWindow 注入内嵌弹窗宿主 */
    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    @Override
    public void onShow() {
        refreshVersions();
        refreshAccounts();
        refreshState();

        // 首次显示主页时检查更新（后台进行，不阻塞界面）
        checkUpdateOnStartup();
    }

    /**
     * 启动时自动检查更新
     *
     * 有新版则弹出内嵌面板（用户可选择立即更新 / 稍后）。
     * 整个检查在后台线程执行，失败静默忽略（不打扰用户）。
     */
    private void checkUpdateOnStartup() {
        if (updateChecked) {
            return;     // 每次运行只查一次
        }
        updateChecked = true;

        if (modalHost == null) {
            return;
        }

        final String currentVersion = config.getLauncherVersion();

        new Thread(() -> {
            try {
                com.mclauncher.network.UpdateClient client =
                        new com.mclauncher.network.UpdateClient();
                com.mclauncher.network.UpdateClient.UpdateInfo info =
                        client.checkUpdate(currentVersion);

                if (info.updateAvailable && info.latestVersion != null
                        && !info.latestVersion.isBlank()) {
                    Platform.runLater(() -> {
                        com.mclauncher.ui.window.UpdateDialog dialog =
                                new com.mclauncher.ui.window.UpdateDialog(
                                        modalHost, info, statusConsumer)
                                        // 退出前把配置落盘，避免用户刚改的设置丢失
                                        .setBeforeRestart(() -> config.forceSave())
                                        // 游戏在跑时不允许重启（会把游戏一起带走）
                                        .setGameRunningCheck(() -> gameLauncher.isRunning());
                        dialog.show();
                    });
                } else {
                    logger.info("启动检查：{}", info.describe());
                }
            } catch (Exception e) {
                // 检查更新失败不影响使用，只记日志
                logger.debug("启动时检查更新失败: {}", e.getMessage());
            }
        }, "update-check").start();
    }

    // ==================== UI ====================

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");

        // 间距与内边距都收紧：主页要一屏放下、不需要滚动。
        // 原先是 VBox(20) + Insets(28)，光骨架就吃掉近百像素。
        VBox content = new VBox(12);
        content.setPadding(new Insets(18, 22, 16, 22));

        // ---- 顶部：标题 ----
        VBox header = new VBox(2);
        Label title = new Label(L.t("s_a5d9fdec5a"));
        title.getStyleClass().add("page-title");

        Label subtitle = new Label(L.t("s_eab944f50d"));
        subtitle.getStyleClass().add("page-subtitle");

        header.getChildren().addAll(title, subtitle);

        // ---- 公告区 ----
        VBox announcement = buildAnnouncement();

        // ---- 启动区（核心） ----
        VBox launchArea = buildLaunchArea();

        // ---- 日志面板（默认隐藏） ----
        logPanel = buildLogPanel();
        logPanel.setVisible(false);
        logPanel.setManaged(false);

        content.getChildren().addAll(header, announcement, launchArea, logPanel);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        // 内容比视口矮时不显示滚动条，避免出现一条没用的灰条
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // 用样式类而非内联样式：内联会硬编码颜色，导致切换浅色主题时
        // 这块区域仍是深色（出现一条深色带）
        scroll.getStyleClass().add("bg-main");

        pane.setCenter(scroll);
        return pane;
    }

    /**
     * 公告卡片
     *
     * 注意：这里用 Label 而不是 Text。
     * JavaFX 的 Text 节点只认 CSS 的 `-fx-fill`，对 `-fx-text-fill` 无效 ——
     * 之前用 Text 导致文字没被主题着色，在深色背景上显示为黑色，几乎看不清。
     * Label 正常响应 `-fx-text-fill`，并能自动换行。
     */
    private VBox buildAnnouncement() {
        VBox box = new VBox(6);
        box.getStyleClass().add("announcement-box");

        Label title = new Label(L.t("s_7149272f8e"));
        title.getStyleClass().add("announcement-title");

        VBox body = new VBox(2);

        // 文案保持简短：主页要能一屏放下，不需要滚动。
        // 原来的三条各 60 字左右，在 860px 宽度下会折成 6 行，
        // 是页面超高、必须滚动的主因。
        String[] lines = {
            L.t("s_4788a2b966"),
            L.t("s_2f6eb44ce7")
        };

        for (String line : lines) {
            Label l = new Label(line);
            l.getStyleClass().add("announcement-body");
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            body.getChildren().add(l);
        }

        box.getChildren().addAll(title, body);
        return box;
    }

    /**
     * 启动区：版本选择 + 账号选择 + 启动按钮
     */
    private VBox buildLaunchArea() {
        // 间距收紧到 10：卡片内部原本 16px 的行距在主页显得松散
        VBox box = new VBox(10);
        box.getStyleClass().add("card");

        Label title = new Label(L.t("s_2857a3704a"));
        title.getStyleClass().add("card-title");

        // 版本选择
        HBox versionRow = new HBox(12);
        versionRow.setAlignment(Pos.CENTER_LEFT);

        Label versionLabel = new Label(L.t("s_f4e1ba8015"));
        versionLabel.setMinWidth(70);
        versionLabel.getStyleClass().add("text-secondary");

        versionCombo = new ComboBox<>();
        versionCombo.setPrefWidth(300);
        versionCombo.setPromptText(L.t("s_514c33af5c"));
        versionCombo.setOnAction(e -> refreshState());

        versionInfoLabel = new Label("");
        versionInfoLabel.getStyleClass().add("text-muted");

        versionRow.getChildren().addAll(versionLabel, versionCombo, versionInfoLabel);

        // 账号选择
        HBox accountRow = new HBox(12);
        accountRow.setAlignment(Pos.CENTER_LEFT);

        Label accountLabel = new Label(L.t("s_784465f6ea"));
        accountLabel.setMinWidth(70);
        accountLabel.getStyleClass().add("text-secondary");

        accountCombo = new ComboBox<>();
        accountCombo.setPrefWidth(300);
        accountCombo.setPromptText(L.t("s_1e571e5f31"));
        accountCombo.setOnAction(e -> onAccountSelected());

        accountInfoLabel = new Label("");
        accountInfoLabel.getStyleClass().add("text-muted");

        accountRow.getChildren().addAll(accountLabel, accountCombo, accountInfoLabel);

        // 注意：主页刻意**不显示** Java 环境信息。
        // Java 检测结果在「设置 → Java 环境」里查看 —— 那里能看全所有
        // 已安装版本、架构和安装入口。主页塞一行只显示单个版本号，
        // 信息量有限反而占地方。

        // 状态提示
        hintLabel = new Label("");
        hintLabel.getStyleClass().add("text-secondary");
        hintLabel.setWrapText(true);

        // 启动按钮 + 日志开关
        HBox actionRow = new HBox(14);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        launchButton = new Button(L.t("s_e05557937a"));
        launchButton.getStyleClass().add("button-launch");
        launchButton.setOnAction(e -> onLaunchClicked());

        Button logToggle = new Button(L.t("s_b923b26d33"));
        logToggle.getStyleClass().add("button");
        logToggle.setOnAction(e -> {
            boolean v = !logPanel.isVisible();
            logPanel.setVisible(v);
            logPanel.setManaged(v);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        actionRow.getChildren().addAll(launchButton, logToggle, spacer);

        stateLabel = new Label("");
        stateLabel.getStyleClass().add("text-muted");
        stateLabel.setWrapText(true);

        box.getChildren().addAll(title, versionRow, accountRow,
                new Separator(), hintLabel, actionRow, stateLabel);

        return box;
    }

    /**
     * 日志面板
     */
    private VBox buildLogPanel() {
        VBox box = new VBox(8);
        box.getStyleClass().add("card");

        Label title = new Label(L.t("s_a8ce402665"));
        title.getStyleClass().add("card-title");

        logArea = new TextArea();
        logArea.getStyleClass().add("log-area");
        logArea.setEditable(false);
        // 140 而不是 220：展开日志面板后主页也不该超高。
        // 实测主页默认 320px、可用 516px；日志面板约 180px，
        // 140 的高度让展开后总高约 460px，仍在可视区内。
        // 需要看更长的历史可以拉大窗口，或到 .mc_launcher/logs 看完整日志。
        logArea.setPrefHeight(140);
        logArea.setWrapText(false);

        box.getChildren().addAll(title, logArea);
        return box;
    }

    // ==================== 数据刷新 ====================

    /**
     * 刷新版本列表（读取本地 versions 目录）
     */
    private void refreshVersions() {
        Path versionsDir = config.getGameDir().resolve("versions");

        CompletableFuture.supplyAsync(() -> {
            List<String> result = new java.util.ArrayList<>();
            if (Files.isDirectory(versionsDir)) {
                try (var stream = Files.newDirectoryStream(versionsDir)) {
                    for (Path p : stream) {
                        if (Files.isDirectory(p)
                                && Files.exists(p.resolve(p.getFileName() + ".json"))) {
                            result.add(p.getFileName().toString());
                        }
                    }
                } catch (Exception e) {
                    logger.debug("扫描版本目录失败: {}", e.getMessage());
                }
            }
            // 本地版本倒序（新的在前）
            result.sort((a, b) -> b.compareToIgnoreCase(a));
            return result;
        }).thenAccept(list -> Platform.runLater(() -> {
            versionIds = list;
            String previous = versionCombo.getSelectionModel().getSelectedItem();

            // 每次刷新都先清空提示，避免残留上一次的文案
            // （此前非空分支不清空，导致「已安装 1.21.1」时仍显示「还没有安装任何游戏版本」）
            hintLabel.setText("");

            versionCombo.getItems().clear();
            if (list.isEmpty()) {
                versionCombo.setPromptText(L.t("s_b297ef2489"));
                hintLabel.setText(L.t("s_61b86707b2"));
            } else {
                versionCombo.getItems().addAll(list);
                versionCombo.setPromptText(L.t("s_81d81d95b2"));

                // 恢复上次选择，否则选第一个
                String last = config.getGameVersion();
                if (previous != null && list.contains(previous)) {
                    versionCombo.getSelectionModel().select(previous);
                } else if (last != null && list.contains(last)) {
                    versionCombo.getSelectionModel().select(last);
                } else {
                    versionCombo.getSelectionModel().select(0);
                }
            }
            refreshState();
        }));
    }

    /**
     * 刷新账号列表
     */
    private void refreshAccounts() {
        List<Account> accounts = accountManager.getAllAccounts();

        String previous = accountCombo.getSelectionModel().getSelectedItem();
        accountCombo.getItems().clear();

        for (Account a : accounts) {
            accountCombo.getItems().add(a.getUsername() + "  (" + a.getType().getDisplayName() + ")");
        }

        Account current = accountManager.getCurrentAccount();
        if (current != null) {
            int idx = accounts.indexOf(current);
            if (idx >= 0) {
                accountCombo.getSelectionModel().select(idx);
            }
        } else if (!accounts.isEmpty()) {
            accountCombo.getSelectionModel().select(0);
            onAccountSelected();
        } else {
            accountCombo.setPromptText(L.t("s_bb0d746fe3"));
        }
    }


    /**
     * 刷新当前状态提示（是否可启动）
     */
    private void refreshState() {
        if (stateLabel == null) {
            return;
        }

        String version = versionCombo.getSelectionModel().getSelectedItem();
        Account account = accountManager.getCurrentAccount();
        GameDirectory activeDir = config.getActiveDirectory();

        StringBuilder sb = new StringBuilder();

        if (activeDir == null) {
            sb.append(L.t("s_a313885354"));
            launchButton.setDisable(true);
        } else if (!activeDir.existsOnDisk()) {
            sb.append(L.t("s_1295364f6b")).append(activeDir.getRootPathString())
              .append(L.t("s_de677092a7"));
            launchButton.setDisable(true);
        } else if (version == null) {
            sb.append(L.t("s_2b177b270b"));
            launchButton.setDisable(true);
        } else if (account == null) {
            sb.append(L.t("s_4d47fe85be"));
            launchButton.setDisable(true);
        } else if (gameLauncher.isRunning()) {
            sb.append(L.t("s_b4675fabd2"));
            launchButton.setDisable(true);
        } else {
            sb.append(L.t("s_0759e81f6e")).append(activeDir.getRootPathString())
              .append(L.t("s_e4872dc7f4")).append(version)
              .append(L.t("s_48d74b8b63")).append(account.getUsername()).append("）");
            launchButton.setDisable(false);
        }

        stateLabel.setText(sb.toString());

        if (version != null) {
            versionInfoLabel.setText(L.t("s_eb88ff57c9"));
        } else {
            versionInfoLabel.setText("");
        }
    }

    // ==================== 交互 ====================

    private void onAccountSelected() {
        int idx = accountCombo.getSelectionModel().getSelectedIndex();
        List<Account> accounts = accountManager.getAllAccounts();
        if (idx >= 0 && idx < accounts.size()) {
            Account a = accounts.get(idx);
            accountManager.setCurrentAccount(a.getId());
            config.setLastAccountId(a.getId());
            accountInfoLabel.setText(a.getType().getDisplayName());
            refreshState();
        }
    }

    private void onLaunchClicked() {
        String version = versionCombo.getSelectionModel().getSelectedItem();
        Account account = accountManager.getCurrentAccount();

        if (version == null || account == null) {
            refreshState();
            return;
        }

        launchButton.setDisable(true);
        launchButton.setText(L.t("s_a5b885735c"));

        // 展开日志区
        logPanel.setVisible(true);
        logPanel.setManaged(true);

        Consumer<String> log = msg -> Platform.runLater(() -> appendLog(msg));
        gameLauncher.setLogConsumer(log);
        gameLauncher.setExitConsumer(code -> Platform.runLater(() -> {
            appendLog(L.t("s_9394649693") + code + "）");
            launchButton.setText(L.t("s_e05557937a"));
            launchButton.setDisable(false);
            refreshState();

            // 非 0 退出码通常是崩溃 —— 自动分析日志并给出原因。
            // 放到后台线程：读日志 + 正则匹配可能要几百毫秒，
            // 不能卡住 FX 线程。
            if (code != 0) {
                analyzeCrashAsync();
            }
        }));

        // 注入弹窗宿主，使 GameLauncher 能在 Java 不足时弹出下载进度
        gameLauncher.setModalHost(modalHost);
        String javaPath = config.getJavaPath();

        CompletableFuture.supplyAsync(() ->
                gameLauncher.launch(version, account, javaPath,
                        acct -> accountManager == null ? acct : accountManager.ensureFresh(acct))
        ).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                launchButton.setText(L.t("s_686a6c5793"));
                if (statusConsumer != null) {
                    statusConsumer.accept(L.t("s_dbc26a1aca") + version);
                }
            } else {
                launchButton.setText(L.t("s_e05557937a"));
                launchButton.setDisable(false);
                appendLog(L.t("s_a049fcdeb4"));
                if (statusConsumer != null) {
                    statusConsumer.accept(L.t("s_65525f0f44"));
                }
            }
            refreshState();
            // 启动流程可能自动下载了 Java，重新刷新主页 Java 状态
            }));
    }

    /**
     * 后台分析崩溃日志，然后弹窗告诉用户原因
     *
     * 设计取舍：
     *   - 没识别出已知模式时**不弹窗**，只在日志区提示一句 —— 弹一个
     *     "没分析出原因" 的框只会增加困惑
     *   - 识别出多条时按级别排序，全部列出（玩家自己判断哪条最像）
     */
    private void analyzeCrashAsync() {
        final java.nio.file.Path gameDir = config.getGameDir();
        if (gameDir == null) {
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return new com.mclauncher.launcher.CrashAnalyzer().analyze(gameDir);
            } catch (Exception e) {
                logger.debug("崩溃分析失败: {}", e.getMessage());
                return null;
            }
        }).thenAccept(report -> Platform.runLater(() -> {
            if (report == null || !report.hasFinding()) {
                // 没有已知模式：只记日志，不打扰用户
                appendLog(L.t("s_3d37583f73"));
                if (report != null && report.logSource != null) {
                    appendLog(L.t("s_eaaf3ab914") + report.logSource);
                }
                return;
            }
            showCrashReport(report);
        }));
    }

    /**
     * 把分析结果渲染成弹窗
     */
    private void showCrashReport(com.mclauncher.launcher.CrashAnalyzer.Report report) {
        if (modalHost == null) {
            return;
        }

        VBox content = new VBox(10);
        content.setPadding(new Insets(16, 18, 14, 18));
        content.setPrefWidth(620);

        Label head = new Label(L.t("s_880a8393cc"));
        head.getStyleClass().add("text-secondary");
        head.setWrapText(true);
        content.getChildren().add(head);

        // 最多展示 4 条，避免弹窗过长
        int shown = 0;
        for (com.mclauncher.launcher.CrashAnalyzer.Finding f : report.findings) {
            if (shown >= 4) {
                break;
            }
            shown++;

            VBox card = new VBox(4);
            card.getStyleClass().add("card");

            String tag = f.level == com.mclauncher.launcher.CrashAnalyzer.Finding.Level.HIGH
                    ? "● " : f.level == com.mclauncher.launcher.CrashAnalyzer.Finding.Level.MEDIUM
                    ? "◐ " : "○ ";
            Label title = new Label(tag + f.title);
            title.getStyleClass().add("card-title");
            title.setWrapText(true);

            Label detail = new Label(f.detail);
            detail.getStyleClass().add("text-secondary");
            detail.setWrapText(true);
            detail.setMaxWidth(Double.MAX_VALUE);

            Label fix = new Label(L.t("s_025837508a") + f.suggestion);
            fix.getStyleClass().add("text-accent");
            fix.setWrapText(true);
            fix.setMaxWidth(Double.MAX_VALUE);

            card.getChildren().addAll(title, detail, fix);

            // 证据折叠在下面，字号小一点
            if (f.evidence != null && !f.evidence.isBlank()) {
                Label ev = new Label(f.evidence);
                ev.getStyleClass().add("text-muted");
                ev.setWrapText(true);
                ev.setMaxWidth(Double.MAX_VALUE);
                ev.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 11px;");
                card.getChildren().add(ev);
            }

            content.getChildren().add(card);
        }

        if (report.findings.size() > shown) {
            Label more = new Label(L.t("s_0ca74c11ad") + (report.findings.size() - shown) + L.t("s_3fb3d1542c"));
            more.getStyleClass().add("text-muted");
            content.getChildren().add(more);
        }

        if (report.logSource != null) {
            Label src = new Label(L.t("s_1716ce5ee9") + report.logSource);
            src.getStyleClass().add("text-muted");
            src.setWrapText(true);
            content.getChildren().add(src);
        }

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        if (report.logSource != null) {
            Button openLog = new Button(L.t("s_bc20b5032f"));
            openLog.getStyleClass().add("button");
            openLog.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().open(report.logSource.getParent().toFile());
                } catch (Exception ignored) {
                    // 打不开就算了
                }
            });
            buttons.getChildren().add(openLog);
        }

        Button close = new Button(L.t("s_cb63c62e50"));
        close.getStyleClass().add("button-primary");
        close.setOnAction(e -> modalHost.close());
        buttons.getChildren().add(close);

        content.getChildren().add(buttons);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("bg-main");

        modalHost.show(L.t("s_e5d8070f04"), scroll, null);
    }

    /**
     * 追加日志（供外部调用，如 GameLauncher 的输出）
     */
    public void appendLog(String message) {
        if (logArea == null) {
            return;
        }
        String ts = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.appendText("[" + ts + "] " + message + "\n");

        if (logConsumer != null) {
            logConsumer.accept(message);
        }
    }

    private String shorten(String path) {
        if (path == null) {
            return "";
        }
        return path.length() <= 42 ? path : "..." + path.substring(path.length() - 39);
    }
}
