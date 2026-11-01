package com.mclauncher.ui.window;


import com.mclauncher.i18n.L;
import com.mclauncher.mod.ModDownloader;
import com.mclauncher.mod.ModrinthClient;
import com.mclauncher.ui.component.ModalHost;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 模组版本选择面板（内嵌弹窗）
 *
 * 交互要点：
 * 1. 内嵌在主窗口内（ModalHost），跟随主窗口移动
 * 2. 打开时先显示加载圈，数据到位后替换为内容
 * 3. 顶部「智能推荐」卡片，高亮置顶
 * 4. **版本按游戏版本分组，可折叠展开**（默认只展开最新一组）
 * 5. 每行格式为 `游戏版本 --> 模组版本`
 *
 * 所有网络请求都在后台线程执行，不阻塞 JavaFX 线程。
 */
public class ModVersionChooserWindow {

    private static final Logger logger = LoggerFactory.getLogger(ModVersionChooserWindow.class);

    private final ModalHost modalHost;
    private final ModrinthClient modrinthClient;
    private final ModDownloader modDownloader;
    private final ModrinthClient.ModProject project;
    /** 用户当前的游戏版本，用于智能推荐与默认展开 */
    private final String preferredGameVersion;
    private final Path gameDir;
    private final Consumer<String> statusConsumer;

    // ---- 数据 ----
    /** 全部版本（未过滤） */
    private List<ModrinthClient.ModVersion> allVersions = new ArrayList<>();
    /** 智能推荐 */
    private ModrinthClient.ModVersion recommended;
    /** 当前选中的版本 */
    private ModrinthClient.ModVersion selected;
    /**
     * 本窗口是否已经关闭
     *
     * 首次打开就要分页拉几百个版本；玩家扫一眼就关掉是常态。
     * 不拦一下，关窗后剩下的每一页都会继续下载并构建界面（浪费流量，
     * 而且在不可见节点上反复布局）。
     */
    private volatile boolean closed = false;
    /** 分组容器是否有待重建（在一次批量更新里只重建最后一遍） */
    private boolean rebuildPending = false;

    // ---- UI ----
    private StackPane contentHost;
    private VBox loadingBox;
    private VBox contentBox;
    private VBox recommendBox;
    /** 分组容器（按游戏版本折叠） */
    private VBox groupContainer;
    private ComboBox<String> gameVersionFilter;
    private ComboBox<String> loaderFilter;
    private ComboBox<String> sortCombo;
    private Label summaryLabel;
    /** 中文简介来源（可为 null，此时退回 Modrinth 的英文简介） */
    private com.mclauncher.mod.McmodIntroMap introMap;
    private Button downloadButton;
    private ProgressBar downloadProgress;
    private Label downloadStatus;
    private Label selectedLabel;

    public ModVersionChooserWindow(ModalHost modalHost,
                                   ModrinthClient modrinthClient,
                                   ModDownloader modDownloader,
                                   ModrinthClient.ModProject project,
                                   String preferredGameVersion,
                                   Path gameDir,
                                   Consumer<String> statusConsumer) {
        this.modalHost = modalHost;
        this.modrinthClient = modrinthClient;
        this.modDownloader = modDownloader;
        this.project = project;
        this.preferredGameVersion = preferredGameVersion;
        this.gameDir = gameDir;
        this.statusConsumer = statusConsumer;
    }

    /**
     * 设置中文简介来源
     *
     * 详情页原本直接显示 Modrinth 的英文描述，和列表页的中文简介不一致。
     * 列表页已经加载了映射表，这里复用同一个实例即可（不重复下载）。
     */
    public void setIntroMap(com.mclauncher.mod.McmodIntroMap introMap) {
        this.introMap = introMap;
    }

    public void show() {
        BorderPane root = buildUI();
        modalHost.show(L.t("s_57355c8136") + project.getTitle(), root, () -> closed = true);

        // 先显示加载圈，再异步拉数据
        showLoading();
        loadVersions();
    }

    // ==================== UI 骨架 ====================

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("version-dialog-root");
        root.setPrefSize(820, 560);
        // 弹窗宿主（BorderPane.center / HBox / StackPane）会严格尊重子节点 min，
        // 内容一多根节点的 min 就超过窗口高度，底部的下载按钮直接被挤出可视区
        root.setMinHeight(0);

        // 顶部：模组简介
        VBox header = new VBox(4);
        header.setPadding(new Insets(16, 20, 8, 20));

        String desc = introMap == null ? null
                : introMap.lookupIntro(project.getTitle(), project.getSlug());
        if (desc == null || desc.isBlank()) {
            desc = project.getDescription();
        }
        if (desc == null) {
            desc = "";
        }
        Label sub = new Label(desc.length() > 160 ? desc.substring(0, 160) + "..." : desc);
        sub.getStyleClass().add("page-subtitle");
        sub.setWrapText(true);
        // 详情页宽度 820，长简介要能完整换行显示
        sub.setMaxWidth(Double.MAX_VALUE);

        header.getChildren().add(sub);

        // 中部：加载圈 与 内容 叠放
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(46, 46);

        Label loadingText = new Label(L.t("s_24a3ae10d7"));
        loadingText.getStyleClass().add("text-secondary");

        loadingBox = new VBox(14, spinner, loadingText);
        loadingBox.setAlignment(Pos.CENTER);

        contentBox = buildContent();
        contentBox.setVisible(false);
        contentBox.setManaged(false);

        contentHost = new StackPane(loadingBox, contentBox);
        // StackPane 居中布局会让子节点的 min 变成自身 min，必须放开
        contentHost.setMinHeight(0);
        contentHost.setPadding(new Insets(0, 20, 8, 20));
        VBox.setVgrow(contentHost, Priority.ALWAYS);

        // 底部：选中信息 + 按钮
        HBox footer = new HBox(10);
        footer.setPadding(new Insets(8, 20, 16, 20));
        footer.setAlignment(Pos.CENTER_LEFT);

        selectedLabel = new Label(L.t("s_2d9db6ca66"));
        selectedLabel.getStyleClass().add("text-secondary");
        selectedLabel.setWrapText(true);
        HBox.setHgrow(selectedLabel, Priority.ALWAYS);

        downloadStatus = new Label("");
        downloadStatus.getStyleClass().add("text-muted");

        downloadButton = new Button(L.t("s_9d34c0f0f7"));
        downloadButton.getStyleClass().add("button-primary");
        downloadButton.setDisable(true);
        downloadButton.setOnAction(e -> downloadSelected());

        footer.getChildren().addAll(selectedLabel, downloadStatus, downloadButton);

        // 下载进度条（默认隐藏）
        downloadProgress = new ProgressBar(0);
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        downloadProgress.setVisible(false);
        downloadProgress.setManaged(false);

        VBox bottom = new VBox(downloadProgress, footer);

        root.setTop(header);
        root.setCenter(contentHost);
        root.setBottom(bottom);
        return root;
    }

    private VBox buildContent() {
        VBox box = new VBox(12);
        // 弹窗宿主会按子节点 min 分配高度；不放开 min，窗口一矮整块内容就被压成一条
        box.setMinHeight(0);

        // ---- 智能推荐卡片 ----
        recommendBox = new VBox(6);
        recommendBox.getStyleClass().add("recommend-card");
        recommendBox.setVisible(false);
        recommendBox.setManaged(false);

        // ---- 筛选与排序 ----
        FlowPane filterRow = new FlowPane(10, 8);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        Label gvLabel = new Label(L.t("s_f4e1ba8015"));
        gvLabel.getStyleClass().add("text-secondary");
        gvLabel.setMinWidth(Region.USE_PREF_SIZE);

        gameVersionFilter = new ComboBox<>();
        gameVersionFilter.setPrefWidth(130);
        gameVersionFilter.setMinWidth(110);
        gameVersionFilter.setOnAction(e -> rebuildGroups());

        Label ldLabel = new Label(L.t("s_455e832fd9"));
        ldLabel.getStyleClass().add("text-secondary");
        ldLabel.setMinWidth(Region.USE_PREF_SIZE);

        loaderFilter = new ComboBox<>();
        loaderFilter.setPrefWidth(115);
        loaderFilter.setMinWidth(95);
        loaderFilter.setOnAction(e -> rebuildGroups());

        Label stLabel = new Label(L.t("s_dc35af8d69"));
        stLabel.getStyleClass().add("text-secondary");
        stLabel.setMinWidth(Region.USE_PREF_SIZE);

        sortCombo = new ComboBox<>();
        sortCombo.getItems().addAll(L.t("s_6e4042d8e9"), L.t("s_9ae7e2b48d"));
        sortCombo.getSelectionModel().select(0);
        sortCombo.setPrefWidth(150);
        sortCombo.setMinWidth(130);
        sortCombo.setOnAction(e -> rebuildGroups());

        summaryLabel = new Label("");
        summaryLabel.getStyleClass().add("text-muted");

        filterRow.getChildren().addAll(gvLabel, gameVersionFilter,
                ldLabel, loaderFilter, stLabel, sortCombo, summaryLabel);

        // ---- 分组列表（可滚动）----
        groupContainer = new VBox(8);

        ScrollPane scroll = new ScrollPane(groupContainer);
        scroll.setFitToWidth(true);
        // 绝对不能给滚动区设 minHeight：父级（VBox → BorderPane.center → 弹窗）
        // 会**严格遵守**子节点的 min，不按可用高度压缩。之前这里设了 200，
        // 窗口矮的时候整张卡片被撑到窗口外，底部按钮被挤出可视区。
        scroll.setMinHeight(0);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(recommendBox, filterRow, scroll);
        return box;
    }

    private void showLoading() {
        if (loadingBox != null) {
            loadingBox.setVisible(true);
            loadingBox.setManaged(true);
        }
        if (contentBox != null) {
            contentBox.setVisible(false);
            contentBox.setManaged(false);
        }
    }

    private void showContent() {
        if (loadingBox != null) {
            loadingBox.setVisible(false);
            loadingBox.setManaged(false);
        }
        if (contentBox != null) {
            contentBox.setVisible(true);
            contentBox.setManaged(true);
        }
    }

    // ==================== 数据 ====================

    // 下拉框只在本窗口自己的批量更新期间触发 rebuildGroups()；
    // 每页数据到了都要重建一次筛选、重新推荐、重画分组，三次全走一遍太多。
    private int bulkUpdateDepth = 0;

    private void beginBulkUpdate() {
        bulkUpdateDepth++;
        rebuildPending = false;
    }

    /**
     * 结束一次批量更新
     *
     * rebuildPending 必须是「自从 begin 之后有没有过重建请求」的标记 ——
     * 之前 refreshAll 自己先把它置 true，于是第一页数据到达时
     * endBulkUpdate 误以为已经重建过，分组永远不画，页面只剩「正在获取版本列表」。
     */
    private void endBulkUpdate() {
        if (bulkUpdateDepth > 0 && --bulkUpdateDepth == 0 && rebuildPending) {
            rebuildGroups();
        }
    }

    /**
     * 加载版本列表（流式）
     *
     * 先拉第一页（默认 30 条）：通常半秒内就能看到可用版本。
     * 之后在后台继续按页拉完 —— 之前是一次性取全量再整段解析，
     * 几百个版本期间界面一直只有「正在加载完整版本列表」，
     * 玩家等不及关掉窗口，就觉得「版本不全」。
     */
    private void loadVersions() {
        CompletableFuture.runAsync(() -> {
            boolean complete;
            try {
                complete = modrinthClient.fetchVersionsPaged(
                        project.getProjectId(), FIRST_PAGE_SIZE, () -> closed,
                        page -> Platform.runLater(() -> onVersionPage(page)));
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                logger.error("获取版本列表失败", e);
                Platform.runLater(this::onLoadFailed);
                return;
            }
            // 关闭导致的提前退出不算「拉完了」，不再投递完成回调
            if (!complete && closed) {
                return;
            }
            final boolean done = complete;
            Platform.runLater(() -> onLoadDone(done));
        });
    }

    /** 一页版本到了（FX 线程） */
    private void onVersionPage(List<ModrinthClient.ModVersion> page) {
        if (closed) {
            return;
        }
        if (allVersions.isEmpty()) {
            showContent();
        }

        // 按 id 去重：第一页可能和后续页有重叠（项目在拉取途中发了新版本，
        // offset 会整体后移一条）。
        Set<String> seen = new HashSet<>();
        for (ModrinthClient.ModVersion v : allVersions) {
            seen.add(v.getId());
        }
        for (ModrinthClient.ModVersion v : page) {
            // 有主文件的版本排前面；没有文件的版本末尾单独提示，不直接丢 ——
            // 直接丢会让「总计」比接口返回的少，玩家会以为版本不全
            if (v.getPrimaryFile() == null) {
                if (noFileIds.add(v.getId())) {
                    versionsWithoutFile++;
                }
                continue;
            }
            if (seen.add(v.getId())) {
                allVersions.add(v);
            }
        }

        // 顺序很重要：先刷新筛选、再重画分组。
        // buildFilters 里 select() 会触发 onAction → rebuildGroups，
        // 而它此时沿用的还是**旧的**筛选值（例如上一批数据里的「全部」），
        // 重画出来的是全量视图；紧接着这次重建又会重新计算筛选下拉框，
        // 选中项被改回偏好版本，再次重建后只筛选了工具函数，分组就只剩一个。
        // 所以：buildFilters 自己变了选中值时，由它的 onAction 负责重建；
        // 次数增减导致这次才首次选中时，onAction 未必再触发，这里补一次。
        computeRecommendation();
        boolean filterChanged;
        beginBulkUpdate();
        try {
            filterChanged = buildFilters();
            renderRecommendation();
        } finally {
            endBulkUpdate();
        }
        if (!filterChanged) {
            rebuildGroups();
        }

        if (summaryLabel != null && !loadComplete) {
            summaryLabel.setText(L.t("s_bab6c45baa") + allVersions.size() + L.t("s_4214f89938"));
        }
    }

    /** 全部页拉完（或第一页就没有数据） */
    private void onLoadDone(boolean complete) {
        if (closed) {
            return;
        }
        loadComplete = true;
        if (allVersions.isEmpty() && versionsWithoutFile == 0) {
            showContent();
            if (summaryLabel != null) {
                summaryLabel.setText(complete
                        ? L.t("s_7bfa7c10dc")
                        : L.t("s_9554c7f69e"));
            }
            return;
        }
        if (!complete) {
            logger.warn("版本列表未拉完，仅显示已载入的 {} 个", allVersions.size());
        }
        rebuildGroups();
        if (hasOnlyVersionsWithoutFile()) {
            // 有版本，但没有一个带主文件
            if (summaryLabel != null) {
                summaryLabel.setText(L.t("s_6e18b09c2e") + versionsWithoutFile + L.t("s_45dc5e9ff3"));
            }
        }
    }

    private boolean hasOnlyVersionsWithoutFile() {
        return allVersions.isEmpty() && versionsWithoutFile > 0;
    }

    private void onLoadFailed() {
        if (closed) {
            return;
        }
        loadComplete = true;
        showContent();
        if (allVersions.isEmpty()) {
            if (summaryLabel != null) {
                summaryLabel.setText(L.t("s_9554c7f69e"));
            }
            return;
        }
        // 也要重画一次：否则分组标题里的「N 个版本」停在最后一页到达时的数字，
        // 摘要还挂着「仍在载入」
        rebuildGroups();
        if (summaryLabel != null) {
            summaryLabel.setText(L.t("s_ee0fff9d9a") + allVersions.size() + L.t("s_3ff6c7c2a8"));
        }
    }

    /** 是否已拉完全部页 */
    private boolean loadComplete = false;
    /** 接口里有、但没有可下载主文件的版本（去重） */
    private final Set<String> noFileIds = new HashSet<>();
    private int versionsWithoutFile = 0;
    /** 首屏先拿多少条 */
    private static final int FIRST_PAGE_SIZE = 30;

    /**
     * 构建筛选下拉框选项
     *
     * @return 选中值是否被本方法改动过（改动会通过 onAction 触发一次重建）
     */
    private boolean buildFilters() {
        LinkedHashSet<String> gameVersions = new LinkedHashSet<>();
        LinkedHashSet<String> loaders = new LinkedHashSet<>();

        for (ModrinthClient.ModVersion v : allVersions) {
            gameVersions.addAll(v.getGameVersions());
            loaders.addAll(v.getLoaders());
        }

        List<String> gv = new ArrayList<>(gameVersions);
        // 注意不能用字符串倒序：那样 1.9.x 会排在 1.21 前面，
        // 玩家选「最新版本」时选到的其实是 1.9 那一栏
        // 比较器必须自洽：compare(a,b) 与 -compare(b,a) 结果一致，且
        // a<b、b<c 时 a<c。以前的写法「一个以数字开头、另一个不以数字开头」
        // 时按首字符决定先后，但两个都以数字开头、其中一个解析不出数字时
        // 又走 compareVersion 的「有数字的排前面」分支，三者组合可构成环，
        // TimSort 检测到后会直接抛 IllegalArgumentException 让整个窗口崩掉。
        // 这里只按「是否解析出版本号」分区，再在区内用全序比较。
        gv.sort((a, b) -> {
            boolean an = parseVersion(a).length > 0;
            boolean bn = parseVersion(b).length > 0;
            if (an != bn) {
                return an ? -1 : 1;     // 非版本号的键排在最后
            }
            int c = compareVersion(b, a);   // 版本号倒序（1.21 在 1.9 前）
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> ld = new ArrayList<>(loaders);
        ld.sort(String::compareTo);

        gv.add(0, L.t("s_778fc8f994"));
        ld.add(0, L.t("s_778fc8f994"));

        // 只在内容真的变了时才 setAll：每页数据到达都会调用这里，
        // 全选「全部」（几百个游戏版本）意味着每页都要重建一台巨型下拉框
        if (!gv.equals(gameVersionFilter.getItems())) {
            gameVersionFilter.getItems().setAll(gv);
        }
        if (!ld.equals(loaderFilter.getItems())) {
            loaderFilter.getItems().setAll(ld);
        }

        Object gvSel = gameVersionFilter.getSelectionModel().getSelectedItem();
        Object ldSel = loaderFilter.getSelectionModel().getSelectedItem();
        if (gvSel == null) {
            if (preferredGameVersion != null && gv.contains(preferredGameVersion)) {
                gameVersionFilter.getSelectionModel().select(preferredGameVersion);
            } else {
                gameVersionFilter.getSelectionModel().select(L.t("s_778fc8f994"));
            }
        }
        if (ldSel == null) {
            loaderFilter.getSelectionModel().select(L.t("s_778fc8f994"));
        }
        return !java.util.Objects.equals(gvSel, gameVersionFilter.getSelectionModel().getSelectedItem())
                || !java.util.Objects.equals(ldSel, loaderFilter.getSelectionModel().getSelectedItem());
    }

    /**
     * 计算智能推荐
     *
     * 优先级：
     * 1. 游戏版本包含用户当前版本
     * 2. 优先 release（排除 beta/alpha）
     * 3. 发布日期最新
     *
     * 无精确匹配时退化为「最新的 release」。
     */
    private void computeRecommendation() {
        ModrinthClient.ModVersion before = recommended;
        recommended = null;

        if (preferredGameVersion != null && !preferredGameVersion.isBlank()) {
            recommended = pickBest(allVersions.stream()
                    .filter(v -> v.getGameVersions().contains(preferredGameVersion))
                    .toList());
        }

        if (recommended == null) {
            recommended = pickBest(allVersions);
        }

        // 推荐项变了（流式加载途中从「最新一条」扩展成全量）：
        // 之前的手动展开/折叠是针对旧推荐算出来的，留着会让新的推荐组一直折叠
        String beforeKey = before == null || before.getGameVersions().isEmpty()
                ? null : before.getGameVersions().get(0);
        String afterKey = recommended == null || recommended.getGameVersions().isEmpty()
                ? null : recommended.getGameVersions().get(0);
        if (!java.util.Objects.equals(beforeKey, afterKey)) {
            manualExpanded.clear();
        }
    }

    private ModrinthClient.ModVersion pickBest(List<ModrinthClient.ModVersion> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparing((ModrinthClient.ModVersion v) ->
                                "release".equals(v.getVersionType()) ? 0 : 1)
                        .thenComparing(v -> v.getDatePublished() == null
                                ? "" : v.getDatePublished(), Comparator.reverseOrder()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 渲染智能推荐卡片
     */
    private void renderRecommendation() {
        recommendBox.getChildren().clear();

        if (recommended == null) {
            recommendBox.setVisible(false);
            recommendBox.setManaged(false);
            return;
        }

        Label title = new Label(L.t("s_4aae38e04a"));
        title.getStyleClass().add("recommend-title");

        Label main = new Label(formatVersionRow(recommended));
        main.getStyleClass().add("recommend-main");
        main.setWrapText(true);

        StringBuilder reason = new StringBuilder();
        if (preferredGameVersion != null
                && recommended.getGameVersions().contains(preferredGameVersion)) {
            reason.append(L.t("s_a3d3880cd6")).append(preferredGameVersion).append(L.t("s_cb3adfb489"));
        } else {
            reason.append(L.t("s_9601253682"));
        }
        reason.append(L.t("s_48d4231f84")).append(shortDate(recommended.getDatePublished()));

        Label sub = new Label(reason.toString());
        sub.getStyleClass().add("recommend-sub");
        sub.setWrapText(true);

        Button useBtn = new Button(L.t("s_5c3d1f4c3a"));
        useBtn.getStyleClass().add("button-primary");
        useBtn.setOnAction(e -> {
            selectVersion(recommended);
            downloadSelected();
        });

        HBox btnRow = new HBox(useBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        recommendBox.getChildren().addAll(title, main, sub, btnRow);
        recommendBox.setVisible(true);
        recommendBox.setManaged(true);
    }

    // ==================== 分组渲染 ====================

    /**
     * 重建分组列表
     *
     * 按游戏版本分组，每组一个可折叠的标题行；
     * 默认只展开「推荐版本所在的那一组」，其余折叠。
     */
    private void rebuildGroups() {
        if (groupContainer == null) {
            return;
        }

        String gvFilter = gameVersionFilter == null ? L.t("s_778fc8f994")
                : gameVersionFilter.getSelectionModel().getSelectedItem();
        String ldFilter = loaderFilter == null ? L.t("s_778fc8f994")
                : loaderFilter.getSelectionModel().getSelectedItem();
        String sort = sortCombo == null ? L.t("s_6e4042d8e9")
                : sortCombo.getSelectionModel().getSelectedItem();

        boolean asc = L.t("s_9ae7e2b48d").equals(sort);

        // 1) 过滤
        List<ModrinthClient.ModVersion> filtered = new ArrayList<>();
        for (ModrinthClient.ModVersion v : allVersions) {
            if (gvFilter != null && !L.t("s_778fc8f994").equals(gvFilter)
                    && (UNTAGGED.equals(gvFilter)
                        ? !v.getGameVersions().isEmpty()
                        : !v.getGameVersions().contains(gvFilter))) {
                continue;
            }
            if (ldFilter != null && !L.t("s_778fc8f994").equals(ldFilter)
                    && !v.getLoaders().contains(ldFilter)) {
                continue;
            }
            filtered.add(v);
        }

        // 2) 按「主游戏版本」分组
        Map<String, List<ModrinthClient.ModVersion>> groups = new LinkedHashMap<>();
        for (ModrinthClient.ModVersion v : filtered) {
            String key = v.getGameVersions().isEmpty() ? UNTAGGED : v.getGameVersions().get(0);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }

        // 3) 游戏版本排序
        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort((a, b) -> {
            boolean an = !a.isEmpty() && Character.isDigit(a.charAt(0));
            boolean bn = !b.isEmpty() && Character.isDigit(b.charAt(0));
            if (an != bn) {
                return an ? -1 : 1;     // 「未标注版本」始终排在最后
            }
            return asc ? compareVersion(a, b) : compareVersion(b, a);
        });

        // 4) 渲染（批量更新期间只标记，结束时统一重建一次）
        if (bulkUpdateDepth > 0) {
            rebuildPending = true;
            return;
        }
        rebuildPending = false;

        groupContainer.getChildren().clear();

        // 推荐版本所在的分组默认展开
        String expandKey = null;
        if (recommended != null && !recommended.getGameVersions().isEmpty()) {
            expandKey = recommended.getGameVersions().get(0);
        }

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<ModrinthClient.ModVersion> list = groups.get(key);
            // 组内按日期倒序（新的在前）
            list.sort(Comparator.comparing(
                    (ModrinthClient.ModVersion v) -> v.getDatePublished() == null
                            ? "" : v.getDatePublished(),
                    Comparator.reverseOrder()));

            // 玩家手动展开/折叠过的分组以自己的选择为准：
            // 每页数据到达都会重建分组，若每次都回到「默认展开」，
            // 玩家刚展开的旧版本会在加载途中自己合上。
            Boolean manual = manualExpanded.get(key);
            boolean expanded = manual != null ? manual
                    : (expandKey != null && expandKey.equals(key))
                        || (expandKey == null && i == 0);
            groupContainer.getChildren().add(buildGroup(key, list, expanded));
        }

        if (summaryLabel != null) {
            summaryLabel.setText(L.t("s_497064be1f") + filtered.size() + L.t("s_f9f0c7a805")
                    + keys.size() + L.t("s_17a8ed8515")
                    + (filtered.size() != allVersions.size()
                        ? L.t("s_9139399ec8") + allVersions.size() + "）" : "")
                    + (versionsWithoutFile > 0
                        ? L.t("s_08795a63b9") + versionsWithoutFile + L.t("s_e69167f1ca") : "")
                    + (loadComplete ? "" : L.t("s_bba6f666d3")));
        }
    }

    /** 接口没给 game_versions 的版本（多半是编号里的 mc26.x 一类非标准版本）归到这里 */
    private static final String UNTAGGED = L.t("s_9ebde45679");

    /** 玩家手动展开/折叠过的游戏版本分组（true=展开） */
    private final Map<String, Boolean> manualExpanded = new HashMap<>();

    /** 单个分组一次最多直接构建的行数（其余展开时再建） */
    private static final int ROWS_PER_CHUNK = 40;

    /**
     * 构建一个折叠分组
     *
     * 折叠状态的分组只建标题行，展开时才建版本行：一个模组可能有
     * 30 个游戏版本、几百行，全部建出来会让首次显示变慢。
     */
    private VBox buildGroup(String gameVersion, List<ModrinthClient.ModVersion> versions,
                            boolean expanded) {
        VBox group = new VBox(6);

        // ---- 折叠标题行 ----
        HBox header = new HBox(10);
        header.getStyleClass().add("version-group-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));

        Label arrow = new Label(expanded ? "-" : "+");
        arrow.getStyleClass().add("version-arrow");

        Label gvLabel = new Label(gameVersion);
        gvLabel.getStyleClass().add("version-game");

        Label countLabel = new Label(versions.size() + L.t("s_3ff6c7c2a8"));
        countLabel.getStyleClass().add("version-mod");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        boolean isPreferred = gameVersion.equals(preferredGameVersion);
        if (isPreferred) {
            Label badge = new Label(L.t("s_446c43f37a"));
            badge.getStyleClass().add("version-badge");
            header.getChildren().addAll(arrow, gvLabel, badge, countLabel, sp);
        } else {
            header.getChildren().addAll(arrow, gvLabel, countLabel, sp);
        }

        // ---- 版本行容器 ----
        VBox body = new VBox(4);
        body.setPadding(new Insets(2, 0, 4, 12));
        body.setVisible(expanded);
        body.setManaged(expanded);

        fillRows(body, versions, 0);

        // 点击标题切换展开（并记住玩家的选择）
        header.setOnMouseClicked(e -> {
            boolean nowVisible = !body.isVisible();
            body.setVisible(nowVisible);
            body.setManaged(nowVisible);
            arrow.setText(nowVisible ? "+" : "-");
            manualExpanded.put(gameVersion, nowVisible);
        });

        group.getChildren().addAll(header, body);
        return group;
    }

    /**
     * 往分组里填充版本行；超过一批时追加一个「显示更多」，按需再建
     */
    private void fillRows(VBox body, List<ModrinthClient.ModVersion> versions, int from) {
        // 上次余下的「显示更多」按钮先摘掉
        body.getChildren().removeIf(n -> "more".equals(n.getUserData()));

        int to = Math.min(versions.size(), from + ROWS_PER_CHUNK);
        for (int i = from; i < to; i++) {
            body.getChildren().add(buildVersionRow(versions.get(i)));
        }
        if (to < versions.size()) {
            Button more = new Button(L.t("s_33dba85403") + (versions.size() - to) + L.t("s_cd628eba05"));
            more.getStyleClass().addAll("button", "button-sm");
            more.setUserData("more");
            final int next = to;
            more.setOnAction(e -> {
                body.getChildren().remove(more);
                fillRows(body, versions, next);
            });
            body.getChildren().add(more);
        }
    }

    /**
     * 构建单个版本行：`游戏版本 --> 模组版本`
     */
    private HBox buildVersionRow(ModrinthClient.ModVersion v) {
        HBox row = new HBox(8);
        row.getStyleClass().add("version-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));

        Label game = new Label(v.getGameVersions().isEmpty()
                ? "?" : v.getGameVersions().get(0));
        game.getStyleClass().add("version-game");

        Label arrow = new Label("-->");
        arrow.getStyleClass().add("version-arrow");

        String loaderPart = v.getLoaders().isEmpty() ? "" : v.getLoaders().get(0);
        String modText = loaderPart.isEmpty()
                ? v.getVersionNumber()
                : loaderPart + " " + v.getVersionNumber();
        Label mod = new Label(modText);
        mod.getStyleClass().add("version-mod");

        row.getChildren().addAll(game, arrow, mod);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        row.getChildren().add(sp);

        // 徽标
        if (recommended != null && recommended.getId() != null
                && recommended.getId().equals(v.getId())) {
            Label badge = new Label(L.t("s_62b46f24ae"));
            badge.getStyleClass().add("version-badge");
            row.getChildren().add(badge);
        } else if (v.getVersionType() != null && !"release".equals(v.getVersionType())) {
            Label badge = new Label(v.getVersionType());
            // 用主题感知的类，避免浅色主题下出现深色底徽标
            badge.getStyleClass().addAll("version-type-badge", "version-type-snapshot");
            row.getChildren().add(badge);
        }

        Label date = new Label(shortDate(v.getDatePublished()));
        date.getStyleClass().add("version-date");
        row.getChildren().add(date);

        // 点击选中
        row.setUserData(v);              // 供选中高亮定位使用
        row.setOnMouseClicked(e -> selectVersion(v));

        // 高亮当前选中
        updateRowSelectionStyle(row, v);

        return row;
    }

    private void updateRowSelectionStyle(HBox row, ModrinthClient.ModVersion v) {
        boolean isSel = selected != null && selected.getId() != null
                && selected.getId().equals(v.getId());
        // 用伪类而不是内联样式：内联样式优先级最高，以后 CSS 主题无法覆盖它
        row.pseudoClassStateChanged(SELECTED_PSEUDO, isSel);
    }

    private static final javafx.css.PseudoClass SELECTED_PSEUDO =
            javafx.css.PseudoClass.getPseudoClass("selected");

    /**
     * 选中某个版本
     */
    private void selectVersion(ModrinthClient.ModVersion v) {
        selected = v;
        if (downloadButton != null) {
            downloadButton.setDisable(false);
        }
        if (selectedLabel != null) {
            selectedLabel.setText(L.t("s_b12f1ad1bf") + formatVersionRow(v));
        }
        refreshSelectionStyles();
    }

    /**
     * 遍历分组树，依据每行记录的 userData 重设高亮
     */
    private void refreshSelectionStyles() {
        if (groupContainer == null) {
            return;
        }
        for (var groupNode : groupContainer.getChildren()) {
            if (!(groupNode instanceof VBox group)) continue;
            for (var child : group.getChildren()) {
                if (!(child instanceof VBox body)) continue;
                for (var rowNode : body.getChildren()) {
                    if (rowNode instanceof HBox row
                            && row.getUserData() instanceof ModrinthClient.ModVersion rv) {
                        updateRowSelectionStyle(row, rv);
                    }
                }
            }
        }
    }

    /**
     * 游戏版本号比较（用于排序）
     * 例如 1.21.1 > 1.21 > 1.20.6 > 1.20
     */
    private int compareVersion(String a, String b) {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        // 两个都不是版本号（"全部"、"未标注版本"）时按名字比：
        // 否则比较结果为 0，TreeSet 会把它们当成同一个元素合并掉
        if (pa.length == 0 || pb.length == 0) {
            if (pa.length != pb.length) {
                return Integer.compare(pa.length, pb.length);   // 有数字的排前面
            }
            return a.compareTo(b);
        }
        return a.compareTo(b);
    }

    private int[] parseVersion(String v) {
        if (v == null) return new int[0];
        String[] parts = v.split("[.\\-]");
        List<Integer> nums = new ArrayList<>();
        for (String p : parts) {
            try {
                nums.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException e) {
                break;
            }
        }
        int[] arr = new int[nums.size()];
        for (int i = 0; i < nums.size(); i++) {
            arr[i] = nums.get(i);
        }
        return arr;
    }

    /**
     * 行格式：`游戏版本 --> 模组版本`
     */
    private String formatVersionRow(ModrinthClient.ModVersion v) {
        String game = v.getGameVersions().isEmpty() ? "?" : v.getGameVersions().get(0);
        if (v.getGameVersions().size() > 1) {
            game = game + L.t("s_b2a70c6855") + v.getGameVersions().size() + L.t("s_6ee50f2dfa");
        }
        String loaderPart = v.getLoaders().isEmpty() ? "" : v.getLoaders().get(0);
        String mod = loaderPart.isEmpty()
                ? v.getVersionNumber()
                : loaderPart + " " + v.getVersionNumber();
        return game + "  -->  " + mod;
    }

    private String shortDate(String iso) {
        if (iso == null || iso.length() < 10) {
            return L.t("s_d9c32a4c3d");
        }
        return iso.substring(0, 10);
    }

    // ==================== 下载 ====================

    private void downloadSelected() {
        if (selected == null) {
            return;
        }

        downloadButton.setDisable(true);
        downloadButton.setText(L.t("s_a0ef0da255"));
        downloadProgress.setProgress(0);
        downloadProgress.setVisible(true);
        downloadProgress.setManaged(true);

        CompletableFuture.supplyAsync(() ->
                modDownloader.downloadMod(gameDir, selected,
                        new ModDownloader.ProgressListener() {
                            @Override
                            public void onStart(String filename) {
                                Platform.runLater(() -> {
                                    if (!closed) downloadStatus.setText(L.t("s_43d4950cc4") + filename);
                                });
                            }

                            @Override
                            public void onProgress(long downloaded, long total, double percent) {
                                Platform.runLater(() -> {
                                    if (closed) return;
                                    downloadProgress.setProgress(percent / 100.0);
                                    downloadStatus.setText(String.format("%.1f%%",
                                            percent));
                                });
                            }

                            @Override
                            public void onComplete(Path destination) {
                            }

                            @Override
                            public void onError(String message) {
                            }
                        })
        ).thenAccept(result -> Platform.runLater(() -> {
            // 下载途中关掉窗口：文件已落盘就只提示状态栏，不再碰界面节点
            if (closed) {
                if (result.isSuccess() && statusConsumer != null) {
                    statusConsumer.accept(L.t("s_430ed2a2fe") + result.getPath().getFileName());
                }
                return;
            }
            downloadButton.setDisable(false);
            downloadButton.setText(L.t("s_9d34c0f0f7"));

            if (result.isSuccess()) {
                downloadProgress.setProgress(1.0);
                downloadStatus.setText(L.t("s_6d06a95bdd") + result.getPath().getFileName());
                if (statusConsumer != null) {
                    statusConsumer.accept(L.t("s_430ed2a2fe") + result.getPath().getFileName());
                }
            } else {
                downloadProgress.setVisible(false);
                downloadProgress.setManaged(false);
                downloadStatus.setText(L.t("s_9767bac218") + result.getErrorMessage());
            }
        }));
    }
}