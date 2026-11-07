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

/** 主窗口 */
public class MainWindow {

    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);

    private Stage stage;
    private Scene scene;

    // 核心组件
    private ConfigManager config;
    private AccountManager accountManager;
    private BMCLApiClient apiClient;
    private GameLauncher gameLauncher;
    private DependencyBootstrap bootstrap;

    // UI
    private StackPane contentHost;
    /** 内嵌弹窗宿主 */
    private com.mclauncher.ui.component.ModalHost modalHost;
    private Label statusLabel;
    private Label accountLabel;
    private VBox sidebarBox;

    /** 侧边栏当前目录名（紧凑行） */
    private com.mclauncher.ui.component.SkinAvatar sidebarAvatar;
    private Label sidebarAccountName;
    private Label currentDirNameLabel;
    // 页面与导航
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private String currentPageKey;

    public void show(Stage stage) {
        this.stage = stage;

        initCore();

        BorderPane root = buildUI();

        // 应用保存的语言设置（默认：中文用户用中文，其它用英文）
        applyLanguage();

        scene = new Scene(root, config.getWindowWidth(), config.getWindowHeight());
        // 按配置应用主题（深色 / 浅色）
        ThemeManager.applyFromConfig(scene, config);

        stage.setTitle(L.t("app.title") + " v" + config.getLauncherVersion());
        // 窗口/任务栏图标（resources/ui/icon-*.png）
        com.mclauncher.ui.AppIcon.apply(stage);
        stage.setScene(scene);
        // 最小尺寸要小于默认尺寸，否则用户拖不小窗口。
        // 早期 min(980x640) 与默认(1024x768)太接近，几乎没有缩小余地。
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        stage.setOnCloseRequest(e -> {
            // 游戏在跑时不能直接放行关闭：确认框是异步的内嵌面板，
            // 处理函数一返回 Stage 就关了，用户根本来不及选。
            // 所以先吃掉这次事件，等用户在面板里做出选择再手动 stage.close()。
            if (gameLauncher != null && gameLauncher.isRunning() && !closingConfirmed) {
                e.consume();
                askCloseWithGameRunning();
                return;
            }
            onClose();
        });

        stage.show();

        // 默认进入主页
        switchPage("home");

        // 后台预热皮肤站连接：进程内第一条 HTTPS 请求有 ~1.5s 的
        // JSSE 初始化开销，提前做掉，玩家切到账号页时头像能秒出。
        com.mclauncher.auth.SkinFetcher.warmUpAsync();

        // 启动时就预热模组中文简介表。
        // 它之前只在打开「下载」页时才加载，导致第一次进下载页要等
        // 3 秒下载 + 1 秒解析；提前跑的话，玩家点进下载页时通常已经就绪。
        com.mclauncher.mod.McmodIntroMap.prewarmAsync();

        runBootstrap();

        // 窗口尺寸变化时记录
        stage.widthProperty().addListener((o, ov, nv) ->
                config.setWindowSize(nv.intValue(), (int) stage.getHeight()));
    }

    /**
     * 应用界面语言
     *
     * 配置里没写过语言时按系统区域猜一次并记住，之后以配置为准。
     */
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

    /**
     * 语言切换后重建常驻界面
     * 侧边栏、状态栏、窗口标题都是构建时取文案的，切换语言必须重建。
     */
    public void rebuildForLanguage() {
        if (stage == null || scene == null) {
            return;
        }
        BorderPane fresh = buildUI();
        scene.setRoot(fresh);
        stage.setTitle(L.t("app.title") + " v" + config.getLauncherVersion());

        // 丢弃缓存的页面实例：它们内部全是旧语言的控件
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
        // 启动器数据目录：与 C++ 引导器保持一致（%APPDATA%\NCL_Launcher）
        Path launcherDir = com.mclauncher.utils.AppPaths.appDataDir();
        bootstrap = new DependencyBootstrap(config);
        gameLauncher = new GameLauncher(config, launcherDir);

        // VPN 联机：游戏进程接到的 VPN 玩家连接交给当前 VPN 会话配对
        // 游戏进程接住 VPN 玩家后：把预读字节写回连接，再交给当前 VPN 房主会话配对
        gameLauncher.setPlayerJoinConsumer((id, head, peerSock, srcIp, srcPort) -> {
            // peerSock 已经连到本机 MC；先把 MC 握手/login 的预读字节写回去
            try {
                if (head != null && head.length > 0) {
                    peerSock.getOutputStream().write(head);
                    peerSock.getOutputStream().flush();
                }
            } catch (java.io.IOException e) {
                logger.debug("回放 VPN 预读字节失败: {}", e.getMessage());
            }
            com.mclauncher.multiplayer.vpn.VpnSession host =
                    com.mclauncher.multiplayer.vpn.VpnSession.activeHost();
            if (host == null) {
                try { peerSock.close(); } catch (java.io.IOException ignored) { }
                return;
            }
            // 按「来源虚拟 IP:源端口」登记：之后这位玩家发往 10.77.0.1 的字节
            // 只会写进这一条连接，不会和其他玩家的流混在一起
            host.registerGatewayConn(srcIp, srcPort, peerSock);
        });
        gameLauncher.setVpnForwardingGate(() ->
                com.mclauncher.multiplayer.vpn.VpnSession.activeHost() != null);

        String last = config.getLastAccountId();
        if (last != null && !last.isEmpty()) {
            accountManager.setCurrentAccount(last);
        }
    }

    // ==================== UI ====================

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("main-root");

        root.setLeft(buildSidebar());

        contentHost = new StackPane();
        contentHost.getStyleClass().add("bg-main");

        // 内嵌弹窗层：叠在页面内容之上，随主窗口一起移动
        modalHost = new com.mclauncher.ui.component.ModalHost();

        StackPane centerStack = new StackPane(contentHost, modalHost.getNode());
        // 关键：这一层也必须带背景样式。
        // 之前漏了，导致切换到某些页面时外层露出默认白色背景。
        centerStack.getStyleClass().add("bg-main");
        root.setCenter(centerStack);

        root.setBottom(buildStatusBar());

        return root;
    }

    /**
     * 获取内嵌弹窗宿主（供各页面弹出面板）
     */
    public com.mclauncher.ui.component.ModalHost getModalHost() {
        return modalHost;
    }

    /**
     * 获取主场景（供设置页即时切换主题）
     */
    public Scene getScene() {
        return scene;
    }

    /**
     * 侧边栏：标题 + 导航按钮 + 底部信息
     */
    private VBox buildSidebar() {
        sidebarBox = new VBox(8);
        sidebarBox.getStyleClass().add("sidebar");

        // 侧边栏只有 220px 宽，「Novus Minecraft Launcher」在这个字号下放不下，
        // 会被截断。缩写 NCL 简短且与程序内的简称一致。
        // 完整名称在窗口标题栏和「设置 → 详情」里都有。
        Label title = new Label("NCL");
        title.getStyleClass().add("sidebar-title");

        // 导航按钮容器
        VBox navBox = new VBox(8);
        navBox.setFillWidth(true);

        // 账号入口只在底部那一行（带头像和用户名）—— 导航里再放一个「账号」
        // 会重复，而且两处样式不同容易让人以为是两个功能。
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

        // 目录管理：放在导航之后、底部版本号之前，紧凑一行
        sidebarBox.getChildren().addAll(title, navBox, spacer,
                buildSidebarAccountRow(), buildCompactDirectoryRow(), version);

        return sidebarBox;
    }

    /** 侧边栏底部的账号行（头像 + 名字，点击进账号页） */
    private HBox buildSidebarAccountRow() {
        sidebarAvatar = com.mclauncher.ui.component.SkinAvatar.circle(28);
        sidebarAccountName = new Label(L.t("s_e8041a8c93"));
        sidebarAccountName.getStyleClass().add("sidebar-account-name");

        HBox row = new HBox(10, sidebarAvatar.getNode(), sidebarAccountName);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 8, 8, 8));
        row.getStyleClass().add("dir-card");     // 复用卡片外观
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

    /** 侧边栏的目录管理行（紧凑版） */
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

    /**
     * 刷新侧边栏的当前目录显示（单行文本 + tooltip）
     */
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

    /**
     * 打开目录管理窗口
     */
    private void openDirectoryManager() {
        DirectoryManagerWindow window = new DirectoryManagerWindow(
                modalHost, config, msg -> {
                    setStatus(msg);
                    // 目录变化后刷新侧边栏与当前页面
                    updateDirectoryRow();
                    // 刷新当前页面（下载/游戏设置页依赖游戏目录）
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

    /**
     * 底部状态栏
     */
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

        // BMCLAPI 协议要求：标明资源来源
        Label source = new Label("bmcl".equals(config.getSourceType())
                ? L.t("s_2920a7f87d")
                : L.t("s_0e9ee7bfaf"));
        source.getStyleClass().add("status-text");

        bar.getChildren().addAll(statusLabel, spacer, accountLabel, source);

        updateAccountLabel();
        return bar;
    }

    // ==================== 页面切换 ====================

    /**
     * 切换页面：懒加载并缓存页面实例
     */
    private void switchPage(String key) {
        if (key.equals(currentPageKey)) {
            return;
        }

        // 通知旧页面离开
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

        // 更新导航按钮选中态
        navButtons.forEach((k, btn) -> {
            btn.getStyleClass().remove("nav-button-active");
            if (k.equals(key)) {
                btn.getStyleClass().add("nav-button-active");
            }
        });

        contentHost.getChildren().setAll(page.getContent());
        currentPageKey = key;

        // 关键：换了页面内容后，必须把弹窗遮罩重新提到最前。
        // 之前漏了这一步，切页后遮罩会被新页面盖住 —— 看起来像界面卡死。
        if (modalHost != null && modalHost.isShowing()) {
            modalHost.bringToFront();
        }

        // 每次切页都刷新顶栏账号 —— 用户可能刚在账号页切换了账号
        updateAccountLabel();

        try {
            page.onShow();
        } catch (Exception e) {
            logger.error("页面 onShow 异常", e);
        }

        setStatus(page.getTitle());
    }

    /**
     * 创建页面实例
     */
    private Page createPage(String key) {
        return switch (key) {
            case "home" -> {
                HomePage p = new HomePage(config, accountManager, gameLauncher,
                        this::setStatus, null);
                // 注入弹窗宿主，使其能弹出「发现新版本」面板
                p.setModalHost(modalHost);
                yield p;
            }

            case "download" -> {
                DownloadPage p = new DownloadPage(config, apiClient, this::setStatus);
                // 注入内嵌弹窗宿主，供版本选择/进度面板使用
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
                // 注入内嵌弹窗宿主：确认删除/卸载、错误提示都用它
                p.setModalHost(modalHost);
                yield p;
            }

            case "account" -> {
                AccountPage p = new AccountPage(accountManager, config, this::setStatus);
                // 注入内嵌弹窗宿主：删除账号确认用
                p.setModalHost(modalHost);
                yield p;
            }

            case "launcher" -> {
                // 使用与 C++ 引导器相同的数据目录：%APPDATA%\NCL_Launcher
                Path launcherDir = com.mclauncher.utils.AppPaths.appDataDir();
                LauncherSettingsPage p = new LauncherSettingsPage(config, launcherDir);
                p.setGameLauncher(gameLauncher);
                // 注入内嵌弹窗宿主，使其能弹出目录管理面板
                p.setModalHost(modalHost);
                // 注入主场景，使「外观」标签页能即时切换主题
                p.setHostScene(scene);
                // 切换语言后重建主窗口（侧边栏/状态栏/页面缓存）
                p.setLanguageChangeListener(this::rebuildForLanguage);
                yield p;
            }

            default -> null;
        };
    }

    /**
     * 外部可调用：刷新账号显示
     */
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

    // ==================== 生命周期 ====================

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

    /** 关闭流程只跑一次（确认框回调里会再次调用 onClose） */
    private volatile boolean closingConfirmed;
    private boolean closeAsked;

    /**
     * 游戏运行中点击关闭：弹确认面板，用户选择后再真正关闭。
     * 面板回调里不能直接 stage.close() 再走一遍 setOnCloseRequest —— 那时
     */
    private void askCloseWithGameRunning() {
        if (closeAsked) {
            return;
        }
        closeAsked = true;
        com.mclauncher.ui.component.MessageDialog.confirmOrCancel(
                modalHost,
                L.t("s_cf5e5123a5"),
                L.t("s_4860bb67bd"),
                L.t("s_a70be374e3"),
                L.t("s_f20a6f7516"),
                L.t("s_d19a71eebb"),
                () -> {
                    // 用户选择关闭：先结束游戏，再按已确认的路径关窗口
                    gameLauncher.killProcess();
                    onClose();          // 直接走收尾（不再经 close 事件，避免二次询问）
                    stage.close();      // closingConfirmed 已置位，事件处理会直接放行
                },
                () -> {
                    // 取消：什么都不做，窗口保持打开
                    closeAsked = false;
                });
    }

    private void onClose() {
        if (closingConfirmed) {
            return;
        }
        closingConfirmed = true;
        logger.info("启动器关闭中...");
        // 联机隧道要在退出时主动断开，否则中转要等心跳超时（45 秒）才回收房间
        com.mclauncher.ui.page.Page mp = pages.get("multiplayer");
        if (mp instanceof com.mclauncher.ui.page.MultiplayerPage m) {
            m.shutdown();
        }
        // 其它页面持有的下载器 / 网络客户端也统一释放（Page.shutdown 默认空实现）
        if (pages != null) {
            for (com.mclauncher.ui.page.Page p : pages.values()) {
                if (p == mp) {
                    continue;
                }
                try {
                    p.shutdown();
                } catch (Exception e) {
                    logger.debug("关闭页面资源失败: {}", e.getMessage());
                }
            }
        }
        config.forceSave();

        try {
            bootstrap.shutdown();
        } catch (Exception e) {
            logger.debug("关闭引导器失败: {}", e.getMessage());
        }
    }
}