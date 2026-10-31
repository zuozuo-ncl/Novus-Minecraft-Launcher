package com.mclauncher.ui.window;

import com.mclauncher.i18n.L;
import com.mclauncher.network.Downloader;
import com.mclauncher.network.UpdateClient;
import com.mclauncher.ui.component.ModalHost;
import com.mclauncher.utils.FileUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Consumer;

public class UpdateDialog {

    private static final Logger logger = LoggerFactory.getLogger(UpdateDialog.class);

    private final ModalHost modalHost;
    private final UpdateClient.UpdateInfo info;
    private final Consumer<String> statusConsumer;

    private Runnable beforeRestart;

    private java.util.function.BooleanSupplier gameRunningCheck;

    private Button downloadButton;
    private Button laterButton;
    private Button restartButton;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Label resultLabel;

    private Path downloadedJar;

    public UpdateDialog(ModalHost modalHost,
                        UpdateClient.UpdateInfo info,
                        Consumer<String> statusConsumer) {
        this.modalHost = modalHost;
        this.info = info;
        this.statusConsumer = statusConsumer;
    }

    public UpdateDialog setBeforeRestart(Runnable r) {
        this.beforeRestart = r;
        return this;
    }

    public UpdateDialog setGameRunningCheck(java.util.function.BooleanSupplier check) {
        this.gameRunningCheck = check;
        return this;
    }

    public void show() {
        BorderPane content = buildUI();
        modalHost.show(L.t("s_01047404ef"), content, null, true);
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("bg-main");
        root.setPrefSize(560, 340);

        VBox header = new VBox(10);
        header.setPadding(new Insets(18, 20, 10, 20));

        Label title = new Label(L.t("s_d5be7ecb43"));
        title.getStyleClass().add("page-title");

        HBox verRow = new HBox(12);
        verRow.setAlignment(Pos.CENTER_LEFT);

        Label curLabel = new Label(L.t("s_46e66f6321"));
        curLabel.getStyleClass().add("text-secondary");
        Label curVer = new Label(info.currentVersion == null ? L.t("s_d9c32a4c3d") : info.currentVersion);
        curVer.getStyleClass().add("version-game");

        Label arrow = new Label("-->");
        arrow.getStyleClass().add("version-arrow");

        Label newLabel = new Label(L.t("s_be6ba1bd82"));
        newLabel.getStyleClass().add("text-secondary");
        Label newVer = new Label(info.latestVersion == null ? L.t("s_d9c32a4c3d") : info.latestVersion);
        newVer.getStyleClass().add("version-game");
        newVer.setStyle("-fx-text-fill: #3B82F6; -fx-font-size: 15px; "
                + "-fx-font-weight: bold;");

        verRow.getChildren().addAll(curLabel, curVer, arrow, newLabel, newVer);

        Label note = new Label(
                L.t("s_3833997333"));
        note.getStyleClass().add("text-secondary");
        note.setWrapText(true);

        header.getChildren().addAll(title, verRow, note);

        VBox center = new VBox(10);
        center.setPadding(new Insets(0, 20, 10, 20));

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        progressLabel = new Label("");
        progressLabel.getStyleClass().add("text-muted");
        progressLabel.setWrapText(true);

        resultLabel = new Label("");
        resultLabel.setWrapText(true);

        center.getChildren().addAll(progressBar, progressLabel, resultLabel);

        HBox footer = new HBox(10);
        footer.setPadding(new Insets(10, 20, 18, 20));
        footer.setAlignment(Pos.CENTER_RIGHT);

        laterButton = new Button(L.t("s_52b7c8fada"));
        laterButton.getStyleClass().add("button");
        laterButton.setOnAction(e -> modalHost.close());

        restartButton = new Button(L.t("s_c4b0582a9b"));
        restartButton.getStyleClass().add("button-primary");
        restartButton.setVisible(false);
        restartButton.setManaged(false);
        restartButton.setOnAction(e -> onRestartClicked());

        downloadButton = new Button(L.t("s_c0417a25dd"));
        downloadButton.getStyleClass().add("button-primary");
        downloadButton.setOnAction(e -> startDownload());

        footer.getChildren().addAll(laterButton, restartButton, downloadButton);

        root.setTop(header);
        root.setCenter(center);
        root.setBottom(footer);
        return root;
    }

    private void startDownload() {
        downloadButton.setDisable(true);
        downloadButton.setText(L.t("s_a0ef0da255"));
        laterButton.setDisable(true);

        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(0);

        resultLabel.getStyleClass().clear();
        resultLabel.setText("");

        final String targetVersion = info.latestVersion;

        new Thread(() -> {
            try {
                Path appData = UpdateClient.getAppDataDir();
                UpdateClient client = new UpdateClient();

                String expected = info.latestSha256;
                if (expected == null && info.sha256ByVersion != null) {
                    expected = info.sha256ByVersion.get(targetVersion);
                }

                com.mclauncher.update.VersionManifest manifest =
                        com.mclauncher.update.VersionManifest.load();
                Path jar = client.installVersion(appData, targetVersion, expected, manifest,
                        null,
                        new Downloader.DownloadListener() {
                            @Override
                            public void onStart() {
                                Platform.runLater(() ->
                                        progressLabel.setText(L.t("s_d1c4de9a27")));
                            }

                            @Override
                            public void onProgress(long downloaded, long total, double percent) {
                                Platform.runLater(() -> {
                                    progressBar.setProgress(percent / 100.0);
                                    progressLabel.setText(String.format(
                                            "%s / %s   (%.1f%%)",
                                            FileUtils.formatFileSizeShort(downloaded),
                                            FileUtils.formatFileSizeShort(total),
                                            percent));
                                });
                            }

                            @Override
                            public void onSpeed(long bytesPerSecond) {

                            }

                            @Override
                            public void onComplete(Path destination) {
                            }

                            @Override
                            public void onError(Exception e) {
                            }
                        });

                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    progressLabel.setText("");
                    resultLabel.setText(L.t("s_09055c783a") + jar + L.t("s_96db721f7a"));
                    resultLabel.getStyleClass().add("text-accent");

                    downloadedJar = jar;

                    

                    registerInManifest(targetVersion, jar,
                            com.mclauncher.config.ConfigManager.staticLauncherVersion());

                    downloadButton.setVisible(false);
                    downloadButton.setManaged(false);

                    laterButton.setText(L.t("s_52b7c8fada"));
                    laterButton.setDisable(false);

                    restartButton.setVisible(true);
                    restartButton.setManaged(true);

                    
                    modalHost.setClosable(false);

                    if (statusConsumer != null) {
                        statusConsumer.accept(L.t("s_fd18bd39bd") + targetVersion + L.t("s_bc920e604e"));
                    }
                });

                logger.info("新版本下载完成并已登记: {}", jar);

            } catch (Exception ex) {
                logger.error("下载新版本失败", ex);
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    progressBar.setManaged(false);
                    resultLabel.setText(L.t("s_b0578cac28") + ex.getMessage()
                            + L.t("s_a79b7461b6"));
                    resultLabel.getStyleClass().add("status-fail");

                    downloadButton.setDisable(false);
                    downloadButton.setText(L.t("s_e2d53a6d3a"));
                    laterButton.setDisable(false);
                });
            }
        }, "update-downloader").start();
    }

    private void onRestartClicked() {

        if (gameRunningCheck != null && gameRunningCheck.getAsBoolean()) {
            showRestartError(L.t("s_cace03dc38"));
            return;
        }

        Path target = downloadedJar != null
                ? downloadedJar
                : com.mclauncher.utils.RestartUtils.findLatestDownloadedJar();

        if (target == null) {
            showRestartError(L.t("s_d79b54ef83"));
            return;
        }

        restartButton.setDisable(true);
        restartButton.setText(L.t("s_5aaa7b3d8e"));
        laterButton.setDisable(true);

        com.mclauncher.utils.RestartUtils.Result r =
                com.mclauncher.utils.RestartUtils.restartWith(target);

        if (r.ok) {

            if (beforeRestart != null) {
                try {
                    beforeRestart.run();
                } catch (Exception e) {
                    logger.warn("重启前收尾失败（继续重启）: {}", e.getMessage());
                }
            }
            com.mclauncher.utils.RestartUtils.exitCurrent();
        } else {
            showRestartError(r.message + L.t("s_f7406dca97"));
        }
    }

    private void showRestartError(String text) {
        resultLabel.setText(L.t("s_8703d7ba63") + text);
        resultLabel.getStyleClass().clear();
        resultLabel.getStyleClass().add("status-fail");

        restartButton.setDisable(false);
        restartButton.setText(L.t("s_c4b0582a9b"));
        laterButton.setDisable(false);
    }

    private void registerInManifest(String version, Path jar, String keepCurrent) {
        try {
            com.mclauncher.update.VersionManifest m =
                    com.mclauncher.update.VersionManifest.load();
            m.syncWithDisk();

            com.mclauncher.update.VersionManifest.Entry e =
                    m.findOrCreate(version, jar.getFileName().toString());
            e.usable = false;       
            e.failures = 0;

            

            

            

            if (keepCurrent != null && !keepCurrent.isBlank()) {
                m.current = keepCurrent;
            }

            m.save();
            logger.info("新版本 {} 已登记到版本清单（待验证）", version);

        } catch (Exception ex) {
            logger.warn("登记新版本到清单失败（不影响使用）: {}", ex.getMessage());
        }
    }
}
