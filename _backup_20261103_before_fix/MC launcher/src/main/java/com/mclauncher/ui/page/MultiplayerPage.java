package com.mclauncher.ui.page;

import com.mclauncher.config.ConfigManager;
import com.mclauncher.i18n.L;
import com.mclauncher.multiplayer.LocalPortScanner;
import com.mclauncher.multiplayer.RelayTunnel;
import com.mclauncher.ui.component.MessageDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 联机页
 *
 * 让没有公网 IP 的玩家通过中转服务器开联机房。
 *
 * 只负责 UI 和状态显示；实际的隧道逻辑在 {@link RelayTunnel} 里。
 */
public class MultiplayerPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(MultiplayerPage.class);

    /** 中转服务器（写死，后续可从配置读取） */
    private static final String RELAY_HOST = "39.106.60.15";
    private static final int RELAY_HTTP_PORT = 8080;

    private final ConfigManager config;
    private final java.util.function.Consumer<String> statusConsumer;

    private RelayTunnel tunnel;
    private com.mclauncher.ui.component.ModalHost modalHost;

    @Override
    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }
    /** 用户选中的中转服务器 */
    private ComboBox<com.mclauncher.multiplayer.RelayServers.Server> serverCombo;

    // UI
    private Label stateLabel;
    private Label addressLabel;
    private Label playersLabel;
    private Button startButton;
    private Button stopButton;
    private Button copyButton;
    private ProgressIndicator spinner;
    private TextField portField;
    private Button detectPortButton;
    private Button testButton;
    private TextArea logArea;

    /** 页面根节点 */
    private VBox root;

    /** 当前 VPN 会话（虚拟局域网）。null 表示未开启 */
    private com.mclauncher.multiplayer.vpn.VpnSession vpn;
    private TextField vpnRoomField;
    private Label vpnStatusLabel;
    private Button vpnHostButton;
    private Button vpnJoinButton;
    private Button vpnStopButton;
    /** 防止「开房间」与「加入」被同时点击，造成两个会话互相覆盖 */
    private final java.util.concurrent.atomic.AtomicBoolean vpnBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public MultiplayerPage(ConfigManager config,
                           java.util.function.Consumer<String> statusConsumer) {
        this.config = config;
        this.statusConsumer = statusConsumer;
    }

    @Override
    public String getTitle() {
        return "联机";
    }

    @Override
    public javafx.scene.layout.Region getContent() {
        if (root == null) {
            root = buildUI();
            // 内容较多时允许整页滚动；窗口足够高时 ScrollPane 不显示滚动条，
            // 日志区仍会撑满剩余空间
            javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(root);
            sp.setFitToWidth(true);
            sp.setFitToHeight(false);     // 不锁高度：窗口矮时整页滚动，日志区保持固有高度
            sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
            sp.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
            sp.getStyleClass().add("account-scroll");
            sp.setMinHeight(0);
            // 内容比窗口矮时不应该被 ScrollPane 压扁：给一个最小高度，
            // 由 VBox.setVgrow(logArea, ALWAYS) 把多余空间分给日志
            // 给一个合理的最小高度即可（不要把 min 绑成 USE_PREF_SIZE：
            // 那样窗口一变矮，日志区按行数算出的高度会把下面的按钮顶出可视区）
            root.setMinHeight(480);
            content = sp;
        }
        return content;
    }

    private javafx.scene.layout.Region content;

    @Override
    public void onHide() {
        // 联机不因切页中断：隧道在后台继续跑
    }

    /** 启动器关闭时调用（由 MainWindow 负责） */
    public void shutdown() {
        if (tunnel != null) {
            tunnel.stop();
        }
        // 启动器退出 / 切换语言时页面会被丢弃：必须把虚拟网卡和路由一起收拾掉
        if (vpn != null) {
            try {
                vpn.close();
            } catch (Exception e) {
                logger.debug("关闭虚拟局域网失败: {}", e.getMessage());
            }
            vpn = null;
        }
    }

    private VBox buildUI() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(16));
        root.getStyleClass().add("bg-surface");
        root.setFillWidth(true);

        // ---- 标题 ----
        Label title = new Label("联机");
        title.getStyleClass().add("page-title");

        Label hint = new Label("没有公网 IP 也能和朋友联机：先用下面的「虚拟局域网」，"
                + "在游戏里点「对局域网开放」后用朋友连 10.77.0.1。");
        hint.setWrapText(true);
        hint.getStyleClass().add("text-secondary");

        // 离线账号 / 正版验证这类注意事项收进提示，不占首屏
        Label note = new Label("单人存档的「对局域网开放」强制正版验证，离线账号请改用独立服务端。");
        note.setWrapText(true);
        note.getStyleClass().add("text-muted");
        note.setVisible(false);
        note.setManaged(false);

        javafx.scene.control.Button noteToggle = new javafx.scene.control.Button("注意事项");
        noteToggle.getStyleClass().add("button");
        noteToggle.setStyle("-fx-font-size: 11px; -fx-padding: 2 8 2 8;");
        noteToggle.setOnAction(e -> {
            boolean show = !note.isVisible();
            note.setVisible(show);
            note.setManaged(show);
        });

        // ---- 状态卡片 ----
        VBox statusCard = new VBox(10);
        statusCard.getStyleClass().add("card");
        statusCard.setAlignment(Pos.CENTER);

        stateLabel = new Label("就绪");
        stateLabel.getStyleClass().add("card-title");
        stateLabel.setWrapText(true);
        stateLabel.setTextAlignment(TextAlignment.CENTER);
        stateLabel.setMaxWidth(Double.MAX_VALUE);

        addressLabel = new Label("");
        addressLabel.getStyleClass().add("relay-address");
        addressLabel.setWrapText(true);
        addressLabel.setTextAlignment(TextAlignment.CENTER);
        addressLabel.setMaxWidth(Double.MAX_VALUE);
        addressLabel.setVisible(false);
        addressLabel.setManaged(false);

        playersLabel = new Label("");
        playersLabel.getStyleClass().add("text-secondary");
        playersLabel.setTextAlignment(TextAlignment.CENTER);
        playersLabel.setMaxWidth(Double.MAX_VALUE);
        playersLabel.setVisible(false);
        playersLabel.setManaged(false);

        spinner = new ProgressIndicator();
        spinner.setMaxSize(32, 32);
        spinner.setVisible(false);
        spinner.setManaged(false);

        // 用 Separator 强制分行，避免被压成一行
        statusCard.getChildren().addAll(stateLabel, spinner, addressLabel,
                new Separator(), playersLabel);

        // ---- 中转服务器选择 ----
        HBox serverRow = new HBox(10);
        serverRow.setAlignment(Pos.CENTER_LEFT);

        Label serverLabel = new Label("中转服务器");
        serverLabel.getStyleClass().add("text-secondary");
        serverLabel.setMinWidth(80);

        serverCombo = new ComboBox<>();
        serverCombo.getItems().addAll(com.mclauncher.multiplayer.RelayServers.ALL);
        // 只显示「名称 + 延迟」，不暴露具体 IP
        serverCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(com.mclauncher.multiplayer.RelayServers.Server item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : describeServer(item));
            }
        });
        serverCombo.setButtonCell(serverCombo.getCellFactory().call(null));
        serverCombo.getSelectionModel().select(0);
        serverCombo.setPrefWidth(420);
        HBox.setHgrow(serverCombo, Priority.ALWAYS);
        serverCombo.setMaxWidth(Double.MAX_VALUE);

        // 启动后异步测延迟，测完自动刷新显示
        com.mclauncher.multiplayer.RelayServers.measureLatencyAsync(() ->
                Platform.runLater(() -> {
                    // 重新构建单元格，让延迟显示出来
                    serverCombo.setCellFactory(cb -> new ListCell<>() {
                        @Override
                        protected void updateItem(com.mclauncher.multiplayer.RelayServers.Server item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : describeServer(item));
                        }
                    });
                    serverCombo.setButtonCell(serverCombo.getCellFactory().call(null));
                    serverCombo.getItems().setAll(com.mclauncher.multiplayer.RelayServers.ALL);
                    serverCombo.getSelectionModel().select(0);
                }));

        serverRow.getChildren().addAll(serverLabel, serverCombo);

        // ---- 按钮行 ----
        HBox buttonRow = new HBox(10);
        buttonRow.setAlignment(Pos.CENTER);

        startButton = new Button("开启联机");
        startButton.getStyleClass().add("button-primary");
        startButton.setOnAction(e -> startHosting());

        stopButton = new Button("停止");
        stopButton.getStyleClass().add("button");
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopHosting());

        copyButton = new Button("复制地址");
        copyButton.getStyleClass().add("button");
        copyButton.setDisable(true);
        copyButton.setOnAction(e -> copyAddress());

        buttonRow.getChildren().addAll(startButton, stopButton, copyButton);

        // ---- 本地端口 ----
        // 以前写死 25565：但「对局域网开放」会随机挑一个五位数端口，
        // server.properties 也可能被改过，所以这里允许手动填写 + 自动检测
        HBox portRow = new HBox(10);
        portRow.setAlignment(Pos.CENTER_LEFT);

        Label portLabel = new Label(L.t("mp.port.label"));
        portLabel.getStyleClass().add("text-secondary");
        portLabel.setMinWidth(80);

        portField = new TextField();
        portField.setPromptText(L.t("mp.port.prompt"));
        portField.setPrefWidth(220);
        portField.setText(savedPort(config));
        // 回车 = 立即生效
        // 回车 = 明确地应用端口（联机中修改时，对后续新玩家立即生效）
        portField.setOnAction(e -> applyPortFromField(true));
        // 注意：**不要**监听 textProperty 去改隧道端口。
        // 早期版本在这里挂了 textProperty 监听，任何 setText 都会把值写进
        // 正在运行的隧道 —— 包括后台自动检测线程写回检测结果。结果就是
        // 用户明明填的是 55555，隧道实际连的却是检测线程猜出来的另一个端口。

        detectPortButton = new Button(L.t("mp.port.detect"));
        detectPortButton.getStyleClass().add("button");
        detectPortButton.setOnAction(e -> detectPortAsync());

        testButton = new Button(L.t("mp.port.test"));
        testButton.getStyleClass().add("button");
        testButton.setOnAction(e -> testLocalPort());

        portRow.getChildren().addAll(portLabel, portField, detectPortButton, testButton);

        // ---- 日志 ----
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(6);
        logArea.setMinHeight(120);
        logArea.setPromptText("日志输出…");
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        // 端口栏为空：后台静默检测一次，把猜出来的端口填进去
        if (isBlank(portField.getText())) {
            Platform.runLater(() -> {
                if (isBlank(portField.getText())) {
                    Thread t = new Thread(() -> {
                        var found = LocalPortScanner.scan(gameDirs(), rememberedPorts(), gameDirs());
                        if (!found.isEmpty()) {
                            int p = found.get(0).port();
                            Platform.runLater(() -> {
                                // 联机已开启 / 正在连接时绝不覆盖：此时端口已被隧道使用，
                                // 改输入框只会让「界面上显示的端口」和「实际使用的端口」不一致
                                if (isBlank(portField.getText()) && !isRelayBusy()) {
                                    portField.setText(String.valueOf(p));
                                }
                            });
                        }
                    }, "port-autodetect");
                    t.setDaemon(true);
                    t.start();
                }
            });
        }

        // ---- 组装 ----
        HBox titleRow = new HBox(8, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Region titleSp = new Region();
        HBox.setHgrow(titleSp, Priority.ALWAYS);
        titleRow.getChildren().addAll(titleSp, noteToggle);
        VBox header = new VBox(4, titleRow, hint, note);
        root.getChildren().addAll(header, buildVpnSection(), statusCard, serverRow,
                portRow, buttonRow, logArea);
        return root;
    }

    // ==================== 虚拟局域网（VPN） ====================

    /**
     * 虚拟局域网区块：房主一键开网 / 朋友输房间号加入。
     *
     * 与下面的「中继房间」是两套独立方案：
     *   · 中继房间：朋友 MC 里填 39.106.60.15:40000 这类公网地址；
     *   · 虚拟局域网：双方各有一块虚拟网卡，朋友 MC 里填 10.77.0.1:端口，
     *     像连同一台电脑上的局域网一样。
     */
    private VBox buildVpnSection() {
        VBox box = new VBox(8);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(12));

        Label title = new Label("虚拟局域网（推荐）");
        title.getStyleClass().add("card-title");
        Label desc = new Label("朋友在 MC 里填 10.77.0.1 + 开服端口即可（需管理员运行）。");
        desc.setWrapText(true);
        desc.getStyleClass().add("text-secondary");

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        vpnHostButton = new Button("开一个房间");
        vpnHostButton.getStyleClass().add("button-primary");
        vpnHostButton.setOnAction(e -> startVpnHost());

        Label roomLabel = new Label("房间号");
        roomLabel.getStyleClass().add("text-secondary");
        vpnRoomField = new TextField();
        vpnRoomField.setPromptText("房主给的 6 位房间号");
        vpnRoomField.setPrefWidth(150);

        vpnJoinButton = new Button("加入");
        vpnJoinButton.getStyleClass().add("button");
        vpnJoinButton.setOnAction(e -> startVpnJoin());

        vpnStopButton = new Button("断开");
        vpnStopButton.getStyleClass().add("button");
        vpnStopButton.setDisable(true);
        vpnStopButton.setOnAction(e -> stopVpn());

        row.getChildren().addAll(vpnHostButton, roomLabel, vpnRoomField, vpnJoinButton, vpnStopButton);

        vpnStatusLabel = new Label("");
        vpnStatusLabel.setWrapText(true);
        vpnStatusLabel.getStyleClass().add("text-secondary");

        box.getChildren().addAll(title, desc, row, vpnStatusLabel);
        return box;
    }

    private void setVpnStatus(String text) {
        Platform.runLater(() -> vpnStatusLabel.setText(text));
    }

    private void startVpnHost() {
        String err = com.mclauncher.multiplayer.vpn.VpnSession.precheck();
        if (err != null) {
            setLog("虚拟局域网无法开启：" + err);
            setVpnStatus(err);
            return;
        }
        if (vpn != null) {
            setLog("虚拟局域网已经开着，请先断开");
            return;
        }
        if (!vpnBusy.compareAndSet(false, true)) {
            setLog("上一个操作还没完成，请稍候");
            return;
        }
        int localPort = detectMcPort();
        Integer fieldPort = parsePort(portField.getText());
        if (fieldPort != null) {
            localPort = fieldPort;
        }
        portField.setText(String.valueOf(localPort));
        savePort(config, localPort);
        if (!isPortListening(localPort)) {
            setLog("本地 " + localPort + " 端口无响应 —— 请先在 Minecraft 里点「对局域网开放」");
        }

        vpnHostButton.setDisable(true);
        setVpnStatus("正在创建虚拟网卡…（首次会安装驱动，可能需要几秒）");
        final int port = localPort;
        new Thread(() -> {
            try {
                com.mclauncher.multiplayer.vpn.VpnSession s =
                        com.mclauncher.multiplayer.vpn.VpnSession.host(
                                com.mclauncher.multiplayer.RelayServers.ALL[0].first().host,
                                com.mclauncher.multiplayer.RelayServers.ALL[0].first().port, "我的联机房间");
                synchronized (MultiplayerPage.this) {
                    if (vpn != null) {
                        // 另一个入口刚建好会话：丢弃这个，避免互相覆盖
                        s.close();
                        return;
                    }
                    vpn = s;
                }
                String room = s.roomId();
                Platform.runLater(() -> {
                    vpnStatusLabel.setText("已开启！房间号：" + room
                            + "\n把房间号发给朋友，让他们点「加入」。"
                            + "\n朋友在 Minecraft 里填：10.77.0.1:" + port);
                    vpnStopButton.setDisable(false);
                    vpnRoomField.setText(room);
                });
                setLog("虚拟局域网已开启，房间号 " + room);
                setLog("朋友的连接地址：10.77.0.1:" + port);
            } catch (Exception e) {
                logger.warn("开虚拟局域网失败", e);
                Platform.runLater(() -> {
                    vpnHostButton.setDisable(false);
                    vpnStatusLabel.setText("开启失败：" + e.getMessage());
                });
                setLog("虚拟局域网开启失败：" + e.getMessage());
            } finally {
                vpnBusy.set(false);
            }
        }, "vpn-host").start();
    }

    private void startVpnJoin() {
        String err = com.mclauncher.multiplayer.vpn.VpnSession.precheck();
        if (err != null) {
            setLog("虚拟局域网无法加入：" + err);
            return;
        }
        String room = vpnRoomField.getText() == null ? "" : vpnRoomField.getText().trim();
        if (room.isEmpty()) {
            setLog("请先填写房主给的房间号");
            return;
        }
        if (vpn != null) {
            setLog("虚拟局域网已经连着，请先断开");
            return;
        }
        if (!vpnBusy.compareAndSet(false, true)) {
            setLog("上一个操作还没完成，请稍候");
            return;
        }
        vpnJoinButton.setDisable(true);
        setVpnStatus("正在加入房间…");
        final int localMcPort = detectMcPort();
        new Thread(() -> {
            try {
                com.mclauncher.multiplayer.vpn.VpnSession s =
                        com.mclauncher.multiplayer.vpn.VpnSession.join(
                                com.mclauncher.multiplayer.RelayServers.ALL[0].first().host,
                                com.mclauncher.multiplayer.RelayServers.ALL[0].first().port, room, 1);
                synchronized (MultiplayerPage.this) {
                    if (vpn != null) {
                        s.close();
                        return;
                    }
                    vpn = s;
                }
                Platform.runLater(() -> {
                    vpnStatusLabel.setText("已加入房间 " + room
                            + "\n现在在 Minecraft 里直接连接服务器：10.77.0.1:" + detectMcPort());
                    vpnStopButton.setDisable(false);
                });
                setLog("已加入虚拟局域网 " + room);
            } catch (Exception e) {
                logger.warn("加入虚拟局域网失败", e);
                Platform.runLater(() -> {
                    vpnJoinButton.setDisable(false);
                    vpnStatusLabel.setText("加入失败：" + e.getMessage());
                });
                setLog("加入失败：" + e.getMessage());
            } finally {
                vpnBusy.set(false);
            }
        }, "vpn-join").start();
    }

    private void stopVpn() {
        if (vpn != null) {
            try {
                vpn.close();
            } catch (Exception e) {
                logger.debug("关闭 VPN 失败: {}", e.getMessage());
            }
            vpn = null;
            setLog("虚拟局域网已断开");
        }
        vpnStatusLabel.setText("");
        vpnHostButton.setDisable(false);
        vpnJoinButton.setDisable(false);
        vpnStopButton.setDisable(true);
    }

    // ==================== 隧道操作 ====================

    private void startHosting() {
        if (tunnel != null && tunnel.getState() == RelayTunnel.State.ONLINE) {
            return;
        }

        Integer fieldPort = parsePort(portField.getText());
        if (!isBlank(portField.getText()) && fieldPort == null) {
            setLog(L.t("mp.port.invalid"));
            portField.requestFocus();
            return;
        }
        int localPort = fieldPort != null ? fieldPort : detectMcPort();
        // 用「当前的输入框内容」判断：自动检测线程可能刚把值改过，
        // 这里以最终要用的 localPort 为准，再同步回界面，保证两者一致
        if (!String.valueOf(localPort).equals(portField.getText().trim())) {
            setLog(L.t("mp.port.synced", localPort));
            portField.setText(String.valueOf(localPort));
        }
        savePort(config, localPort);
        logger.info("开启联机：本地 MC 端口 = {}（来自{}）", localPort,
                fieldPort != null ? "用户填写" : "自动检测");

        com.mclauncher.multiplayer.RelayServers.Server picked =
                serverCombo.getSelectionModel().getSelectedItem();
        if (picked == null) {
            picked = com.mclauncher.multiplayer.RelayServers.ALL[0];
        }
        // 尝试检测本机 MC 是否已开服
        if (!isPortListening(localPort)) {
            setLog(L.t("mp.port.closed", localPort));
        }

        // 开房前先探测服务端是否强制正版验证：单人存档的「对局域网开放」
        // 强制 online-mode=true，离线账号加入时会在加密验证阶段被秒踢。
        // 探测含网络 IO，放后台线程，避免卡住 FX 线程。
        setLog("正在检测本地服务端的验证模式…");
        setBusy(true);
        final int probePort = localPort;
        // picked 上面可能被兜底赋值过，lambda 要求 effectively final，另存一份
        final com.mclauncher.multiplayer.RelayServers.Server finalPicked = picked;
        Thread probe = new Thread(() -> {
            LocalPortScanner.OnlineMode mode = LocalPortScanner.probeOnlineMode(probePort);
            Platform.runLater(() -> handleOnlineMode(mode, finalPicked, probePort));
        }, "online-mode-probe");
        probe.setDaemon(true);
        probe.start();
    }

    /** 根据 login 探测结果决定弹警告还是直接开房 */
    private void handleOnlineMode(LocalPortScanner.OnlineMode mode,
                                  com.mclauncher.multiplayer.RelayServers.Server picked,
                                  int localPort) {
        if (mode != LocalPortScanner.OnlineMode.ONLINE) {
            // OFFLINE：离线账号可加入；UNKNOWN：探测失败，不拦用户，按原流程走
            createAndStartTunnel(picked, localPort);
            return;
        }
        logger.info("端口 {} 强制正版验证（online-mode=true），提示用户", localPort);
        if (modalHost == null) {
            createAndStartTunnel(picked, localPort);
            return;
        }
        String message = "当前开的是单人存档的「对局域网开放」，它强制开启正版验证，"
                + "离线账号无法加入，朋友那边会报「无效的会话」并被断开。\n\n"
                + "想和朋友用离线账号联机，请改用独立服务端（server.jar），"
                + "并把 server.properties 里的 online-mode 改成 false。";
        MessageDialog.confirmOrCancel(modalHost, "无法离线联机", "检测到正版验证",
                message, "仍然开启", "取消",
                () -> {
                    setLog("已知晓风险，仍然开启联机");
                    createAndStartTunnel(picked, localPort);
                },
                () -> {
                    setBusy(false);
                    setSpinner(false);
                    stateLabel.setText("就绪");
                    setLog("已取消开启联机：请改用独立服务端并把 online-mode 设为 false");
                });
    }

    /** 建立隧道并启动（探测通过或用户坚持开启后调用） */
    private void createAndStartTunnel(
            com.mclauncher.multiplayer.RelayServers.Server picked, int localPort) {
        setLog("正在向中转申请房间…");

        tunnel = new RelayTunnel(picked, "127.0.0.1", localPort,
                "我的联机房间", new RelayTunnel.Listener() {
                    @Override
                    public void onState(RelayTunnel.State state, String message) {
                        Platform.runLater(() -> {
                            switch (state) {
                                case CONNECTING -> {
                                    setSpinner(true);
                                    stateLabel.setText(message);
                                    addressLabel.setVisible(false);
                                    addressLabel.setManaged(false);
                                }
                                case ONLINE -> {
                                    setBusy(false);
                                    setSpinner(false);
                                    stateLabel.setText("联机已开启");
                                    String addr = tunnel.getPublicAddress();
                                    addressLabel.setText(addr);
                                    addressLabel.setVisible(true);
                                    addressLabel.setManaged(true);
                                    startButton.setDisable(true);
                                    stopButton.setDisable(false);
                                    copyButton.setDisable(false);
                                    serverCombo.setDisable(true);
                                    setLog("✓ 联机已开启\n  地址：" + addr + "\n  等待朋友加入…");
                                    if (statusConsumer != null)
                                        statusConsumer.accept("联机已开启 " + addr);
                                }
                                case CLOSED -> {
                                    setBusy(false);
                                    setSpinner(false);
                                    stateLabel.setText("已断开");
                                    addressLabel.setVisible(false);
                                    addressLabel.setManaged(false);
                                    startButton.setDisable(false);
                                    stopButton.setDisable(true);
                                    copyButton.setDisable(true);
                                    serverCombo.setDisable(false);
                                    if (statusConsumer != null)
                                        statusConsumer.accept("联机已断开");
                                }
                            }
                        });
                    }

                    @Override
                    public void onAddress(String host, int port) {
                        String addr = host + ":" + port;
                        Platform.runLater(() -> {
                            addressLabel.setText(addr);
                            addressLabel.setVisible(true);
                            addressLabel.setManaged(true);
                            setLog("已分配到公网地址: " + addr);
                        });
                    }

                    @Override
                    public void onJoinFailed(int id, String from, String reason) {
                        Platform.runLater(() -> {
                            if ("timeout".equals(reason)) {
                                setLog("✗ 玩家 " + from + " 等待确认太久（超过 20 秒），中转已断开，\n"
                                        + "  对方看到的会是「连接已丢失」。请让对方重新连接，你在 20 秒内点「允许」。");
                            } else {
                                setLog("✗ 已允许玩家 " + from + "，但连不上本地游戏（端口 "
                                        + tunnel.getLocalPort() + "）。\n"
                                        + "  请确认 Minecraft 里已经点过「对局域网开放」，且端口填对了。");
                            }
                        });
                    }

                    @Override
                    public void onPlayers(int count) {
                        Platform.runLater(() -> {
                            playersLabel.setText("在线玩家：" + count);
                            playersLabel.setVisible(true);
                            playersLabel.setManaged(true);
                            if (statusConsumer != null && count > 0) {
                                statusConsumer.accept("联机中 " + count + " 人在线");
                            }
                        });
                    }
                });

        // 新玩家接入确认：中转的房间号在公开列表里，端口也可能被猜到，
        // 不确认的话任何人都能接管联机。确认框在 FX 线程弹出，结果回传给后台线程。
        final RelayTunnel t = tunnel;
        t.setJoinApprover((id, from) -> {
            if (modalHost == null) {
                setLog("收到玩家接入请求（" + from + "），但无法弹出确认框，已拒绝");
                return false;
            }
            java.util.concurrent.CompletableFuture<Boolean> answer =
                    new java.util.concurrent.CompletableFuture<>();
            Platform.runLater(() -> com.mclauncher.ui.component.MessageDialog.confirmOrCancel(
                    modalHost,
                    "新玩家接入",
                    "有玩家想加入你的联机房间",
                    "来源地址：" + from,
                    "允许",
                    "拒绝",
                    () -> {
                        setLog("已允许玩家接入（" + from + "）");
                        answer.complete(true);
                    },
                    () -> {
                        setLog("已拒绝玩家接入（" + from + "）");
                        answer.complete(false);
                    }));
            try {
                // 等房主点按钮；超时按拒绝处理，不要无限挂住中继连接
                return answer.get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                setLog("玩家接入确认超时，已拒绝（" + from + "）");
                return false;
            }
        });

        t.start();
    }

    private void stopHosting() {
        if (tunnel != null) {
            tunnel.stop();
        }
        setBusy(false);
        setSpinner(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        copyButton.setDisable(true);
        serverCombo.setDisable(false);
        addressLabel.setVisible(false);
        addressLabel.setManaged(false);
        playersLabel.setVisible(false);
        playersLabel.setManaged(false);
        stateLabel.setText("已停止");
        setLog("联机已手动停止");
    }

    private void copyAddress() {
        String addr = tunnel != null ? tunnel.getPublicAddress() : null;
        if (addr == null) {
            setLog("还没有地址");
            return;
        }
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(addr);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        setLog("已复制到剪贴板: " + addr);
    }

    private void testLocalPort() {
        Integer port = parsePort(portField.getText());
        if (port == null) {
            port = detectMcPort();
        }
        final int p = port;
        portField.setText(String.valueOf(p));
        setLog(L.t("mp.port.detecting"));
        new Thread(() -> {
            String version = LocalPortScanner.probeVersion(p);
            Platform.runLater(() -> {
                if (version == null) {
                    setLog(L.t("mp.port.bad", p));
                } else {
                    setLog(L.t("mp.port.ok", p, version.isBlank() ? "?" : version));
                }
            });
        }, "port-test").start();
    }

    /**
     * 自动检测：先看 server.properties / 启动日志 / 历史端口，
     * 都没有再做端口扫描。全区间扫描可能持续十几秒，放在后台线程。
     */
    private void detectPortAsync() {
        detectPortButton.setDisable(true);
        setLog(L.t("mp.port.detecting"));
        Thread t = new Thread(() -> {
            var found = LocalPortScanner.scan(gameDirs(), rememberedPorts(), gameDirs());
            Platform.runLater(() -> {
                detectPortButton.setDisable(false);
                if (found.isEmpty()) {
                    setLog(L.t("mp.port.notfound"));
                    return;
                }
                int p = found.get(0).port();
                portField.setText(String.valueOf(p));
                savePort(config, p);
                // 联机进行中不偷偷改隧道端口：用户必须明确决定，
                // 否则会出现「界面 55555、实际连 61243」这种难以排查的状态
                if (isRelayBusy()) {
                    setLog(L.t("mp.port.need-restart", p));
                }
                if (found.size() > 1) {
                    setLog(L.t("mp.port.foundone", p, found.size()));
                } else {
                    setLog(L.t("mp.port.found", p));
                }
            });
        }, "port-detect");
        t.setDaemon(true);
        t.start();
    }

    /** 联机是否正在运行（连接中 / 已开启） */
    private boolean isRelayBusy() {
        if (tunnel == null) {
            return false;
        }
        RelayTunnel.State st = tunnel.getState();
        return st == RelayTunnel.State.ONLINE || st == RelayTunnel.State.CONNECTING;
    }

    /** 端口输入框校验：空 / 非法返回 null */
    private static Integer parsePort(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(t);
            return (p > 0 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 把输入框里的端口应用到隧道；校验失败时提示 */
    private boolean applyPortFromField(boolean showError) {
        if (isBlank(portField.getText())) {
            return false;
        }
        Integer p = parsePort(portField.getText());
        if (p == null) {
            if (showError) {
                setLog(L.t("mp.port.invalid"));
            }
            return false;
        }
        portField.setText(String.valueOf(p));
        savePort(config, p);
        if (tunnel != null && tunnel.getState() == RelayTunnel.State.ONLINE) {
            tunnel.setLocalPort(p);
        }
        return true;
    }

    /** 要检查的游戏目录（当前目录优先，然后是用户添加的目录） */
    private java.util.List<Path> gameDirs() {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        try {
            Path cur = config.getGameDir();
            if (cur != null) {
                dirs.add(cur);
            }
        } catch (Exception ignored) {
            // 配置异常时不影响探测
        }
        for (var gd : config.getGameDirectories()) {
            try {
                Path p = gd.getRootPath();
                if (p != null && !dirs.contains(p)) {
                    dirs.add(p);
                }
            } catch (Exception ignored) {
                // 忽略单个目录
            }
        }
        return dirs;
    }

    /** 记得的端口：最近一次 + 上一次 */
    private java.util.List<Integer> rememberedPorts() {
        java.util.List<Integer> ports = new java.util.ArrayList<>();
        Integer last = parsePort(config.getString("relay.lastLocalPort", ""));
        if (last != null) {
            ports.add(last);
        }
        Integer prev = parsePort(config.getString("relay.prevLocalPort", ""));
        if (prev != null && !ports.contains(prev)) {
            ports.add(prev);
        }
        return ports;
    }

    private static String savedPort(ConfigManager cfg) {
        return cfg.getString("relay.lastLocalPort", "");
    }

    /** 记住本次端口，供下次启动和自动检测优先使用 */
    private static void savePort(ConfigManager cfg, int port) {
        String prev = cfg.getString("relay.lastLocalPort", "");
        if (!String.valueOf(port).equals(prev)) {
            if (!isBlank(prev)) {
                cfg.setString("relay.prevLocalPort", prev);
            }
            cfg.setString("relay.lastLocalPort", String.valueOf(port));
        }
    }

    /** 服务器显示文本：名称 + 延迟 */
    private static String describeServer(com.mclauncher.multiplayer.RelayServers.Server s) {
        int ms = s.getLatencyMs();
        String lat;
        if (ms < 0) {
            lat = "检测中…";
        } else if (ms < 80) {
            lat = ms + "ms  信号极好";
        } else if (ms < 160) {
            lat = ms + "ms  良好";
        } else if (ms < 300) {
            lat = ms + "ms  一般";
        } else {
            lat = ms + "ms  较慢";
        }
        return s.name + "    ·    " + lat;
    }

    // ==================== 工具 ====================

    /**
     * 兜底猜一个本地 MC 端口（不扫全区间，保持调用方期望的「快」）：
     * 启动日志 → server.properties → 历史端口 → 25565
     *
     * 为什么日志优先：「对局域网开放」不改 server.properties，
     * 真正的端口只出现在 latest.log 的「本地游戏已在端口 N 上开启」里。
     * 反过来按 server.properties 优先，会拿着独立服务端的端口去连单人存档。
     */
    private int detectMcPort() {
        for (Path dir : gameDirs()) {
            Integer p = LocalPortScanner.readPortFromLog(dir);
            if (p != null) {
                return p;
            }
        }
        for (Path dir : gameDirs()) {
            Integer p = LocalPortScanner.readServerPort(dir);
            if (p != null) {
                return p;
            }
        }
        for (Integer p : rememberedPorts()) {
            if (isPortListening(p)) {
                return p;
            }
        }
        return 25565;
    }

    private static boolean isPortListening(int port) {
        return RelayTunnel.isLocalPortOpen("127.0.0.1", port);
    }

    private void setLog(String msg) {
        if (logArea != null) {
            logArea.appendText(msg + "\n");
            // 滚动到底
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void setBusy(boolean busy) {
        startButton.setDisable(busy);
    }

    private void setSpinner(boolean show) {
        spinner.setVisible(show);
        spinner.setManaged(show);
        if (show) {
            stateLabel.setText("连接中转服务器…");
        }
    }
}