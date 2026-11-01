package com.mclauncher.ui.page;

import com.mclauncher.config.ConfigManager;
import com.mclauncher.i18n.L;
import com.mclauncher.multiplayer.LocalPortScanner;
import com.mclauncher.multiplayer.RelayTunnel;
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

public class MultiplayerPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(MultiplayerPage.class);

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

    private ComboBox<com.mclauncher.multiplayer.RelayServers.Server> serverCombo;

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

    private VBox root;

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
        }
        return root;
    }

    @Override
    public void onHide() {

    }

    public void shutdown() {
        if (tunnel != null) {
            tunnel.stop();
        }
    }

    private VBox buildUI() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(16));
        root.getStyleClass().add("bg-surface");

        Label title = new Label("联机");
        title.getStyleClass().add("page-title");

        Label hint = new Label(
                "没有公网 IP 也能和朋友联机。你只需要在游戏里点「对局域网开放」，"
                + "然后在这里开启联机，把地址发给朋友即可。");
        hint.setWrapText(true);
        hint.getStyleClass().add("text-secondary");

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

        statusCard.getChildren().addAll(stateLabel, spinner, addressLabel,
                new Separator(), playersLabel);

        HBox serverRow = new HBox(10);
        serverRow.setAlignment(Pos.CENTER_LEFT);

        Label serverLabel = new Label("中转服务器");
        serverLabel.getStyleClass().add("text-secondary");
        serverLabel.setMinWidth(80);

        serverCombo = new ComboBox<>();
        serverCombo.getItems().addAll(com.mclauncher.multiplayer.RelayServers.ALL);

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

        com.mclauncher.multiplayer.RelayServers.measureLatencyAsync(() ->
                Platform.runLater(() -> {

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

        

        HBox portRow = new HBox(10);
        portRow.setAlignment(Pos.CENTER_LEFT);

        Label portLabel = new Label(L.t("mp.port.label"));
        portLabel.getStyleClass().add("text-secondary");
        portLabel.setMinWidth(80);

        portField = new TextField();
        portField.setPromptText(L.t("mp.port.prompt"));
        portField.setPrefWidth(220);
        portField.setText(savedPort(config));

        portField.setOnAction(e -> applyPortFromField(true));

        portField.textProperty().addListener((obs, o, n) -> {
            if (tunnel != null && tunnel.getState() == RelayTunnel.State.ONLINE) {
                Integer p = parsePort(n);
                if (p != null) {
                    tunnel.setLocalPort(p);
                }
            }
        });

        detectPortButton = new Button(L.t("mp.port.detect"));
        detectPortButton.getStyleClass().add("button");
        detectPortButton.setOnAction(e -> detectPortAsync());

        testButton = new Button(L.t("mp.port.test"));
        testButton.getStyleClass().add("button");
        testButton.setOnAction(e -> testLocalPort());

        portRow.getChildren().addAll(portLabel, portField, detectPortButton, testButton);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(5);
        logArea.setPromptText("日志输出…");
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        if (isBlank(portField.getText())) {
            Platform.runLater(() -> {
                if (isBlank(portField.getText())) {
                    Thread t = new Thread(() -> {
                        var found = LocalPortScanner.scan(gameDirs(), rememberedPorts(), gameDirs());
                        if (!found.isEmpty()) {
                            int p = found.get(0).port();
                            Platform.runLater(() -> {
                                if (isBlank(portField.getText())) {
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

        VBox header = new VBox(4, title, hint);
        root.getChildren().addAll(header, statusCard, serverRow, portRow, buttonRow, logArea);
        return root;
    }

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
        portField.setText(String.valueOf(localPort));
        savePort(config, localPort);

        com.mclauncher.multiplayer.RelayServers.Server picked =
                serverCombo.getSelectionModel().getSelectedItem();
        if (picked == null) {
            picked = com.mclauncher.multiplayer.RelayServers.ALL[0];
        }

        if (!isPortListening(localPort)) {
            setLog(L.t("mp.port.closed", localPort));
        }

        setLog("正在向中转申请房间…");
        setBusy(true);

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
                if (tunnel != null && tunnel.getState() == RelayTunnel.State.ONLINE) {
                    tunnel.setLocalPort(p);
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

    private java.util.List<Path> gameDirs() {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        try {
            Path cur = config.getGameDir();
            if (cur != null) {
                dirs.add(cur);
            }
        } catch (Exception ignored) {

        }
        for (var gd : config.getGameDirectories()) {
            try {
                Path p = gd.getRootPath();
                if (p != null && !dirs.contains(p)) {
                    dirs.add(p);
                }
            } catch (Exception ignored) {

            }
        }
        return dirs;
    }

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

    private static void savePort(ConfigManager cfg, int port) {
        String prev = cfg.getString("relay.lastLocalPort", "");
        if (!String.valueOf(port).equals(prev)) {
            if (!isBlank(prev)) {
                cfg.setString("relay.prevLocalPort", prev);
            }
            cfg.setString("relay.lastLocalPort", String.valueOf(port));
        }
    }

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

    private int detectMcPort() {
        for (Path dir : gameDirs()) {
            Integer p = LocalPortScanner.readServerPort(dir);
            if (p != null) {
                return p;
            }
            p = LocalPortScanner.readPortFromLog(dir);
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
