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

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 更新提示面板（内嵌弹窗）
 *
 * 用法：
 *   - 主页启动时自动检测，有新版就弹出此面板询问
 *   - 设置页「检查更新」也复用它
 *
 * 下载的新版 jar 会放到「启动器数据目录」（%APPDATA%\NCL_Launcher），
 * **旧版本保留不删**。
 *
 * 下载完成后会询问是否**立即重启**到新版本 —— 因为当前进程没法把自己
 * 换成新 jar（见 {@link com.mclauncher.utils.RestartUtils}），
 * 不重启的话要等用户下次手动打开才会生效。
 */
public class UpdateDialog {

    private static final Logger logger = LoggerFactory.getLogger(UpdateDialog.class);

    private final ModalHost modalHost;
    private final UpdateClient.UpdateInfo info;
    private final Consumer<String> statusConsumer;

    /** 可选的「退出前收尾」回调（如保存配置），重启前调用 */
    private Runnable beforeRestart;

    /**
     * 可选的「游戏是否在运行」查询
     *
     * 游戏在跑时不能重启：新进程起来后当前进程退出，
     * 会把游戏进程一起带走（游戏是本进程的子进程）。
     */
    private java.util.function.BooleanSupplier gameRunningCheck;

    private Button downloadButton;
    private Button laterButton;
    private Button restartButton;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Label resultLabel;

    /** 下载完成后的新 jar 路径 */
    private Path downloadedJar;

    public UpdateDialog(ModalHost modalHost,
                        UpdateClient.UpdateInfo info,
                        Consumer<String> statusConsumer) {
        this.modalHost = modalHost;
        this.info = info;
        this.statusConsumer = statusConsumer;
    }

    /**
     * 设置重启前的收尾动作（可选）
     *
     * 重启会直接结束当前进程，此回调用来把配置落盘、
     * 避免用户刚改的设置丢掉。
     */
    public UpdateDialog setBeforeRestart(Runnable r) {
        this.beforeRestart = r;
        return this;
    }

    /**
     * 设置「游戏是否在运行」的查询（可选）
     *
     * 传入后，游戏运行期间点「立即重启」会被拦下并提示先关游戏。
     */
    public UpdateDialog setGameRunningCheck(java.util.function.BooleanSupplier check) {
        this.gameRunningCheck = check;
        return this;
    }

    /**
     * 显示更新面板
     */
    public void show() {
        BorderPane content = buildUI();
        modalHost.show(L.t("s_01047404ef"), content, null, true);
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("bg-main");
        root.setPrefSize(560, 340);

        // ---- 顶部：版本信息 ----
        VBox header = new VBox(10);
        header.setPadding(new Insets(18, 20, 10, 20));

        Label title = new Label(L.t("s_d5be7ecb43"));
        title.getStyleClass().add("page-title");

        // 版本对比
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

        // ---- 中部：进度 ----
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

        // ---- 底部按钮 ----
        HBox footer = new HBox(10);
        footer.setPadding(new Insets(10, 20, 18, 20));
        footer.setAlignment(Pos.CENTER_RIGHT);

        laterButton = new Button(L.t("s_52b7c8fada"));
        laterButton.getStyleClass().add("button");
        laterButton.setOnAction(e -> modalHost.close());

        // 「立即重启」：下载完成后才显示（见 onDownloadFinished）
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

    /**
     * 开始下载新版
     */
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

                // 服务器给出的官方哈希（只有 HTTPS 接口的才会被 UpdateClient 接受）
                String expected = info.latestSha256;
                if (expected == null && info.sha256ByVersion != null) {
                    expected = info.sha256ByVersion.get(targetVersion);
                }
                if (expected == null) {
                    // 接口没下发哈希时，去文件服务器取同目录的 .sha256（弱兜底）；
                    // 仍然取不到就拒绝安装，不再「只验 ZIP 魔数」放行
                    Platform.runLater(() -> progressLabel.setText("正在获取官方校验值…"));
                    expected = client.fetchOfficialSha256(targetVersion);
                }
                if (expected == null) {
                    throw new IOException("拿不到该版本官方 SHA-256，为安全起见已拒绝安装。"
                            + "请稍后重试或联系作者。");
                }
                // 事务式安装：下载+校验，再把「清单登记 + 信任表登记」一次性写好
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
                                // 速度可选展示，这里不占用界面空间
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

                    // 记下新 jar，供「立即重启」使用
                    downloadedJar = jar;

                    // 登记到版本清单：新版本尚未验证可用（usable=false），
                    // 要等它真正跑起来、界面显示成功后才由 LauncherApp 标为可用。
                    // 这样「下载完但一启动就崩」的坏版本能被自动回退掉。
                    // installVersion 已经用 recordDownloaded 登记好（usable=false，
                    // 并把本版本哈希放进 pendingTrusted）；这里只需 save() 落盘，
                    // 它会把清单、selected.json、trusted-hashes.json 一并写出
                    // installVersion 已把「清单 + 信任表」写进 manifest 实例；
                    // save() 才会真正落盘（并把 pendingTrusted 刷进 trusted-hashes.json）
                    try {
                        manifest.save();
                    } catch (Exception e2) {
                        logger.warn("保存版本清单失败: {}", e2.getMessage());
                    }

                    // 按钮切换：下载按钮退场，换成「立即重启」
                    downloadButton.setVisible(false);
                    downloadButton.setManaged(false);

                    laterButton.setText(L.t("s_52b7c8fada"));
                    laterButton.setDisable(false);

                    restartButton.setVisible(true);
                    restartButton.setManaged(true);

                    // 更新已落地，此后面板不该再被误关 ——
                    // 否则用户点一下遮罩就看不到「重启」这个选项了
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

    /**
     * 点击「立即重启」
     *
     * 流程：
     *   1. 定位新 jar（优先用刚下载的那个；拿不到就从数据目录挑最新的）
     *   2. 拉起新进程
     *   3. 成功 -> 收尾后退出当前进程；失败 -> 就地提示，**不退出**
     *
     * 第 3 步的失败分支很关键：如果新进程起不来又退了当前进程，
     * 用户手上就什么都没有了。宁可让他手动重启。
     */
    private void onRestartClicked() {
        // 游戏在跑时不能直接退出 —— 会把游戏进程一起带走
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
            // 先落盘再退出，避免用户刚改的设置丢失
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

    /**
     * 重启失败的提示（就地显示，不关面板）
     */
    private void showRestartError(String text) {
        resultLabel.setText(L.t("s_8703d7ba63") + text);
        resultLabel.getStyleClass().clear();
        resultLabel.getStyleClass().add("status-fail");

        restartButton.setDisable(false);
        restartButton.setText(L.t("s_c4b0582a9b"));
        laterButton.setDisable(false);
    }

}