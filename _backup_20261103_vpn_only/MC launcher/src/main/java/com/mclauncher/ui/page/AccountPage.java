package com.mclauncher.ui.page;


import com.mclauncher.i18n.L;
import com.mclauncher.auth.Account;
import com.mclauncher.auth.AccountManager;
import com.mclauncher.auth.MicrosoftAuth;
import com.mclauncher.auth.SkinFetcher;
import com.mclauncher.auth.SkinSites;
import com.mclauncher.auth.DefaultSkins;
import com.mclauncher.ui.component.SkinAvatar;
import com.mclauncher.config.ConfigManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 账号页
 *
 * 支持离线账号与微软正版登录（设备代码流）。
 */
public class AccountPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(AccountPage.class);

    /** 皮肤来源（静态方法类，但缓存是实例/静态混合的，这里统一用实例） */
    private static final SkinFetcher SKINS = new SkinFetcher();

    /** 内嵌弹窗宿主（由 MainWindow 注入） */
    private com.mclauncher.ui.component.ModalHost modalHost;

    @Override
    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    private final AccountManager accountManager;
    private final ConfigManager config;
    private final Consumer<String> statusConsumer;

    private BorderPane root;
    private ListView<Account> accountList;
    /** 当前账号卡片上的头像与皮肤预览 */
    private SkinAvatar currentAvatar;
    private StackPane currentSkinView;
    private Label currentNameLabel;
    private Label currentTypeLabel;
    private Label currentSkinHint;
    /** 皮肤站选择（每个账号各自记住） */
    private ComboBox<SkinOption> skinSiteCombo;
    /** 防止程序设置下拉框时触发保存 */
    private boolean suppressSiteChange = false;
    private TextField offlineNameField;
    private Label offlineStatus;
    private Label msStatus;
    private Button msLoginButton;
    private VBox codeBox;
    private Label deviceCodeLabel;

    public AccountPage(AccountManager accountManager, ConfigManager config,
                       Consumer<String> statusConsumer) {
        this.accountManager = accountManager;
        this.config = config;
        this.statusConsumer = statusConsumer;
    }

    @Override
    public String getTitle() {
        return L.t("s_9013849179");
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
        refreshAccountList();
    }

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");
        // 会作为 ScrollPane 的内容，必须允许被压到比内容还小
        pane.setMinHeight(0);

        VBox header = new VBox(4);
        header.setPadding(new Insets(24, 28, 12, 28));

        Label title = new Label(L.t("s_9d4ca7f307"));
        title.getStyleClass().add("page-title");

        Label subtitle = new Label(L.t("s_914f56a875"));
        subtitle.getStyleClass().add("page-subtitle");

        header.getChildren().addAll(title, subtitle);

        contentBox = new VBox(12, buildCurrentAccountCard());
        contentBox.setPadding(new Insets(0, 28, 24, 28));
        contentBox.setMinHeight(0);
        installTabs();

        // 整个页面放进 ScrollPane：窗口矮一点或系统缩放较大时，
        // 账号卡 + 标签页会超出可视区域。以前没有任何滚动条，
        // 下面的账号列表根本够不着
        ScrollPane scroll = new ScrollPane(contentBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("account-scroll");
        scroll.setMinHeight(0);

        pane.setTop(header);
        pane.setCenter(scroll);
        return pane;
    }

    /** 账号卡 + 标签页的容器，重建标签页时只替换其中一项 */
    private VBox contentBox;

    /**
     * 创建标签页（列表 / 离线 / 微软 / 皮肤）。
     *
     * 每次都新建：皮肤页的控件是懒创建的，重建后 currentSkinView 等字段
     * 必须指向当前真正显示的那一份，否则会去更新已被替换掉的节点。
     */
    private TabPane createTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // 标签页内容各自可滚动：内容不高时不出滚动条，超出时能滚
        tabs.getTabs().addAll(
                new Tab(L.t("s_56c18f8733"), buildListTab()),
                new Tab(L.t("s_c1c38438c5"), buildOfflineTab()),
                new Tab(L.t("s_172f1237ed"), buildMicrosoftTab()),
                // 皮肤从「当前账号卡」里拆出来单独一栏
                new Tab(L.t("s_acct_skin_tab"), buildSkinTab())
        );
        tabs.setMinHeight(0);
        return tabs;
    }

    /** 创建并换上新的标签页（首次构建 / 语言切换） */
    private TabPane installTabs() {
        TabPane tabs = createTabs();
        tabs.getSelectionModel().select(0);
        if (contentBox == null) {
            return tabs;
        }
        if (contentBox.getChildren().size() >= 2) {
            contentBox.getChildren().set(1, tabs);
        } else {
            contentBox.getChildren().add(tabs);
            VBox.setVgrow(tabs, Priority.ALWAYS);
        }
        // 皮肤页刚建好：把当前账号的皮肤状态同步过去
        updateCurrentCard(accountManager.getCurrentAccount());
        return tabs;
    }

    /** 标签页内容统一包一层滚动容器（内容不高时不出滚动条） */
    private ScrollPane scrollable(javafx.scene.Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.getStyleClass().add("tab-scroll");
        sp.setMinHeight(0);
        return sp;
    }

    // ---------- 账号列表 ----------

    private ScrollPane buildListTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        Label hint = new Label(L.t("s_a2a2654b63"));
        hint.getStyleClass().add("text-secondary");

        accountList = new ListView<>();
        accountList.setPlaceholder(new Label(L.t("s_7ce2e85cf6")));
        accountList.getStyleClass().add("account-list");
        accountList.setCellFactory(lv -> new AccountCell());
        VBox.setVgrow(accountList, Priority.ALWAYS);
        accountList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, old, idx) -> {
                    if (idx != null && idx.intValue() >= 0) {
                        Account a = accountList.getItems().get(idx.intValue());
                        if (a != null) {
                            accountManager.setCurrentAccount(a.getId());
                            config.setLastAccountId(a.getId());
                            if (statusConsumer != null) {
                                statusConsumer.accept(L.t("s_2c26eef310") + a.getUsername());
                            }
                            updateCurrentCard(a);
                        }
                    }
                });

        HBox actions = new HBox(10);
        Button refresh = new Button(L.t("s_38108eaa1d"));
        refresh.getStyleClass().add("button");
        refresh.setOnAction(e -> refreshAccountList());

        Button remove = new Button(L.t("s_42b7a0e011"));
        remove.getStyleClass().add("button");
        remove.setStyle("-fx-background-color: #E05D5D; -fx-text-fill: white;");
        remove.setOnAction(e -> removeSelected());

        actions.getChildren().addAll(refresh, remove);

        box.getChildren().addAll(hint, accountList, actions);
        // 列表给一个最小高度，外层 ScrollPane 滚动时不会把它压成一条缝
        accountList.setMinHeight(240);
        box.setPrefHeight(Region.USE_COMPUTED_SIZE);
        return scrollable(box);
    }

    private void refreshAccountList() {
        if (accountList == null) return;
        List<Account> accounts = accountManager.getAllAccounts();

        Account keep = accountList.getSelectionModel().getSelectedItem();

        accountList.getItems().setAll(accounts);

        String currentId = accountManager.getCurrentAccountId();
        Account selected = null;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).getId().equals(currentId)) {
                selected = accounts.get(i);
                accountList.getSelectionModel().select(i);
                break;
            }
        }
        // 当前账号不在列表里（刚被删掉）时，保留用户原来的选择
        if (selected == null && keep != null) {
            int i = accounts.indexOf(keep);
            if (i >= 0) {
                accountList.getSelectionModel().select(i);
                selected = keep;
            }
        }

        updateCurrentCard(selected != null ? selected : accountManager.getCurrentAccount());
    }

    private void removeSelected() {
        Account a = accountList.getSelectionModel().getSelectedItem();
        if (a == null) {
            return;
        }
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_3c06abe116"),
                L.t("s_176bb417ed") + a.getUsername() + "」",
                null,
                L.t("s_13db6031a8"),
                L.t("s_3755f56f2f"),
                () -> {
                    accountManager.removeAccount(a.getId());
                    refreshAccountList();
                });
    }

    /**
     * 列表单元格：头像 + 用户名 + 账号类型
     *
     * 头像是真实皮肤（异步加载），取不到时显示首字母占位。
     */
    private class AccountCell extends ListCell<Account> {
        private final SkinAvatar avatar = SkinAvatar.circle(34);
        private final Label name = new Label();
        private final Label type = new Label();
        private final VBox text;
        private final HBox box;
        /** 这一格当前绑定的是哪个账号，避免列表复用导致头像串号 */
        private Account bound;

        AccountCell() {
            name.getStyleClass().add("account-cell-name");
            type.getStyleClass().add("account-cell-type");
            text = new VBox(1, name, type);
            text.setAlignment(Pos.CENTER_LEFT);

            box = new HBox(12, avatar.getNode(), text);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(6, 8, 6, 8));
        }

        @Override
        protected void updateItem(Account item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                bound = null;
                return;
            }
            setText(null);
            setGraphic(box);

            name.setText(item.getUsername() == null ? L.t("s_d9c32a4c3d") : item.getUsername());
            boolean isCurrent = item.getId().equals(accountManager.getCurrentAccountId());
            type.setText((isCurrent ? L.t("s_3a4f242e00") : "")
                    + (item.getType() != null ? item.getType().getDisplayName() : ""));

            if (item != bound) {
                bound = item;
                avatar.bind(item);
            }
        }
    }

    // ---------- 当前账号卡 ----------

    /**
     * 当前账号卡：大号皮肤预览 + 名字
     *
     * 预览显示的是皮肤原图的放大版（含外层/帽子），
     * 点击「更换头像」会清缓存重新拉取 —— 玩家刚在皮肤站换了皮肤时用得上。
     */
    /**
     * 当前账号卡（顶部）：头像 + 名字 + 刷新皮肤
     *
     * 皮肤相关设置（来源、上传、全身预览）已移到「皮肤设置」标签页，
     * 这里只留一眼能看到的身份信息，避免把下面的标签页挤出可视区。
     */
    private Region buildCurrentAccountCard() {
        currentAvatar = SkinAvatar.square(56);
        currentNameLabel = new Label(L.t("s_e8041a8c93"));
        currentNameLabel.getStyleClass().add("account-current-name");
        currentTypeLabel = new Label(L.t("s_d528e32520"));
        currentTypeLabel.getStyleClass().add("text-secondary");

        VBox names = new VBox(3, currentNameLabel, currentTypeLabel);
        names.setAlignment(Pos.CENTER_LEFT);

        Button refreshSkin = new Button(L.t("s_47b6b0ebe3"));
        refreshSkin.getStyleClass().add("button");
        refreshSkin.setOnAction(e -> {
            SKINS.invalidate(accountManager.getCurrentAccount());
            updateCurrentCard(accountManager.getCurrentAccount());
            refreshAccountList();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(14, currentAvatar.getNode(), names, spacer, refreshSkin);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, row);
        card.getStyleClass().add("card");
        VBox.setMargin(card, new Insets(0, 28, 12, 28));
        card.setMaxHeight(Region.USE_PREF_SIZE);

        updateCurrentCard(accountManager.getCurrentAccount());
        return card;
    }

    /**
     * 皮肤设置标签页：账号提示 / 来源选择 / 上传 / 刷新 / 全身预览
     */
    private ScrollPane buildSkinTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        Label caption = new Label(L.t("s_cd5778aef9"));
        caption.getStyleClass().add("card-title");
        caption.setMinWidth(Region.USE_PREF_SIZE);

        Label who = new Label(L.t("s_acct_current") + "：" + currentAccountName());
        who.getStyleClass().add("text-secondary");

        // ---- 皮肤来源 ----
        // 选项包含：自动 / 9 个内置基础皮肤 / Mojang 官方 / LittleSkin / Blessing Skin，
        // 也可以手动填写任意 Blessing Skin 站点的地址。
        // 注意要用 ComboBox<SkinOption> 声明，否则得到 ComboBox<String>，装不进自定义选项
        ComboBox<SkinOption> combo = new ComboBox<>();
        combo.setPrefWidth(260);
        combo.setEditable(true);
        combo.getItems().setAll(buildSkinOptions());
        skinSiteCombo = combo;
        skinSiteCombo.setPromptText(L.t("s_1df8ac8aa4"));
        combo.setCellFactory(lv -> new SkinOptionCell());
        skinSiteCombo.setButtonCell(new SkinOptionCell());
        skinSiteCombo.getSelectionModel().select(0);

        skinSiteCombo.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, opt) -> {
                    if (suppressSiteChange || opt == null) {
                        return;
                    }
                    Account a = accountManager.getCurrentAccount();
                    if (a != null) {
                        applySkinOption(a, opt);
                    }
                });
        // 手填 URL：回车提交
        skinSiteCombo.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                    && skinSiteCombo.getEditor() != null) {
                String text = skinSiteCombo.getEditor().getText();
                Account a = accountManager.getCurrentAccount();
                if (a != null && text != null && !text.isBlank()
                        && SkinSites.guessApiRoot(text) != null) {
                    SkinOption opt = SkinOption.custom(text.trim());
                    applySkinOption(a, opt);
                    // 自定义站点也要进选项列表：否则收起下拉/失焦时编辑器
                    // 会被回填成空串或旧值，显示与账号实际 skinSite 不一致
                    rememberCustomOption(opt);
                    suppressSiteChange = true;
                    try {
                        skinSiteCombo.getSelectionModel().select(opt);
                    } finally {
                        suppressSiteChange = false;
                    }
                }
            }
        });

        Button uploadSkin = new Button(L.t("s_95c7fc7c1e"));
        uploadSkin.getStyleClass().add("button");
        uploadSkin.setTooltip(new Tooltip(L.t("s_7550df8a1e")));
        uploadSkin.setOnAction(e -> {
            Account a = accountManager.getCurrentAccount();
            String url = a != null ? SkinSites.homepageOf(resolveSite(a)) : null;
            openUrl(url == null ? SkinSites.LITTLESKIN.homepage : url);
        });

        Button refreshSkin = new Button(L.t("s_47b6b0ebe3"));
        refreshSkin.getStyleClass().add("button");
        refreshSkin.setOnAction(e -> {
            SKINS.invalidate(accountManager.getCurrentAccount());
            updateCurrentCard(accountManager.getCurrentAccount());
            refreshAccountList();
        });

        HBox siteRow = new HBox(10, caption, skinSiteCombo, uploadSkin, refreshSkin);
        siteRow.setAlignment(Pos.CENTER_LEFT);

        Label siteNote = new Label(L.t("s_e32aac2f24"));
        siteNote.getStyleClass().add("text-muted");
        siteNote.setWrapText(true);

        // 全身像预览
        currentSkinView = new StackPane();
        currentSkinView.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        currentSkinView.getStyleClass().add("skin-preview");
        currentSkinView.setVisible(false);
        currentSkinView.setManaged(false);

        currentSkinHint = new Label("");
        currentSkinHint.getStyleClass().add("text-muted");
        currentSkinHint.setVisible(false);
        currentSkinHint.setManaged(false);

        HBox previewRow = new HBox(16, currentSkinView);
        previewRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(who, siteRow, siteNote, previewRow, currentSkinHint);
        return scrollable(box);
    }

    /** 当前账号名（没有账号时显示「未登录」） */
    private String currentAccountName() {
        Account a = accountManager.getCurrentAccount();
        return a == null || a.getUsername() == null ? L.t("s_e8041a8c93") : a.getUsername();
    }

    /**
     * 刷新当前账号卡
     */
    private void updateCurrentCard(Account account) {
        if (currentAvatar == null) {
            return;
        }
        if (account == null) {
            currentAvatar.bind(null);
            currentNameLabel.setText(L.t("s_e8041a8c93"));
            currentTypeLabel.setText(L.t("s_d528e32520"));
            hideSkinPreview();
            return;
        }

        currentAvatar.bind(account);
        currentNameLabel.setText(account.getUsername());
        currentTypeLabel.setText(account.getType() != null
                ? account.getType().getDisplayName() : "");

        // 皮肤设置标签页懒加载：没打开过时控件为空，跳过即可
        if (skinSiteCombo != null) {
            suppressSiteChange = true;
            try {
                String site = account.getSkinSite();
                SkinOption match = findOption(site);
                if (match != null) {
                    skinSiteCombo.getSelectionModel().select(match);
                } else {
                    skinSiteCombo.getSelectionModel().clearSelection();
                    skinSiteCombo.getEditor().setText(site == null ? "" : site);
                }
            } finally {
                suppressSiteChange = false;
            }
        }

        if (currentSkinView == null) {
            return;
        }
        // 缓存命中时直接显示皮肤预览；没命中等头像组件加载完成后回调
        Image cached = SKINS.peekSkin(account);
        if (cached != null) {
            showSkinPreview(cached, account);
        } else {
            hideSkinPreview();
            pollSkinPreview(account, 0);
        }
    }

    private void hideSkinPreview() {
        if (currentSkinView != null) {
            currentSkinView.setVisible(false);
            currentSkinView.setManaged(false);
        }
        if (currentSkinHint != null) {
            currentSkinHint.setVisible(false);
            currentSkinHint.setManaged(false);
        }
    }

    /** 轮询等皮肤进入缓存（最多约 6 秒；也没必要更长） */
    private void pollSkinPreview(Account account, int attempt) {
        if (attempt > 24) {
            return;
        }
        Image img = SKINS.peekSkin(account);
        if (img != null) {
            if (Platform.isFxApplicationThread()) {
                showSkinPreview(img, account);
            } else {
                Platform.runLater(() -> showSkinPreview(img, account));
            }
            return;
        }
        CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() ->
                        // 必须回到 FX 线程：后面会改 ImageView / 可见性
                        Platform.runLater(() -> {
                            Account cur = accountManager.getCurrentAccount();
                            if (cur != null && cur.getId().equals(account.getId())) {
                                pollSkinPreview(account, attempt + 1);
                            }
                        }));
    }

    /**
     * 显示全身像
     *
     * 皮肤图是人物的展开图（头/身体/手臂/腿分开摊着），
     * 直接放大整张图玩家看到的是一堆碎片 —— 必须拼成正面站立的形象。
     * 拼接逻辑见 {@link com.mclauncher.ui.component.SkinRenderer}。
     */
    private void showSkinPreview(Image skin, Account account) {
        if (skin == null || skin.getPixelReader() == null || currentSkinView == null) {
            return;
        }

        boolean slim = "slim".equals(SKINS.peekModel(account));
        int px = 5;      // 每皮肤像素放大 5 倍：全身约 70~80px 宽、160px 高
        Image full = com.mclauncher.ui.component.SkinRenderer.fullBody(skin, px, slim);
        if (full == null) {
            return;
        }

        ImageView iv = new ImageView(full);
        iv.setSmooth(false);        // 像素风：不要插值模糊

        currentSkinView.getChildren().setAll(iv);
        currentSkinView.setPrefSize(full.getWidth(), full.getHeight());
        currentSkinView.setMinSize(full.getWidth(), full.getHeight());
        currentSkinView.setMaxSize(full.getWidth(), full.getHeight());
        currentSkinView.setVisible(true);
        currentSkinView.setManaged(true);

        currentSkinHint.setText(L.t("s_ddefc841c9") + (slim ? L.t("s_da058dfb2e") : L.t("s_14ea6d33e8"))
                + L.t("s_33a0000a37"));
        currentSkinHint.setVisible(true);
        currentSkinHint.setManaged(true);
    }

// ---------- 皮肤来源选项 ----------

    /**
     * 皮肤来源下拉框的一个选项
     *
     * value 是存到账号上的 skinSite 值：
     *   null       -> <自动>
     *   default:xx -> 内置基础皮肤
     *   mojang     -> Mojang 官方
     *   其它 URL   -> 皮肤站的 API 根
     */
    static final class SkinOption {
        final String value;
        final String label;
        /** 内置皮肤时非空（用于显示缩略图） */
        final DefaultSkins.Preset preset;

        SkinOption(String value, String label, DefaultSkins.Preset preset) {
            this.value = value;
            this.label = label;
            this.preset = preset;
        }

        static SkinOption custom(String url) {
            String normalized = SkinSites.guessApiRoot(url);
            return new SkinOption(normalized == null ? url : normalized, url.trim(), null);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 构建选项列表：自动 / 9 个内置皮肤 / Mojang / 两个常用站点 */
    private List<SkinOption> buildSkinOptions() {
        List<SkinOption> opts = new ArrayList<>();
        opts.add(new SkinOption(null, L.t("s_a16f1edbe4"), null));
        for (DefaultSkins.Preset p : DefaultSkins.all()) {
            opts.add(new SkinOption(p.getValue(), p.displayName, p));
        }
        opts.add(new SkinOption("mojang", L.t("s_430a7a3592"), null));
        opts.add(new SkinOption(SkinSites.LITTLESKIN.homepage,
                "LittleSkin  ·  " + SkinSites.LITTLESKIN.homepage, null));
        opts.add(new SkinOption(SkinSites.BLESSING.homepage,
                "Blessing Skin  ·  " + SkinSites.BLESSING.homepage, null));
        return opts;
    }

    /**
     * 按存储值找回选项（兼容旧数据里的大小写、末尾斜杠差异）
     *
     * @return 匹配的选项；不在列表里（用户手填的新地址）返回 null
     */
    private SkinOption findOption(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
            return skinSiteCombo.getItems().get(0);
        }
        for (SkinOption o : skinSiteCombo.getItems()) {
            if (o.value != null && o.value.equalsIgnoreCase(value)) {
                return o;
            }
        }
        // 历史数据里微软账号可能存成 "microsoft"
        if ("microsoft".equalsIgnoreCase(value)) {
            for (SkinOption o : skinSiteCombo.getItems()) {
                if ("mojang".equals(o.value)) {
                    return o;
                }
            }
        }
        // URL：按推导出的 API 根比较，https://a.com 与 https://a.com/ 视为同一个
        String norm = SkinSites.guessApiRoot(value);
        if (norm != null) {
            for (SkinOption o : skinSiteCombo.getItems()) {
                if (o.value != null && norm.equalsIgnoreCase(SkinSites.guessApiRoot(o.value))) {
                    return o;
                }
            }
        }
        return null;
    }

    /** 把用户的选择写到当前账号并刷新显示 */
    /** 把自定义皮肤站加入下拉列表（已存在则不重复） */
    private void rememberCustomOption(SkinOption opt) {
        for (SkinOption o : skinSiteCombo.getItems()) {
            if (opt.value != null && opt.value.equals(o.value)) {
                return;
            }
        }
        skinSiteCombo.getItems().add(opt);
    }

    private void applySkinOption(Account a, SkinOption opt) {
        a.setSkinSite(opt.value);
        accountManager.save();
        SKINS.invalidate(a);
        updateCurrentCard(a);
        refreshAccountList();

        if (statusConsumer != null) {
            statusConsumer.accept(opt.preset != null
                    ? L.t("s_c37d88b6c9") + opt.preset.displayName + L.t("s_7cc6b1bc50")
                    : L.t("s_248aa08a60") + (opt.value == null ? L.t("s_4afad87755") : opt.label));
        }
    }

    /**
     * 皮肤选项渲染：内置皮肤显示头部缩略图，其它只显示文字
     */
    private static class SkinOptionCell extends ListCell<SkinOption> {
        private final javafx.scene.image.ImageView thumb = new javafx.scene.image.ImageView();
        private final Label text = new Label();
        private final HBox box;

        SkinOptionCell() {
            // 皮肤图头部在 (8,8) 8x8，直接截这块当作缩略图（像素风不插值）
            thumb.setViewport(new javafx.geometry.Rectangle2D(8, 8, 8, 8));
            thumb.setFitWidth(26);
            thumb.setFitHeight(26);
            thumb.setSmooth(false);
            box = new HBox(8, thumb, text);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(SkinOption item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            setText(null);
            text.setText(item.label);
            javafx.scene.image.Image img = item.preset == null ? null : DefaultSkins.load(item.preset);
            thumb.setImage(img);
            thumb.setVisible(img != null);
            thumb.setManaged(img != null);
            setGraphic(box);
        }
    }

    /** 账号实际使用的 API 根（供「上传皮肤」按钮跳转） */
    private String resolveSite(Account a) {
        String site = a.getSkinSite();
        if (site == null || site.isBlank() || "auto".equalsIgnoreCase(site)) {
            return SkinSites.LITTLESKIN.apiRoot;
        }
        if ("mojang".equalsIgnoreCase(site) || "microsoft".equalsIgnoreCase(site)) {
            return null;
        }
        return SkinSites.guessApiRoot(site);
    }

    // ---------- 离线登录 ----------

    private ScrollPane buildOfflineTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        Label title = new Label(L.t("s_c1c38438c5"));
        title.getStyleClass().add("card-title");

        Label desc = new Label(
                L.t("s_1ed5bb559f"));
        desc.getStyleClass().add("text-secondary");
        desc.setWrapText(true);

        Label nameLabel = new Label(L.t("s_a1aaf352cb"));
        nameLabel.getStyleClass().add("text-secondary");

        offlineNameField = new TextField();
        offlineNameField.setPromptText(L.t("s_bfa2548fe4"));
        offlineNameField.setMaxWidth(320);

        Button add = new Button(L.t("s_e9bf92d831"));
        add.getStyleClass().add("button-primary");
        add.setOnAction(e -> addOffline());

        offlineStatus = new Label("");
        offlineStatus.setWrapText(true);

        box.getChildren().addAll(title, desc, new Separator(),
                nameLabel, offlineNameField, add, offlineStatus);
        return scrollable(box);
    }

    private void addOffline() {
        String name = offlineNameField.getText() == null
                ? "" : offlineNameField.getText().trim();

        if (name.isEmpty()) {
            setOfflineStatus(L.t("s_ad5f05f1cf"), true);
            return;
        }
        if (name.length() < 3 || name.length() > 16) {
            setOfflineStatus(L.t("s_452e54852f"), true);
            return;
        }
        if (!name.matches("[a-zA-Z0-9_]+")) {
            setOfflineStatus(L.t("s_acfbb2420c"), true);
            return;
        }
        if (accountManager.findByName(name) != null) {
            setOfflineStatus(L.t("s_82ede223c7"), true);
            return;
        }

        Account a = Account.createOffline(name);
        accountManager.addAccount(a);
        accountManager.setCurrentAccount(a.getId());

        setOfflineStatus(L.t("s_70bc6d997d") + name, false);
        offlineNameField.clear();
        refreshAccountList();

        if (statusConsumer != null) {
            statusConsumer.accept(L.t("s_2c26eef310") + name);
        }
    }

    private void setOfflineStatus(String msg, boolean error) {
        offlineStatus.setText(msg);
        offlineStatus.getStyleClass().removeAll("text-accent", "text-secondary");
        offlineStatus.getStyleClass().add(error ? "text-secondary" : "text-accent");
        if (error) {
            offlineStatus.getStyleClass().add("status-fail");
        } else {
            offlineStatus.setStyle("");
        }
    }

    // ---------- 微软登录 ----------

    private ScrollPane buildMicrosoftTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        Label title = new Label(L.t("s_51ac065f18"));
        title.getStyleClass().add("card-title");

        Label desc = new Label(
                L.t("s_8f90ca9a70"));
        desc.getStyleClass().add("text-secondary");
        desc.setWrapText(true);

        String clientId = config.getString("azureClientId", "");
        if (clientId.isBlank()) {
            Label warn = new Label(
                    L.t("s_5b7644e1c1"));
            warn.setWrapText(true);
            warn.setPadding(new Insets(10));
            warn.getStyleClass().add("warn-box");
            box.getChildren().add(warn);
        }

        msLoginButton = new Button(L.t("s_a3d95286ef"));
        msLoginButton.getStyleClass().add("button-primary");
        msLoginButton.setDisable(clientId.isBlank());
        msLoginButton.setOnAction(e -> startMsLogin());

        // 设备代码展示
        codeBox = new VBox(8);
        codeBox.getStyleClass().add("card");
        codeBox.setAlignment(Pos.CENTER);
        codeBox.setVisible(false);
        codeBox.setManaged(false);

        Label codeTitle = new Label(L.t("s_b749e36153"));
        codeTitle.getStyleClass().add("card-title");

        deviceCodeLabel = new Label("--------");
        deviceCodeLabel.getStyleClass().add("device-code");

        Label codeHint = new Label(L.t("s_768cf480d5"));
        codeHint.getStyleClass().add("text-muted");

        Button openBrowser = new Button(L.t("s_3f550f3594"));
        openBrowser.getStyleClass().add("button");
        openBrowser.setOnAction(e -> openUrl("https://microsoft.com/link"));

        codeBox.getChildren().addAll(codeTitle, deviceCodeLabel, codeHint, openBrowser);

        msStatus = new Label("");
        msStatus.setWrapText(true);

        box.getChildren().addAll(title, desc, new Separator(),
                msLoginButton, codeBox, msStatus);
        return scrollable(box);
    }

    private void startMsLogin() {
        String clientId = config.getString("azureClientId", "");
        if (clientId.isBlank()) {
            msStatus.setText(L.t("s_4b9f9eb050"));
            return;
        }

        msLoginButton.setDisable(true);
        msLoginButton.setText(L.t("s_ec507ddfa7"));
        msStatus.setText(L.t("s_2a7118278a"));

        CompletableFuture.runAsync(() -> {
            try {
                MicrosoftAuth auth = new MicrosoftAuth();
                auth.setClientId(clientId);

                MicrosoftAuth.DeviceCodeResponse dc = auth.requestDeviceCode();

                Platform.runLater(() -> {
                    deviceCodeLabel.setText(dc.getUserCode());
                    codeBox.setVisible(true);
                    codeBox.setManaged(true);
                    msStatus.setText(L.t("s_683cc62d6e"));

                    // 复制验证码到剪贴板，方便用户粘贴
                    var content = new javafx.scene.input.ClipboardContent();
                    content.putString(dc.getUserCode());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);

                    openUrl(dc.getVerificationUri());
                });

                Account account = auth.login(dc, new MicrosoftAuth.StatusCallback() {
                    public void onWaiting(String c, String u) {
                        Platform.runLater(() -> msStatus.setText(L.t("s_fd9f4beb33")));
                    }
                    public void onPending() {
                        Platform.runLater(() -> msStatus.setText(L.t("s_adbfe358d9")));
                    }
                    public void onSuccess() {
                        Platform.runLater(() -> msStatus.setText(L.t("s_8334c2589c")));
                    }
                    public void onStatus(String s) {
                        Platform.runLater(() -> msStatus.setText(s));
                    }
                });

                accountManager.addAccount(account);
                accountManager.setCurrentAccount(account.getId());

                Platform.runLater(() -> {
                    msLoginButton.setDisable(false);
                    msLoginButton.setText(L.t("s_a3d95286ef"));
                    codeBox.setVisible(false);
                    codeBox.setManaged(false);
                    msStatus.setText(L.t("s_943dc1528f") + account.getUsername());
                    msStatus.getStyleClass().removeIf(cls -> cls.startsWith("status-"));
                    msStatus.getStyleClass().add("status-ok");
                    refreshAccountList();

                    if (statusConsumer != null) {
                        statusConsumer.accept(L.t("s_2c26eef310") + account.getUsername());
                    }
                });

            } catch (Exception e) {
                logger.error("微软登录失败", e);
                Platform.runLater(() -> {
                    msLoginButton.setDisable(false);
                    msLoginButton.setText(L.t("s_a3d95286ef"));
                    codeBox.setVisible(false);
                    codeBox.setManaged(false);
                    msStatus.setText(L.t("s_dde1d2dc48") + e.getMessage());
                    msStatus.getStyleClass().removeIf(cls -> cls.startsWith("status-"));
                    msStatus.getStyleClass().add("status-fail");
                });
            }
        });
    }

    private void openUrl(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                } else {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        Runtime.getRuntime().exec(new String[]{
                                "rundll32", "url.dll,FileProtocolHandler", url});
                    } else if (os.contains("mac")) {
                        Runtime.getRuntime().exec(new String[]{"open", url});
                    } else {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", url});
                    }
                }
            } catch (Exception e) {
                logger.warn("打开浏览器失败: {}", e.getMessage());
            }
        });
    }

}