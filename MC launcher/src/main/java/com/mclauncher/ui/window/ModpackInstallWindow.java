package com.mclauncher.ui.window;

import com.mclauncher.i18n.L;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.game.ModpackInstaller;
import com.mclauncher.mod.ModrinthClient;
import com.mclauncher.network.BMCLApiClient;
import com.mclauncher.ui.component.ModalHost;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ModpackInstallWindow {

    private static final Logger logger = LoggerFactory.getLogger(ModpackInstallWindow.class);

    private final ModalHost modalHost;
    private final ModrinthClient client;
    private final ModrinthClient.ModProject project;
    private final ConfigManager config;
    private final Consumer<String> statusConsumer;

    private ComboBox<ModrinthClient.ModVersion> versionCombo;
    private Label metaLabel;
    private Label targetLabel;
    private Path targetDir;
    private Button installButton;
    private ProgressBar progressBar;
    private Label progressText;
    private TextArea logArea;

    private volatile ModpackInstaller installer;

    private volatile boolean closed = false;

    private volatile int runId = 0;

    public ModpackInstallWindow(ModalHost modalHost,
                                   ModrinthClient client,
                                   ModrinthClient.ModProject project,
                                   ConfigManager config,
                                   Consumer<String> statusConsumer) {
        this.modalHost = modalHost;
        this.client = client;
        this.project = project;
        this.config = config;
        this.statusConsumer = statusConsumer;
        this.targetDir = defaultTargetDir();
    }

    private Path defaultTargetDir() {
        String name = project.getSlug() != null && !project.getSlug().isBlank()
                ? project.getSlug() : project.getTitle();
        if (name == null || name.isBlank()) {
            name = "modpack";
        }

        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return config.getGameDir().toAbsolutePath().resolve("packs").resolve(name);
    }

    public void show() {
        closed = false;
        runId = 0;
        BorderPane root = buildUI();
        modalHost.show(L.t("s_5bc09cb578") + project.getTitle(), root, () -> {
            closed = true;
            runId++;            
            ModpackInstaller inst = installer;
            if (inst != null) {
                inst.cancel();
            }
        });
        loadVersions();
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("version-dialog-root");
        root.setPrefSize(720, 480);

        VBox header = new VBox(6);
        header.setPadding(new Insets(16, 20, 8, 20));

        Label title = new Label(project.getTitle());
        title.getStyleClass().add("card-title");

        Label desc = new Label(project.getDescription() == null ? ""
                : (project.getDescription().length() > 160
                    ? project.getDescription().substring(0, 160) + "..."
                    : project.getDescription()));
        desc.getStyleClass().add("page-subtitle");
        desc.setWrapText(true);

        header.getChildren().addAll(title, desc);

        VBox center = new VBox(14);
        center.setPadding(new Insets(4, 20, 8, 20));

        HBox versionRow = new HBox(10);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        Label vl = new Label(L.t("s_989d1affa0"));
        vl.getStyleClass().add("text-secondary");
        versionCombo = new ComboBox<>();
        versionCombo.setPrefWidth(320);
        versionCombo.setPromptText(L.t("s_514c33af5c"));

        
        versionCombo.setCellFactory(lv -> versionCell());
        versionCombo.setButtonCell(versionCell());
        versionCombo.setOnAction(e -> onVersionSelected());
        versionRow.getChildren().addAll(vl, versionCombo);

        metaLabel = new Label("");
        metaLabel.getStyleClass().add("text-secondary");
        metaLabel.setWrapText(true);

        HBox targetRow = new HBox(10);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        Label tl = new Label(L.t("s_7d95c47ebf"));
        tl.getStyleClass().add("text-secondary");
        targetLabel = new Label(targetDir.toString());
        targetLabel.getStyleClass().add("mod-meta");
        targetLabel.setWrapText(true);
        HBox.setHgrow(targetLabel, Priority.ALWAYS);
        targetLabel.setMaxWidth(Double.MAX_VALUE);
        targetLabel.setTooltip(new Tooltip(targetDir.toString()));

        Button changeDir = new Button(L.t("s_b2ffef16e6"));
        changeDir.getStyleClass().add("button");
        changeDir.setOnAction(e -> chooseTargetDir());
        targetRow.getChildren().addAll(tl, targetLabel, changeDir);

        Label note = new Label(L.t("s_d92855550f"));
        note.getStyleClass().add("text-muted");
        note.setWrapText(true);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        progressText = new Label("");
        progressText.getStyleClass().add("text-secondary");
        progressText.setWrapText(true);
        progressText.setVisible(false);
        progressText.setManaged(false);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(6);
        logArea.setVisible(false);
        logArea.setManaged(false);
        logArea.getStyleClass().add("log-area");

        center.getChildren().addAll(versionRow, metaLabel, new Separator(),
                targetRow, note, progressBar, progressText, logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        HBox footer = new HBox(10);
        footer.setPadding(new Insets(8, 20, 16, 20));
        footer.setAlignment(Pos.CENTER_RIGHT);

        installButton = new Button(L.t("s_5d183d0c0c"));
        installButton.getStyleClass().add("button-primary");
        installButton.setDisable(true);
        installButton.setOnAction(e -> startInstall());

        footer.getChildren().add(installButton);

        root.setTop(header);
        root.setCenter(center);
        root.setBottom(footer);
        return root;
    }

    private void loadVersions() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return client.getVersions(project.getProjectId(), null, null);
            } catch (Exception e) {
                logger.error("获取整合包版本失败", e);
                return null;
            }
        }).thenAccept(list -> Platform.runLater(() -> {
            if (list == null || list.isEmpty()) {
                versionCombo.setPromptText(L.t("s_c2924103b0"));
                metaLabel.setText(L.t("s_95831308d1"));
                return;
            }

            
            java.util.List<ModrinthClient.ModVersion> usable = new java.util.ArrayList<>();
            for (ModrinthClient.ModVersion v : list) {
                if (v.getPrimaryFile() != null) {
                    usable.add(v);
                }
            }
            usable.sort((a, b) ->
                    (b.getDatePublished() == null ? "" : b.getDatePublished())
                            .compareTo(a.getDatePublished() == null ? "" : a.getDatePublished()));
            versionCombo.getItems().setAll(usable);
            if (usable.isEmpty()) {
                versionCombo.setPromptText(L.t("s_2af4305b2c"));
                metaLabel.setText(L.t("s_650f581043"));
                return;
            }
            versionCombo.getSelectionModel().select(0);
            onVersionSelected();
        }));
    }

    private ListCell<ModrinthClient.ModVersion> versionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ModrinthClient.ModVersion v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText(null);
                    return;
                }
                String gv = v.getGameVersions().isEmpty() ? "-" : v.getGameVersions().get(0);
                String ld = v.getLoaders().isEmpty() ? "" : " · " + v.getLoaders().get(0);
                setText("MC " + gv + ld + " · " + v.getVersionNumber());
            }
        };
    }

    private void onVersionSelected() {
        ModrinthClient.ModVersion v = versionCombo.getSelectionModel().getSelectedItem();
        if (v == null) {
            return;
        }

        String gameVersions = String.join(", ", v.getGameVersions());
        String loaders = String.join(", ", v.getLoaders());
        long size = 0;
        ModrinthClient.ModFile f = v.getPrimaryFile();
        if (f != null) {
            size = f.getSize();
        }

        StringBuilder meta = new StringBuilder();
        meta.append("Minecraft ").append(gameVersions.isEmpty() ? L.t("s_d9c32a4c3d") : gameVersions);
        if (!loaders.isEmpty()) {
            meta.append("   ·   ").append(loaders);
        }
        if (size > 0) {
            meta.append(L.t("s_c951b42585")).append(formatSize(size));
        }
        meta.append(L.t("s_ebf99874b7"));
        metaLabel.setText(meta.toString());

        installButton.setDisable(false);
    }

    private void chooseTargetDir() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(L.t("s_99f555e552"));
        if (Files.isDirectory(targetDir)) {
            chooser.setInitialDirectory(targetDir.toFile());
        } else if (targetDir.getParent() != null && Files.isDirectory(targetDir.getParent())) {
            chooser.setInitialDirectory(targetDir.getParent().toFile());
        }
        java.io.File sel = chooser.showDialog(null);
        if (sel != null) {
            targetDir = sel.toPath();
            targetLabel.setText(targetDir.toString());
            targetLabel.setTooltip(new Tooltip(targetDir.toString()));
        }
    }

    private void startInstall() {

        
        ModpackInstaller oldInst = installer;
        if (oldInst != null) {
            oldInst.cancel();
            installer = null;
        }
        final int myRun = ++runId;
        ModrinthClient.ModVersion v = versionCombo.getSelectionModel().getSelectedItem();
        if (v == null) {
            return;
        }
        ModrinthClient.ModFile file = v.getPrimaryFile();
        if (file == null || file.getUrl() == null || file.getUrl().isBlank()) {
            progressText.setText(L.t("s_e4f69e056c"));
            return;
        }

        installButton.setDisable(true);
        versionCombo.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressText.setVisible(true);
        progressText.setManaged(true);
        logArea.setVisible(true);
        logArea.setManaged(true);
        logArea.clear();

        progressBar.setProgress(0);
        progressText.setText(L.t("s_abb2d4c496"));

        CompletableFuture.runAsync(() -> {
            BMCLApiClient api = new BMCLApiClient();
            ModpackInstaller inst = new ModpackInstaller(config, api, client);
            installer = inst;
            final int run = myRun;
            try {

                Path packFile = downloadPackFile(file);

                ModpackInstaller.Result r = inst.install(packFile, targetDir,
                        new ModpackInstaller.Progress() {
                            @Override
                            public void onStage(String text) {
                                Platform.runLater(() -> {
                                    if (closed || run != runId) return;
                                    progressText.setText(text);
                                });
                            }

                            @Override
                            public void onPercent(double percent) {
                                Platform.runLater(() -> {
                                    if (closed || run != runId) return;
                                    progressBar.setProgress(percent);
                                });
                            }

                            @Override
                            public void onLog(String line) {
                                Platform.runLater(() -> {
                                    if (closed || run != runId) return;
                                    logArea.appendText(line + "\n");
                                    logArea.setScrollTop(Double.MAX_VALUE);
                                });
                            }
                        });

                if (r.success) {
                    Platform.runLater(() -> {
                        if (closed || run != runId) return;
                        finishSuccess(r);
                    });
                } else {
                    Platform.runLater(() -> {
                        if (closed || run != runId) return;
                        finishFailure(r.error);
                    });
                }
            } catch (Exception e) {
                logger.error("整合包安装异常", e);
                Platform.runLater(() -> {
                    if (closed || run != runId) return;
                    finishFailure(e.getMessage());
                });
            } finally {
                inst.cancel();
            }
        });
    }

    private Path downloadPackFile(ModrinthClient.ModFile file) throws Exception {
        Path tmpDir = config.getDownloadDir().toAbsolutePath().resolve("modpacks");
        Files.createDirectories(tmpDir);
        Path dest = tmpDir.resolve(file.getFilename() == null || file.getFilename().isBlank()
                ? project.getSlug() + ".mrpack" : file.getFilename());

        Platform.runLater(() -> progressText.setText(L.t("s_749d25fdd8")));

        String mirrored = client.toMirrorFileUrl(file.getUrl());
        com.mclauncher.network.Downloader dl = new com.mclauncher.network.Downloader();
        try {
            com.mclauncher.network.Downloader.DownloadResult r = dl.download(mirrored, dest, null);
            if (!r.isSuccess() && !mirrored.equals(file.getUrl())) {
                Files.deleteIfExists(dest);
                r = dl.download(file.getUrl(), dest, null);
            }
            if (!r.isSuccess()) {
                throw new java.io.IOException("下载整合包失败: " + r.getErrorMessage());
            }
        } finally {
            dl.shutdown();
        }
        return dest;
    }

    private void finishSuccess(ModpackInstaller.Result r) {
        progressBar.setProgress(1);
        progressText.setText(L.t("s_f423573349"));

        config.addGameDirectory(targetDir.toAbsolutePath().toString(),
                project.getTitle() + " (" + (r.versionId == null ? L.t("s_b3a621217c") : r.versionId) + ")");

        if (r.filesFailed > 0) {
            logArea.appendText(L.t("s_13f2cf999e") + r.filesFailed + L.t("s_6faaca3436"));
        }

        installButton.setText(L.t("s_6c14bd7f6f"));
        installButton.setDisable(false);
        installButton.setOnAction(e -> modalHost.close());

        if (statusConsumer != null) {
            statusConsumer.accept(L.t("s_04d26eaf77") + targetDir);
        }
        logger.info("整合包安装完成: {} -> {}", project.getTitle(), targetDir);
    }

    private void finishFailure(String msg) {
        progressText.setText("安装失败: " + msg);
        logArea.appendText(L.t("s_d07aee015b") + msg + "\n");

        installButton.setText(L.t("s_e2d53a6d3a"));
        installButton.setDisable(false);
        installButton.setOnAction(e -> {
            installButton.setText(L.t("s_5d183d0c0c"));
            startInstall();
        });
        versionCombo.setDisable(false);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
