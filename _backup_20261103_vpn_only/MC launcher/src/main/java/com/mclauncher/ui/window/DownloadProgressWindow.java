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

/**
 * 游戏下载进度窗口
 *
 * 按需求把「进度条 / 当前文件 / 速度 / 日志」从下载页移到这里，
 * 让下载页专注在版本选择上。
 *
 * 交互约定：
 * - **不阻塞主窗口**（非模态）：用户可以边下载边切页面做别的事
 * - **关闭窗口不会取消下载**：下载在后台继续，重新点「开始下载」可再次打开窗口
 * - 窗口内提供「取消下载」按钮，那才是真正中断下载的入口
 */
public class DownloadProgressWindow {

    private static final Logger logger = LoggerFactory.getLogger(DownloadProgressWindow.class);

    private final ModalHost modalHost;
    private final String titleText;

    /** 用户点击「取消下载」时回调 */
    private final Consumer<Void> onCancel;

    /** 是否已结束（结束后取消按钮禁用） */
    private volatile boolean finished = false;
    /** 是否已请求过取消，避免重复触发 */
    private volatile boolean cancelRequested = false;

    private Label stageLabel;
    private Label percentLabel;
    private ProgressBar progressBar;
    private Label fileLabel;
    private Label statsLabel;
    private TextArea logArea;
    private Button closeButton;
    private Button cancelButton;

    /** 本窗口当前的根节点；面板关闭后置空，用于幂等关闭 */
    private javafx.scene.layout.Region panelRoot;

    public DownloadProgressWindow(ModalHost modalHost, String titleText, Consumer<Void> onCancel) {
        this.modalHost = modalHost;
        this.titleText = titleText;
        this.onCancel = onCancel;
    }

    /**
     * 显示窗口
     */
    public void show() {
        // 已在显示中：不再重复入栈（否则每次点「显示进度」都多压一层）
        if (modalHost.isShowingPanel(panelRoot)) {
            return;
        }
        BorderPane root = buildUI();
        panelRoot = root;
        // 内嵌面板：点遮罩不关闭（避免误关），只通过自身的按钮收起
        modalHost.show(L.t("s_1d20307b2f") + titleText, root, null, false);
    }

    /**
     * 关闭本窗口（幂等，且只会关掉自己这一层）。
     *
     * 下载进度面板是 closable=false：没有 ✕ 也不能点遮罩关闭。
     * 若外部在取消下载后拿不到面板引用去主动收起，就会留下一个任何按钮
     * 都关不掉的遮罩，整个页面被永久锁死。
     */
    public void close() {
        javafx.scene.layout.Region root = panelRoot;
        panelRoot = null;
        if (root != null) {
            modalHost.closePanel(root);
        }
    }

    /**
     * 请求取消下载（幂等）
     */
    private void requestCancel() {
        if (cancelRequested || finished) {
            return;
        }

        // 取消前确认，避免误触中断大文件下载
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_7115f2e29d"),
                L.t("s_205afef767"),
                null,
                L.t("s_d731ca5dbe"),
                L.t("s_7115f2e29d"),
                () -> doCancel());

    }

    /** 真正执行取消 */
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

        // ---- 顶部：阶段 + 百分比 ----
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

        // ---- 中部：进度条 + 详细信息 + 日志 ----
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

        // ---- 底部按钮 ----
        HBox footer = new HBox(10);
        footer.setPadding(new Insets(10, 20, 18, 20));
        footer.setAlignment(Pos.CENTER_RIGHT);

        cancelButton = new Button(L.t("s_7115f2e29d"));
        cancelButton.getStyleClass().add("button-danger");
        cancelButton.setOnAction(e -> requestCancel());

        closeButton = new Button(L.t("s_6c14bd7f6f"));
        closeButton.getStyleClass().add("button");
        // 关闭不取消下载 —— 下载在后台继续。
        // 必须关「本面板」而不是栈顶：用户可能在本面板之上又开了确认框
        closeButton.setOnAction(e -> close());

        footer.getChildren().addAll(cancelButton, closeButton);

        root.setTop(header);
        root.setCenter(center);
        root.setBottom(footer);
        return root;
    }

    // ==================== 供外部调用的更新方法 ====================

    /** 更新阶段文字与百分比 */
    public void updateProgress(int current, int total, String message) {
        Platform.runLater(() -> {
            double p = total > 0 ? (double) current / total : 0;
            if (progressBar != null) progressBar.setProgress(p);
            if (stageLabel != null) stageLabel.setText(message);
            if (percentLabel != null) percentLabel.setText(current + "%");
        });
    }

    /** 更新当前文件、速度、剩余数量 */
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

    /** 追加日志 */
    public void appendLog(String message) {
        Platform.runLater(() -> {
            if (logArea == null) return;
            String ts = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.appendText("[" + ts + "] " + message + "\n");
        });
    }

    /** 标记下载结束（成功或失败） */
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
                // 主题感知：浅色背景上不能用亮绿
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
        // 已取消：面板上已没有可用的操作，直接收起，避免取消慢下载时一直占着遮罩
        if (cancelRequested) {
            Platform.runLater(this::close);
        }
    }

    /**
     * 若下载是在本窗口打开期间完成的，是否应保持窗口
     */
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