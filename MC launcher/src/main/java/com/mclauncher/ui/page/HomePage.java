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

public class HomePage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(HomePage.class);

    private final ConfigManager config;
    private final AccountManager accountManager;
    private final GameLauncher gameLauncher;
    private final Consumer<String> statusConsumer;
    private final Consumer<String> logConsumer;

    private com.mclauncher.ui.component.ModalHost modalHost;

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

    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    @Override
    public void onShow() {
        refreshVersions();
        refreshAccounts();
        refreshState();

        checkUpdateOnStartup();
    }

    private void checkUpdateOnStartup() {
        if (updateChecked) {
            return;     
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

                                        .setBeforeRestart(() -> config.forceSave())

                                        .setGameRunningCheck(() -> gameLauncher.isRunning());
                        dialog.show();
                    });
                } else {
                    logger.info("启动检查：{}", info.describe());
                }
            } catch (Exception e) {

                logger.debug("启动时检查更新失败: {}", e.getMessage());
            }
        }, "update-check").start();
    }

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");

        
        VBox content = new VBox(12);
        content.setPadding(new Insets(18, 22, 16, 22));

        VBox header = new VBox(2);
        Label title = new Label(L.t("s_a5d9fdec5a"));
        title.getStyleClass().add("page-title");

        Label subtitle = new Label(L.t("s_eab944f50d"));
        subtitle.getStyleClass().add("page-subtitle");

        header.getChildren().addAll(title, subtitle);

        VBox announcement = buildAnnouncement();

        VBox launchArea = buildLaunchArea();

        logPanel = buildLogPanel();
        logPanel.setVisible(false);
        logPanel.setManaged(false);

        content.getChildren().addAll(header, announcement, launchArea, logPanel);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        
        scroll.getStyleClass().add("bg-main");

        pane.setCenter(scroll);
        return pane;
    }

    private VBox buildAnnouncement() {
        VBox box = new VBox(6);
        box.getStyleClass().add("announcement-box");

        Label title = new Label(L.t("s_7149272f8e"));
        title.getStyleClass().add("announcement-title");

        VBox body = new VBox(2);

        

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

    private VBox buildLaunchArea() {

        VBox box = new VBox(10);
        box.getStyleClass().add("card");

        Label title = new Label(L.t("s_2857a3704a"));
        title.getStyleClass().add("card-title");

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

        

        

        hintLabel = new Label("");
        hintLabel.getStyleClass().add("text-secondary");
        hintLabel.setWrapText(true);

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

    private VBox buildLogPanel() {
        VBox box = new VBox(8);
        box.getStyleClass().add("card");

        Label title = new Label(L.t("s_a8ce402665"));
        title.getStyleClass().add("card-title");

        logArea = new TextArea();
        logArea.getStyleClass().add("log-area");
        logArea.setEditable(false);

        

        
        logArea.setPrefHeight(140);
        logArea.setWrapText(false);

        box.getChildren().addAll(title, logArea);
        return box;
    }

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

            result.sort((a, b) -> b.compareToIgnoreCase(a));
            return result;
        }).thenAccept(list -> Platform.runLater(() -> {
            versionIds = list;
            String previous = versionCombo.getSelectionModel().getSelectedItem();

            
            hintLabel.setText("");

            versionCombo.getItems().clear();
            if (list.isEmpty()) {
                versionCombo.setPromptText(L.t("s_b297ef2489"));
                hintLabel.setText(L.t("s_61b86707b2"));
            } else {
                versionCombo.getItems().addAll(list);
                versionCombo.setPromptText(L.t("s_81d81d95b2"));

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

        logPanel.setVisible(true);
        logPanel.setManaged(true);

        Consumer<String> log = msg -> Platform.runLater(() -> appendLog(msg));
        gameLauncher.setLogConsumer(log);
        gameLauncher.setExitConsumer(code -> Platform.runLater(() -> {
            appendLog(L.t("s_9394649693") + code + "）");
            launchButton.setText(L.t("s_e05557937a"));
            launchButton.setDisable(false);
            refreshState();

            

            if (code != 0) {
                analyzeCrashAsync();
            }
        }));

        gameLauncher.setModalHost(modalHost);
        String javaPath = config.getJavaPath();

        CompletableFuture.supplyAsync(() ->
                gameLauncher.launch(version, account, javaPath)
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

            }));
    }

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

                appendLog(L.t("s_3d37583f73"));
                if (report != null && report.logSource != null) {
                    appendLog(L.t("s_eaaf3ab914") + report.logSource);
                }
                return;
            }
            showCrashReport(report);
        }));
    }

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
