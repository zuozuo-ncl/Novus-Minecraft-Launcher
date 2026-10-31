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

public class AccountPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(AccountPage.class);

    private static final SkinFetcher SKINS = new SkinFetcher();

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

    private SkinAvatar currentAvatar;
    private StackPane currentSkinView;
    private Label currentNameLabel;
    private Label currentTypeLabel;
    private Label currentSkinHint;

    private ComboBox<SkinOption> skinSiteCombo;

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

    private VBox contentBox;

    private TabPane createTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabs.getTabs().addAll(
                new Tab(L.t("s_56c18f8733"), buildListTab()),
                new Tab(L.t("s_c1c38438c5"), buildOfflineTab()),
                new Tab(L.t("s_172f1237ed"), buildMicrosoftTab()),

                new Tab(L.t("s_acct_skin_tab"), buildSkinTab())
        );
        tabs.setMinHeight(0);
        return tabs;
    }

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

        updateCurrentCard(accountManager.getCurrentAccount());
        return tabs;
    }

    private ScrollPane scrollable(javafx.scene.Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.getStyleClass().add("tab-scroll");
        sp.setMinHeight(0);
        return sp;
    }

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

    private class AccountCell extends ListCell<Account> {
        private final SkinAvatar avatar = SkinAvatar.circle(34);
        private final Label name = new Label();
        private final Label type = new Label();
        private final VBox text;
        private final HBox box;

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

    private ScrollPane buildSkinTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        Label caption = new Label(L.t("s_cd5778aef9"));
        caption.getStyleClass().add("card-title");
        caption.setMinWidth(Region.USE_PREF_SIZE);

        Label who = new Label(L.t("s_acct_current") + "：" + currentAccountName());
        who.getStyleClass().add("text-secondary");

        

        
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

        skinSiteCombo.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                    && skinSiteCombo.getEditor() != null) {
                String text = skinSiteCombo.getEditor().getText();
                Account a = accountManager.getCurrentAccount();
                if (a != null && text != null && !text.isBlank()
                        && SkinSites.guessApiRoot(text) != null) {
                    applySkinOption(a, SkinOption.custom(text.trim()));
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

    private String currentAccountName() {
        Account a = accountManager.getCurrentAccount();
        return a == null || a.getUsername() == null ? L.t("s_e8041a8c93") : a.getUsername();
    }

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

                        Platform.runLater(() -> {
                            Account cur = accountManager.getCurrentAccount();
                            if (cur != null && cur.getId().equals(account.getId())) {
                                pollSkinPreview(account, attempt + 1);
                            }
                        }));
    }

    private void showSkinPreview(Image skin, Account account) {
        if (skin == null || skin.getPixelReader() == null || currentSkinView == null) {
            return;
        }

        boolean slim = "slim".equals(SKINS.peekModel(account));
        int px = 5;      
        Image full = com.mclauncher.ui.component.SkinRenderer.fullBody(skin, px, slim);
        if (full == null) {
            return;
        }

        ImageView iv = new ImageView(full);
        iv.setSmooth(false);        

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

    static final class SkinOption {
        final String value;
        final String label;

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

    private SkinOption findOption(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
            return skinSiteCombo.getItems().get(0);
        }
        for (SkinOption o : skinSiteCombo.getItems()) {
            if (o.value != null && o.value.equalsIgnoreCase(value)) {
                return o;
            }
        }

        if ("microsoft".equalsIgnoreCase(value)) {
            for (SkinOption o : skinSiteCombo.getItems()) {
                if ("mojang".equals(o.value)) {
                    return o;
                }
            }
        }

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

    private static class SkinOptionCell extends ListCell<SkinOption> {
        private final javafx.scene.image.ImageView thumb = new javafx.scene.image.ImageView();
        private final Label text = new Label();
        private final HBox box;

        SkinOptionCell() {

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
