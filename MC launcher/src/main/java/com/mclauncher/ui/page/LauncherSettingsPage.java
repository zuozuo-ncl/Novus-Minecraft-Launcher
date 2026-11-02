package com.mclauncher.ui.page;


import com.mclauncher.i18n.L;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.config.GameDirectory;
import com.mclauncher.java.JavaRuntimeManager;
import com.mclauncher.utils.FileUtils;
import com.mclauncher.utils.JavaDetector;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 启动器设置页
 *
 * 包含：目录设置、下载源、内存、Java 环境、微软登录配置。
 */
public class LauncherSettingsPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(LauncherSettingsPage.class);

    private final ConfigManager config;
    private final Path launcherDir;

    private BorderPane root;
    /** 语言切换后通知主窗口重建（由 MainWindow 注入） */
    private Runnable languageChangeListener;

    /** 内嵌弹窗宿主（由 MainWindow 注入） */
    private com.mclauncher.ui.component.ModalHost modalHost;

    /** 主场景（由 MainWindow 注入，用于即时切换主题） */
    private javafx.scene.Scene hostScene;

    /** 外观页的主题下拉框 */
    private ComboBox<String> themeCombo;

    /** 常规页的「检查更新」按钮与结果标签 */
    private Button updateCheckButton;
    private Label updateResultLabel;

    private TextField downloadDirField;
    private ComboBox<String> sourceCombo;
    private TextField memoryMinField;
    private TextField memoryMaxField;
    private ComboBox<String> javaCombo;
    private CheckBox autoDownloadJavaCheck;

    /** 手动安装 Java：版本选择 */
    private ComboBox<String> javaVersionCombo;

    /** 显示启动器已安装的 Java 版本 */
    private Label installedJavaLabel;
    private Label javaStatusLabel;
    private TextField clientIdField;

    public LauncherSettingsPage(ConfigManager config, Path launcherDir) {
        this.config = config;
        this.launcherDir = launcherDir;
    }

    /** 由 MainWindow 注入：用于「检查更新」时判断游戏是否在运行 */
    private com.mclauncher.launcher.GameLauncher gameLauncher;

    public void setGameLauncher(com.mclauncher.launcher.GameLauncher g) {
        this.gameLauncher = g;
    }

    /** 由 MainWindow 注入内嵌弹窗宿主 */
    public void setLanguageChangeListener(Runnable r) {
        this.languageChangeListener = r;
    }

    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    /** 由 MainWindow 注入主场景 */
    public void setHostScene(javafx.scene.Scene scene) {
        this.hostScene = scene;
    }

    @Override
    public String getTitle() {
        return L.t("s_1dcd31805a");
    }

    @Override
    public Region getContent() {
        if (root == null) {
            root = buildUI();
        }
        return root;
    }

    /**
     * 语言切换后重建整个设置页
     *
     * JavaFX 的控件不会因为文案变化自己刷新，必须重新构建。
     * 这里把根节点换掉，并通知主窗口重建侧边栏等常驻界面。
     */
    private void rebuildAfterLanguageChange() {
        if (root == null) {
            return;
        }
        javafx.scene.Parent parent = root.getParent();
        BorderPane fresh = buildUI();
        fresh.getStyleClass().add("bg-main");
        if (parent instanceof javafx.scene.layout.Pane pane) {
            int idx = pane.getChildren().indexOf(root);
            if (idx >= 0) {
                pane.getChildren().set(idx, fresh);
            } else {
                pane.getChildren().setAll(fresh);
            }
        }
        root = fresh;
        // 通知主窗口（侧边栏、状态栏等）一起重建
        if (languageChangeListener != null) {
            languageChangeListener.run();
        }
    }

    @Override
    public void onShow() {
        detectJavaAsync();
        refreshInstalledJava();
    }

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");

        VBox header = new VBox(4);
        header.setPadding(new Insets(24, 28, 12, 28));

        Label title = new Label(L.t("s_1dcd31805a"));
        title.getStyleClass().add("page-title");

        Label subtitle = new Label(L.t("s_6093ad8bb3"));
        subtitle.getStyleClass().add("page-subtitle");

        header.getChildren().addAll(title, subtitle);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab(L.t("s_f1484fa78b"), buildGeneralTab()),
                new Tab(L.t("s_aa84617d51"), buildJavaTab()),
                new Tab(L.t("s_9013849179"), buildAccountTab()),
                new Tab(L.t("s_09b58aa342"), buildAppearanceTab()),
                new Tab(L.t("s_4f55ee1e68"), buildAboutTab())
        );

        HBox buttons = new HBox(10);
        buttons.setPadding(new Insets(12, 28, 24, 28));
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Button reset = new Button(L.t("s_a191935bc6"));
        reset.getStyleClass().add("button");
        reset.setOnAction(e -> resetSettings());

        Button save = new Button(L.t("s_bb79ec7c15"));
        save.getStyleClass().add("button-primary");
        save.setOnAction(e -> saveSettings());

        buttons.getChildren().addAll(reset, save);

        VBox center = new VBox(10, tabs, buttons);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        center.setPadding(new Insets(0, 28, 0, 28));

        pane.setTop(header);
        pane.setCenter(center);
        return pane;
    }

    // ---------- 常规 ----------

    // ---------- 版本更新 ----------

    /**
     * 手动检查更新
     *
     * 在后台线程请求接口，避免阻塞界面；
     * 有新版则弹出更新面板（与主页自动检测共用同一套 UI）。
     */
    private void onCheckUpdate() {
        if (updateCheckButton == null) {
            return;
        }

        updateCheckButton.setDisable(true);
        updateCheckButton.setText(L.t("s_a778bdcfbf"));
        updateResultLabel.setText("");
        updateResultLabel.getStyleClass().clear();

        final String currentVersion = config.getLauncherVersion();

        new Thread(() -> {
            try {
                com.mclauncher.network.UpdateClient client =
                        new com.mclauncher.network.UpdateClient();
                com.mclauncher.network.UpdateClient.UpdateInfo info =
                        client.checkUpdate(currentVersion);

                Platform.runLater(() -> {
                    updateCheckButton.setDisable(false);
                    updateCheckButton.setText(L.t("s_a6df38586d"));

                    if (info.updateAvailable && info.latestVersion != null
                            && !info.latestVersion.isBlank()) {
                        updateResultLabel.setText(L.t("s_bdeaca9059") + info.latestVersion);
                        updateResultLabel.getStyleClass().add("text-accent");

                        // 弹出更新面板
                        if (modalHost != null) {
                            com.mclauncher.ui.window.UpdateDialog dialog =
                                    new com.mclauncher.ui.window.UpdateDialog(
                                            modalHost, info, null)
                                            // 重启前落盘，保证刚保存的设置不丢
                                            .setBeforeRestart(() -> config.forceSave());
                            // 和主页一样注入「游戏是否在运行」：否则游戏开着时
                            // 点「立即重启」会把游戏进程一起带走
                            if (gameLauncher != null) {
                                dialog.setGameRunningCheck(gameLauncher::isRunning);
                            }
                            dialog.show();
                        } else {
                            showMsg(L.t("s_bdeaca9059") + info.latestVersion
                                    + L.t("s_d57faccc59"));
                        }
                    } else {
                        updateResultLabel.setText(L.t("s_e0f88c2982"));
                        updateResultLabel.getStyleClass().add("text-secondary");
                    }
                });

            } catch (Exception ex) {
                logger.error("检查更新失败", ex);
                Platform.runLater(() -> {
                    updateCheckButton.setDisable(false);
                    updateCheckButton.setText(L.t("s_a6df38586d"));
                    updateResultLabel.setText(L.t("s_59c6009a14") + ex.getMessage());
                    updateResultLabel.getStyleClass().add("status-fail");
                });
            }
        }, "update-check-manual").start();
    }

    // ---------- 外观 ----------

    /**
     * 外观设置：明/暗主题切换
     *
     * 切换即时生效（不需重启）—— 直接替换主场景的 stylesheet。
     * 内嵌弹窗是主场景内的节点，因此也自动跟随主题。
     */
    private VBox buildAppearanceTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        box.getChildren().add(sectionLabel(L.t("s_31472ea4de")));

        Label desc = new Label(L.t("s_35391d4599"));
        desc.getStyleClass().add("text-secondary");
        desc.setWrapText(true);

        Label themeLabel = new Label(L.t("s_e848ddd482"));
        themeLabel.getStyleClass().add("text-secondary");

        themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll(L.t("s_d8cdf1fd4a"), L.t("s_80ec9e2b1b"));
        themeCombo.setPrefWidth(220);
        themeCombo.getSelectionModel().select(
                com.mclauncher.ui.ThemeManager.isLight(config.getTheme()) ? 1 : 0);
        themeCombo.setOnAction(e -> onThemeChanged());

        Label hint = new Label(
                L.t("s_49b86f69dd"));
        hint.getStyleClass().add("text-muted");
        hint.setWrapText(true);

        // 预览色块，让用户直观看到两套主题的主色
        HBox preview = new HBox(10);
        preview.setPadding(new Insets(10, 0, 0, 0));

        preview.getChildren().addAll(
                colorChip("#1E1E1E", L.t("s_952e19802f")),
                colorChip("#2D2D2D", L.t("s_35cad0fa7d")),
                colorChip("#3B82F6", L.t("s_dbbbfb19f0")),
                colorChip("#F5F5F5", L.t("s_18080c70c2")),
                colorChip("#FFFFFF", L.t("s_6b1e48622e")));

        box.getChildren().addAll(desc, themeLabel, themeCombo, hint, preview);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        // 滚轮滚动幅度：调大默认增量（JavaFX 默认过小）
        scroll.setPannable(true);
        installScrollTuning(scroll);

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    // ==================== 详情 ====================

    private VBox buildAboutTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        box.getChildren().add(sectionLabel(L.t("s_a2c6f15e72")));

        Label tech = new Label(
                L.t("s_5ddd8cfbdf")
                + L.t("s_c84bd0cf71")
                + L.t("s_db972499ad")
                + L.t("s_03daff7dfd")
                + L.t("s_7287677806"));
        tech.getStyleClass().add("text-secondary");
        tech.setWrapText(true);
        tech.setPadding(new Insets(4, 0, 4, 0));

        box.getChildren().addAll(tech, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_26b6120e78")));

        Label apis = new Label(
                L.t("s_67ba2871e1")
                + L.t("s_556a99c938")
                + L.t("s_6cef583a41")
                + L.t("s_380d646065"));
        apis.getStyleClass().add("text-secondary");
        apis.setWrapText(true);
        apis.setPadding(new Insets(4, 0, 4, 0));

        box.getChildren().addAll(apis, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_989d1affa0")));

        Label ver = new Label(
                L.t("s_3801dec333") + config.getLauncherVersion() + L.t("s_31ff5e1116")
                + L.t("s_706602a78d") + BUILD_DATE);
        ver.getStyleClass().add("text-secondary");
        ver.setWrapText(true);
        ver.setPadding(new Insets(4, 0, 4, 0));

        box.getChildren().addAll(ver, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_22336e6b89")));

        Label proj = new Label(
                L.t("s_a689afb22f")
                + L.t("s_7dd57cb517")
                + L.t("s_49f8772bc6")
                + L.t("s_f11ea07798")
                + L.t("s_4953d9b2df")
                + L.t("s_d5ff38bcd0"));
        proj.getStyleClass().add("text-secondary");
        proj.setWrapText(true);
        proj.setPadding(new Insets(4, 0, 4, 0));

        box.getChildren().addAll(proj, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_989b18f266")));

        Label lic = new Label(
                L.t("s_0fced0e28b")
                + "Gson（Apache 2.0）、SLF4J（MIT）、\n"
                + "Logback（EPL 1.0 / LGPL 2.1）、\n"
                + L.t("s_79ce222a40")
                + "· Eclipse Temurin（Adoptium）— GPLv2+CE\n"
                + L.t("s_cc3d1a9015"));
        lic.getStyleClass().add("text-muted");
        lic.setWrapText(true);
        lic.setPadding(new Insets(4, 0, 4, 0));

        box.getChildren().add(lic);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setPannable(true);
        installScrollTuning(scroll);
        scroll.setFitToWidth(true);

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    // ---- 构建日期 ----
    /** 构建日期（编译期常量，用占位符或当前日期） */
    private static final String BUILD_DATE = "2026-10-26";

    /** 主题色小圆块（仅用于预览展示） */
    private HBox colorChip(String color, String label) {
        javafx.scene.shape.Rectangle rect = new javafx.scene.shape.Rectangle(26, 26);
        rect.setArcWidth(8);
        rect.setArcHeight(8);
        rect.setFill(javafx.scene.paint.Color.web(color));
        rect.setStroke(javafx.scene.paint.Color.web("#888888"));
        rect.setStrokeWidth(0.5);

        Label l = new Label(label);
        l.getStyleClass().add("text-muted");
        l.setStyle("-fx-font-size: 11px;");

        VBox v = new VBox(4, rect, l);
        v.setAlignment(Pos.CENTER);

        HBox holder = new HBox(v);
        holder.setAlignment(Pos.CENTER);
        return holder;
    }

    /**
     * 主题切换：保存配置 + 立即应用到主场景
     */
    private void onThemeChanged() {
        boolean light = themeCombo.getSelectionModel().getSelectedIndex() == 1;
        String theme = light
                ? com.mclauncher.ui.ThemeManager.THEME_LIGHT
                : com.mclauncher.ui.ThemeManager.THEME_DARK;

        config.setTheme(theme);
        config.forceSave();

        if (hostScene != null) {
            com.mclauncher.ui.ThemeManager.apply(hostScene, theme);
        }
        logger.info("主题已切换为: {}", theme);
    }

    /**
     * 调大 ScrollPane 的滚轮滚动幅度
     *
     * JavaFX 默认的滚动增量偏小，滚一格几乎看不出移动。
     * 这里拦截 SCROLL 事件，按比例手动调整 vvalue。
     */
    private void installScrollTuning(ScrollPane scroll) {
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) {
                return;
            }
            // 每次滚动移动视口的比例（0.12 比默认手感明显更快）
            double delta = -e.getDeltaY() / 40.0 * 0.12;
            double target = scroll.getVvalue() + delta;
            scroll.setVvalue(Math.max(0, Math.min(1, target)));
            e.consume();
        });
    }

    private VBox buildGeneralTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        // ---- 语言 ----
        // 放在常规页最上面：这是最常改的一次性设置
        box.getChildren().add(sectionLabel(L.t("lang.section")));

        Label langLabel = new Label(L.t("lang.label"));
        langLabel.getStyleClass().add("text-secondary");

        ComboBox<com.mclauncher.i18n.L.Lang> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(com.mclauncher.i18n.L.Lang.values());
        langCombo.setCellFactory(cb -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(com.mclauncher.i18n.L.Lang item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName);
            }
        });
        langCombo.setButtonCell(langCombo.getCellFactory().call(null));
        langCombo.getSelectionModel().select(com.mclauncher.i18n.L.lang());
        langCombo.setPrefWidth(320);
        langCombo.setOnAction(e -> {
            com.mclauncher.i18n.L.Lang picked = langCombo.getSelectionModel().getSelectedItem();
            if (picked != null && picked != com.mclauncher.i18n.L.lang()) {
                config.setString("language", picked.code);
                config.forceSave();
                // 切换后整页重建（已经构建好的控件不会自己更新文案）
                com.mclauncher.i18n.L.setLang(picked);
                rebuildAfterLanguageChange();
            }
        });

        Label langHint = new Label(L.t("lang.hint"));
        langHint.getStyleClass().add("text-muted");
        langHint.setWrapText(true);

        box.getChildren().addAll(langLabel, langCombo, langHint, new Separator());

        // 下载目录
        box.getChildren().add(sectionLabel(L.t("s_2e186f04e9")));

        Label dl = new Label(L.t("s_28bc5d1641"));
        dl.getStyleClass().add("text-secondary");
        downloadDirField = new TextField(config.getDownloadDir().toAbsolutePath().toString());
        downloadDirField.setMaxWidth(Double.MAX_VALUE);
        Button dlBrowse = new Button(L.t("s_ef23ea67b0"));
        dlBrowse.getStyleClass().add("button");
        dlBrowse.setOnAction(e -> chooseDir(downloadDirField, L.t("s_55e40f47a9")));
        HBox dlRow = new HBox(8, downloadDirField, dlBrowse);
        HBox.setHgrow(downloadDirField, Priority.ALWAYS);

        // 游戏目录（只读显示 + 跳转管理）
        Label gl = new Label(L.t("s_bc013dae7e"));
        gl.getStyleClass().add("text-secondary");

        GameDirectory active = config.getActiveDirectory();
        String dirText = active != null
                ? active.getRootPathString() : L.t("s_045286a79f");
        Label dirDisplay = new Label(dirText);
        // 用「可读的输入框样式」呈现路径：dir-display 在主题 CSS 里定义，
        // 保证浅色/深色下文字都清晰（此前误用 .card，文字颜色未被定义）
        dirDisplay.getStyleClass().add("dir-display");
        dirDisplay.setWrapText(true);
        dirDisplay.setMaxWidth(Double.MAX_VALUE);

        Label dirHint = new Label(L.t("s_ad1b0eb7fb") + (active != null
                ? active.getLauncherDataPath() : "—"));
        dirHint.getStyleClass().add("text-muted");
        dirHint.setWrapText(true);

        Button dirBtn = new Button(L.t("s_9c50bc988a"));
        dirBtn.getStyleClass().add("button");
        dirBtn.setOnAction(e -> {
            com.mclauncher.ui.window.DirectoryManagerWindow w =
                    new com.mclauncher.ui.window.DirectoryManagerWindow(
                            modalHost, config, msg -> {
                                // 刷新显示
                                GameDirectory updated = config.getActiveDirectory();
                                dirDisplay.setText(updated != null
                                        ? updated.getRootPathString()
                                        : L.t("s_045286a79f"));
                                dirHint.setText(L.t("s_ad1b0eb7fb") + (updated != null
                                        ? updated.getLauncherDataPath() : "—"));
                            });
            w.show();
        });

        box.getChildren().addAll(dl, dlRow, gl, dirDisplay, dirHint, dirBtn, new Separator());

        // 下载源
        box.getChildren().add(sectionLabel(L.t("s_abdb438fc5")));

        Label sl = new Label(L.t("s_d582d4de13"));
        sl.getStyleClass().add("text-secondary");
        sourceCombo = new ComboBox<>();
        sourceCombo.getItems().addAll(
                L.t("s_e5972bcf67"),
                L.t("s_b05acbd695"));
        sourceCombo.getSelectionModel().select(
                "bmcl".equals(config.getSourceType()) ? 1 : 0);
        sourceCombo.setPrefWidth(320);

        Label sHint = new Label(
                L.t("s_9500a82c00")
                + L.t("s_03e65fcfa7"));
        sHint.getStyleClass().add("text-muted");
        sHint.setWrapText(true);

        box.getChildren().addAll(sl, sourceCombo, sHint, new Separator());

        // 内存
        box.getChildren().add(sectionLabel(L.t("s_7b02c8eec8")));

        HBox memRow = new HBox(12);
        memRow.setAlignment(Pos.CENTER_LEFT);

        Label minL = new Label(L.t("s_31793b6729"));
        minL.getStyleClass().add("text-secondary");
        memoryMinField = new TextField(config.getMemoryMin());
        memoryMinField.setPrefWidth(100);

        Label maxL = new Label(L.t("s_ed690ebb15"));
        maxL.getStyleClass().add("text-secondary");
        memoryMaxField = new TextField(config.getMemoryMax());
        memoryMaxField.setPrefWidth(100);

        memRow.getChildren().addAll(minL, memoryMinField, maxL, memoryMaxField);

        Label memHint = new Label(L.t("s_cc98935c53"));
        memHint.getStyleClass().add("text-muted");
        memHint.setWrapText(true);

        box.getChildren().addAll(memRow, memHint);

        // ---------- 版本更新 ----------
        box.getChildren().addAll(new Separator(), sectionLabel(L.t("s_ca95761a28")));

        Label verLabel = new Label(L.t("s_9578061aaf") + config.getLauncherVersion());
        verLabel.getStyleClass().add("text-secondary");

        Label verHint = new Label(
                L.t("s_94596bf211"));
        verHint.getStyleClass().add("text-muted");
        verHint.setWrapText(true);

        updateCheckButton = new Button(L.t("s_a6df38586d"));
        updateCheckButton.getStyleClass().add("button-primary");
        updateCheckButton.setOnAction(e -> onCheckUpdate());

        updateResultLabel = new Label("");
        updateResultLabel.setWrapText(true);

        HBox updateRow = new HBox(10, updateCheckButton, updateResultLabel);
        updateRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(verLabel, verHint, updateRow);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setPannable(true);
        installScrollTuning(scroll);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    // ---------- Java ----------

    private VBox buildJavaTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        box.getChildren().add(sectionLabel(L.t("s_a5355b797e")));

        javaStatusLabel = new Label(L.t("s_d3c8f2ef02"));
        javaStatusLabel.setWrapText(true);
        javaStatusLabel.getStyleClass().addAll("card", "java-status");
        javaStatusLabel.setPadding(new Insets(12));
        javaStatusLabel.setMaxWidth(Double.MAX_VALUE);

        Button redetect = new Button(L.t("s_7bdecac379"));
        redetect.getStyleClass().add("button");
        redetect.setOnAction(e -> detectJavaAsync());

        box.getChildren().addAll(javaStatusLabel, redetect, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_a9f015612a")));

        Label jl = new Label(L.t("s_1c7b455739"));
        jl.getStyleClass().add("text-secondary");

        javaCombo = new ComboBox<>();
        javaCombo.setMaxWidth(Double.MAX_VALUE);
        javaCombo.getItems().add(L.t("s_ea00a52d63"));
        javaCombo.getSelectionModel().select(0);

        String configured = config.getJavaPath();
        if (configured != null && !configured.isBlank()) {
            javaCombo.getItems().add(configured);
            javaCombo.getSelectionModel().select(configured);
        }

        Button browseJava = new Button(L.t("s_9bad30420b"));
        browseJava.getStyleClass().add("button");
        browseJava.setOnAction(e -> chooseJava());

        box.getChildren().addAll(jl, javaCombo, browseJava, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_b6ee98a995")));

        // ---- 版本选择 ----
        //
        // Minecraft 各代需要的 Java 版本不同：
        //   1.12 及以下 -> Java 8
        //   1.16.5     -> Java 8（也可用 11/16）
        //   1.17~1.20.4 -> Java 17
        //   1.20.5+    -> Java 21
        // 之前只能下「配置里那一个版本」，换成别的游戏版本就得手改配置，
        // 这里直接让用户选。
        HBox verRow = new HBox(10);
        verRow.setAlignment(Pos.CENTER_LEFT);

        Label verLabel = new Label(L.t("s_bdf645f4b5"));
        verLabel.getStyleClass().add("text-secondary");
        verLabel.setMinWidth(90);

        javaVersionCombo = new ComboBox<>();
        javaVersionCombo.getItems().addAll(
                L.t("s_f836d820f1"),
                "Java 17  （MC 1.17 ~ 1.20.4）",
                L.t("s_7a22a1d602"));
        // 默认选中配置里要求的版本，省得用户再选一次
        javaVersionCombo.getSelectionModel().select(versionIndexFor(config.getJavaVersion()));
        javaVersionCombo.setPrefWidth(280);

        verRow.getChildren().addAll(verLabel, javaVersionCombo);

        // ---- 已安装的启动器 Java ----
        installedJavaLabel = new Label(L.t("s_d3c8f2ef02"));
        installedJavaLabel.getStyleClass().add("text-muted");
        installedJavaLabel.setWrapText(true);
        installedJavaLabel.setMaxWidth(Double.MAX_VALUE);

        Button dlJava = new Button(L.t("s_ffa40ad820"));
        dlJava.getStyleClass().add("button-primary");
        dlJava.setOnAction(e -> downloadJava());

        Button openJavaSite = new Button(L.t("s_26a071f755"));
        openJavaSite.getStyleClass().add("button");
        openJavaSite.setOnAction(e ->
                com.mclauncher.utils.UrlOpener.open(JAVA_DOWNLOAD_SITE));

        HBox btnRow = new HBox(10);
        btnRow.getChildren().addAll(dlJava, openJavaSite);

        box.getChildren().addAll(verRow, installedJavaLabel, btnRow, new Separator());

        box.getChildren().add(sectionLabel(L.t("s_7b030318ec")));

        autoDownloadJavaCheck = new CheckBox(L.t("s_3df6c98acf"));
        autoDownloadJavaCheck.setSelected(config.isAutoDownloadJava());

        Label hint = new Label(
                L.t("s_b3d1b9c961"));
        hint.getStyleClass().add("text-muted");
        hint.setWrapText(true);

        box.getChildren().addAll(autoDownloadJavaCheck, hint);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setPannable(true);
        installScrollTuning(scroll);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    // ---------- 账号 ----------

    private VBox buildAccountTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        box.getChildren().add(sectionLabel(L.t("s_35442047f2")));

        Label desc = new Label(
                L.t("s_4cfdba3c44"));
        desc.getStyleClass().add("text-secondary");
        desc.setWrapText(true);

        Label cl = new Label("Azure Client ID");
        cl.getStyleClass().add("text-secondary");

        clientIdField = new TextField(config.getString("azureClientId", ""));
        clientIdField.setPromptText(L.t("s_c869b66f39"));
        clientIdField.setMaxWidth(Double.MAX_VALUE);

        Label steps = new Label(
                L.t("s_d38c78e29b")
                + L.t("s_005c61a4bd")
                + L.t("s_9f29ba800c"));
        steps.getStyleClass().add("text-muted");
        steps.setWrapText(true);

        box.getChildren().addAll(desc, cl, clientIdField, steps);

        ScrollPane scroll = new ScrollPane(box);
        scroll.setPannable(true);
        installScrollTuning(scroll);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    // ==================== 逻辑 ====================

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private void chooseDir(TextField field, String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);

        String current = field.getText();
        if (current != null && !current.isBlank()) {
            File f = new File(current);
            if (f.exists() && f.isDirectory()) {
                chooser.setInitialDirectory(f);
            }
        }

        File selected = chooser.showDialog(null);
        if (selected != null) {
            field.setText(selected.getAbsolutePath());
        }
    }

    private void chooseJava() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(L.t("s_94e8b52c57"));

        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                L.t("s_638752651a"), win ? "java.exe" : "java"));

        File f = chooser.showOpenDialog(null);
        if (f != null) {
            String path = f.getAbsolutePath();
            if (validateJava(path)) {
                if (!javaCombo.getItems().contains(path)) {
                    javaCombo.getItems().add(path);
                }
                javaCombo.getSelectionModel().select(path);
            } else {
                com.mclauncher.ui.component.MessageDialog.error(modalHost, L.t("s_e59e888c25"),
                        L.t("s_1af37ce51c"));
            }
        }
    }

    private boolean validateJava(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void detectJavaAsync() {
        if (javaStatusLabel == null) return;
        javaStatusLabel.setText(L.t("s_d3c8f2ef02"));

        CompletableFuture.supplyAsync(JavaDetector::detectJavaVersions)
                .thenAccept(list -> Platform.runLater(() -> {
                    if (list.isEmpty()) {
                        javaStatusLabel.setText(L.t("s_c4233d8d9f"));
                        return;
                    }

                    StringBuilder sb = new StringBuilder(L.t("s_f6d6f9d119") + list.size() + L.t("s_6ce56810b6"));
                    for (JavaDetector.JavaInfo info : list) {
                        sb.append("  · Java ").append(info.getMajorVersion())
                          .append("   ").append(info.getPath()).append("\n");

                        String exe = info.getJavaExecutable();
                        if (javaCombo != null && !javaCombo.getItems().contains(exe)) {
                            javaCombo.getItems().add(exe);
                        }
                    }
                    javaStatusLabel.setText(sb.toString().trim());
                }));
    }

    private void downloadJava() {
        // 用下拉框里选的版本（而不是配置里那个），
        // 这样用户能按游戏版本需要自由安装不同 Java
        final int javaVersion = selectedJavaVersion();

        ProgressPanel progress = new ProgressPanel(modalHost, javaVersion);
        progress.show();

        CompletableFuture.runAsync(() -> {
            try {
                JavaRuntimeManager mgr = new JavaRuntimeManager(launcherDir);
                Path exe;
                try {
                    exe = mgr.downloadJava(javaVersion, new JavaRuntimeManager.DownloadCallback() {
                        public void onStatus(String s) {
                            progress.setStatus(s);
                        }
                        public void onProgress(double percent, long done, long total) {
                            progress.setProgress(percent / 100.0,
                                    String.format("%.1f%%  %s / %s", percent,
                                            FileUtils.formatFileSizeShort(done),
                                            FileUtils.formatFileSizeShort(total)));
                        }
                    });
                } finally {
                    // 关闭线程池/连接池，避免每次下载泄漏约 20 个非守护线程
                    mgr.shutdown();
                }

                progress.finish(L.t("s_2f2b6bd0b2") + exe);
                if (!javaCombo.getItems().contains(exe.toString())) {
                    javaCombo.getItems().add(exe.toString());
                }
                javaCombo.getSelectionModel().select(exe.toString());
                detectJavaAsync();
                refreshInstalledJava();

            } catch (Exception e) {
                logger.error("下载 Java 失败", e);

                // 先收起进度面板，再弹带链接的提示 ——
                // 否则提示会被进度面板盖住
                progress.finishWithError(L.t("s_9767bac218") + e.getMessage());

                Platform.runLater(() -> {
                    // 关掉进度面板（它是不可关闭的，需要显式收起）
                    if (modalHost != null) {
                        modalHost.close();
                    }

                    com.mclauncher.ui.component.MessageDialog.infoWithLink(
                            modalHost,
                            L.t("s_7a970893d6"),
                            L.t("s_c87d21cd23") + javaVersion + L.t("s_d491439d1d") + e.getMessage() + "\n\n"
                                    + "你可以到 Java 官网手动下载并安装，"
                                    + "装好后在「使用的 Java」里手动指定路径即可。\n\n"
                                    + "建议选择 Temurin 的 JRE、Windows x64 版本。",
                            L.t("s_ba37e21877"),
                            JAVA_DOWNLOAD_SITE);
                });
            }
        });
    }

    /** Java 官方下载页（下载失败时引导用户自行下载） */
    private static final String JAVA_DOWNLOAD_SITE =
            "https://adoptium.net/zh-CN/temurin/releases/?os=windows&arch=x64&package=jre";

    /** 下拉框里可安装的 Java 主版本（与选项顺序一一对应） */
    private static final int[] JAVA_CHOICES = {8, 17, 21};

    /** 把配置里的版本号映射到下拉框下标 */
    private int versionIndexFor(String version) {
        if (version != null) {
            try {
                int v = Integer.parseInt(version.trim());
                for (int i = 0; i < JAVA_CHOICES.length; i++) {
                    if (JAVA_CHOICES[i] == v) {
                        return i;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;   // 默认 Java 17
    }

    /** 取下拉框当前选中的主版本号 */
    private int selectedJavaVersion() {
        if (javaVersionCombo == null) {
            return 17;
        }
        int idx = javaVersionCombo.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= JAVA_CHOICES.length) {
            return 17;
        }
        return JAVA_CHOICES[idx];
    }

    /**
     * 刷新「启动器已安装的 Java」提示
     */
    private void refreshInstalledJava() {
        if (installedJavaLabel == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            JavaRuntimeManager mgr = new JavaRuntimeManager(launcherDir);
            try {
                List<JavaDetector.JavaInfo> all = mgr.detectInstalledJava();
                StringBuilder sb = new StringBuilder();
                for (JavaDetector.JavaInfo info : all) {
                    // 只列启动器自己装的（路径在 java 目录下）
                    if (info.getPath() != null
                            && info.getPath().startsWith(launcherDir.toString())) {
                        if (sb.length() > 0) {
                            sb.append("、");
                        }
                        sb.append("Java ").append(info.getMajorVersion());
                    }
                }
                return sb.length() == 0
                        ? L.t("s_2dfa288630")
                        : L.t("s_01737e761a") + sb;
            } catch (Exception e) {
                return L.t("s_32a3550b59") + e.getMessage();
            } finally {
                mgr.shutdown();
            }
        }).thenAccept(text -> Platform.runLater(
                () -> installedJavaLabel.setText(text)));
    }

    private void resetSettings() {
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_30a6079ce3"),
                L.t("s_7d399484ae"),
                null,
                L.t("s_0fc58d4f88"),
                L.t("s_3d81345303"),
                () -> {
                    config.reset();
                    // 重建界面以反映默认值
                    root = buildUI();
                    onShow();
                });
    }

    private void saveSettings() {
        String min = memoryMinField.getText().trim();
        String max = memoryMaxField.getText().trim();

        if (!min.matches("\\d+[GgMmKk]?")) {
            showMsg(L.t("s_1b2e005571"));
            return;
        }
        if (!max.matches("\\d+[GgMmKk]?")) {
            showMsg(L.t("s_acbe66db59"));
            return;
        }

        config.setString("downloadDir", downloadDirField.getText().trim());
        config.setString("memoryMin", min);
        config.setString("memoryMax", max);
        config.setSourceType(sourceCombo.getSelectionModel().getSelectedIndex() == 0
                ? "official" : "bmcl");

        String javaSel = javaCombo.getSelectionModel().getSelectedItem();
        if (javaSel == null || javaSel.startsWith(L.t("s_00ab1b8858"))) {
            config.setJavaPath("");
            config.setBoolean("javaAutoDetect", true);
        } else {
            config.setJavaPath(javaSel);
        }

        config.setAutoDownloadJava(autoDownloadJavaCheck.isSelected());
        config.setString("azureClientId", clientIdField.getText().trim());

        config.forceSave();
        showMsg(L.t("s_cfa1bb48b5"));
    }

    private void showMsg(String msg) {
        com.mclauncher.ui.component.MessageDialog.info(modalHost, L.t("s_ab3656a956"), msg);
    }

    /**
     * 内嵌式进度面板（用于 Java 下载）
     *
     * 原来是独立 Stage，改成挂在 ModalHost 上，与其它弹窗统一样式，
     * 也自动跟随主窗口与主题。
     *
     * 下载中不允许关闭（closable=false），只能等它跑完自动收起；
     * 若下载失败，会在面板上显示错误并给出「关闭」按钮。
     */
    private static class ProgressPanel {
        private final com.mclauncher.ui.component.ModalHost host;
        private final int javaVersion;
        private ProgressBar bar;
        private Label label;
        private Button closeBtn;

        ProgressPanel(com.mclauncher.ui.component.ModalHost host, int javaVersion) {
            this.host = host;
            this.javaVersion = javaVersion;
        }

        void show() {
            Platform.runLater(() -> {
                VBox box = new VBox(12);
                box.setPadding(new Insets(20, 20, 18, 20));
                box.setPrefWidth(520);

                Label title = new Label(L.t("s_806027bfdd") + javaVersion);
                title.getStyleClass().add("card-title");

                bar = new ProgressBar(0);
                bar.setMaxWidth(Double.MAX_VALUE);

                label = new Label(L.t("s_abb2d4c496"));
                label.getStyleClass().add("text-secondary");
                label.setWrapText(true);
                label.setMaxWidth(Double.MAX_VALUE);

                HBox footer = new HBox(10);
                footer.setAlignment(Pos.CENTER_RIGHT);
                closeBtn = new Button(L.t("s_6c14bd7f6f"));
                closeBtn.getStyleClass().addAll("button", "button-primary");
                closeBtn.setVisible(false);
                closeBtn.setManaged(false);
                closeBtn.setOnAction(e -> host.close());
                footer.getChildren().add(closeBtn);

                box.getChildren().addAll(title, bar, label, footer);

                // closable=false：下载途中不能点 ✕ 或遮罩关掉，
                // 避免用户以为「关掉就等于取消」而实际下载还在跑
                host.show(L.t("s_732c2382fe") + javaVersion, box, null, false);
            });
        }

        void setStatus(String s) {
            Platform.runLater(() -> {
                if (label != null) label.setText(s);
            });
        }

        void setProgress(double p, String text) {
            Platform.runLater(() -> {
                if (bar != null) bar.setProgress(p);
                if (label != null) label.setText(text);
            });
        }

        /** 成功结束：显示提示，2 秒后自动收起 */
        void finish(String text) {
            Platform.runLater(() -> {
                if (bar != null) bar.setProgress(1.0);
                if (label != null) label.setText(text);

                javafx.animation.PauseTransition delay =
                        new javafx.animation.PauseTransition(
                                javafx.util.Duration.seconds(2));
                delay.setOnFinished(e -> host.close());
                delay.play();
            });
        }

        /** 失败结束：不自动关闭，等用户点「关闭」 */
        void finishWithError(String text) {
            Platform.runLater(() -> {
                if (bar != null) bar.setProgress(0);
                if (label != null) label.setText(text);
                if (closeBtn != null) {
                    closeBtn.setVisible(true);
                    closeBtn.setManaged(true);
                }
                // 失败时允许用户自行关闭（包括点遮罩 / ESC）
                host.setClosable(true);
            });
        }
    }
}