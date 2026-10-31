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

public class ModVersionChooserWindow {

    private static final Logger logger = LoggerFactory.getLogger(ModVersionChooserWindow.class);

    private final ModalHost modalHost;
    private final ModrinthClient modrinthClient;
    private final ModDownloader modDownloader;
    private final ModrinthClient.ModProject project;

    private final String preferredGameVersion;
    private final Path gameDir;
    private final Consumer<String> statusConsumer;

    
    private List<ModrinthClient.ModVersion> allVersions = new ArrayList<>();

    private ModrinthClient.ModVersion recommended;

    private ModrinthClient.ModVersion selected;

    private volatile boolean closed = false;

    private boolean rebuildPending = false;

    private StackPane contentHost;
    private VBox loadingBox;
    private VBox contentBox;
    private VBox recommendBox;

    private VBox groupContainer;
    private ComboBox<String> gameVersionFilter;
    private ComboBox<String> loaderFilter;
    private ComboBox<String> sortCombo;
    private Label summaryLabel;

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

    public void setIntroMap(com.mclauncher.mod.McmodIntroMap introMap) {
        this.introMap = introMap;
    }

    public void show() {
        BorderPane root = buildUI();
        modalHost.show(L.t("s_57355c8136") + project.getTitle(), root, () -> closed = true);

        showLoading();
        loadVersions();
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("version-dialog-root");
        root.setPrefSize(820, 560);

        
        root.setMinHeight(0);

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

        sub.setMaxWidth(Double.MAX_VALUE);

        header.getChildren().add(sub);

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

        contentHost.setMinHeight(0);
        contentHost.setPadding(new Insets(0, 20, 8, 20));
        VBox.setVgrow(contentHost, Priority.ALWAYS);

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

        box.setMinHeight(0);

        recommendBox = new VBox(6);
        recommendBox.getStyleClass().add("recommend-card");
        recommendBox.setVisible(false);
        recommendBox.setManaged(false);

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

        groupContainer = new VBox(8);

        ScrollPane scroll = new ScrollPane(groupContainer);
        scroll.setFitToWidth(true);

        

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

    
    private int bulkUpdateDepth = 0;

    private void beginBulkUpdate() {
        bulkUpdateDepth++;
        rebuildPending = false;
    }

    private void endBulkUpdate() {
        if (bulkUpdateDepth > 0 && --bulkUpdateDepth == 0 && rebuildPending) {
            rebuildGroups();
        }
    }

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

            if (!complete && closed) {
                return;
            }
            final boolean done = complete;
            Platform.runLater(() -> onLoadDone(done));
        });
    }

    private void onVersionPage(List<ModrinthClient.ModVersion> page) {
        if (closed) {
            return;
        }
        if (allVersions.isEmpty()) {
            showContent();
        }

        
        Set<String> seen = new HashSet<>();
        for (ModrinthClient.ModVersion v : allVersions) {
            seen.add(v.getId());
        }
        for (ModrinthClient.ModVersion v : page) {

            
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

        
        rebuildGroups();
        if (summaryLabel != null) {
            summaryLabel.setText(L.t("s_ee0fff9d9a") + allVersions.size() + L.t("s_3ff6c7c2a8"));
        }
    }

    private boolean loadComplete = false;

    private final Set<String> noFileIds = new HashSet<>();
    private int versionsWithoutFile = 0;

    private static final int FIRST_PAGE_SIZE = 30;

    private boolean buildFilters() {
        LinkedHashSet<String> gameVersions = new LinkedHashSet<>();
        LinkedHashSet<String> loaders = new LinkedHashSet<>();

        for (ModrinthClient.ModVersion v : allVersions) {
            gameVersions.addAll(v.getGameVersions());
            loaders.addAll(v.getLoaders());
        }

        List<String> gv = new ArrayList<>(gameVersions);

        

        

        

        
        gv.sort((a, b) -> {
            boolean an = parseVersion(a).length > 0;
            boolean bn = parseVersion(b).length > 0;
            if (an != bn) {
                return an ? -1 : 1;     
            }
            int c = compareVersion(b, a);   
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> ld = new ArrayList<>(loaders);
        ld.sort(String::compareTo);

        gv.add(0, L.t("s_778fc8f994"));
        ld.add(0, L.t("s_778fc8f994"));

        
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

        Map<String, List<ModrinthClient.ModVersion>> groups = new LinkedHashMap<>();
        for (ModrinthClient.ModVersion v : filtered) {
            String key = v.getGameVersions().isEmpty() ? UNTAGGED : v.getGameVersions().get(0);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }

        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort((a, b) -> {
            boolean an = !a.isEmpty() && Character.isDigit(a.charAt(0));
            boolean bn = !b.isEmpty() && Character.isDigit(b.charAt(0));
            if (an != bn) {
                return an ? -1 : 1;     
            }
            return asc ? compareVersion(a, b) : compareVersion(b, a);
        });

        if (bulkUpdateDepth > 0) {
            rebuildPending = true;
            return;
        }
        rebuildPending = false;

        groupContainer.getChildren().clear();

        String expandKey = null;
        if (recommended != null && !recommended.getGameVersions().isEmpty()) {
            expandKey = recommended.getGameVersions().get(0);
        }

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<ModrinthClient.ModVersion> list = groups.get(key);

            list.sort(Comparator.comparing(
                    (ModrinthClient.ModVersion v) -> v.getDatePublished() == null
                            ? "" : v.getDatePublished(),
                    Comparator.reverseOrder()));

            

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

    private static final String UNTAGGED = L.t("s_9ebde45679");

    private final Map<String, Boolean> manualExpanded = new HashMap<>();

    private static final int ROWS_PER_CHUNK = 40;

    private VBox buildGroup(String gameVersion, List<ModrinthClient.ModVersion> versions,
                            boolean expanded) {
        VBox group = new VBox(6);

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

        VBox body = new VBox(4);
        body.setPadding(new Insets(2, 0, 4, 12));
        body.setVisible(expanded);
        body.setManaged(expanded);

        fillRows(body, versions, 0);

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

    private void fillRows(VBox body, List<ModrinthClient.ModVersion> versions, int from) {

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

        if (recommended != null && recommended.getId() != null
                && recommended.getId().equals(v.getId())) {
            Label badge = new Label(L.t("s_62b46f24ae"));
            badge.getStyleClass().add("version-badge");
            row.getChildren().add(badge);
        } else if (v.getVersionType() != null && !"release".equals(v.getVersionType())) {
            Label badge = new Label(v.getVersionType());

            badge.getStyleClass().addAll("version-type-badge", "version-type-snapshot");
            row.getChildren().add(badge);
        }

        Label date = new Label(shortDate(v.getDatePublished()));
        date.getStyleClass().add("version-date");
        row.getChildren().add(date);

        row.setUserData(v);              
        row.setOnMouseClicked(e -> selectVersion(v));

        updateRowSelectionStyle(row, v);

        return row;
    }

    private void updateRowSelectionStyle(HBox row, ModrinthClient.ModVersion v) {
        boolean isSel = selected != null && selected.getId() != null
                && selected.getId().equals(v.getId());

        row.pseudoClassStateChanged(SELECTED_PSEUDO, isSel);
    }

    private static final javafx.css.PseudoClass SELECTED_PSEUDO =
            javafx.css.PseudoClass.getPseudoClass("selected");

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

        
        if (pa.length == 0 || pb.length == 0) {
            if (pa.length != pb.length) {
                return Integer.compare(pa.length, pb.length);   
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
