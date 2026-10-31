package com.mclauncher.ui.window;

import com.mclauncher.i18n.L;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import com.mclauncher.ui.component.ModalHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class DownloadProgressWindow {

    private static final Logger logger = LoggerFactory.getLogger(DownloadProgressWindow.class);

    private final ModalHost modalHost;
    private final String titleText;

    private final Consumer<Void> onCancel;

    private volatile boolean finished = false;

    private volatile boolean cancelRequested = false;

    private Label stageLabel;
    private Label percentLabel;
    private ProgressBar progressBar;
    private Label fileLabel;
    private Label statsLabel;
    private TextArea logArea;
    private Button closeButton;
    private Button cancelButton;

    private javafx.scene.layout.Region panelRoot;

    public DownloadProgressWindow(ModalHost modalHost, String titleText, Consumer<Void> onCancel) {
        this.modalHost = modalHost;
        this.titleText = titleText;
        this.onCancel = onCancel;
    }

    public void show() {

        if (modalHost.isShowingPanel(panelRoot)) {
            return;
        }
        BorderPane root = buildUI();
        panelRoot = root;

        modalHost.show(L.t("s_1d20307b2f") + titleText, root, null, false);
    }

    public void close() {
        javafx.scene.layout.Region root = panelRoot;
        panelRoot = null;
        if (root != null) {
            modalHost.closePanel(root);
        }
    }

    private void requestCancel() {
        if (cancelRequested || finished) {
            return;
        }

        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_7115f2e29d"),
                L.t("s_205afef767"),
                null,
                L.t("s_d731ca5dbe"),
                L.t("s_7115f2e29d"),
                () -> doCancel());

    }

    private void doCancel() {
        cancelRequested = true;
        appendLog(L.t("s_42c389594e"));
        if (stageLabel != null) {
            stageLabel.setText(L.t("s_a5ffdc95ee"));
        }
        if (onCancel != null) {
            try {
                onCancel.accept(null);
            } catch (Exception ex) {
                logger.debug("取消回调异常: {}", ex.getMessage());
            }
        }

        if (cancelButton != null) {
            cancelButton.setDisable(true);
        }
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("bg-main");

        VBox header = new VBox(10);
        header.setPadding(new Insets(18, 20, 10, 20));

        Label title = new Label(L.t("s_43d4950cc4") + titleText);
        title.getStyleClass().add("page-title");

        HBox stageRow = new HBox(10);
        stageRow.setAlignment(Pos.CENTER_LEFT);

        stageLabel = new Label(L.t("s_4f1f8aa3ff"));
        stageLabel.getStyleClass().add("card-title");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        percentLabel = new Label("0%");
        percentLabel.getStyleClass().add("text-accent");

        stageRow.getChildren().addAll(stageLabel, sp, percentLabel);

        header.getChildren().addAll(title, stageRow);

        VBox center = new VBox(12);
        center.setPadding(new Insets(0, 20, 10, 20));

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(18);

        fileLabel = new Label("—");
        fileLabel.getStyleClass().add("text-secondary");
        fileLabel.setWrapText(true);
        fileLabel.setMaxWidth(Double.MAX_VALUE);

        statsLabel = new Label("—");
        statsLabel.getStyleClass().add("text-muted");
        statsLabel.setMaxWidth(Double.MAX_VALUE);

        logArea = new TextArea();
        logArea.getStyleClass().add("log-area");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        center.getChildren().addAll(progressBar, fileLabel, statsLabel, logArea);

        HBox footer = new HBox(10);
        footer.setPadding(new Insets(10, 20, 18, 20));
        footer.setAlignment(Pos.CENTER_RIGHT);

        cancelButton = new Button(L.t("s_7115f2e29d"));
        cancelButton.getStyleClass().add("button-danger");
        cancelButton.setOnAction(e -> requestCancel());

        closeButton = new Button(L.t("s_6c14bd7f6f"));
        closeButton.getStyleClass().add("button");

        closeButton.setOnAction(e -> modalHost.close());

        footer.getChildren().addAll(cancelButton, closeButton);

        root.setTop(header);
        root.setCenter(center);
        root.setBottom(footer);
        return root;
    }

    public void updateProgress(int current, int total, String message) {
        Platform.runLater(() -> {
            double p = total > 0 ? (double) current / total : 0;
            if (progressBar != null) progressBar.setProgress(p);
            if (stageLabel != null) stageLabel.setText(message);
            if (percentLabel != null) percentLabel.setText(current + "%");
        });
    }

    public void updateDetail(String currentFile, long speed, int remaining) {
        Platform.runLater(() -> {
            if (currentFile != null && !currentFile.isBlank() && fileLabel != null) {
                fileLabel.setText(L.t("s_57549a4bfe") + shorten(currentFile));
            }
            if (statsLabel != null) {
                statsLabel.setText(buildStats(speed, remaining));
            }
        });
    }

    public void appendLog(String message) {
        Platform.runLater(() -> {
            if (logArea == null) return;
            String ts = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.appendText("[" + ts + "] " + message + "\n");
        });
    }

    public void markFinished(boolean success, String summary) {
        finished = true;
        Platform.runLater(() -> {
            if (progressBar != null && success) {
                progressBar.setProgress(1.0);
            }
            if (percentLabel != null && success) {
                percentLabel.setText("100%");
            }
            if (stageLabel != null) {
                stageLabel.setText(success ? L.t("s_79236c3acf") : L.t("s_e0dab22b1a"));

                stageLabel.getStyleClass().removeAll("status-ok", "status-fail", "text-primary");
                stageLabel.getStyleClass().add(success ? "status-ok" : "status-fail");
            }
            if (fileLabel != null && summary != null) {
                fileLabel.setText(summary);
            }
            if (statsLabel != null) {
                statsLabel.setText("");
            }
            if (cancelButton != null) {
                cancelButton.setDisable(true);
                cancelButton.setVisible(false);
                cancelButton.setManaged(false);
            }
            if (closeButton != null) {
                closeButton.setText(L.t("s_6c14bd7f6f"));
            }
        });

        if (cancelRequested) {
            Platform.runLater(this::close);
        }
    }

    public boolean isCancelled() {
        return cancelRequested;
    }

    private String buildStats(long speed, int remaining) {
        StringBuilder sb = new StringBuilder();
        if (speed > 0) {
            sb.append(L.t("s_5ac2644278"))
              .append(com.mclauncher.utils.FileUtils.formatFileSizeShort(speed))
              .append("/s");
        } else {
            sb.append(L.t("s_ae72ba1c73"));
        }
        if (remaining >= 0) {
            sb.append(L.t("s_54f40c126b")).append(remaining).append(L.t("s_753bd807cd"));
        }
        return sb.toString();
    }

    private String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 64 ? s : "..." + s.substring(s.length() - 61);
    }
}
