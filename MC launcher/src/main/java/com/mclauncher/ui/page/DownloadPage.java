package com.mclauncher.ui.page;


import com.mclauncher.i18n.L;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.game.GameDownloader;
import com.mclauncher.game.ModLoaderInstaller;
import com.mclauncher.mod.ModDownloader;
import com.mclauncher.mod.McmodIntroMap;
import com.mclauncher.mod.ModrinthClient;
import com.mclauncher.network.BMCLApiClient;
import com.mclauncher.ui.component.VersionListPane;
import com.mclauncher.ui.window.DownloadProgressWindow;
import com.mclauncher.ui.window.ModVersionChooserWindow;
import javafx.stage.Stage;
import com.mclauncher.utils.FileUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 下载页
 *
 * 两个子标签：
 * - 游戏下载：选择版本下载（含详细进度：当前文件/速度/剩余）
 * - 模组下载：Modrinth 搜索 → 选版本 → 下载
 */
public class DownloadPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(DownloadPage.class);

    private final ConfigManager config;
    private final BMCLApiClient apiClient;
    private final java.util.function.Consumer<String> statusConsumer;

    /** 模组图标缓存：URL -> Image，避免列表滚动时重复下载 */
    private static final java.util.Map<String, javafx.scene.image.Image> ICON_CACHE
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** 正在下载中的图标 URL（防止重复发起请求） */
    private static final java.util.Set<String> LOADING_ICONS
            = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 正在下载的图标 -> 等它完成的单元格回调
     *
     * 单元格不会一直占着下载线程：下载中的图标挂一个回调，
     * 下载完统一回填所有等它的格子。
     */
    /**
     * 图标下载线程池
     *
     * 串行下载 20 张要 73 秒；并发 6 路可以压到十几秒。
     * 上限 6 是为了避免触发 Modrinth 限流。
     */
    private static final java.util.concurrent.ExecutorService ICON_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "icon-loader");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.Map<String, java.util.List<Runnable>> ICON_WAITERS
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** 已确认加载失败的图标 URL（避免反复重试） */
    private static final java.util.Set<String> FAILED_ICONS
            = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BorderPane root;
    private TabPane tabPane;

    /** 内嵌弹窗宿主（由 MainWindow 注入） */
    private com.mclauncher.ui.component.ModalHost modalHost;

    // ---------- 游戏下载 ----------
    // 版本列表（带搜索与类型过滤，替代原来的 ComboBox）
    private com.mclauncher.ui.component.VersionListPane versionPane;

    // 加载器（可选，随游戏一起安装）
    private CheckBox withLoaderCheck;
    private ComboBox<String> loaderTypeCombo;
    private ComboBox<String> loaderVersionCombo;
    private Label loaderHintLabel;
    /** 防止重复触发加载器版本查询 */
    private volatile boolean loadingLoaderVersions = false;
    private Button gameDownloadButton;
    private Button gameCancelButton;
    private GameDownloader gameDownloader;
    /** 后台线程写、FX 线程读：必须 volatile，否则取消/按钮状态可能读到旧值 */
    private volatile boolean gameDownloading = false;
    /** 当前下载进度窗口（关掉后可用「显示进度」再次打开） */
    private DownloadProgressWindow activeProgressWindow;
    /** 当前下载的取消标志（后台线程读、FX 线程写），由 startGameDownload 赋值 */
    private volatile java.util.concurrent.atomic.AtomicBoolean currentCancelRef;
    private List<String> availableVersions = new ArrayList<>();

    // ---------- 模组下载 ----------
    private ModrinthClient modrinthClient;
    private ModDownloader modDownloader;
    private TextField modSearchField;
    private Button modSearchButton;
    private ComboBox<String> modLoaderFilter;
    private ComboBox<String> modVersionFilter;
    /** 本地版本列表（目录名 <-> 纯游戏版本映射） */
    private List<LocalVersion> localVersions = new ArrayList<>();
    private ListView<ModrinthClient.ModProject> modResultList;
    /** 结果区标题（热门模组 / 搜索结果） */
    private Label modResultTitle;
    /** 搜索/加载时的转圈提示（列表内容保留，只加提示） */
    private ProgressIndicator modLoadingIndicator;
    private Label modLoadingLabel;
    /** 是否正在加载热门模组 */
    private volatile boolean loadingPopular = false;
    /**
     * 两个列表（模组 / 整合包）各自等待简介表的「上屏任务」
     *
     * 之前只有一个槽：两个 Tab 的公加载种结果会互相覆盖，而且
     * onLoaded 回调和轮询各执行一次，同一次结果被渲染两遍（图标批量回填、
     * setAll、淡入都跟着翻倍，表现为页面闪动、条目数从 20 变成 36）。
     * 现在按列表各占一个槽，并绑定「未完成才能取走」的令牌：
     * 无论哪条路径先到，同一份结果只会应用一次。
     */
    private final java.util.Map<ListView<ModrinthClient.ModProject>, IntroTicket> pendingIntro =
            new java.util.IdentityHashMap<>();

    /** 抢一份待上屏结果；拿不到说明已完成过或没有待办 */
    private IntroTicket takePending(ListView<ModrinthClient.ModProject> list) {
        return pendingIntro.remove(list);
    }
    /** 由单元格点击调用：直接打开详情（与列表选中状态无关） */
    private void openModDetails(int index) {
        List<ModrinthClient.ModProject> items = modResultList.getItems();
        if (index < 0 || index >= items.size()) {
            return;
        }
        // 直接传项目本身，不再先写 selectedProject 再让 openVersionChooser 读回去：
        // 选中监听器也在写这个字段，两者一旦不同步就会打开另一个模组。
        ModrinthClient.ModProject project = items.get(index);
        selectedProject = project;
        openVersionChooser(project);
    }

    private void openPackDetails(int index) {
        if (index < 0 || index >= packResultList.getItems().size()) {
            return;
        }
        // 用列表自身的内容定位：列表持有的是副本，而 packResults 会被
        // 搜索回调 clear/addAll 原地改写，两者一旦错位就会打开错误的包。
        openPackInstaller(packResultList.getItems().get(index));
    }

    /** 当前选中的模组 */
    private ModrinthClient.ModProject selectedProject;
    /** 由版本选择窗口选定的版本（供下载按钮使用） */
    private ModrinthClient.ModVersion chosenVersion;
    private Label modStatusLabel;
    private Label modFileLabel;
    private Label modStatsLabel;
    private ProgressBar modProgressBar;
    private Button modDownloadButton;
    private Button modNextPageButton;
    private Label modPageLabel;

    // ---------- 整合包 ----------
    private TextField packSearchField;
    private Button packSearchButton;
    private ComboBox<String> packVersionFilter;
    private ComboBox<String> packLoaderFilter;
    private ListView<ModrinthClient.ModProject> packResultList;
    private Label packResultTitle;
    private Label packStatusLabel;
    private Button packNextPageButton;
    private Label packPageLabel;
    private ProgressIndicator packLoadingIndicator;
    private Label packLoadingLabel;
    /** 整合包搜索的是 Modrinth 的 project_type:modpack，与模组互不干扰 */
    private final List<ModrinthClient.ModProject> packResults = new ArrayList<>();
    private int packPage = 0;
    private volatile boolean packLoading = false;
    /**
     * 程序化更新筛选下拉框时抑制 onAction
     *
     * ComboBox.setItems/setAll/select 都会触发 onAction。搜索结果的回调里
     * 正在更新「游戏版本」下拉框，如果不在 onAction 里拦住，就会立刻再发一次
     * searchPacks —— 新的结果回来又更新下拉框 —— 无限递归，整个页面闪个不停。
     */
    private boolean suppressPackFilterAction = false;
    /** 同上，模组页的筛选下拉框 */
    private boolean suppressModFilterAction = false;

    private List<ModrinthClient.ModProject> searchResults = new ArrayList<>();
    private List<ModrinthClient.ModVersion> modVersions = new ArrayList<>();
    private int currentPage = 0;
    private static final int PAGE_SIZE = 20;

    /**
     * 异步搜索的「最新请求号」
     *
     * 没有它，先发后到的旧响应会覆盖新结果：玩家连按两次回车或快速切换筛选，
     * 列表显示的却是上一次查询的内容，页码也跟着错位。
     */
    private static final class ViewState {
        int gen;

        /** 开始一次新请求，返回本次请求号 */
        int next() {
            return ++gen;
        }

        boolean isCurrent(int g) {
            return g == gen;
        }
    }

    private final ViewState modView = new ViewState();
    private final ViewState packView = new ViewState();

    public DownloadPage(ConfigManager config, BMCLApiClient apiClient,
                        java.util.function.Consumer<String> statusConsumer) {
        this.config = config;
        this.apiClient = apiClient;
        this.statusConsumer = statusConsumer;
    }

    /** 由 MainWindow 注入内嵌弹窗宿主 */
    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    @Override
    public String getTitle() {
        return L.t("s_2b9d013177");
    }

    @Override
    public Region getContent() {
        if (root == null) {
            root = buildUI();
        }
        return root;
    }

    @Override
    public void onShow() {
        // 版本列表按需加载（内部会记住是否已加载）
        if (versionPane != null) {
            versionPane.loadIfNeeded();
        }
        // 刷新模组页的「游戏版本」下拉框（取本地已安装的版本）
        loadLocalGameVersions();
        // 首次进入模组页时自动加载热门模组，避免空白。
        // 注意要同时看 searchResults / loadingPopular：
        // 简介表就绪前列表还是空的，但搜索已经在路上，
        // 不加这个判断就会重复发一次请求、上屏两次。
        if (modResultList != null && modResultList.getItems().isEmpty()
                && searchResults.isEmpty() && !loadingPopular
                && !pendingIntro.containsKey(modResultList)) {
            loadPopularMods();
        }
        // 整合包页同理
        if (packResultList != null && packResultList.getItems().isEmpty()
                && packResults.isEmpty() && !packLoading
                && !pendingIntro.containsKey(packResultList)) {
            searchPacks(0);
        }
    }

    @Override
    public void onHide() {
        // 下载不中断，允许切到别的页面继续下
    }

    // ==================== UI ====================

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");

        // 只留标题：副标题纯属占用垂直空间，Tab 名称已经说明了功能
        VBox header = new VBox();
        header.setPadding(new Insets(12, 20, 6, 20));

        Label title = new Label(L.t("s_2b9d013177"));
        title.getStyleClass().add("page-title");

        header.getChildren().add(title);

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab gameTab = new Tab(L.t("s_be41d03d59"), buildGameDownloadTab());
        Tab modTab = new Tab(L.t("s_8c49a8bc50"), buildModDownloadTab());
        Tab packTab = new Tab(L.t("s_b3a621217c"), buildModpackTab());
        tabPane.getTabs().addAll(gameTab, modTab, packTab);

        VBox center = new VBox(tabPane);
        center.setPadding(new Insets(0, 16, 8, 16));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        pane.setTop(header);
        pane.setCenter(center);
        return pane;
    }

    // ---------- 游戏下载页 ----------

    private BorderPane buildGameDownloadTab() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-surface");

        VBox top = new VBox(12);
        top.setPadding(new Insets(16));

        // 版本选择行：搜索框 + 类型过滤 + 列表（见 VersionListPane）
        FlowPane selectRow = new FlowPane(10, 8);
        selectRow.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label(L.t("s_81d81d95b2"));
        label.getStyleClass().add("text-secondary");
        label.setMinWidth(Region.USE_PREF_SIZE);

        gameDownloadButton = new Button(L.t("s_92f35590d5"));
        gameDownloadButton.getStyleClass().add("button-primary");
        gameDownloadButton.setMinWidth(110);
        gameDownloadButton.setDisable(true);
        gameDownloadButton.setOnAction(e -> startGameDownload());

        gameCancelButton = new Button(L.t("s_4d0b4688c7"));
        gameCancelButton.getStyleClass().add("button");
        gameCancelButton.setMinWidth(60);
        gameCancelButton.setDisable(true);
        gameCancelButton.setOnAction(e -> cancelGameDownload());

        selectRow.getChildren().addAll(label, gameDownloadButton, gameCancelButton);

        // 版本列表（搜索 + 正式版/快照/远古过滤）
        versionPane = new VersionListPane(apiClient, statusConsumer, this::onVersionPicked);
        // 版本列表占据剩余空间
        VBox.setVgrow(versionPane.getContent(), Priority.ALWAYS);

        // ---- 加载器选项行 ----
        // 允许「下载原版的同时顺带装好加载器」，省去用户下载完再跑一趟安装流程
        FlowPane loaderRow = new FlowPane(10, 8);
        loaderRow.setAlignment(Pos.CENTER_LEFT);

        withLoaderCheck = new CheckBox(L.t("s_434f0b45b8"));
        withLoaderCheck.setMinWidth(Region.USE_PREF_SIZE);

        loaderTypeCombo = new ComboBox<>();
        loaderTypeCombo.getItems().addAll("Fabric", "Forge", "OptiFine");
        loaderTypeCombo.getSelectionModel().select(0);
        loaderTypeCombo.setPrefWidth(110);
        loaderTypeCombo.setMinWidth(110);
        loaderTypeCombo.setDisable(true);

        loaderVersionCombo = new ComboBox<>();
        loaderVersionCombo.setPrefWidth(180);
        loaderVersionCombo.setMinWidth(140);
        loaderVersionCombo.setPromptText(L.t("s_3dc7ad0b70"));
        loaderVersionCombo.setDisable(true);

        loaderHintLabel = new Label("");
        loaderHintLabel.getStyleClass().add("text-muted");
        loaderHintLabel.setMinWidth(Region.USE_PREF_SIZE);

        // 勾选后才启用下拉框，并自动去拉加载器版本列表
        withLoaderCheck.setOnAction(e -> {
            boolean on = withLoaderCheck.isSelected();
            loaderTypeCombo.setDisable(!on);
            loaderVersionCombo.setDisable(!on);
            if (on) {
                loadLoaderVersionsForDownload();
            } else {
                loaderVersionCombo.getItems().clear();
                loaderHintLabel.setText("");
            }
        });

        // 切换加载器类型时重新拉取可用版本
        loaderTypeCombo.setOnAction(e -> {
            if (withLoaderCheck.isSelected()) {
                loadLoaderVersionsForDownload();
            }
        });
        // 无需给游戏版本列表绑监听器：当用户选中一个版本时，
        // onVersionPicked 会负责——如果勾选了加载器，就自动刷新加载器版本列表。

        loaderRow.getChildren().addAll(withLoaderCheck, loaderTypeCombo,
                loaderVersionCombo, loaderHintLabel);

        // 进度与日志已移到独立的下载进度窗口（DownloadProgressWindow），
        // 本页面只负责版本选择，因此把全部剩余空间让给版本列表。

        // 布局顺序：操作行 -> 加载器行 -> 版本列表（占满剩余空间）
        Region versionContent = versionPane.getContent();
        VBox.setVgrow(versionContent, Priority.ALWAYS);

        top.getChildren().addAll(selectRow, loaderRow, versionContent);

        pane.setCenter(top);
        return pane;
    }

    // ---------- 模组下载页 ----------

    /** MC 百科中文简介映射（异步加载，失败则退回英文） */
    private McmodIntroMap mcmodIntros;

    private BorderPane buildModDownloadTab() {
        this.modrinthClient = new ModrinthClient();
        this.modDownloader = new ModDownloader(this.modrinthClient);

        // 中文简介：优先复用启动时预热的共享实例（已在加载或已就绪），
        // 只有预热没跑（例如单独构造本页做测试）时才自己加载。
        // 拉取不到就继续显示 Modrinth 的英文简介，功能不受影响。
        McmodIntroMap shared = McmodIntroMap.shared();
        if (shared != null) {
            this.mcmodIntros = shared;
        } else {
            this.mcmodIntros = new McmodIntroMap();
            this.mcmodIntros.loadAsync(com.mclauncher.utils.AppPaths.appDataDir());
        }
        // 简介表加载完成后，把已经在屏幕上的结果重画一遍 ——
        // 没有这一步，首屏永远停在英文，必须手动点开一个模组才会变中文。
        this.mcmodIntros.onLoaded(() -> javafx.application.Platform.runLater(() -> {
            logger.info("简介表已就绪，刷新模组/整合包列表");
            if (modResultList != null) {
                modResultList.refresh();
            }
            if (packResultList != null) {
                packResultList.refresh();
            }
            // 首屏如果是在简介就绪前渲染的，这里重跑一次，把中文和图标一起补上。
            // 每个列表各自一份待办；与定时器谁先到谁执行，另一半会因令牌失效而不动。
            for (ListView<ModrinthClient.ModProject> list : new ArrayList<>(pendingIntro.keySet())) {
                IntroTicket ticket = pendingIntro.get(list);
                if (ticket != null) {
                    firePending(list, ticket);
                }
            }
        }));
        // 下载页可能在下拉框构造触发的那次 onAction 之后才有数据，
        // onShow 里那次检查会落空。这里等构建结束后再补一次，否则
        // 「热门整合包」在玩家手动切到整合包 Tab 之前永远是空白。
        Platform.runLater(() -> {
            if (tabPane != null && tabPane.getSelectionModel().getSelectedIndex() == 2
                    && packResultList != null && packResultList.getItems().isEmpty()
                    && packResults.isEmpty() && !packLoading
                    && !pendingIntro.containsKey(packResultList)) {
                searchPacks(0);
            }
        });

        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-surface");

        VBox top = new VBox(8);
        top.setPadding(new Insets(10, 10, 6, 10));

        // 搜索行：用 FlowPane 让控件在窗口变窄时自动换行，避免被压成省略号
        FlowPane searchRow = new FlowPane(10, 8);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        modSearchField = new TextField();
        modSearchField.setPromptText(L.t("s_b6f4bf3b80"));
        modSearchField.setPrefWidth(220);
        modSearchField.setMinWidth(160);
        modSearchField.setOnAction(e -> searchMods(0));

        modSearchButton = new Button(L.t("s_f04090805c"));
        modSearchButton.getStyleClass().add("button-primary");
        modSearchButton.setMinWidth(70);
        modSearchButton.setOnAction(e -> searchMods(0));

        Label ll = new Label(L.t("s_455e832fd9"));
        ll.getStyleClass().add("text-secondary");
        ll.setMinWidth(Region.USE_PREF_SIZE);

        modLoaderFilter = new ComboBox<>();
        modLoaderFilter.getItems().addAll(L.t("s_778fc8f994"), "Fabric", "Forge", "NeoForge", "Quilt");
        modLoaderFilter.getSelectionModel().select(0);
        modLoaderFilter.setPrefWidth(105);
        modLoaderFilter.setMinWidth(105);
        modLoaderFilter.setOnAction(e -> {
            if (!searchResults.isEmpty() || !modSearchField.getText().isBlank()) {
                searchMods(0);
            }
        });

        Label vl = new Label(L.t("s_989d1affa0"));
        vl.getStyleClass().add("text-secondary");
        vl.setMinWidth(Region.USE_PREF_SIZE);

        modVersionFilter = new ComboBox<>();
        modVersionFilter.setPrefWidth(110);
        modVersionFilter.setMinWidth(110);
        modVersionFilter.setPromptText(L.t("s_eaf1b3133c"));
        modVersionFilter.setOnAction(e -> {
            if (suppressModFilterAction) {
                return;
            }
            if (!searchResults.isEmpty() || !modSearchField.getText().isBlank()) {
                searchMods(0);
            }
        });

        modNextPageButton = new Button(L.t("s_67a246a344"));
        modNextPageButton.getStyleClass().addAll("button", "button-sm");
        modNextPageButton.setMinWidth(70);
        modNextPageButton.setDisable(true);
        modNextPageButton.setOnAction(e -> searchMods(currentPage + 1));

        modPageLabel = new Label(L.t("s_3d127c8d78"));
        modPageLabel.getStyleClass().add("text-muted");
        modPageLabel.setMinWidth(Region.USE_PREF_SIZE);

        searchRow.getChildren().addAll(modSearchField, modSearchButton,
                ll, modLoaderFilter, vl, modVersionFilter,
                modNextPageButton, modPageLabel);

        top.getChildren().add(searchRow);

        // 主体：左结果 / 右版本
        HBox body = new HBox(14);
        body.setPadding(new Insets(0, 10, 0, 10));
        VBox.setVgrow(body, Priority.ALWAYS);

        // 左：搜索结果的详情列表
        VBox left = new VBox(6);
        HBox.setHgrow(left, Priority.ALWAYS);

        modResultTitle = new Label(L.t("s_7e17935278"));
        // 这个标签之前只声明没创建，null 保护把问题掩盖了很久
        modStatusLabel = new Label("");
        modStatusLabel.getStyleClass().add("text-muted");
        modResultTitle.getStyleClass().add("section-title");

        modResultList = new ListView<>();
        modResultList.getStyleClass().add("mod-list");
        modResultList.setPlaceholder(new Label(L.t("s_30350f719a")));
        VBox.setVgrow(modResultList, Priority.ALWAYS);
        modResultList.setCellFactory(lv -> new ModCardCell());

        // 选中监听器只负责记录「当前选中」，**不弹窗**：
        // 点击/回车由单元格自己触发（见 ModCardCell 的鼠标处理器与列表的按键处理器），
        // 程序化 select(0) 也就不会误弹，不再需要 suppressAutoOpen 这种时序标志。
        modResultList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, old, idx) -> {
                    int i = idx == null ? -1 : idx.intValue();
                    selectedProject = i >= 0 && i < searchResults.size()
                            ? searchResults.get(i) : null;
                });

        // 键盘操作：回车打开选中项（鼠标已由单元格处理）
        modResultList.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                openModDetails(modResultList.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });

        // 结果区上方的加载提示（防卡死体验：列表保留，只加提示）
        modLoadingIndicator = new ProgressIndicator();
        modLoadingIndicator.setMaxSize(22, 22);
        modLoadingIndicator.setVisible(false);
        modLoadingIndicator.setManaged(false);

        modLoadingLabel = new Label("");
        modLoadingLabel.getStyleClass().add("text-secondary");

        HBox modLoadingRow = new HBox(10, modLoadingIndicator, modLoadingLabel);
        modLoadingRow.setAlignment(Pos.CENTER_LEFT);

        // 只保留列表本身。标题（热门模组/搜索结果）与状态合并进底部一行，
        // 每屏能多显示 3~4 条。
        left.getChildren().addAll(modResultList);

        modResultTitle.getStyleClass().remove("section-title");
        modResultTitle.getStyleClass().add("list-footer");
        modStatusLabel.getStyleClass().add("list-footer");
        modLoadingLabel.getStyleClass().add("list-footer");

        HBox modFooter = new HBox(10, modResultTitle, modLoadingIndicator, modLoadingLabel,
                new Region(), modStatusLabel);
        modFooter.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modFooter.getChildren().get(3), Priority.ALWAYS);
        modFooter.setPadding(new Insets(4, 4, 0, 4));


        // 结果列表占满整个主体宽度。
        //
        // 原来右侧还有一个「操作」面板（选中的模组信息 + 查看版本按钮 + 下载进度）。
        // 现在点击模组直接弹出可下载版本窗口，那个面板就是多余的一栏 ——
        // 不仅占掉 300px 宽度，还要用户「先选中、再点按钮」两步操作。
        body.getChildren().addAll(left);

        VBox center = new VBox(6, top, body, modFooter);
        VBox.setVgrow(body, Priority.ALWAYS);
        pane.setCenter(center);

        // 模组页的「游戏版本」下拉框会在 onShow() 里由 loadLocalGameVersions() 填充

        return pane;
    }

// ==================== 整合包 Tab ====================

    /**
     * 整合包页
     *
     * 结构与模组页一致（搜索 + 列表），只是搜索的项目类型是 modpack。
     * 单击某个整合包 -> 弹出 {@link com.mclauncher.ui.window.ModpackInstallWindow}，
     * 在那里选版本并一键安装。
     */
    private BorderPane buildModpackTab() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-surface");

        VBox top = new VBox(10);
        top.setPadding(new Insets(16, 16, 8, 16));

        FlowPane searchRow = new FlowPane(10, 8);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        packSearchField = new TextField();
        packSearchField.setPromptText(L.t("s_1192b6eb44"));
        packSearchField.setPrefWidth(240);
        packSearchField.setMinWidth(160);
        packSearchField.setOnAction(e -> searchPacks(0));

        packSearchButton = new Button(L.t("s_f04090805c"));
        packSearchButton.getStyleClass().add("button-primary");
        packSearchButton.setMinWidth(70);
        packSearchButton.setOnAction(e -> searchPacks(0));

        Label gl = new Label(L.t("s_f4e1ba8015"));
        gl.getStyleClass().add("text-secondary");
        gl.setMinWidth(Region.USE_PREF_SIZE);

        packVersionFilter = new ComboBox<>();
        packVersionFilter.setPrefWidth(120);
        packVersionFilter.setMinWidth(110);
        packVersionFilter.getItems().add(L.t("s_eaf1b3133c"));
        packVersionFilter.getSelectionModel().select(0);
        packVersionFilter.setOnAction(e -> {
            if (suppressPackFilterAction) {
                return;
            }
            if (!packResults.isEmpty() || !packSearchField.getText().isBlank()) {
                searchPacks(0);
            }
        });

        Label ll = new Label(L.t("s_455e832fd9"));
        ll.getStyleClass().add("text-secondary");
        ll.setMinWidth(Region.USE_PREF_SIZE);

        packLoaderFilter = new ComboBox<>();
        packLoaderFilter.getItems().addAll(L.t("s_778fc8f994"), "Fabric", "Forge", "NeoForge", "Quilt");
        packLoaderFilter.getSelectionModel().select(0);
        packLoaderFilter.setPrefWidth(105);
        packLoaderFilter.setMinWidth(105);
        packLoaderFilter.setOnAction(e -> {
            if (suppressPackFilterAction) {
                return;
            }
            if (!packResults.isEmpty() || !packSearchField.getText().isBlank()) {
                searchPacks(0);
            }
        });

        packNextPageButton = new Button(L.t("s_67a246a344"));
        packNextPageButton.getStyleClass().addAll("button", "button-sm");
        packNextPageButton.setMinWidth(70);
        packNextPageButton.setDisable(true);
        packNextPageButton.setOnAction(e -> searchPacks(packPage + 1));

        packPageLabel = new Label(L.t("s_3d127c8d78"));
        packPageLabel.getStyleClass().add("text-muted");
        packPageLabel.setMinWidth(Region.USE_PREF_SIZE);

        searchRow.getChildren().addAll(packSearchField, packSearchButton,
                gl, packVersionFilter, ll, packLoaderFilter,
                packNextPageButton, packPageLabel);

        top.getChildren().add(searchRow);

        packResultTitle = new Label(L.t("s_cc1bbccf93"));
        packResultTitle.getStyleClass().add("section-title");

        packLoadingIndicator = new ProgressIndicator();
        packLoadingIndicator.setMaxSize(20, 20);
        packLoadingIndicator.setVisible(false);
        packLoadingIndicator.setManaged(false);

        packLoadingLabel = new Label("");
        packLoadingLabel.getStyleClass().add("text-secondary");

        HBox loadingRow = new HBox(8, packLoadingIndicator, packLoadingLabel);
        loadingRow.setAlignment(Pos.CENTER_LEFT);

        packResultList = new ListView<>();
        packResultList.getStyleClass().add("mod-list");
        packResultList.setPlaceholder(new Label(L.t("s_30350f719a")));
        packResultList.setCellFactory(lv -> new PackCardCell());
        VBox.setVgrow(packResultList, Priority.ALWAYS);

        // 单击整合包 -> 直接弹出安装窗口（由单元格处理器触发，与模组页一致）。
        // 选中监听器只负责高亮，不弹窗。
        packResultList.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                openPackDetails(packResultList.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });

        packStatusLabel = new Label("");
        packStatusLabel.getStyleClass().add("text-muted");

        // 标题/加载提示/状态合并成底部一行，列表占满剩余空间
        packResultTitle.getStyleClass().remove("section-title");
        packResultTitle.getStyleClass().add("list-footer");
        packStatusLabel.getStyleClass().add("list-footer");
        packLoadingLabel.getStyleClass().add("list-footer");

        HBox packFooter = new HBox(8, packResultTitle, packLoadingIndicator, packLoadingLabel,
                new Region(), packStatusLabel);
        packFooter.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(packFooter.getChildren().get(3), Priority.ALWAYS);
        packFooter.setPadding(new Insets(4, 4, 0, 4));

        VBox listBox = new VBox(4, packResultList, packFooter);
        VBox.setVgrow(packResultList, Priority.ALWAYS);
        listBox.setPadding(new Insets(0, 10, 4, 10));

        pane.setTop(top);
        pane.setCenter(listBox);
        return pane;
    }

    /**
     * 搜索整合包
     */
    private void searchPacks(int page) {
        if (modrinthClient == null || packResultList == null) {
            return;
        }

        String query = packSearchField.getText() == null ? "" : packSearchField.getText().trim();
        String version = packVersionFilter.getSelectionModel().getSelectedItem();
        String loader = packLoaderFilter.getSelectionModel().getSelectedItem();
        final String gv = (version == null || version.isBlank() || L.t("s_eaf1b3133c").equals(version))
                ? null : version;
        final String ld = (loader == null || L.t("s_778fc8f994").equals(loader)) ? null : loader.toLowerCase();

        final int gen = packView.next();
        packLoading = true;
        packPage = page;
        packSearchButton.setDisable(true);
        packSearchButton.setText(L.t("s_8694cd1416"));
        packLoadingIndicator.setVisible(true);
        packLoadingIndicator.setManaged(true);
        packLoadingLabel.setText(page == 0 ? L.t("s_befe807824") : L.t("s_d7cb9df7e2"));
        packPageLabel.setText(L.t("s_f495347d6a") + (page + 1) + L.t("s_af8438feb2"));

        CompletableFuture.supplyAsync(() -> {
            try {
                // 空查询按下载量排序拿到「热门榜」，有查询词按相关度
                String index = query.isEmpty() ? "downloads" : "relevance";
                return modrinthClient.search(query, gv, ld, page * PAGE_SIZE, PAGE_SIZE,
                        index, "modpack");
            } catch (Exception e) {
                logger.error("搜索整合包失败", e);
                return null;
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            // 已有更新的请求在途：这次的结果作废，也不动加载状态（由新请求负责）
            if (!packView.isCurrent(gen)) {
                return;
            }
            packLoading = false;
            packSearchButton.setDisable(false);
            packSearchButton.setText(L.t("s_f04090805c"));
            packLoadingIndicator.setVisible(false);
            packLoadingIndicator.setManaged(false);
            packLoadingLabel.setText("");

            if (result == null) {
                // 失败不清空已有列表（与模组页一致的防卡死体验）
                packStatusLabel.setText(L.t("s_f0a914e43d"));
                return;
            }

            packResults.clear();
            packResults.addAll(result.getProjects());

            // 把结果里出现的游戏版本补进下拉框（新→旧）
            java.util.TreeSet<String> versions = new java.util.TreeSet<>(
                    (a, b) -> compareVersions(b, a));
            for (ModrinthClient.ModProject p : packResults) {
                versions.addAll(p.getGameVersions());
            }
            if (!versions.isEmpty()) {
                String keep = packVersionFilter.getSelectionModel().getSelectedItem();
                List<String> items = new ArrayList<>();
                items.add(L.t("s_eaf1b3133c"));
                items.addAll(versions);
                // 关键：更新下拉框期间抑制 onAction，否则会递归触发搜索
                suppressPackFilterAction = true;
                try {
                    packVersionFilter.getItems().setAll(items);
                    packVersionFilter.getSelectionModel().select(
                            keep != null && items.contains(keep) ? keep : L.t("s_eaf1b3133c"));
                } finally {
                    suppressPackFilterAction = false;
                }
            }

            // 和模组页一致：等简介 + 图标就绪再上屏。
            // 传副本：packResults 会被下一次搜索原地改写，
            // 而等简介期间可能要好几百毫秒
            presentWhenReady(packResultList, new ArrayList<>(packResults),
                    query.isEmpty() ? L.t("s_cc1bbccf93") : L.t("s_988282b375") + query,
                    L.t("s_497064be1f") + result.getTotalHits() + L.t("s_1e87867693"),
                    result, "pack");
        }));
    }

    /**
     * 打开整合包安装窗口
     */
    private void openPackInstaller(ModrinthClient.ModProject project) {
        if (project == null || modalHost == null) {
            return;
        }
        com.mclauncher.ui.window.ModpackInstallWindow window =
                new com.mclauncher.ui.window.ModpackInstallWindow(
                        modalHost, modrinthClient, project, config, msg -> {
                            if (statusConsumer != null) {
                                statusConsumer.accept(msg);
                            }
                        });
        window.show();
    }

    /**
     * 简单的版本号比较（1.20.1 > 1.20 > 1.9.4）
     */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? toInt(pa[i]) : 0;
            int y = i < pb.length ? toInt(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return a.compareTo(b);
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 整合包列表单元格
     *
     * 复用模组的图标加载机制（同一个缓存），但元信息显示「支持的游戏版本」
     * 而不是模组单元格里的「加载器」—— 整合包本身不支持选加载器筛选。
     */
    private class PackCardCell extends ListCell<ModrinthClient.ModProject> {
        /** 图标槽位：尺寸固定，加载完成后只替换里面的 ImageView */
        private final StackPane iconSlot = createIconSlot();
        private final Label name = new Label();
        private final Label desc = new Label();
        private final Label meta = new Label();
        private final HBox card = new HBox(12);

        /** 本格当前绑定的项目，避免列表复用导致图标串号 */
        private ModrinthClient.ModProject bound;

        PackCardCell() {
            name.getStyleClass().add("mod-name");
            name.setWrapText(true);
            name.setMaxWidth(Double.MAX_VALUE);
            desc.getStyleClass().add("mod-desc");
            desc.setWrapText(true);
            desc.setMaxWidth(Double.MAX_VALUE);
            meta.getStyleClass().add("mod-meta");

            VBox textBox = new VBox(4, name, desc, meta);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            card.getStyleClass().add("mod-card");
            card.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().addAll(iconSlot, textBox);

            // 单击卡片直接打开详情：不看「旧索引 != 新索引」，
            // 同一张卡片被点左右两次、或从键盘选中后再点，旧逻辑都不响应。
            card.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    openPackDetails(getIndex());
                }
            });
        }

        @Override
        protected void updateItem(ModrinthClient.ModProject item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                bound = null;
                return;
            }

            // 节点复用：只改文本，不重建卡片 —— 重建会让列表每次重绘都闪一下
            bound = item;
            name.setText(item.getTitle());

            // 整合包简介也优先用中文（MC 百科里偶尔有同名条目）
            String d = mcmodIntros != null
                    ? mcmodIntros.lookupIntro(item.getTitle(), item.getSlug()) : null;
            if (d == null || d.isBlank()) {
                d = item.getDescription() == null ? "" : item.getDescription();
            }
            desc.setText(d.length() > 110 ? d.substring(0, 110) + "..." : d);

            StringBuilder m = new StringBuilder();
            if (!item.getGameVersions().isEmpty()) {
                List<String> gv = new ArrayList<>(item.getGameVersions());
                // 只显示最新的 4 个，避免一串版本号把行撑爆
                int show = Math.min(4, gv.size());
                m.append(L.t("s_95b1485290")).append(String.join(" / ", gv.subList(0, show)));
                if (gv.size() > show) {
                    m.append(L.t("s_93ac7be2bc"));
                }
            }
            if (item.getDownloads() > 0) {
                if (m.length() > 0) {
                    m.append("    ");
                }
                long n = item.getDownloads();
                m.append(L.t("s_3f10b573ee")).append(n >= 10_000
                        ? String.format(L.t("s_c8af81cb79"), n / 10_000.0) : String.valueOf(n));
            }
            meta.setText(m.toString());

            // 用卡片的图标槽位做存活判断（不用 bound == item）：
        // 同一个图标常常同时被 20 个格子等待，而之前这里会为每个格子新建
        // 一个线程去下载同一张图。
        mountIconAsync(iconSlot, item, () -> {
            String key = item.getIconUrl();
            return key != null && key.equals(iconSlot.getProperties().get(ICON_KEY));
        });
            setGraphic(card);
            setText(null);
        }
    }

    /** 创建固定尺寸的图标槽位 */
    private static StackPane createIconSlot() {
        StackPane slot = new StackPane();
        slot.setMinSize(40, 40);
        slot.setPrefSize(40, 40);
        slot.setMaxSize(40, 40);
        // 圆角裁剪：占位图与真实图标形状一致，换图时不会有方角闪一下
        Rectangle clip = new Rectangle(40, 40);
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        slot.setClip(clip);
        return slot;
    }

    /** 图标槽位里占位图/真实图的标识键，用于判断是否需要换 */
    private static final String ICON_KEY = "icon-key";

    /**
     * 把图标挂到槽位里（**同步**：要么是真实图，要么是占位图）
     *
     * 只负责从缓存取；缓存没有就放占位图并返回 false，由调用方决定
     * 是等（新列表）还是异步补（列表已在屏幕上）。
     *
     * @return true 表示已放入真实图标
     */
    private boolean mountIconCached(StackPane slot, ModrinthClient.ModProject item) {
        String url = item.getIconUrl();
        if (url != null && !url.isBlank() && !FAILED_ICONS.contains(url)) {
            Image img = ICON_CACHE.get(url);
            if (img != null) {
                if (!url.equals(slot.getProperties().get(ICON_KEY))) {
                    slot.getChildren().setAll(modIconView(img));
                    slot.getProperties().put(ICON_KEY, url);
                }
                return true;
            }
        }
        // 占位图：同一个项目重复渲染时不要重建（重建会闪）
        String phKey = "placeholder:" + item.getTitle();
        if (!phKey.equals(slot.getProperties().get(ICON_KEY))) {
            slot.getChildren().setAll(modIconPlaceholder(item.getTitle()));
            slot.getProperties().put(ICON_KEY, phKey);
        }
        return false;
    }

    /** 项目是否有图标可用（缓存里有） */
    private boolean hasCachedIcon(ModrinthClient.ModProject item) {
        String url = item.getIconUrl();
        return url != null && !url.isBlank() && ICON_CACHE.containsKey(url);
    }

    /**
     * 确保这批项目的图标都在下载
     *
     * 只对「缓存没有、也没失败、没在下载」的项目开线程；
     * 线程结束后写缓存并执行 onDone（FX 线程）。
     */
    private void ensureIconsLoaded(List<ModrinthClient.ModProject> items, Runnable onDone) {
        // 统计本批需要下载的图标数。**必须等全部完成才回调一次** ——
        // 之前每张图下载完都会调 onDone，一屏 20 个图标就把结果渲染了 20 次，
        // 列表反复重建，表现出来就是页面狂闪。
        final java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean fired =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Runnable maybeDone = () -> {
            if (onDone != null && remaining.decrementAndGet() == 0 && fired.compareAndSet(false, true)) {
                onDone.run();
            }
        };

        for (ModrinthClient.ModProject item : items) {
            String url = item.getIconUrl();
            if (url == null || url.isBlank() || FAILED_ICONS.contains(url)
                    || ICON_CACHE.containsKey(url)) {
                continue;
            }
            remaining.incrementAndGet();
            final String key = url;
            if (!LOADING_ICONS.add(key)) {
                // 已在下载：本批也要等它。之前这里直接 continue，
                // 而「谁负责计数」只算发起下载的那一批 —— 本批的 remaining
                // 永远减不到 0，onDone 永不执行，列表就一直停在首字母占位图。
                ICON_WAITERS.computeIfAbsent(key,
                                k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(maybeDone);
                continue;
            }
            ICON_POOL.execute(() -> {
                byte[] bytes = null;
                try {
                    bytes = modrinthClient.downloadImageBytes(key);
                } catch (Exception e) {
                    logger.debug("图标下载失败: {}", e.getMessage());
                } finally {
                    LOADING_ICONS.remove(key);
                }
                final byte[] data = bytes;
                Platform.runLater(() -> {
                    try {
                        if (data == null || data.length == 0) {
                            FAILED_ICONS.add(key);
                        } else {
                            try {
                                Image img = new Image(new ByteArrayInputStream(data));
                                if (img.getWidth() > 0) {
                                    ICON_CACHE.put(key, img);
                                } else {
                                    FAILED_ICONS.add(key);
                                }
                            } catch (Exception e) {
                                FAILED_ICONS.add(key);
                            }
                        }
                    } finally {
                        maybeDone.run();
                    }
                });
            });
        }

        if (remaining.get() == 0 && onDone != null) {
            fired.set(true);
            Platform.runLater(onDone);
        }
    }

    /**
     * 给已经在屏幕上的单元格补图标（异步）
     *
     * 只换图标槽位，不 refresh 整个列表 —— 那是之前闪动的根源。
     */
    private void mountIconAsync(StackPane slot, ModrinthClient.ModProject item,
                                java.util.function.BooleanSupplier alive) {
        String url = item.getIconUrl();
        // 先确定图标键：即使图标正在下载、这里只是挂等待回调，
        // 也要登记「本槽想显示的是哪张图」，存活判断才有效。
        if (url == null || url.isBlank() || FAILED_ICONS.contains(url)) {
            mountIconCached(slot, item);    // 占位图
            return;
        }
        final String key = url;
        Image cached = ICON_CACHE.get(key);
        if (cached != null) {
            mountIconCached(slot, item);
            return;
        }
        if (!key.equals(slot.getProperties().get(ICON_KEY))) {
            slot.getChildren().setAll(modIconPlaceholder(item.getTitle()));
            slot.getProperties().put(ICON_KEY, key);
        }

        // 正在下载：挂一个一次性回调，下载完回填本格
        ICON_WAITERS.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(() -> {
                    if (!alive.getAsBoolean()) {
                        return;
                    }
                    Image img = ICON_CACHE.get(key);
                    if (img != null) {
                        slot.getChildren().setAll(modIconView(img));
                        slot.getProperties().put(ICON_KEY, key);
                    }
                });

        if (LOADING_ICONS.add(key)) {
            ICON_POOL.execute(() -> {
                byte[] bytes = null;
                try {
                    bytes = modrinthClient.downloadImageBytes(key);
                } catch (Exception e) {
                    logger.debug("图标下载失败: {}", e.getMessage());
                } finally {
                    LOADING_ICONS.remove(key);
                }
                final byte[] data = bytes;
                Platform.runLater(() -> {
                    try {
                        if (data != null && data.length > 0) {
                            Image img = new Image(new ByteArrayInputStream(data));
                            if (img.getWidth() > 0) {
                                ICON_CACHE.put(key, img);
                            } else {
                                FAILED_ICONS.add(key);
                            }
                        } else {
                            FAILED_ICONS.add(key);
                        }
                    } catch (Exception e) {
                        FAILED_ICONS.add(key);
                    }
                    java.util.List<Runnable> waiters = ICON_WAITERS.remove(key);
                    if (waiters != null) {
                        for (Runnable w : waiters) {
                            try {
                                w.run();
                            } catch (Exception ignored) {
                                // 单格失败不影响其它格
                            }
                        }
                    }
                });
            });
        }
    }

    /** 40x40 圆形图标（槽位已负责裁剪，这里只负责缩放） */
    private Node modIconView(Image img) {
        ImageView iv = new ImageView(img);
        iv.setFitWidth(40);
        iv.setFitHeight(40);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        StackPane holder = new StackPane(iv);
        holder.setMinSize(40, 40);
        holder.setPrefSize(40, 40);
        holder.setMaxSize(40, 40);
        return holder;
    }

    /** 首字母占位图标 */
    private Node modIconPlaceholder(String title) {
        StackPane placeholder = new StackPane();
        placeholder.getStyleClass().add("mod-icon-placeholder");
        String letter = (title == null || title.isBlank())
                ? "?" : title.substring(0, 1).toUpperCase();
        Label l = new Label(letter);
        l.getStyleClass().add("mod-icon-letter");
        placeholder.getChildren().add(l);
        return placeholder;
    }
    /**
     * 模组卡片单元
     *
     * 重写目的：原实现只往单元格里塞一个 Label，
     * 正文与背景糊在一起、没有层次。
     *
     * 现在按规范渲染：
     * - 卡片背景 #2D2D2D（见 .mod-card）
     * - 模组名 纯白加粗（.mod-name）
     * - 简介 浅灰（.mod-desc）
     * - 加载器/下载量 主题蓝（.mod-meta）
     * - 左侧圆形图标（无图标时用首字母占位）
     */
    private class ModCardCell extends ListCell<ModrinthClient.ModProject> {
        /** 图标槽位：节点复用，加载完成后只替换里面的 ImageView */
        private final StackPane iconSlot = createIconSlot();
        private final Label name = new Label();
        private final Label desc = new Label();
        private final Label meta = new Label();
        private final HBox card = new HBox(12);

        /** 本格当前绑定的项目，避免列表复用导致图标串号 */
        private ModrinthClient.ModProject bound;

        ModCardCell() {
            name.getStyleClass().add("mod-name");
            name.setWrapText(true);
            name.setMaxWidth(Double.MAX_VALUE);
            desc.getStyleClass().add("mod-desc");
            desc.setWrapText(true);
            desc.setMaxWidth(Double.MAX_VALUE);
            meta.getStyleClass().add("mod-meta");

            VBox textBox = new VBox(4, name, desc, meta);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            card.getStyleClass().add("mod-card");
            card.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().addAll(iconSlot, textBox);

            // 单击卡片直接打开详情（理由同 PackCardCell）
            card.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    openModDetails(getIndex());
                }
            });
        }

        @Override
        protected void updateItem(ModrinthClient.ModProject item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                bound = null;
                return;
            }

            // 节点复用：只改文本。以前每次 updateItem 都新建整个卡片，
            // 列表只要有重绘就会闪。
            bound = item;
            name.setText(item.getTitle());

            // 优先用中文简介，没有再退回 Modrinth 的英文
            String d = mcmodIntros != null
                    ? mcmodIntros.lookupIntro(item.getTitle(), item.getSlug()) : null;
            if (d == null || d.isBlank()) {
                d = item.getDescription() == null ? "" : item.getDescription();
            }
            desc.setText(d.length() > 110 ? d.substring(0, 110) + "..." : d);

            // 元信息：加载器 + 下载量（主题蓝强调）
            StringBuilder m = new StringBuilder();
            if (!item.getLoaders().isEmpty()) {
                m.append(String.join(" / ", item.getLoaders()));
            }
            if (item.getDownloads() > 0) {
                if (m.length() > 0) m.append("    ");
                m.append(L.t("s_3f10b573ee")).append(formatDownloads(item.getDownloads()));
            }
            meta.setText(m.toString());

            // 用卡片的图标槽位做存活判断（不用 bound == item）：
        // 同一个图标常常同时被 20 个格子等待，而之前这里会为每个格子新建
        // 一个线程去下载同一张图。
        mountIconAsync(iconSlot, item, () -> {
            String key = item.getIconUrl();
            return key != null && key.equals(iconSlot.getProperties().get(ICON_KEY));
        });
            setGraphic(card);
            setText(null);
        }

        private static String formatDownloads(long n) {
            if (n >= 100_000_000) return String.format(L.t("s_48be8f0bcb"), n / 100_000_000.0);
            if (n >= 10_000) return String.format(L.t("s_c8af81cb79"), n / 10_000.0);
            return String.valueOf(n);
        }
    }

    // ==================== 游戏下载逻辑 ====================

    /**
     * 版本列表选中回调（版本 ID，无类型后缀）
     */
    private String currentVersionId;  // 选中的纯 ID

    private void onVersionPicked(String versionId) {
        this.currentVersionId = versionId;

        // 下载进行中：按钮保持「显示进度」，不因切换选中项而改变
        if (gameDownloading) {
            return;
        }

        gameDownloadButton.setDisable(false);

        // 若勾选了加载器，自动刷新加载器版本列表
        if (withLoaderCheck.isSelected()) {
            loadLoaderVersionsForDownload();
        }
    }

    private void startGameDownload() {
        if (currentVersionId == null) {
            return;
        }

        // 若已有下载在进行，按钮变成「显示进度」——
        // 重新打开进度窗口，而不是重复启动一次下载
        if (activeProgressWindow != null) {
            // 含「刚取消、后台线程还没收尾」的情况：此时不能重新开始下载，
            // 否则会有两个进程同时在写同一个版本目录
            activeProgressWindow.show();
            return;
        }

        final String versionId = currentVersionId;
        // 本次下载是否已被取消（后台线程只读，FX 线程写）
        final java.util.concurrent.atomic.AtomicBoolean currentCancel =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        this.currentCancelRef = currentCancel;

        // 进度/日志在内嵌面板里展示；面板可关闭而下载在后台继续
        DownloadProgressWindow progressWin = new DownloadProgressWindow(
                modalHost,
                versionId,
                v -> cancelGameDownload());
        activeProgressWindow = progressWin;

        gameDownloading = true;
        gameDownloadButton.setText(L.t("s_83dba218eb"));
        gameCancelButton.setDisable(true);   // 取消改由进度窗口负责

        progressWin.show();
        progressWin.appendLog(L.t("s_a0a16feb25") + versionId);

        // 在 FX 线程上把「是否装加载器 / 装哪个」拷成局部变量：
        // 下面的后台线程里直接读 ComboBox / CheckBox 属于跨线程访问控件，
        // 行为没有保证；而且用户下载途中改动勾选也不该影响这次下载。
        final boolean installLoader = withLoaderCheck.isSelected();
        final String loaderTypeNow = loaderTypeCombo.getSelectionModel().getSelectedItem();
        final String loaderVerNow = loaderVersionCombo.getSelectionModel().getSelectedItem();

        CompletableFuture.runAsync(() -> {
            try {
                gameDownloader = new GameDownloader(apiClient, config.getGameDir());

                gameDownloader.setCallback(new GameDownloader.ProgressCallback() {
                    @Override
                    public void onProgress(int current, int total, String message) {
                        progressWin.updateProgress(current, total, message);
                    }

                    @Override
                    public void onDetail(String currentFile, long speed, int remaining) {
                        progressWin.updateDetail(currentFile, speed, remaining);
                    }

                    @Override
                    public void onError(String error) {
                        progressWin.appendLog(L.t("s_c191f9f13a") + error);
                    }
                });

                boolean ok = gameDownloader.downloadVersion(versionId);

                // 用户已在进度窗口点了「取消」：不是失败，也不再继续装加载器。
                // 进度面板在取消时已经由 markFinished 收起，这里不能再去操作它。
                if (currentCancel.get()) {
                    progressWin.close();
                    return;
                }

                // 若用户勾选了「同时安装加载器」，在原版下载成功后继续装加载器
                String installedVersionId = versionId;
                if (ok && installLoader) {
                    String loaderType = loaderTypeNow;
                    String loaderVer = loaderVerNow;

                    if (loaderType != null && loaderVer != null && !loaderVer.isBlank()) {
                        installedVersionId = installLoaderAfterDownload(
                                versionId, loaderType, loaderVer, progressWin);
                    } else {
                        progressWin.appendLog(L.t("s_808f3d1235"));
                    }
                }

                // 加载器安装也可能耗时较久，装完后再确认一次取消
                if (currentCancel.get()) {
                    progressWin.close();
                    return;
                }

                final boolean finalOk = ok;
                final String finalVersionId = installedVersionId;

                activeProgressWindow = null;

                if (finalOk) {
                    boolean loaderInstalled = !finalVersionId.equals(versionId);
                    progressWin.markFinished(true, loaderInstalled
                            ? L.t("s_461ed13c3a") + finalVersionId + L.t("s_1689d8fbd2")
                            : L.t("s_393e769d23") + versionId + L.t("s_161d93c654"));
                    progressWin.appendLog(L.t("s_4bbcf94739"));

                    if (statusConsumer != null) {
                        statusConsumer.accept(loaderInstalled
                                ? L.t("s_f54e2e9eaa") + finalVersionId
                                : L.t("s_6d06a95bdd") + versionId);
                    }
                } else {
                    progressWin.markFinished(false, L.t("s_bdb3730739"));
                }

            } catch (Exception e) {
                if (currentCancel.get()) {
                    logger.info("下载已取消");
                    progressWin.close();
                    return;
                }
                logger.error("下载异常", e);
                progressWin.markFinished(false, L.t("s_f8ec176452") + e.getMessage());
                progressWin.appendLog("[异常] " + e.getMessage());
            } finally {
                // 在 FX 线程上释放面板引用并回收下载器：取消路径可能提前 return，
                // 若不在这里统一清空，activeProgressWindow 会一直指向旧面板，
                // 直到下次点「开始下载」——它只会重开旧窗口，永远无法重新下载
                javafx.application.Platform.runLater(() -> {
                    if (activeProgressWindow == progressWin) {
                        activeProgressWindow = null;
                    }
                    gameDownloading = false;
                    // 无论成功失败，按钮恢复为「开始下载」
                    if (gameDownloadButton != null) {
                        gameDownloadButton.setText(L.t("s_92f35590d5"));
                        gameDownloadButton.setDisable(false);
                    }
                });
                try {
                    if (gameDownloader != null) {
                        gameDownloader.shutdown();
                    }
                } catch (Exception e) {
                    logger.debug("关闭下载器失败: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 拉取当前选中游戏版本可用的加载器版本列表
     *
     * 加载器版本是依赖游戏版本的，所以游戏版本变化时也要重新拉。
     */
    private void loadLoaderVersionsForDownload() {
        if (loadingLoaderVersions) {
            return;
        }

        String selected = currentVersionId;
        String type = loaderTypeCombo.getSelectionModel().getSelectedItem();
        if (selected == null || type == null) {
            return;
        }

        String gameVersion = selected;

        loadingLoaderVersions = true;
        loaderVersionCombo.getItems().clear();
        loaderVersionCombo.setPromptText(L.t("s_514c33af5c"));
        loaderHintLabel.setText(L.t("s_bd9a1236bf") + type + L.t("s_a1ef0a01e6"));

        CompletableFuture.supplyAsync(() -> {
            try {
                return switch (type) {
                    case "Fabric" -> apiClient.getFabricVersions(gameVersion);
                    case "Forge" -> apiClient.getForgeVersions(gameVersion);
                    default -> apiClient.getOptifineVersions(gameVersion);
                };
            } catch (Exception e) {
                logger.error("获取加载器版本失败", e);
                return null;
            }
        }).thenAccept(list -> Platform.runLater(() -> {
            loadingLoaderVersions = false;
            loaderVersionCombo.setPromptText(L.t("s_3dc7ad0b70"));

            if (list == null || list.isEmpty()) {
                loaderHintLabel.setText(L.t("s_c2924103b0"));
                loaderVersionCombo.setPromptText(L.t("s_a14b15b788") + type + L.t("s_d0e9f788da"));
                logger.info("未找到适用于 {} 的 {} 版本", gameVersion, type);
                return;
            }

            loaderVersionCombo.getItems().setAll(list);
            loaderVersionCombo.getSelectionModel().select(0);
            loaderHintLabel.setText(list.size() + L.t("s_3ff6c7c2a8"));
        }));
    }

    /**
     * 在原版下载完成后安装加载器（在后台线程中调用）
     *
     * @return 安装后的版本 ID；失败时返回原版版本 ID
     */
    private String installLoaderAfterDownload(String gameVersion, String loaderType,
                                              String loaderVersion,
                                              com.mclauncher.ui.window.DownloadProgressWindow pw) {
        pw.appendLog(L.t("s_b4056e1002") + loaderType + " " + loaderVersion);
        pw.updateProgress(98, 100, L.t("s_8b50af569e") + loaderType + "...");

        ModLoaderInstaller installer =
                new ModLoaderInstaller(apiClient, config.getGameDir());

        try {
            String id = switch (loaderType) {
                case "Fabric" -> installer.installFabric(gameVersion, loaderVersion);
                case "Forge" -> installer.installForge(gameVersion, loaderVersion);
                default -> installer.installOptifine(gameVersion, loaderVersion);
            };

            pw.appendLog(L.t("s_4b5592c1d3") + id);
            return id;

        } catch (Exception e) {
            logger.error("随游戏安装加载器失败", e);
            pw.appendLog("[加载器安装失败] " + e.getMessage());
            // 原版已下好，仍算成功，只是没装上加载器
            return gameVersion;

        } finally {
            installer.shutdown();
        }
    }

    /**
     * 取消下载（由进度窗口的关闭/取消操作触发）
     */
    private void cancelGameDownload() {
        if (gameDownloader != null && (gameDownloading || activeProgressWindow != null)) {
            // 只置取消标志，后台线程收尾后统一把 activeProgressWindow 置空；
            // 这里提前置空会导致后台完成时找不到面板，遮罩卡死在屏幕上
            if (currentCancelRef != null) currentCancelRef.set(true);
            gameDownloader.cancel();
            logger.info("用户取消了下载");
        }
        if (gameDownloadButton != null) {
            gameDownloadButton.setDisable(false);
            gameDownloadButton.setText(L.t("s_92f35590d5"));
        }
    }

    // ==================== 模组逻辑 ====================

    private void searchMods(int page) {
        String rawQuery = modSearchField.getText();
        String rawVersion = modVersionFilter.getSelectionModel().getSelectedItem();
        String rawLoader = modLoaderFilter.getSelectionModel().getSelectedItem();

        final String query = rawQuery == null ? "" : rawQuery.trim();
        final String version = (rawVersion == null || rawVersion.isBlank()
                || L.t("s_eaf1b3133c").equals(rawVersion)) ? null : rawVersion;
        final String loader = L.t("s_778fc8f994").equals(rawLoader) ? null : rawLoader;

        final int gen = modView.next();
        setModLoading(true, page == 0 ? L.t("s_befe807824") : L.t("s_d7cb9df7e2"));
        modSearchButton.setDisable(true);
        modSearchButton.setText(L.t("s_8694cd1416"));
        currentPage = page;
        modPageLabel.setText(L.t("s_f495347d6a") + (page + 1) + L.t("s_af8438feb2"));

        CompletableFuture.supplyAsync(() -> {
            try {
                return modrinthClient.search(query, version, loader,
                        page * PAGE_SIZE, PAGE_SIZE);
            } catch (Exception e) {
                logger.error("搜索失败", e);
                return null;
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            // 已有更新的请求在途：丢弃旧响应，避免「快速连续搜索时列表停在旧结果」
            if (!modView.isCurrent(gen)) {
                return;
            }
            setModLoading(false, null);
            modSearchButton.setDisable(false);
            modSearchButton.setText(L.t("s_f04090805c"));

            if (result == null) {
                // 关键：失败时不清空已有结果，只提示
                modStatusLabel.setText(L.t("s_f0a914e43d"));
                return;
            }

            searchResults = result.getProjects();

            // 简介表没就绪时先等待：否则首屏是英文，之后没人会去改它
            presentWhenReady(modResultList, new ArrayList<>(searchResults),
                    query.isEmpty() ? L.t("s_7e17935278") : L.t("s_988282b375") + query,
                    String.format(L.t("s_8a6085c3ba"), result.getTotalHits()),
                    result, "mod");
        }));
    }

    /**
     * 加载热门模组（首次进入模组页时调用）
     *
     * 用「空查询 + 按下载量排序」拿到 Modrinth 的热门榜单。
     * 目的：用户刚进来时不要面对一片空白。
     */
    private void loadPopularMods() {
        final int gen = modView.next();
        loadingPopular = true;
        setModLoading(true, L.t("s_aacaa6001e"));
        modResultTitle.setText(L.t("s_7e17935278"));

        CompletableFuture.supplyAsync(() -> {
            try {
                return modrinthClient.search("", null, null, 0, 20, "downloads");
            } catch (Exception e) {
                logger.error("加载热门模组失败", e);
                return null;
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            // 玩家已经先发起了搜索：热门榜的响应直接丢弃，否则会盖掉搜索结果。
            // 丢弃时也必须复位 loadingPopular：否则 onShow 的「列表为空 && !loadingPopular」
            // 永远不成立，热门榜从此不会再自动加载。
            if (!modView.isCurrent(gen)) {
                loadingPopular = false;
                return;
            }
            if (result == null) {
                loadingPopular = false;
                setModLoading(false, null);
                modResultList.setPlaceholder(new Label(L.t("s_f9119f596f")));
                return;
            }
            searchResults = result.getProjects();
            // 等简介 + 图标都就绪再一次性上屏，避免「先英文后中文」跳变
            presentWhenReady(modResultList, new ArrayList<>(searchResults), L.t("s_7e17935278"),
                    L.t("s_c8d76324ee") + result.getTotalHits() + L.t("s_aa10f570ef"),
                    result, "mod");
        }));
    }

    /**
     * 等简介表就绪后再上屏
     *
     * 简介表是 1.7MB（gzip）/5.7MB（原始）的 JSON，慢网络下要几十秒。
     * 如果先把列表画出来，玩家看到的就是一串英文，而且之后再没有机制去改它
     * —— 这正是「必须手动点开一个模组才会变中文」的原因。
     *
     * 所以策略是：**没就绪就先显示加载提示，不渲染英文结果**；
     * 就绪后一次性画出中文 + 图标。
     * 只有长时间（60 秒）仍失败时才退回英文，避免永远空白。
     */
    private void presentWhenReady(ListView<ModrinthClient.ModProject> list,
                                  List<ModrinthClient.ModProject> items,
                                  String title, String status,
                                  ModrinthClient.SearchResult result,
                                  String kind) {
        if (mcmodIntros != null && mcmodIntros.isLoaded()) {
            renderResults(list, items, title, status, result, kind);
            return;
        }

        // 正在等简介：明确告诉玩家在等什么，而不是先给英文再跳变
        if ("mod".equals(kind)) {
            setModLoading(true, L.t("s_842255d115"));
        } else {
            packLoadingLabel.setText(L.t("s_842255d115"));
            packLoadingIndicator.setVisible(true);
            packLoadingIndicator.setManaged(true);
        }

        pendingIntro.put(list, new IntroTicket(items, title, status, result, kind));
        waitIntro(list, kind);
    }

    /**
     * 简介表就绪前每 100ms 检查一次；最多等 60 秒
     *
     * 只靠这个定时器兜底：onLoaded 回调也会触发同一次上屏，两边都通过
     * takePending() 抢同一张令牌，先到的那个执行，后到的拿不到就不做。
     * 每轮都重新从 map 里取令牌，被新一次搜索替换掉的旧令牌会自然停下。
     */
    private void waitIntro(ListView<ModrinthClient.ModProject> list, String kind) {
        IntroTicket ticket = pendingIntro.get(list);
        if (ticket == null) {
            return;
        }
        if (mcmodIntros != null && mcmodIntros.isLoaded()) {
            firePending(list, ticket);
            return;
        }
        if (++ticket.ticks >= 600) {     // 60 秒
            logger.warn("简介表长时间未就绪，先显示英文结果");
            firePending(list, ticket);
            return;
        }
        CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> Platform.runLater(() -> waitIntro(list, kind)));
    }

    /** 抢令牌并上屏：同一份结果只会被应用一次 */
    private void firePending(ListView<ModrinthClient.ModProject> list, IntroTicket ticket) {
        if (takePending(list) != ticket) {
            return;
        }
        if (ticket.hidePackLoading && packLoadingIndicator != null) {
            packLoadingIndicator.setVisible(false);
            packLoadingIndicator.setManaged(false);
            if (packLoadingLabel != null) {
                packLoadingLabel.setText("");
            }
        }
        renderResults(list, ticket.items, ticket.title, ticket.status,
                ticket.result, ticket.kind);
    }

    /** 一次「等简介再上屏」的待办；ticks 在定时器里累加 */
    private static final class IntroTicket {
        final java.util.List<ModrinthClient.ModProject> items;
        final String title;
        final String status;
        final ModrinthClient.SearchResult result;
        final String kind;
        final boolean hidePackLoading;
        int ticks;

        IntroTicket(List<ModrinthClient.ModProject> items,
                    String title, String status,
                    ModrinthClient.SearchResult result, String kind) {
            this.items = items;
            this.title = title;
            this.status = status;
            this.result = result;
            this.kind = kind;
            // 用 kind 判断（"mod" 只在模组页传入），静态内部类碰不到实例字段
            this.hidePackLoading = !"mod".equals(kind);
        }
    }

    /**
     * 列表上屏：图标全部就绪后才切换内容（避免逐张图片跳出来）
     *
     * 注意 kind 必须原样传下去。之前这里写死成 "mod"，整合包的结果会被
     * 当成模组去操作 modStatusLabel / modResultTitle，触发 NPE。
     */
    private void renderResults(ListView<ModrinthClient.ModProject> list,
                               List<ModrinthClient.ModProject> items,
                               String title, String status,
                               ModrinthClient.SearchResult result, String kind) {
        // 用列表身份判断，而不是靠调用方传进来的字符串标记 ——
        // 字符串一旦传错（之前确实传错过），就会去操作另一个 Tab 的控件。
        final boolean isMod = list == modResultList;

        // **立刻上屏**，不要等图标。
        // 实测 20 张图标串行下载合计 73 秒、最慢单张 15 秒
        // （Modrinth 的 webp 缩略图 JavaFX 解不了，要走一次原始图查询 + 下载）。
        // 如果等图标全部就绪再显示，页面会空白一分钟以上。
        // 单元格本身支持「先占位、图标到了再补」，所以直接上屏完全不会闪。
        applyModResults(list, items, title, status, result, isMod);

        // 图标在后台预取；每批下完就让列表重绘一次，
        // 单元格的 mountIconCached 会从缓存取到图并替换占位。
        // （重绘是 ListView.refresh，单元格节点复用，不会闪烁。）
        ensureIconsLoaded(items, () -> {
            if (isMod ? modResultList != null : packResultList != null) {
                list.refresh();
            }
        });
    }

    /** 真正替换列表内容（图标与简介都已在缓存里） */
    private void applyModResults(ListView<ModrinthClient.ModProject> list,
                                 List<ModrinthClient.ModProject> items,
                                 String title, String status,
                                 ModrinthClient.SearchResult result, boolean isMod) {
        // 只复位属于本列表的状态：模组页和整合包页共用这个方法，
        // 之前无条件调 setModLoading 会在整合包页上碰 modStatusLabel 为 null
        // （那个 Tab 还没被构建过）而抛 NPE，渲染中途断掉。
        if (isMod) {
            loadingPopular = false;
            setModLoading(false, null);
        } else {
            packLoading = false;
            if (packLoadingIndicator != null) {
                packLoadingIndicator.setVisible(false);
                packLoadingIndicator.setManaged(false);
            }
            if (packLoadingLabel != null) {
                packLoadingLabel.setText("");
            }
        }

        // 换内容的瞬间列表会先清空再重建，视觉上是一帧白闪。
        // 隐藏后在同一帧里替换完再淡入：玩家只会看到一次柔和过渡，而不是闪一下。
        list.setOpacity(0);
        // 必须拷一份副本：packResults 是复用的同一个 ArrayList，下一次搜索会
        // clear() 再 addAll()；列表持有它的引用，内容会在搜索途中原地变化。
        list.getItems().setAll(new ArrayList<>(items));
        javafx.animation.FadeTransition fade =
                new javafx.animation.FadeTransition(javafx.util.Duration.millis(140), list);
        fade.setFromValue(0);
        fade.setToValue(1);
        // 等布局跑完一帧再开始淡入，避免淡入的起点仍是空列表
        javafx.application.Platform.runLater(fade::play);

        // 控件可能还没创建：模组页的结果可能在玩家打开「下载」页之前就回来了
        // （启动时的预热路径），此时 Tab 尚未构建，控件全是 null。
        setLabel(isMod ? modResultTitle : packResultTitle, title);
        setLabel(isMod ? modStatusLabel : packStatusLabel, status);

        if (items.isEmpty()) {
            list.setPlaceholder(new Label(isMod ? L.t("s_1132e0e407") : L.t("s_fa6ff73815")));
            if (isMod) {
                modNextPageButton.setDisable(true);
            } else {
                packNextPageButton.setDisable(true);
            }
            return;
        }
        if (result != null) {
            boolean noMore = result.getOffset() + result.getLimit() >= result.getTotalHits();
            if (isMod) {
                modNextPageButton.setDisable(noMore);
            } else {
                packNextPageButton.setDisable(noMore);
            }
        }

        // 自动选中第一项：监听器已不再弹窗，这里不需要任何抑制标志
        list.getSelectionModel().select(0);
    }

    /** 只在控件存在时设文本（Tab 可能还没被构建过） */
    private static void setLabel(Label l, String text) {
        if (l != null) {
            l.setText(text);
        }
    }

    /**
     * 显示/隐藏模组区的加载提示
     *
     * 注意：只控制提示本身，**不动列表内容** ——
     * 这样用户在等待时仍能看到之前的结果，不会觉得「卡死/内容消失」。
     */
    private void setModLoading(boolean loading, String text) {
        if (modLoadingIndicator != null) {
            modLoadingIndicator.setVisible(loading);
            modLoadingIndicator.setManaged(loading);
        }
        if (modLoadingLabel != null) {
            modLoadingLabel.setText(loading && text != null ? text : "");
        }
    }

    /**
     * 更新右侧「已选模组」卡片
     */
    private static String formatDownloadsShort(long n) {
        if (n >= 100_000_000) return String.format(L.t("s_48be8f0bcb"), n / 100_000_000.0);
        if (n >= 10_000) return String.format(L.t("s_c8af81cb79"), n / 10_000.0);
        return String.valueOf(n);
    }

    /**
     * 打开版本选择窗口（模态）
     *
     * 交互重构：点击「查看可选版本」不再跳转/清空页面，
     * 而是弹出独立窗口，原搜索结果完整保留。
     */
    /**
     * 填充模组页的「游戏版本」下拉框
     *
     * 数据来源是**本地已安装的游戏版本**（扫描游戏目录下的 versions/），
     * 而不是模组支持的所有版本 —— 用户关心的是「我自己装了哪些版本」。
     *
     * 这样智能推荐才能拿到正确的 preferredGameVersion。
     */
    /**
     * 填充模组页的「游戏版本」下拉框
     *
     * 关键：下拉框显示的是**本地版本目录名**（如 `1.20.1-fabric0.20.1`），
     * 但传给「智能推荐」的必须是**纯游戏版本号**（`1.20.1`）——
     * 模组的 game_versions 字段存的是后者。直接拿目录名去匹配永远匹配不上，
     * 于是推荐会退化到「最新发布」（例如 26.3），与用户实际在玩的版本无关。
     */
    private void loadLocalGameVersions() {
        if (modVersionFilter == null) {
            return;
        }

        java.nio.file.Path versionsDir = config.getGameDir().resolve("versions");
        List<LocalVersion> local = new ArrayList<>();

        if (java.nio.file.Files.isDirectory(versionsDir)) {
            try (var stream = java.nio.file.Files.newDirectoryStream(versionsDir)) {
                for (java.nio.file.Path p : stream) {
                    if (java.nio.file.Files.isDirectory(p)
                            && java.nio.file.Files.exists(
                                    p.resolve(p.getFileName() + ".json"))) {
                        String dirName = p.getFileName().toString();
                        local.add(new LocalVersion(dirName, extractMcVersion(dirName)));
                    }
                }
            } catch (Exception e) {
                logger.debug("扫描本地版本失败: {}", e.getMessage());
            }
        }

        local.sort((a, b) -> b.mcVersion.compareToIgnoreCase(a.mcVersion));

        String previous = modVersionFilter.getSelectionModel().getSelectedItem();

        // 同整合包页：填充/选中都会触发 onAction，不拦住就会递归搜索、页面狂闪
        suppressModFilterAction = true;
        try {
            modVersionFilter.getItems().clear();
            localVersions = local;

            if (local.isEmpty()) {
                modVersionFilter.setPromptText(L.t("s_6f39c52c35"));
                modVersionFilter.setDisable(true);
                return;
            }

            modVersionFilter.setDisable(false);
            for (LocalVersion lv : local) {
                modVersionFilter.getItems().add(lv.dirName);
            }

            int idx = 0;
            if (previous != null) {
                for (int i = 0; i < local.size(); i++) {
                    if (local.get(i).dirName.equals(previous)) {
                        idx = i;
                        break;
                    }
                }
            }
            modVersionFilter.getSelectionModel().select(idx);
        } finally {
            suppressModFilterAction = false;
        }
    }

    /**
     * 从版本目录名里提取纯 Minecraft 版本号
     *
     *   1.20.1                  -> 1.20.1
     *   1.20.1-fabric0.20.1     -> 1.20.1
     *   1.20.1-forge-47.2.0     -> 1.20.1
     *   1.20.1-OptiFine_HD_U_I5 -> 1.20.1
     */
    private String extractMcVersion(String dirName) {
        if (dirName == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+\\.\\d+(?:\\.\\d+)?)")
                .matcher(dirName);
        if (m.find()) {
            return m.group(1);
        }
        return dirName;
    }

    /**
     * 取得当前选中项对应的纯游戏版本号（供智能推荐使用）
     */
    private String getSelectedMcVersion() {
        if (modVersionFilter == null || localVersions == null || localVersions.isEmpty()) {
            return config.getGameVersion();
        }
        String sel = modVersionFilter.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return config.getGameVersion();
        }
        for (LocalVersion lv : localVersions) {
            if (lv.dirName.equals(sel)) {
                return lv.mcVersion;
            }
        }
        return extractMcVersion(sel);
    }

    /** 本地版本条目：目录名（展示） + 纯游戏版本（推荐匹配） */
    private static class LocalVersion {
        final String dirName;
        final String mcVersion;

        LocalVersion(String dirName, String mcVersion) {
            this.dirName = dirName;
            this.mcVersion = mcVersion;
        }
    }

    private void openVersionChooser() {
        openVersionChooser(selectedProject);
    }

    private void openVersionChooser(ModrinthClient.ModProject project) {
        if (project == null) {
            int idx = modResultList.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < searchResults.size()) {
                project = searchResults.get(idx);
            }
        }
        if (project == null) {
            return;
        }

        if (modalHost == null) {
            logger.warn("ModalHost 未注入，无法打开版本选择面板");
            return;
        }

        // 取「纯游戏版本号」作为推荐依据。
        // 注意不能直接用下拉框显示值（那是版本目录名，如 1.20.1-fabric0.20.1），
        // 否则模组的 game_versions 匹配不上，推荐会退化到最新版本。
        String preferred = getSelectedMcVersion();

        ModVersionChooserWindow window = new ModVersionChooserWindow(
                modalHost,
                modrinthClient,
                modDownloader,
                project,
                preferred,
                config.getGameDir(),
                msg -> {
                    if (statusConsumer != null) {
                        statusConsumer.accept(msg);
                    }
                });
        // 详情页也用同一份中文简介映射（列表页已加载，不会重复下载）
        window.setIntroMap(mcmodIntros);
        window.show();
    }

    /**
     * 下载当前选定的版本
     *
     * 保留此方法以兼容旧的隐藏下载按钮；
     * 正常流程下用户是通过版本选择窗口下载的。
     */
    private void downloadSelectedMod() {
        if (chosenVersion == null) {
            return;
        }
        modStatusLabel.setText(L.t("s_d6ad72122d"));
    }
}
