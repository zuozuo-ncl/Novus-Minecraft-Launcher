package com.mclauncher.ui.page;


import com.mclauncher.i18n.L;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.game.ModLoaderInstaller;
import com.mclauncher.network.BMCLApiClient;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 游戏设置页 */
public class GameSettingsPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(GameSettingsPage.class);

    /** 内嵌弹窗宿主（由 MainWindow 注入） */
    private com.mclauncher.ui.component.ModalHost modalHost;

    @Override
    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    /** 禁用的模组统一加这个后缀 */
    private static final String DISABLED_SUFFIX = ".disabled";

    private final ConfigManager config;
    private final BMCLApiClient apiClient;
    private final Consumer<String> statusConsumer;

    private BorderPane root;
    private TabPane tabPane;

    // 模组管理
    private VBox modListContainer;
    private Label modCountLabel;

    // 各文件管理面板
    private FileListPane worldsPane;
    private FileListPane schematicsPane;
    private FileListPane shaderPacksPane;
    private FileListPane fileBrowserPane;

    // 加载器管理
    private ComboBox<String> loaderTypeCombo;
    private ComboBox<String> loaderGameVersionCombo;
    private ComboBox<String> loaderVersionCombo;
    private TextArea loaderLogArea;
    private ProgressBar loaderProgress;
    private Label loaderStatusLabel;
    private Button loaderInstallButton;
    private VBox installedLoadersBox;

    public GameSettingsPage(ConfigManager config, BMCLApiClient apiClient,
                            Consumer<String> statusConsumer) {
        this.config = config;
        this.apiClient = apiClient;
        this.statusConsumer = statusConsumer;
    }

    @Override
    public String getTitle() {
        return L.t("s_af0f2a0a92");
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
        refreshMods();
        refreshInstalledLoaders();
        if (worldsPane != null) worldsPane.refresh();
        if (schematicsPane != null) schematicsPane.refresh();
        if (shaderPacksPane != null) shaderPacksPane.refresh();
        if (fileBrowserPane != null) fileBrowserPane.refresh();
    }

    // ==================== UI ====================

    private BorderPane buildUI() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("bg-main");

        VBox header = new VBox(4);
        header.setPadding(new Insets(24, 28, 12, 28));

        Label title = new Label(L.t("s_af0f2a0a92"));
        title.getStyleClass().add("page-title");

        Label subtitle = new Label(L.t("s_f571cd6c77"));
        subtitle.getStyleClass().add("page-subtitle");

        header.getChildren().addAll(title, subtitle);

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getTabs().addAll(
                new Tab(L.t("s_3c1e271f64"), buildModTab()),
                new Tab(L.t("s_455e832fd9"), buildLoaderTab()),
                new Tab(L.t("s_55315ff1b9"), buildDirTab(DirKind.WORLDS)),
                new Tab(L.t("s_bc63d4dade"), buildDirTab(DirKind.SCHEMATICS)),
                new Tab(L.t("s_b00037fdd9"), buildDirTab(DirKind.SHADERPACKS)),
                new Tab(L.t("s_0238b54322"), buildDirTab(DirKind.BROWSE))
        );

        VBox center = new VBox(tabPane);
        center.setPadding(new Insets(0, 28, 24, 28));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        pane.setTop(header);
        pane.setCenter(center);
        return pane;
    }

    // ---------- 模组管理 ----------

    private VBox buildModTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(L.t("s_7944d3f4d9"));
        title.getStyleClass().add("card-title");

        modCountLabel = new Label("");
        modCountLabel.getStyleClass().add("text-muted");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refreshBtn = new Button(L.t("s_38108eaa1d"));
        refreshBtn.getStyleClass().addAll("button", "button-sm");
        refreshBtn.setOnAction(e -> refreshMods());

        Button openFolderBtn = new Button(L.t("s_3c618ff08a"));
        openFolderBtn.getStyleClass().addAll("button", "button-sm");
        openFolderBtn.setOnAction(e -> openFolder(modsDir()));

        top.getChildren().addAll(title, modCountLabel, sp, refreshBtn, openFolderBtn);

        Label hint = new Label(
                L.t("s_19f90e95c4"));
        hint.getStyleClass().add("text-muted");
        hint.setWrapText(true);

        modListContainer = new VBox(3);

        ScrollPane scroll = new ScrollPane(modListContainer);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(top, hint, scroll);
        return box;
    }

    /**
     * 刷新模组列表
     */
    private void refreshMods() {
        Path dir = modsDir();

        CompletableFuture.supplyAsync(() -> {
            List<ModEntry> list = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
                    for (Path p : s) {
                        String name = p.getFileName().toString().toLowerCase();
                        // 只认 .jar 与 .jar.disabled
                        if (name.endsWith(".jar")) {
                            ModEntry e = new ModEntry();
                            e.path = p;
                            e.enabled = true;
                            list.add(e);
                        } else if (name.endsWith(".jar.disabled")) {
                            ModEntry e = new ModEntry();
                            e.path = p;
                            e.enabled = false;
                            list.add(e);
                        }
                    }
                } catch (IOException e) {
                    logger.debug("扫描 mods 失败: {}", e.getMessage());
                }
            }
            list.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
            return list;
        }).thenAccept(list -> Platform.runLater(() -> renderMods(list)));
    }

    private void renderMods(List<ModEntry> list) {
        modListContainer.getChildren().clear();

        long enabled = list.stream().filter(e -> e.enabled).count();
        modCountLabel.setText(String.format(L.t("s_7d050744de"),
                list.size(), enabled, list.size() - enabled));

        if (list.isEmpty()) {
            Label empty = new Label(
                    L.t("s_4a80fba937"));
            empty.getStyleClass().add("text-muted");
            empty.setPadding(new Insets(20));
            modListContainer.getChildren().add(empty);
            return;
        }

        for (ModEntry e : list) {
            HBox row = new HBox(10);
            row.getStyleClass().add("filerow");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));

            Label statusDot = new Label(e.enabled ? "●" : "○");
            // 用主题感知的样式类：浅色主题下亮绿对比度不足
            statusDot.getStyleClass().add(e.enabled ? "status-ok" : "text-muted");
            statusDot.setStyle("-fx-font-size: 15px;");

            VBox textBox = new VBox(2);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            Label name = new Label(e.displayName());
            name.getStyleClass().add("filerow-label");
            name.setWrapText(true);
            name.setMaxWidth(Double.MAX_VALUE);

            Label state = new Label(e.enabled ? L.t("s_25d2843150") : L.t("s_0fe5a98e9f"));
            state.getStyleClass().add(e.enabled ? "text-accent" : "text-muted");
            state.setStyle("-fx-font-size: 11px;");

            textBox.getChildren().addAll(name, state);

            Button toggle = new Button(e.enabled ? L.t("s_be70be5a2e") : L.t("s_d4e9ca3dd4"));
            toggle.getStyleClass().addAll("button", "button-sm");
            toggle.setOnAction(ev -> toggleMod(e));

            Button del = new Button(L.t("s_3755f56f2f"));
            del.getStyleClass().addAll("button", "button-sm");
            del.setStyle("-fx-background-color: #E05D5D; -fx-text-fill: white;");
            del.setOnAction(ev -> deleteMod(e));

            row.getChildren().addAll(statusDot, textBox, toggle, del);
            modListContainer.getChildren().add(row);
        }
    }

    /**
     * 启用/禁用模组：通过改扩展名实现
     * 这是业界通行做法 —— Minecraft 只加载 .jar，改名即失效
     */
    private void toggleMod(ModEntry entry) {
        CompletableFuture.runAsync(() -> {
            try {
                String name = entry.path.getFileName().toString();
                Path target;

                if (entry.enabled) {
                    // 禁用：xxx.jar -> xxx.jar.disabled
                    target = entry.path.resolveSibling(name + DISABLED_SUFFIX);
                } else {
                    // 启用：xxx.jar.disabled -> xxx.jar
                    if (!name.toLowerCase().endsWith(DISABLED_SUFFIX)) {
                        return;
                    }
                    target = entry.path.resolveSibling(
                            name.substring(0, name.length() - DISABLED_SUFFIX.length()));
                }

                if (Files.exists(target)) {
                    Platform.runLater(() -> showError(L.t("s_a153946562") + target.getFileName()));
                    return;
                }

                Files.move(entry.path, target);

                Platform.runLater(() -> {
                    if (statusConsumer != null) {
                        statusConsumer.accept((entry.enabled ? L.t("s_e612cea18d") : L.t("s_d2b99812c6")) + entry.displayName());
                    }
                    refreshMods();
                });

            } catch (IOException e) {
                logger.error("切换模组状态失败", e);
                Platform.runLater(() -> showError("操作失败: " + e.getMessage()));
            }
        });
    }

    private void deleteMod(ModEntry entry) {
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_3c06abe116"),
                L.t("s_5df8a7e91e") + entry.displayName() + "」",
                null,
                L.t("s_41d0a2222b"),
                L.t("s_3755f56f2f"),
                () -> {
                    CompletableFuture.runAsync(() -> {
                        try {
                            Files.delete(entry.path);
                            Platform.runLater(() -> refreshMods());
                        } catch (IOException e) {
                            Platform.runLater(() -> showError(L.t("s_5a5a8a09cc") + e.getMessage()));
                        }
                    });
                });
    }

    private Path modsDir() {
        return config.getGameDir().resolve("mods");
    }

    // ---------- 加载器管理 ----------

    private VBox buildLoaderTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        // 已安装的加载器
        Label installedTitle = new Label(L.t("s_939a2da274"));
        installedTitle.getStyleClass().add("card-title");

        installedLoadersBox = new VBox(3);

        // 安装区
        Label installTitle = new Label(L.t("s_84fc9a0d9a"));
        installTitle.getStyleClass().add("card-title");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);

        grid.add(new Label(L.t("s_455e832fd9")), 0, 0);
        loaderTypeCombo = new ComboBox<>();
        loaderTypeCombo.getItems().addAll("Fabric", "Forge", "OptiFine");
        loaderTypeCombo.getSelectionModel().select(0);
        loaderTypeCombo.setPrefWidth(200);
        loaderTypeCombo.setOnAction(e -> loadLoaderVersions());
        grid.add(loaderTypeCombo, 1, 0);

        grid.add(new Label(L.t("s_f4e1ba8015")), 0, 1);
        loaderGameVersionCombo = new ComboBox<>();
        loaderGameVersionCombo.setPrefWidth(200);
        loaderGameVersionCombo.setPromptText(L.t("s_05bf5c1f19"));
        loaderGameVersionCombo.setOnAction(e -> loadLoaderVersions());
        grid.add(loaderGameVersionCombo, 1, 1);

        grid.add(new Label(L.t("s_3dc7ad0b70")), 0, 2);
        loaderVersionCombo = new ComboBox<>();
        loaderVersionCombo.setPrefWidth(200);
        loaderVersionCombo.setPromptText(L.t("s_ba7f7110b0"));
        grid.add(loaderVersionCombo, 1, 2);

        HBox btnRow = new HBox(10);
        loaderInstallButton = new Button(L.t("s_d109e7cc01"));
        loaderInstallButton.getStyleClass().add("button-primary");
        loaderInstallButton.setDisable(true);
        loaderInstallButton.setOnAction(e -> installLoader());

        Button loadGameVersionsBtn = new Button(L.t("s_292cb1b875"));
        loadGameVersionsBtn.getStyleClass().add("button");
        loadGameVersionsBtn.setOnAction(e -> loadGameVersions());

        btnRow.getChildren().addAll(loaderInstallButton, loadGameVersionsBtn);

        loaderProgress = new ProgressBar(0);
        loaderProgress.setMaxWidth(Double.MAX_VALUE);
        loaderProgress.setVisible(false);
        loaderProgress.setManaged(false);

        loaderStatusLabel = new Label("");
        loaderStatusLabel.getStyleClass().add("text-secondary");
        loaderStatusLabel.setWrapText(true);

        loaderLogArea = new TextArea();
        loaderLogArea.getStyleClass().add("log-area");
        loaderLogArea.setEditable(false);
        loaderLogArea.setPrefHeight(120);

        box.getChildren().addAll(
                installedTitle, installedLoadersBox,
                new Separator(),
                installTitle, grid, btnRow,
                loaderProgress, loaderStatusLabel, loaderLogArea
        );

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    /**
     * 列出已安装的加载器版本（扫描 versions 目录的目录名特征）
     */
    private void refreshInstalledLoaders() {
        if (installedLoadersBox == null) {
            return;
        }

        Path versionsDir = config.getGameDir().resolve("versions");

        CompletableFuture.supplyAsync(() -> {
            List<String> loaders = new ArrayList<>();
            if (Files.isDirectory(versionsDir)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(versionsDir)) {
                    for (Path p : s) {
                        if (!Files.isDirectory(p)) continue;
                        String n = p.getFileName().toString().toLowerCase();
                        boolean isLoader = n.contains("forge")
                                || n.contains("fabric")
                                || n.contains("optifine")
                                || n.contains("neoforge")
                                || n.contains("quilt");
                        if (isLoader && Files.exists(p.resolve(p.getFileName() + ".json"))) {
                            loaders.add(p.getFileName().toString());
                        }
                    }
                } catch (IOException e) {
                    logger.debug("扫描加载器失败: {}", e.getMessage());
                }
            }
            loaders.sort(String::compareToIgnoreCase);
            return loaders;
        }).thenAccept(list -> Platform.runLater(() -> {
            installedLoadersBox.getChildren().clear();

            if (list.isEmpty()) {
                Label empty = new Label(L.t("s_be823b0398"));
                empty.getStyleClass().add("text-muted");
                installedLoadersBox.getChildren().add(empty);
                return;
            }

            for (String name : list) {
                HBox row = new HBox(10);
                row.getStyleClass().add("filerow");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 12, 8, 12));

                Label label = new Label(name);
                label.getStyleClass().add("filerow-label");
                HBox.setHgrow(label, Priority.ALWAYS);
                label.setMaxWidth(Double.MAX_VALUE);

                Button uninstall = new Button(L.t("s_200cf1550d"));
                uninstall.getStyleClass().addAll("button", "button-sm");
                uninstall.setStyle("-fx-background-color: #E05D5D; -fx-text-fill: white;");
                uninstall.setOnAction(e -> uninstallLoader(name));

                row.getChildren().addAll(label, uninstall);
                installedLoadersBox.getChildren().add(row);
            }
        }));
    }

    /**
     * 卸载加载器：删除对应的版本目录
     */
    private void uninstallLoader(String versionId) {
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_c7ff7b9420"),
                L.t("s_6783d9740c") + versionId + "」",
                null,
                L.t("s_e8c7418ac9"),
                L.t("s_200cf1550d"),
                () -> {
                    CompletableFuture.runAsync(() -> {
                        try {
                            Path dir = config.getGameDir().resolve("versions").resolve(versionId);
                            deleteRecursively(dir);
                            Platform.runLater(() -> {
                                loaderLog(L.t("s_866f09dcc9") + versionId);
                                refreshInstalledLoaders();
                                if (statusConsumer != null) {
                                    statusConsumer.accept(L.t("s_971c100a75") + versionId);
                                }
                            });
                        } catch (IOException e) {
                            Platform.runLater(() -> loaderLog(L.t("s_15f5ed92a4") + e.getMessage()));
                        }
                    });
                });
    }

    private void loadGameVersions() {
        loaderStatusLabel.setText(L.t("s_2db4fedc6d"));
        loaderLog(L.t("s_18c0b0405a"));

        CompletableFuture.supplyAsync(() -> {
            try {
                BMCLApiClient.VersionManifest m = apiClient.getVersionManifest();
                List<String> ids = new ArrayList<>();
                if (m != null && m.getVersions() != null) {
                    for (BMCLApiClient.VersionManifest.Version v : m.getVersions()) {
                        if ("release".equals(v.getType())) {
                            ids.add(v.getId());
                        }
                    }
                }
                return ids;
            } catch (Exception e) {
                logger.error("获取版本失败", e);
                return null;
            }
        }).thenAccept(list -> Platform.runLater(() -> {
            if (list == null || list.isEmpty()) {
                loaderStatusLabel.setText(L.t("s_a74baca8b3"));
                loaderLog(L.t("s_9759052ee1"));
                return;
            }
            loaderGameVersionCombo.getItems().setAll(list);
            loaderGameVersionCombo.getSelectionModel().select(0);
            loaderStatusLabel.setText(L.t("s_23fa82e936") + list.size() + L.t("s_91067e0899"));
            loaderLog(L.t("s_23fa82e936") + list.size() + L.t("s_91067e0899"));
            loadLoaderVersions();
        }));
    }

    private void loadLoaderVersions() {
        String gameVersion = loaderGameVersionCombo.getSelectionModel().getSelectedItem();
        String type = loaderTypeCombo.getSelectionModel().getSelectedItem();
        if (gameVersion == null || type == null) {
            return;
        }

        loaderVersionCombo.getItems().clear();
        loaderVersionCombo.setPromptText(L.t("s_514c33af5c"));
        loaderInstallButton.setDisable(true);

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
            loaderVersionCombo.setPromptText(L.t("s_ba7f7110b0"));

            if (list == null || list.isEmpty()) {
                loaderVersionCombo.setPromptText(L.t("s_c2924103b0"));
                loaderStatusLabel.setText(L.t("s_1fb46e4cb9") + gameVersion + L.t("s_246cfa4efb") + type + L.t("s_d0e9f788da"));
                return;
            }

            loaderVersionCombo.getItems().setAll(list);
            loaderVersionCombo.getSelectionModel().select(0);
            loaderInstallButton.setDisable(false);
            loaderStatusLabel.setText(type + " — " + list.size() + L.t("s_7125cd7317"));
        }));
    }

    private void installLoader() {
        String type = loaderTypeCombo.getSelectionModel().getSelectedItem();
        String gameVersion = loaderGameVersionCombo.getSelectionModel().getSelectedItem();
        String loaderVersion = loaderVersionCombo.getSelectionModel().getSelectedItem();

        if (type == null || gameVersion == null || loaderVersion == null) {
            return;
        }

        loaderInstallButton.setDisable(true);
        loaderInstallButton.setText(L.t("s_1597e6f247"));
        loaderProgress.setVisible(true);
        loaderProgress.setManaged(true);
        loaderProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        loaderStatusLabel.setText(L.t("s_8b50af569e") + type + " " + loaderVersion + "...");
        loaderLog(L.t("s_b4056e1002") + type + " " + loaderVersion + " for " + gameVersion);

        CompletableFuture.supplyAsync(() -> {
            ModLoaderInstaller installer = new ModLoaderInstaller(apiClient, config.getGameDir());
            try {
                String id = switch (type) {
                    case "Fabric" -> installer.installFabric(gameVersion, loaderVersion);
                    case "Forge" -> installer.installForge(gameVersion, loaderVersion);
                    default -> installer.installOptifine(gameVersion, loaderVersion);
                };
                return id;
            } catch (Exception e) {
                logger.error("安装加载器失败", e);
                Platform.runLater(() -> loaderLog("[错误] " + e.getMessage()));
                return null;
            } finally {
                installer.shutdown();
            }
        }).thenAccept(id -> Platform.runLater(() -> {
            loaderInstallButton.setDisable(false);
            loaderInstallButton.setText(L.t("s_d109e7cc01"));

            if (id != null) {
                loaderProgress.setProgress(1.0);
                loaderStatusLabel.setText(L.t("s_2f2b6bd0b2") + id);
                loaderLog(L.t("s_d93c7b3ad2") + id);
                loaderLog(L.t("s_18cfee46fc"));
                refreshInstalledLoaders();
                if (statusConsumer != null) {
                    statusConsumer.accept(L.t("s_09312debe7") + id);
                }
            } else {
                loaderProgress.setVisible(false);
                loaderProgress.setManaged(false);
                loaderStatusLabel.setText(L.t("s_dcefb8ebd4"));
            }
        }));
    }

    private void loaderLog(String msg) {
        Platform.runLater(() -> {
            if (loaderLogArea == null) return;
            String ts = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            loaderLogArea.appendText("[" + ts + "] " + msg + "\n");
        });
    }

    // ---------- 文件管理标签页 ----------

    private enum DirKind { WORLDS, SCHEMATICS, SHADERPACKS, BROWSE }

    private VBox buildDirTab(DirKind kind) {
        Path gameDir = config.getGameDir();
        Path base;
        String hint;
        boolean navigate;
        boolean dirsOnly;
        String[] exts;

        switch (kind) {
            case WORLDS -> {
                base = gameDir.resolve("saves");
                hint = L.t("s_aa553cfcdb");
                navigate = false;   // 存档点进去应该是打开文件夹，不是浏览
                dirsOnly = true;
                exts = null;
            }
            case SCHEMATICS -> {
                base = gameDir.resolve("schematics");
                hint = L.t("s_057cfcb1b8");
                navigate = true;
                dirsOnly = false;
                exts = new String[]{".schematic", ".litematic", ".nbt", ".schem"};
            }
            case SHADERPACKS -> {
                base = gameDir.resolve("shaderpacks");
                hint = L.t("s_25698e1013");
                navigate = false;
                dirsOnly = false;
                exts = new String[]{".zip"};
            }
            default -> {
                base = gameDir;
                hint = L.t("s_1078d55778");
                navigate = true;
                dirsOnly = false;
                exts = null;
            }
        }

        FileListPane pane = new FileListPane(base, hint, statusConsumer,
                navigate, dirsOnly, exts);

        // 传递内嵌弹窗宿主，否则删除确认会退化成「无确认直接删」
        pane.setModalHost(modalHost);

        switch (kind) {
            case WORLDS -> worldsPane = pane;
            case SCHEMATICS -> schematicsPane = pane;
            case SHADERPACKS -> shaderPacksPane = pane;
            default -> fileBrowserPane = pane;
        }

        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");

        Label desc = new Label(switch (kind) {
            case WORLDS -> L.t("s_65a165594e") + base + L.t("s_de6cac6485");
            case SCHEMATICS -> L.t("s_3b6a7537ac") + base;
            case SHADERPACKS -> L.t("s_62710ff27f") + base;
            default -> L.t("s_7561ec31a4");
        });
        desc.getStyleClass().add("text-muted");
        desc.setWrapText(true);

        VBox listBox = (VBox) pane.getContent();
        VBox.setVgrow(listBox, Priority.ALWAYS);

        box.getChildren().addAll(desc, listBox);
        return box;
    }

    // ==================== 工具 ====================

    private void openFolder(Path path) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(path)) {
                    Files.createDirectories(path);
                }
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(path.toFile());
                } else {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        Runtime.getRuntime().exec(new String[]{"explorer", path.toString()});
                    } else if (os.contains("mac")) {
                        Runtime.getRuntime().exec(new String[]{"open", path.toString()});
                    } else {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", path.toString()});
                    }
                }
            } catch (Exception e) {
                logger.warn("打开文件夹失败: {}", e.getMessage());
            }
        });
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(path)) {
                for (Path child : s) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }

    private void showError(String message) {
        com.mclauncher.ui.component.MessageDialog.error(modalHost, L.t("s_b859c7be75"), message);
    }

    /** 模组条目 */
    private static class ModEntry {
        Path path;
        boolean enabled;

        String displayName() {
            String n = path.getFileName().toString();
            return n.toLowerCase().endsWith(DISABLED_SUFFIX)
                    ? n.substring(0, n.length() - DISABLED_SUFFIX.length())
                    : n;
        }
    }
}
