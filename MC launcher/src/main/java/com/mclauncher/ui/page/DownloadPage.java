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

public class DownloadPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(DownloadPage.class);

    private final ConfigManager config;
    private final BMCLApiClient apiClient;
    private final java.util.function.Consumer<String> statusConsumer;

    private static final java.util.Map<String, javafx.scene.image.Image> ICON_CACHE
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Set<String> LOADING_ICONS
            = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.ExecutorService ICON_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "icon-loader");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.Map<String, java.util.List<Runnable>> ICON_WAITERS
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Set<String> FAILED_ICONS
            = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BorderPane root;
    private TabPane tabPane;

    private com.mclauncher.ui.component.ModalHost modalHost;

    
    private com.mclauncher.ui.component.VersionListPane versionPane;

    private CheckBox withLoaderCheck;
    private ComboBox<String> loaderTypeCombo;
    private ComboBox<String> loaderVersionCombo;
    private Label loaderHintLabel;

    private volatile boolean loadingLoaderVersions = false;
    private Button gameDownloadButton;
    private Button gameCancelButton;
    private GameDownloader gameDownloader;

    private volatile boolean gameDownloading = false;

    private DownloadProgressWindow activeProgressWindow;

    private volatile java.util.concurrent.atomic.AtomicBoolean currentCancelRef;
    private List<String> availableVersions = new ArrayList<>();

    private ModrinthClient modrinthClient;
    private ModDownloader modDownloader;
    private TextField modSearchField;
    private Button modSearchButton;
    private ComboBox<String> modLoaderFilter;
    private ComboBox<String> modVersionFilter;

    private List<LocalVersion> localVersions = new ArrayList<>();
    private ListView<ModrinthClient.ModProject> modResultList;

    private Label modResultTitle;

    private ProgressIndicator modLoadingIndicator;
    private Label modLoadingLabel;

    private volatile boolean loadingPopular = false;

    private final java.util.Map<ListView<ModrinthClient.ModProject>, IntroTicket> pendingIntro =
            new java.util.IdentityHashMap<>();

    private IntroTicket takePending(ListView<ModrinthClient.ModProject> list) {
        return pendingIntro.remove(list);
    }

    private void openModDetails(int index) {
        List<ModrinthClient.ModProject> items = modResultList.getItems();
        if (index < 0 || index >= items.size()) {
            return;
        }

        
        ModrinthClient.ModProject project = items.get(index);
        selectedProject = project;
        openVersionChooser(project);
    }

    private void openPackDetails(int index) {
        if (index < 0 || index >= packResultList.getItems().size()) {
            return;
        }

        
        openPackInstaller(packResultList.getItems().get(index));
    }

    private ModrinthClient.ModProject selectedProject;

    private ModrinthClient.ModVersion chosenVersion;
    private Label modStatusLabel;
    private Label modFileLabel;
    private Label modStatsLabel;
    private ProgressBar modProgressBar;
    private Button modDownloadButton;
    private Button modNextPageButton;
    private Label modPageLabel;

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

    private final List<ModrinthClient.ModProject> packResults = new ArrayList<>();
    private int packPage = 0;
    private volatile boolean packLoading = false;

    private boolean suppressPackFilterAction = false;

    private boolean suppressModFilterAction = false;

    private List<ModrinthClient.ModProject> searchResults = new ArrayList<>();
    private List<ModrinthClient.ModVersion> modVersions = new ArrayList<>();
    private int currentPage = 0;
    private static final int PAGE_SIZE = 20;

    private static final class ViewState {
        int gen;

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

        if (versionPane != null) {
            versionPane.loadIfNeeded();
        }

        loadLocalGameVersions();

        

        
        if (modResultList != null && modResultList.getItems().isEmpty()
                && searchResults.isEmpty() && !loadingPopular
                && !pendingIntro.containsKey(modResultList)) {
            loadPopularMods();
        }

        if (packResultList != null && packResultList.getItems().isEmpty()
                && packResults.isEmpty() && !packLoading
                && !pendingIntro.containsKey(packResultList)) {
            searchPacks(0);
        }
    }

    @Override
    public void onHide() {

    }

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");

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

    private BorderPane buildGameDownloadTab() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-surface");

        VBox top = new VBox(12);
        top.setPadding(new Insets(16));

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

        versionPane = new VersionListPane(apiClient, statusConsumer, this::onVersionPicked);

        VBox.setVgrow(versionPane.getContent(), Priority.ALWAYS);

        
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

        loaderTypeCombo.setOnAction(e -> {
            if (withLoaderCheck.isSelected()) {
                loadLoaderVersionsForDownload();
            }
        });

        

        loaderRow.getChildren().addAll(withLoaderCheck, loaderTypeCombo,
                loaderVersionCombo, loaderHintLabel);

        

        Region versionContent = versionPane.getContent();
        VBox.setVgrow(versionContent, Priority.ALWAYS);

        top.getChildren().addAll(selectRow, loaderRow, versionContent);

        pane.setCenter(top);
        return pane;
    }

    private McmodIntroMap mcmodIntros;

    private BorderPane buildModDownloadTab() {
        this.modrinthClient = new ModrinthClient();
        this.modDownloader = new ModDownloader(this.modrinthClient);

        

        McmodIntroMap shared = McmodIntroMap.shared();
        if (shared != null) {
            this.mcmodIntros = shared;
        } else {
            this.mcmodIntros = new McmodIntroMap();
            this.mcmodIntros.loadAsync(com.mclauncher.utils.AppPaths.appDataDir());
        }

        
        this.mcmodIntros.onLoaded(() -> javafx.application.Platform.runLater(() -> {
            logger.info("简介表已就绪，刷新模组/整合包列表");
            if (modResultList != null) {
                modResultList.refresh();
            }
            if (packResultList != null) {
                packResultList.refresh();
            }

            
            for (ListView<ModrinthClient.ModProject> list : new ArrayList<>(pendingIntro.keySet())) {
                IntroTicket ticket = pendingIntro.get(list);
                if (ticket != null) {
                    firePending(list, ticket);
                }
            }
        }));

        

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

        HBox body = new HBox(14);
        body.setPadding(new Insets(0, 10, 0, 10));
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox left = new VBox(6);
        HBox.setHgrow(left, Priority.ALWAYS);

        modResultTitle = new Label(L.t("s_7e17935278"));

        modStatusLabel = new Label("");
        modStatusLabel.getStyleClass().add("text-muted");
        modResultTitle.getStyleClass().add("section-title");

        modResultList = new ListView<>();
        modResultList.getStyleClass().add("mod-list");
        modResultList.setPlaceholder(new Label(L.t("s_30350f719a")));
        VBox.setVgrow(modResultList, Priority.ALWAYS);
        modResultList.setCellFactory(lv -> new ModCardCell());

        

        modResultList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, old, idx) -> {
                    int i = idx == null ? -1 : idx.intValue();
                    selectedProject = i >= 0 && i < searchResults.size()
                            ? searchResults.get(i) : null;
                });

        modResultList.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                openModDetails(modResultList.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });

        modLoadingIndicator = new ProgressIndicator();
        modLoadingIndicator.setMaxSize(22, 22);
        modLoadingIndicator.setVisible(false);
        modLoadingIndicator.setManaged(false);

        modLoadingLabel = new Label("");
        modLoadingLabel.getStyleClass().add("text-secondary");

        HBox modLoadingRow = new HBox(10, modLoadingIndicator, modLoadingLabel);
        modLoadingRow.setAlignment(Pos.CENTER_LEFT);

        
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

        

        

        body.getChildren().addAll(left);

        VBox center = new VBox(6, top, body, modFooter);
        VBox.setVgrow(body, Priority.ALWAYS);
        pane.setCenter(center);

        return pane;
    }

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

        
        packResultList.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                openPackDetails(packResultList.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });

        packStatusLabel = new Label("");
        packStatusLabel.getStyleClass().add("text-muted");

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

                String index = query.isEmpty() ? "downloads" : "relevance";
                return modrinthClient.search(query, gv, ld, page * PAGE_SIZE, PAGE_SIZE,
                        index, "modpack");
            } catch (Exception e) {
                logger.error("搜索整合包失败", e);
                return null;
            }
        }).thenAccept(result -> Platform.runLater(() -> {

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

                packStatusLabel.setText(L.t("s_f0a914e43d"));
                return;
            }

            packResults.clear();
            packResults.addAll(result.getProjects());

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

                suppressPackFilterAction = true;
                try {
                    packVersionFilter.getItems().setAll(items);
                    packVersionFilter.getSelectionModel().select(
                            keep != null && items.contains(keep) ? keep : L.t("s_eaf1b3133c"));
                } finally {
                    suppressPackFilterAction = false;
                }
            }

            

            presentWhenReady(packResultList, new ArrayList<>(packResults),
                    query.isEmpty() ? L.t("s_cc1bbccf93") : L.t("s_988282b375") + query,
                    L.t("s_497064be1f") + result.getTotalHits() + L.t("s_1e87867693"),
                    result, "pack");
        }));
    }

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

    private class PackCardCell extends ListCell<ModrinthClient.ModProject> {

        private final StackPane iconSlot = createIconSlot();
        private final Label name = new Label();
        private final Label desc = new Label();
        private final Label meta = new Label();
        private final HBox card = new HBox(12);

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

            bound = item;
            name.setText(item.getTitle());

            String d = mcmodIntros != null
                    ? mcmodIntros.lookupIntro(item.getTitle(), item.getSlug()) : null;
            if (d == null || d.isBlank()) {
                d = item.getDescription() == null ? "" : item.getDescription();
            }
            desc.setText(d.length() > 110 ? d.substring(0, 110) + "..." : d);

            StringBuilder m = new StringBuilder();
            if (!item.getGameVersions().isEmpty()) {
                List<String> gv = new ArrayList<>(item.getGameVersions());

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

        

        mountIconAsync(iconSlot, item, () -> {
            String key = item.getIconUrl();
            return key != null && key.equals(iconSlot.getProperties().get(ICON_KEY));
        });
            setGraphic(card);
            setText(null);
        }
    }

    private static StackPane createIconSlot() {
        StackPane slot = new StackPane();
        slot.setMinSize(40, 40);
        slot.setPrefSize(40, 40);
        slot.setMaxSize(40, 40);

        Rectangle clip = new Rectangle(40, 40);
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        slot.setClip(clip);
        return slot;
    }

    private static final String ICON_KEY = "icon-key";

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

        String phKey = "placeholder:" + item.getTitle();
        if (!phKey.equals(slot.getProperties().get(ICON_KEY))) {
            slot.getChildren().setAll(modIconPlaceholder(item.getTitle()));
            slot.getProperties().put(ICON_KEY, phKey);
        }
        return false;
    }

    private boolean hasCachedIcon(ModrinthClient.ModProject item) {
        String url = item.getIconUrl();
        return url != null && !url.isBlank() && ICON_CACHE.containsKey(url);
    }

    private void ensureIconsLoaded(List<ModrinthClient.ModProject> items, Runnable onDone) {

        

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

    private void mountIconAsync(StackPane slot, ModrinthClient.ModProject item,
                                java.util.function.BooleanSupplier alive) {
        String url = item.getIconUrl();

        
        if (url == null || url.isBlank() || FAILED_ICONS.contains(url)) {
            mountIconCached(slot, item);    
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

                            }
                        }
                    }
                });
            });
        }
    }

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

    private class ModCardCell extends ListCell<ModrinthClient.ModProject> {

        private final StackPane iconSlot = createIconSlot();
        private final Label name = new Label();
        private final Label desc = new Label();
        private final Label meta = new Label();
        private final HBox card = new HBox(12);

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

            
            bound = item;
            name.setText(item.getTitle());

            String d = mcmodIntros != null
                    ? mcmodIntros.lookupIntro(item.getTitle(), item.getSlug()) : null;
            if (d == null || d.isBlank()) {
                d = item.getDescription() == null ? "" : item.getDescription();
            }
            desc.setText(d.length() > 110 ? d.substring(0, 110) + "..." : d);

            StringBuilder m = new StringBuilder();
            if (!item.getLoaders().isEmpty()) {
                m.append(String.join(" / ", item.getLoaders()));
            }
            if (item.getDownloads() > 0) {
                if (m.length() > 0) m.append("    ");
                m.append(L.t("s_3f10b573ee")).append(formatDownloads(item.getDownloads()));
            }
            meta.setText(m.toString());

        

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

    private String currentVersionId;  

    private void onVersionPicked(String versionId) {
        this.currentVersionId = versionId;

        if (gameDownloading) {
            return;
        }

        gameDownloadButton.setDisable(false);

        if (withLoaderCheck.isSelected()) {
            loadLoaderVersionsForDownload();
        }
    }

    private void startGameDownload() {
        if (currentVersionId == null) {
            return;
        }

        
        if (activeProgressWindow != null) {

            
            activeProgressWindow.show();
            return;
        }

        final String versionId = currentVersionId;

        final java.util.concurrent.atomic.AtomicBoolean currentCancel =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        this.currentCancelRef = currentCancel;

        DownloadProgressWindow progressWin = new DownloadProgressWindow(
                modalHost,
                versionId,
                v -> cancelGameDownload());
        activeProgressWindow = progressWin;

        gameDownloading = true;
        gameDownloadButton.setText(L.t("s_83dba218eb"));
        gameCancelButton.setDisable(true);   

        progressWin.show();
        progressWin.appendLog(L.t("s_a0a16feb25") + versionId);

        

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

                
                if (currentCancel.get()) {
                    progressWin.close();
                    return;
                }

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

                

                javafx.application.Platform.runLater(() -> {
                    if (activeProgressWindow == progressWin) {
                        activeProgressWindow = null;
                    }
                    gameDownloading = false;

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

            return gameVersion;

        } finally {
            installer.shutdown();
        }
    }

    private void cancelGameDownload() {
        if (gameDownloader != null && (gameDownloading || activeProgressWindow != null)) {

            
            if (currentCancelRef != null) currentCancelRef.set(true);
            gameDownloader.cancel();
            logger.info("用户取消了下载");
        }
        if (gameDownloadButton != null) {
            gameDownloadButton.setDisable(false);
            gameDownloadButton.setText(L.t("s_92f35590d5"));
        }
    }

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

            if (!modView.isCurrent(gen)) {
                return;
            }
            setModLoading(false, null);
            modSearchButton.setDisable(false);
            modSearchButton.setText(L.t("s_f04090805c"));

            if (result == null) {

                modStatusLabel.setText(L.t("s_f0a914e43d"));
                return;
            }

            searchResults = result.getProjects();

            presentWhenReady(modResultList, new ArrayList<>(searchResults),
                    query.isEmpty() ? L.t("s_7e17935278") : L.t("s_988282b375") + query,
                    String.format(L.t("s_8a6085c3ba"), result.getTotalHits()),
                    result, "mod");
        }));
    }

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

            presentWhenReady(modResultList, new ArrayList<>(searchResults), L.t("s_7e17935278"),
                    L.t("s_c8d76324ee") + result.getTotalHits() + L.t("s_aa10f570ef"),
                    result, "mod");
        }));
    }

    private void presentWhenReady(ListView<ModrinthClient.ModProject> list,
                                  List<ModrinthClient.ModProject> items,
                                  String title, String status,
                                  ModrinthClient.SearchResult result,
                                  String kind) {
        if (mcmodIntros != null && mcmodIntros.isLoaded()) {
            renderResults(list, items, title, status, result, kind);
            return;
        }

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

    private void waitIntro(ListView<ModrinthClient.ModProject> list, String kind) {
        IntroTicket ticket = pendingIntro.get(list);
        if (ticket == null) {
            return;
        }
        if (mcmodIntros != null && mcmodIntros.isLoaded()) {
            firePending(list, ticket);
            return;
        }
        if (++ticket.ticks >= 600) {     
            logger.warn("简介表长时间未就绪，先显示英文结果");
            firePending(list, ticket);
            return;
        }
        CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> Platform.runLater(() -> waitIntro(list, kind)));
    }

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

            this.hidePackLoading = !"mod".equals(kind);
        }
    }

    private void renderResults(ListView<ModrinthClient.ModProject> list,
                               List<ModrinthClient.ModProject> items,
                               String title, String status,
                               ModrinthClient.SearchResult result, String kind) {

        
        final boolean isMod = list == modResultList;

        

        

        applyModResults(list, items, title, status, result, isMod);

        

        ensureIconsLoaded(items, () -> {
            if (isMod ? modResultList != null : packResultList != null) {
                list.refresh();
            }
        });
    }

    private void applyModResults(ListView<ModrinthClient.ModProject> list,
                                 List<ModrinthClient.ModProject> items,
                                 String title, String status,
                                 ModrinthClient.SearchResult result, boolean isMod) {

        

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

        
        list.setOpacity(0);

        
        list.getItems().setAll(new ArrayList<>(items));
        javafx.animation.FadeTransition fade =
                new javafx.animation.FadeTransition(javafx.util.Duration.millis(140), list);
        fade.setFromValue(0);
        fade.setToValue(1);

        javafx.application.Platform.runLater(fade::play);

        
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

        list.getSelectionModel().select(0);
    }

    private static void setLabel(Label l, String text) {
        if (l != null) {
            l.setText(text);
        }
    }

    private void setModLoading(boolean loading, String text) {
        if (modLoadingIndicator != null) {
            modLoadingIndicator.setVisible(loading);
            modLoadingIndicator.setManaged(loading);
        }
        if (modLoadingLabel != null) {
            modLoadingLabel.setText(loading && text != null ? text : "");
        }
    }

    private static String formatDownloadsShort(long n) {
        if (n >= 100_000_000) return String.format(L.t("s_48be8f0bcb"), n / 100_000_000.0);
        if (n >= 10_000) return String.format(L.t("s_c8af81cb79"), n / 10_000.0);
        return String.valueOf(n);
    }

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

        window.setIntroMap(mcmodIntros);
        window.show();
    }

    private void downloadSelectedMod() {
        if (chosenVersion == null) {
            return;
        }
        modStatusLabel.setText(L.t("s_d6ad72122d"));
    }
}
