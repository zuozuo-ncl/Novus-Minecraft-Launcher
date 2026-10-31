package com.mclauncher.ui;

import com.mclauncher.i18n.L;
import com.mclauncher.auth.Account;
import com.mclauncher.auth.AccountManager;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.config.GameDirectory;
import com.mclauncher.launcher.DependencyBootstrap;
import com.mclauncher.launcher.GameLauncher;
import com.mclauncher.network.BMCLApiClient;
import com.mclauncher.ui.page.AccountPage;
import com.mclauncher.ui.page.DownloadPage;
import com.mclauncher.ui.page.GameSettingsPage;
import com.mclauncher.ui.page.HomePage;
import com.mclauncher.ui.page.LauncherSettingsPage;
import com.mclauncher.ui.page.MultiplayerPage;
import com.mclauncher.ui.page.Page;
import com.mclauncher.ui.window.DirectoryManagerWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MainWindow {

    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);

    private Stage stage;
    private Scene scene;

    private ConfigManager config;
    private AccountManager accountManager;
    private BMCLApiClient apiClient;
    private GameLauncher gameLauncher;
    private DependencyBootstrap bootstrap;

    private StackPane contentHost;

    private com.mclauncher.ui.component.ModalHost modalHost;
    private Label statusLabel;
    private Label accountLabel;
    private VBox sidebarBox;

    private com.mclauncher.ui.component.SkinAvatar sidebarAvatar;
    private Label sidebarAccountName;
    private Label currentDirNameLabel;

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private String currentPageKey;

    public void show(Stage stage) {
        this.stage = stage;

        initCore();

        BorderPane root = buildUI();

        applyLanguage();

        scene = new Scene(root, config.getWindowWidth(), config.getWindowHeight());

        ThemeManager.applyFromConfig(scene, config);

        stage.setTitle(L.t("app.title") + " v" + config.getLauncherVersion());

        com.mclauncher.ui.AppIcon.apply(stage);
        stage.setScene(scene);

        
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        stage.setOnCloseRequest(e -> onClose());

        stage.show();

        switchPage("home");

        
        com.mclauncher.auth.SkinFetcher.warmUpAsync();

        

        com.mclauncher.mod.McmodIntroMap.prewarmAsync();

        runBootstrap();

        stage.widthProperty().addListener((o, ov, nv) ->
                config.setWindowSize(nv.intValue(), (int) stage.getHeight()));
    }

    private void applyLanguage() {
        String saved = config.getString("language", "");
        com.mclauncher.i18n.L.Lang lang;
        if (saved == null || saved.isBlank()) {
            lang = com.mclauncher.i18n.L.detectSystemLanguage();
            config.setString("language", lang.code);
        } else {
            lang = com.mclauncher.i18n.L.Lang.fromCode(saved);
        }
        com.mclauncher.i18n.L.setLangQuiet(lang);
        logger.info("界面语言: {}", lang.displayName);
    }

    public void rebuildForLanguage() {
        if (stage == null || scene == null) {
            return;
        }
        BorderPane fresh = buildUI();
        scene.setRoot(fresh);
        stage.setTitle(L.t("app.title") + " v" + config.getLauncherVersion());

        pages.clear();
        currentPageKey = null;
        navButtons.clear();
        switchPage("home");
        updateAccountLabel();
    }

    private void initCore() {
        config = new ConfigManager();
        accountManager = new AccountManager();
        apiClient = new BMCLApiClient("bmcl".equals(config.getSourceType()));

        Path launcherDir = com.mclauncher.utils.AppPaths.appDataDir();
        bootstrap = new DependencyBootstrap(config);
        gameLauncher = new GameLauncher(config, launcherDir);

        String last = config.getLastAccountId();
        if (last != null && !last.isEmpty()) {
            accountManager.setCurrentAccount(last);
        }
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("main-root");

        root.setLeft(buildSidebar());

        contentHost = new StackPane();
        contentHost.getStyleClass().add("bg-main");

        modalHost = new com.mclauncher.ui.component.ModalHost();

        StackPane centerStack = new StackPane(contentHost, modalHost.getNode());

        
        centerStack.getStyleClass().add("bg-main");
        root.setCenter(centerStack);

        root.setBottom(buildStatusBar());

        return root;
    }

    public com.mclauncher.ui.component.ModalHost getModalHost() {
        return modalHost;
    }

    public Scene getScene() {
        return scene;
    }

    private VBox buildSidebar() {
        sidebarBox = new VBox(8);
        sidebarBox.getStyleClass().add("sidebar");

        

        Label title = new Label("NCL");
        title.getStyleClass().add("sidebar-title");

        VBox navBox = new VBox(8);
        navBox.setFillWidth(true);

        
        addNavButton(navBox, "home", L.t("s_32b5f63ebd"));
        addNavButton(navBox, "download", L.t("s_2b9d013177"));
        addNavButton(navBox, "multiplayer", L.t("multiplayer.title"));
        addNavButton(navBox, "settings", L.t("s_da75bf5957"));
        addNavButton(navBox, "launcher", L.t("s_f45a835ad0"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label version = new Label("v" + config.getLauncherVersion());
        version.getStyleClass().add("text-muted");
        version.setPadding(new Insets(8, 0, 0, 8));

        sidebarBox.getChildren().addAll(title, navBox, spacer,
                buildSidebarAccountRow(), buildCompactDirectoryRow(), version);

        return sidebarBox;
    }

    private HBox buildSidebarAccountRow() {
        sidebarAvatar = com.mclauncher.ui.component.SkinAvatar.circle(28);
        sidebarAccountName = new Label(L.t("s_e8041a8c93"));
        sidebarAccountName.getStyleClass().add("sidebar-account-name");

        HBox row = new HBox(10, sidebarAvatar.getNode(), sidebarAccountName);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 8, 8, 8));
        row.getStyleClass().add("dir-card");     
        row.setOnMouseClicked(e -> switchPage("account"));
        row.setCursor(javafx.scene.Cursor.HAND);

        updateSidebarAccount();
        return row;
    }

    private void updateSidebarAccount() {
        if (sidebarAvatar == null) {
            return;
        }
        Account a = accountManager.getCurrentAccount();
        sidebarAvatar.bind(a);
        sidebarAccountName.setText(a != null ? a.getUsername() : L.t("s_475a0b4c23"));
    }

    private VBox buildCompactDirectoryRow() {
        VBox box = new VBox(4);
        box.setPadding(new Insets(0, 0, 8, 0));

        Label caption = new Label(L.t("s_4f42f5c798"));
        caption.getStyleClass().add("sidebar-caption");

        HBox row = new HBox(8);
        row.getStyleClass().add("dir-card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> openDirectoryManager());

        Label nameLabel = new Label();
        nameLabel.getStyleClass().add("dir-card-name");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label editIcon = new Label("⚙");
        editIcon.getStyleClass().add("dir-edit-icon");

        row.getChildren().addAll(nameLabel, editIcon);

        box.getChildren().addAll(caption, row);

        this.currentDirNameLabel = nameLabel;
        updateDirectoryRow();
        return box;
    }

    private void updateDirectoryRow() {
        if (currentDirNameLabel == null) {
            return;
        }

        GameDirectory active = config.getActiveDirectory();
        if (active == null) {
            currentDirNameLabel.setText(L.t("s_456f0714c1"));
            currentDirNameLabel.setTooltip(null);
            currentDirNameLabel.getStyleClass().add("status-warn");
        } else {
            currentDirNameLabel.setText(active.getDisplayName());
            currentDirNameLabel.setTooltip(
                    new Tooltip(active.getRootPathString()));
            currentDirNameLabel.setStyle("");
        }
    }

    private void openDirectoryManager() {
        DirectoryManagerWindow window = new DirectoryManagerWindow(
                modalHost, config, msg -> {
                    setStatus(msg);

                    updateDirectoryRow();

                    Page p = pages.get(currentPageKey);
                    if (p != null) {
                        try {
                            p.onShow();
                        } catch (Exception ex) {
                            logger.debug("刷新页面失败: {}", ex.getMessage());
                        }
                    }
                });
        window.show();
    }

    private void addNavButton(VBox container, String key, String text) {
        Button btn = new Button(text);
        btn.getStyleClass().add("nav-button");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> switchPage(key));

        navButtons.put(key, btn);
        container.getChildren().add(btn);
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox(16);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label(L.t("s_b796f2d4ca"));
        statusLabel.getStyleClass().add("status-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        accountLabel = new Label(L.t("s_e8041a8c93"));
        accountLabel.getStyleClass().add("status-text");

        Label source = new Label("bmcl".equals(config.getSourceType())
                ? L.t("s_2920a7f87d")
                : L.t("s_0e9ee7bfaf"));
        source.getStyleClass().add("status-text");

        bar.getChildren().addAll(statusLabel, spacer, accountLabel, source);

        updateAccountLabel();
        return bar;
    }

    private void switchPage(String key) {
        if (key.equals(currentPageKey)) {
            return;
        }

        Page old = pages.get(currentPageKey);
        if (old != null) {
            try {
                old.onHide();
            } catch (Exception e) {
                logger.debug("页面 onHide 异常: {}", e.getMessage());
            }
        }

        Page page = pages.computeIfAbsent(key, this::createPage);
        if (page == null) {
            return;
        }

        navButtons.forEach((k, btn) -> {
            btn.getStyleClass().remove("nav-button-active");
            if (k.equals(key)) {
                btn.getStyleClass().add("nav-button-active");
            }
        });

        contentHost.getChildren().setAll(page.getContent());
        currentPageKey = key;

        
        if (modalHost != null && modalHost.isShowing()) {
            modalHost.bringToFront();
        }

        updateAccountLabel();

        try {
            page.onShow();
        } catch (Exception e) {
            logger.error("页面 onShow 异常", e);
        }

        setStatus(page.getTitle());
    }

    private Page createPage(String key) {
        return switch (key) {
            case "home" -> {
                HomePage p = new HomePage(config, accountManager, gameLauncher,
                        this::setStatus, null);

                p.setModalHost(modalHost);
                yield p;
            }

            case "download" -> {
                DownloadPage p = new DownloadPage(config, apiClient, this::setStatus);

                p.setModalHost(modalHost);
                yield p;
            }

            case "multiplayer" -> {
                MultiplayerPage p = new MultiplayerPage(config, this::setStatus);
                p.setModalHost(modalHost);
                yield p;
            }

            case "settings" -> {
                GameSettingsPage p = new GameSettingsPage(config, apiClient, this::setStatus);

                p.setModalHost(modalHost);
                yield p;
            }

            case "account" -> {
                AccountPage p = new AccountPage(accountManager, config, this::setStatus);

                p.setModalHost(modalHost);
                yield p;
            }

            case "launcher" -> {

                Path launcherDir = com.mclauncher.utils.AppPaths.appDataDir();
                LauncherSettingsPage p = new LauncherSettingsPage(config, launcherDir);

                p.setModalHost(modalHost);

                p.setHostScene(scene);

                p.setLanguageChangeListener(this::rebuildForLanguage);
                yield p;
            }

            default -> null;
        };
    }

    public void refreshAccount() {
        updateAccountLabel();
    }

    private void updateAccountLabel() {
        if (accountLabel == null) {
            return;
        }
        Account a = accountManager.getCurrentAccount();
        accountLabel.setText(a != null ? "👤 " + a.getUsername() : L.t("s_e8041a8c93"));
        updateSidebarAccount();
    }

    private void runBootstrap() {
        bootstrap.setStatusCallback(s -> Platform.runLater(() -> setStatus(s)));

        CompletableFuture.runAsync(() -> {
            boolean ok = bootstrap.bootstrap();
            Platform.runLater(() -> setStatus(ok ? L.t("s_b796f2d4ca") : L.t("s_e2d656f9f7")));
        });
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }

    private void onClose() {
        logger.info("启动器关闭中...");

        com.mclauncher.ui.page.Page mp = pages.get("multiplayer");
        if (mp instanceof com.mclauncher.ui.page.MultiplayerPage m) {
            m.shutdown();
        }
        config.forceSave();

        if (gameLauncher.isRunning()) {
            com.mclauncher.ui.component.MessageDialog.confirmOrCancel(
                    modalHost,
                    L.t("s_cf5e5123a5"),
                    L.t("s_4860bb67bd"),
                    L.t("s_a70be374e3"),
                    L.t("s_f20a6f7516"),
                    L.t("s_d19a71eebb"),
                    () -> gameLauncher.killProcess(),
                    null);
        }

        try {
            bootstrap.shutdown();
        } catch (Exception e) {
            logger.debug("关闭引导器失败: {}", e.getMessage());
        }
    }
}
