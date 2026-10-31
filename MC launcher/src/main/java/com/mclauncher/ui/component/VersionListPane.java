package com.mclauncher.ui.component;

import com.mclauncher.i18n.L;
import com.mclauncher.network.BMCLApiClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class VersionListPane {

    private static final Logger logger = LoggerFactory.getLogger(VersionListPane.class);

    private static final String TYPE_RELEASE = "release";

    private static final String TYPE_SNAPSHOT = "snapshot";

    private final BMCLApiClient apiClient;
    private final Consumer<String> statusConsumer;

    private final Consumer<String> onVersionSelected;

    private VBox root;
    private TextField searchField;
    private CheckBox releaseCheck;
    private CheckBox snapshotCheck;
    private CheckBox oldCheck;
    private ListView<VersionEntry> listView;
    private Label countLabel;
    private ProgressIndicator loadingIndicator;

    private HBox loadingRow;

    private final List<VersionEntry> allVersions = new ArrayList<>();

    private final List<VersionEntry> filteredVersions = new ArrayList<>();

    private boolean loaded = false;

    public VersionListPane(BMCLApiClient apiClient,
                           Consumer<String> statusConsumer,
                           Consumer<String> onVersionSelected) {
        this.apiClient = apiClient;
        this.statusConsumer = statusConsumer;
        this.onVersionSelected = onVersionSelected;
    }

    public Region getContent() {
        if (root == null) {
            root = build();
        }
        return root;
    }

    public void loadIfNeeded() {
        if (!loaded) {
            loadVersions();
        }
    }

    public String getSelectedVersion() {
        VersionEntry e = listView == null ? null : listView.getSelectionModel().getSelectedItem();
        return e == null ? null : e.id;
    }

    public void reload() {
        loaded = false;
        loadVersions();
    }

    public void selectVersion(String versionId) {
        if (versionId == null || listView == null) {
            return;
        }
        for (int i = 0; i < filteredVersions.size(); i++) {
            if (versionId.equals(filteredVersions.get(i).id)) {
                listView.getSelectionModel().select(i);
                listView.scrollTo(i);
                return;
            }
        }
    }

    private VBox build() {
        VBox box = new VBox(10);

        searchField = new TextField();
        searchField.setPromptText(L.t("s_1c3e22c8bf"));
        searchField.setMaxWidth(Double.MAX_VALUE);

        searchField.textProperty().addListener((obs, old, val) -> applyFilter());

        HBox filterBar = new HBox(16);
        filterBar.getStyleClass().add("filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        Label filterLabel = new Label(L.t("s_71b6771bc7"));
        filterLabel.getStyleClass().add("text-secondary");

        releaseCheck = new CheckBox(L.t("s_66a09c3a91"));
        releaseCheck.setSelected(true);           
        releaseCheck.setOnAction(e -> applyFilter());

        snapshotCheck = new CheckBox(L.t("s_b0430580d9"));
        snapshotCheck.setSelected(false);
        snapshotCheck.setOnAction(e -> applyFilter());

        oldCheck = new CheckBox(L.t("s_f4e121f584"));
        oldCheck.setSelected(false);
        oldCheck.setOnAction(e -> applyFilter());

        countLabel = new Label("");
        countLabel.getStyleClass().add("text-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshBtn = new Button(L.t("s_38108eaa1d"));
        refreshBtn.getStyleClass().addAll("button", "button-sm");
        refreshBtn.setOnAction(e -> reload());

        filterBar.getChildren().addAll(filterLabel, releaseCheck, snapshotCheck,
                oldCheck, spacer, countLabel, refreshBtn);

        listView = new ListView<>();
        listView.setPlaceholder(new Label(L.t("s_4a3f8b8578")));
        listView.setCellFactory(lv -> new VersionCell());
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, cur) -> {
                    if (cur != null && onVersionSelected != null) {
                        onVersionSelected.accept(cur.id);
                    }
                });

        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setVisible(false);
        loadingIndicator.setManaged(false);
        loadingIndicator.setMaxSize(28, 28);

        loadingRow = new HBox(10, loadingIndicator,
                new Label(L.t("s_24a3ae10d7")));
        loadingRow.setAlignment(Pos.CENTER_LEFT);
        loadingRow.setPadding(new Insets(6, 0, 6, 0));

        loadingRow.setVisible(false);
        loadingRow.setManaged(false);

        box.getChildren().addAll(searchField, filterBar, loadingRow, listView);
        return box;
    }

    private static class VersionCell extends ListCell<VersionEntry> {
        @Override
        protected void updateItem(VersionEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);

            
            Label idLabel = new Label(item.id);
            idLabel.getStyleClass().add("version-id");

            Label typeLabel = new Label(item.typeDisplay());
            typeLabel.getStyleClass().addAll("version-type-badge", item.typeStyleClass());

            row.getChildren().addAll(idLabel, typeLabel);

            if (item.releaseTime != null && item.releaseTime.length() >= 10) {
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                Label dateLabel = new Label(item.releaseTime.substring(0, 10));
                dateLabel.getStyleClass().add("version-date");
                row.getChildren().addAll(sp, dateLabel);
            }

            setGraphic(row);
            setText(null);
        }
    }

    private void loadVersions() {
        setLoading(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                return apiClient.getVersionManifest();
            } catch (Exception e) {
                logger.error("获取版本列表失败", e);
                return null;
            }
        }).thenAccept(manifest -> Platform.runLater(() -> {
            setLoading(false);

            if (manifest == null || manifest.getVersions() == null) {
                listView.setPlaceholder(new Label(L.t("s_0a41ba7403")));
                return;
            }

            allVersions.clear();
            for (BMCLApiClient.VersionManifest.Version v : manifest.getVersions()) {
                VersionEntry e = new VersionEntry();
                e.id = v.getId();
                e.type = v.getType();
                e.releaseTime = v.getReleaseTime() != null ? v.getReleaseTime() : v.getTime();
                allVersions.add(e);
            }

            loaded = true;
            applyFilter();

            if (statusConsumer != null) {
                statusConsumer.accept(L.t("s_23fa82e936") + allVersions.size() + L.t("s_3ff6c7c2a8"));
            }
        }));
    }

    private void setLoading(boolean loading) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisible(loading);
            loadingIndicator.setManaged(loading);
        }

        
        if (loadingRow != null) {
            loadingRow.setVisible(loading);
            loadingRow.setManaged(loading);
        }
    }

    private void applyFilter() {
        String keyword = searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase();

        boolean wantRelease = releaseCheck.isSelected();
        boolean wantSnapshot = snapshotCheck.isSelected();
        boolean wantOld = oldCheck.isSelected();

        boolean noTypeFilter = !wantRelease && !wantSnapshot && !wantOld;

        filteredVersions.clear();
        for (VersionEntry e : allVersions) {

            if (!noTypeFilter) {
                boolean matched;
                if (isRelease(e.type)) {
                    matched = wantRelease;
                } else if (isSnapshot(e.type)) {
                    matched = wantSnapshot;
                } else {
                    matched = wantOld;      
                }
                if (!matched) {
                    continue;
                }
            }

            if (!keyword.isEmpty() && !e.id.toLowerCase().contains(keyword)) {
                continue;
            }

            filteredVersions.add(e);
        }

        listView.getItems().setAll(filteredVersions);
        countLabel.setText(L.t("s_497064be1f") + filteredVersions.size() + L.t("s_6ee50f2dfa"));

        if (!filteredVersions.isEmpty()) {
            listView.getSelectionModel().select(0);
        }
    }

    private static boolean isRelease(String type) {
        return TYPE_RELEASE.equalsIgnoreCase(type);
    }

    private static boolean isSnapshot(String type) {
        return TYPE_SNAPSHOT.equalsIgnoreCase(type);
    }

    private static class VersionEntry {
        String id;
        String type;
        String releaseTime;

        String typeDisplay() {
            if (isRelease(type)) return L.t("s_66a09c3a91");
            if (isSnapshot(type)) return L.t("s_83caf1badc");
            if (type != null && type.startsWith("old_")) return L.t("s_c83d3813a3");
            return type == null ? L.t("s_d9c32a4c3d") : type;
        }

        String typeStyleClass() {
            if (isRelease(type)) {
                return "version-type-release";
            }
            if (isSnapshot(type)) {
                return "version-type-snapshot";
            }
            return "version-type-other";
        }
    }
}
