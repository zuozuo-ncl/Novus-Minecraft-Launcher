package com.mclauncher.ui.page;

import java.io.IOException;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.multiplayer.LocalPortScanner;
import com.mclauncher.multiplayer.vpn.VpnSession;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 联机页（虚拟局域网）
 *
 * 只有一种联机方式：双方各建一块虚拟网卡，经中继按虚拟 IP 交换数据。
 *
 *   房主：开房 → 自己的 MC 点「对局域网开放」→ 把邀请发给朋友
 *   朋友：粘贴邀请加入 → 在 MC 里连 localhost:<房主局域网端口 + 1>
 *
 * 旧的「公网端口转发」已被移除（实测对 MC 不可用），中继也不再分配端口。
 */
public class MultiplayerPage implements Page {

    private static final Logger logger = LoggerFactory.getLogger(MultiplayerPage.class);

    /** 中继的 HTTP 端口（VPN 帧走 HTTP_PORT + 1） */
    private static final int RELAY_HTTP_PORT = 8080;

    /** 中继端点：host + 对外 HTTP 端口（VPN 帧握手同样连这个端口） */
    private static final class RelayEndpoint {
        final String host;
        final int port;

        RelayEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        String label() {
            return port == 8080 ? host : host + ":" + port;
        }
    }

    /**
     * 中继端点列表，按尝试顺序排列：第一个是首选，后面的是退回地址。
     *
     * 每个条目形如 {@code host}（端口用默认 8080）或 {@code host:端口}（经穿透映射）。
     * 用 -Dmclauncher.relayHosts=a,b,... 覆盖整个列表；
     * 没配置时依次尝试：39.106.60.15（主站）→ 45.64.229.50:41181（备站）。
     */
    private static final java.util.List<RelayEndpoint> RELAY_ENDPOINTS = parseRelayEndpoints(
            System.getProperty("mclauncher.relayHosts"),
            System.getProperty("mclauncher.relayHost"),
            System.getProperty("mclauncher.relayHttpPort"));

    private static java.util.List<RelayEndpoint> parseRelayEndpoints(String list, String singleHost, String singlePort) {
        java.util.List<RelayEndpoint> out = new java.util.ArrayList<>();
        if (list != null && !list.isBlank()) {
            for (String item : list.split(",")) {
                String s = item.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int colon = s.lastIndexOf(':');
                try {
                    if (colon > 0) {
                        int p = Integer.parseInt(s.substring(colon + 1).trim());
                        if (p > 0 && p <= 65535) {
                            out.add(new RelayEndpoint(s.substring(0, colon).trim(), p));
                            continue;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // 不合法，整条忽略
                }
                out.add(new RelayEndpoint(s, RELAY_HTTP_PORT));
            }
        }
        // 兼容旧的单项参数：-Dmclauncher.relayHost / -Dmclauncher.relayHttpPort
        if (singleHost != null && !singleHost.isBlank()) {
            int p = RELAY_HTTP_PORT;
            try {
                if (singlePort != null && !singlePort.isBlank()) {
                    p = Integer.parseInt(singlePort.trim());
                }
            } catch (NumberFormatException ignored) {
                // 用默认端口
            }
            out.add(0, new RelayEndpoint(singleHost.trim(), p));
        }
        if (out.isEmpty()) {
            out.add(new RelayEndpoint("39.106.60.15", RELAY_HTTP_PORT));
            out.add(new RelayEndpoint("45.64.229.50", 41181));
        }
        return java.util.List.copyOf(out);
    }

    private final ConfigManager config;
    private final java.util.function.Consumer<String> statusConsumer;

    // ---- UI ----
    private VBox root;
    private javafx.scene.layout.Region content;
    private Label stateLabel;
    private Label addressLabel;
    private TextField portField;
    private Button detectPortButton;
    private Button testButton;
    private TextArea logArea;

    private TextField vpnRoomField;
    private Label vpnStatusLabel;
    private Button vpnHostButton;
    private Button vpnJoinButton;
    private Button vpnStopButton;
    private TextField vpnInviteField;
    private HBox vpnInviteRow;

    /** 当前 VPN 会话（虚拟局域网）。null 表示未开启 */
    private volatile VpnSession vpn;
    /** 防止「开房间」与「加入」被同时点击，造成两个会话互相覆盖 */
    private final java.util.concurrent.atomic.AtomicBoolean vpnBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 房主：当前邀请串（口令由中继分配，晚于开会话到达） */
    private volatile String currentInvite;

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
            javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(root);
            sp.setFitToWidth(true);
            sp.setFitToHeight(false);
            sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
            sp.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
            sp.getStyleClass().add("account-scroll");
            sp.setMinHeight(0);
            root.setMinHeight(480);
            content = sp;
        }
        return content;
    }

    @Override
    public void onHide() {
        // 联机不因切页中断：虚拟局域网在后台继续跑
    }

    /** 启动器关闭时调用（由 MainWindow 负责） */
    public void shutdown() {
        if (vpn != null) {
            try {
                vpn.close();
            } catch (Exception e) {
                logger.debug("关闭虚拟局域网失败: {}", e.getMessage());
            }
            vpn = null;
        }
    }

    // ==================== 界面 ====================

    private VBox buildUI() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("bg-surface");
        box.setFillWidth(true);

        Label title = new Label("联机");
        title.getStyleClass().add("page-title");

        Label hint = new Label("没有公网 IP 也能和朋友联机：房主在游戏里点「对局域网开放」，"
                + "把下面的邀请发给朋友；朋友加入后在他的 MC 里连接 localhost 加 1 后的端口。"
                + "（双方都需要以管理员身份运行启动器）");
        hint.setWrapText(true);
        hint.getStyleClass().add("text-secondary");

        Label note = new Label("单人存档的「对局域网开放」强制正版验证，离线账号请改用独立服务端。");
        note.setWrapText(true);
        note.getStyleClass().add("text-muted");
        note.setVisible(false);
        note.setManaged(false);

        Button noteToggle = new Button("注意事项");
        noteToggle.getStyleClass().add("button");
        noteToggle.setStyle("-fx-font-size: 11px; -fx-padding: 2 8 2 8;");
        noteToggle.setOnAction(e -> {
            boolean show = !note.isVisible();
            note.setVisible(show);
            note.setManaged(show);
        });

        HBox titleRow = new HBox(8, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Region titleSp = new Region();
        HBox.setHgrow(titleSp, Priority.ALWAYS);
        titleRow.getChildren().addAll(titleSp, noteToggle);

        VBox header = new VBox(4, titleRow, hint, note);
        box.getChildren().addAll(header, buildVpnSection(), buildPortSection(), buildLogArea());
        return box;
    }

    /** 虚拟局域网区块：房主开房 / 朋友粘贴邀请加入 */
    private VBox buildVpnSection() {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12));

        Label t = new Label("虚拟局域网");
        t.getStyleClass().add("card-title");
        Label desc = new Label("房主点「开一个房间」，朋友把邀请粘贴到右侧后点「加入」。");
        desc.setWrapText(true);
        desc.getStyleClass().add("text-secondary");

        // ---- 状态 ----
        stateLabel = new Label("就绪");
        stateLabel.getStyleClass().add("card-title");
        stateLabel.setWrapText(true);
        stateLabel.setMaxWidth(Double.MAX_VALUE);

        addressLabel = new Label("");
        addressLabel.getStyleClass().add("relay-address");
        addressLabel.setWrapText(true);
        addressLabel.setMaxWidth(Double.MAX_VALUE);
        addressLabel.setVisible(false);
        addressLabel.setManaged(false);

        // ---- 操作行 ----
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        vpnHostButton = new Button("开一个房间");
        vpnHostButton.getStyleClass().add("button-primary");
        vpnHostButton.setOnAction(e -> startVpnHost());

        Label roomLabel = new Label("邀请");
        roomLabel.getStyleClass().add("text-secondary");
        vpnRoomField = new TextField();
        vpnRoomField.setPromptText("粘贴房主发来的邀请");
        vpnRoomField.setPrefWidth(220);
        HBox.setHgrow(vpnRoomField, Priority.ALWAYS);

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

        // ---- 房主的邀请串 ----
        vpnInviteField = new TextField();
        vpnInviteField.setEditable(false);
        vpnInviteField.setPromptText("开房后这里会显示邀请");
        vpnInviteField.setPrefWidth(360);
        Label inviteLabel = new Label("邀请");
        inviteLabel.getStyleClass().add("text-secondary");
        Button copyBtn = new Button("复制邀请");
        copyBtn.getStyleClass().add("button-primary");
        Runnable copyInvite = () -> {
            String text = vpnInviteField.getText();
            if (text != null && !text.isEmpty()) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                setLog("邀请已复制，发给朋友即可");
            }
        };
        vpnInviteField.setOnMouseClicked(e -> copyInvite.run());
        copyBtn.setOnAction(e -> copyInvite.run());
        HBox inviteRow = new HBox(8);
        inviteRow.setAlignment(Pos.CENTER_LEFT);
        inviteRow.getChildren().addAll(inviteLabel, vpnInviteField, copyBtn);
        inviteRow.setVisible(false);
        inviteRow.setManaged(false);
        vpnInviteRow = inviteRow;

        card.getChildren().addAll(t, desc, stateLabel, addressLabel, row, inviteRow, vpnStatusLabel);
        return card;
    }

    /** 本地 MC 端口区块 */
    private VBox buildPortSection() {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12));

        Label t = new Label("本地 MC 端口");
        t.getStyleClass().add("card-title");
        Label desc = new Label("你在 MC 里「对局域网开放」时显示的端口。"
                + "房主开房时会用它；朋友加入时用它在本地生成「端口 + 1」的入口。");
        desc.setWrapText(true);
        desc.getStyleClass().add("text-secondary");

        HBox portRow = new HBox(10);
        portRow.setAlignment(Pos.CENTER_LEFT);

        Label portLabel = new Label("端口");
        portLabel.getStyleClass().add("text-secondary");
        portLabel.setMinWidth(60);

        portField = new TextField();
        portField.setPromptText("例如 25565");
        portField.setPrefWidth(160);
        portField.setText(savedPort(config));
        portField.setOnAction(e -> applyPortFromField(true));

        detectPortButton = new Button("自动检测");
        detectPortButton.getStyleClass().add("button");
        detectPortButton.setOnAction(e -> detectPortAsync());

        testButton = new Button("测试连通");
        testButton.getStyleClass().add("button");
        testButton.setOnAction(e -> testLocalPort());

        portRow.getChildren().addAll(portLabel, portField, detectPortButton, testButton);
        card.getChildren().addAll(t, desc, portRow);

        // 空着就静默检测一次
        if (isBlank(portField.getText())) {
            Platform.runLater(() -> {
                if (isBlank(portField.getText())) {
                    detectPortAsync();
                }
            });
        }
        return card;
    }

    private TextArea buildLogArea() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(6);
        logArea.setMinHeight(120);
        logArea.setPromptText("日志输出…");
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        return logArea;
    }

    // ==================== 房主 / 朋友 ====================

    private void startVpnHost() {
        String err = VpnSession.precheck();
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
            setLog("本地 " + localPort + " 端口没在监听 —— 请先在 Minecraft 里点「对局域网开放」");
        }

        vpnHostButton.setDisable(true);
        vpnJoinButton.setDisable(true);
        setVpnStatus("正在创建虚拟网卡…（首次会安装驱动，可能需要几秒）");
        final int port = localPort;
        final int playerPort = forwardPort(port);
        config.setVpnLocalMcPort(port);

        new Thread(() -> {
            RelayEndpoint used = null;
            try {
                // 按顺序尝试各端点：首选失败自动退回后面的地址
                Exception last = null;
                for (RelayEndpoint ep : RELAY_ENDPOINTS) {
                    try {
                        setVpnStatus("正在连接中继 " + ep.label() + "…");
                        VpnSession s = VpnSession.host(ep.host, ep.port, "我的联机房间", port, relayApiKey(ep));
                        used = ep;
                        synchronized (MultiplayerPage.this) {
                            if (vpn != null) {
                                s.close();
                                return;
                            }
                            vpn = s;
                        }
                        break;
                    } catch (Exception e) {
                        last = e;
                        logger.warn("中继 {} 创建房间失败：{}", ep.label(), e.getMessage());
                        if (RELAY_ENDPOINTS.size() > 1) {
                            setLog("中继 " + ep.label() + " 不可用：" + e.getMessage());
                        }
                    }
                }
                if (used == null) {
                    throw last != null ? last : new java.io.IOException("没有可用的中继地址");
                }
                @SuppressWarnings("ConstantConditions")
                VpnSession s = vpn;
                if (RELAY_ENDPOINTS.size() > 1) {
                    setLog("已使用中继 " + used.label());
                }
                final String room = s.roomId();
                final String baseInvite = buildInvite(playerPort, room, "");
                // 房主自己在 MC 里点「对局域网开放」并让客户端连进来时，
                // 中继会分配一个新的口令，界面随之更新
                s.setPeerListener(peer -> {
                    currentInvite = buildInvite(playerPort, room, peer.code);
                    refreshInvite();
                });
                Platform.runLater(() -> {
                    stateLabel.setText("房间已开启");
                    addressLabel.setText("朋友要在 MC 里连：localhost:" + playerPort);
                    addressLabel.setVisible(true);
                    addressLabel.setManaged(true);
                    vpnStatusLabel.setText("点「复制邀请」发给朋友。你自己的 MC 要先「对局域网开放」，端口设为 "
                            + port + "；朋友加入后成员才会出现在邀请里。");
                    vpnStopButton.setDisable(false);
                    currentInvite = baseInvite;
                    vpnInviteField.setText(baseInvite);
                    vpnInviteRow.setVisible(true);
                    vpnInviteRow.setManaged(true);
                });
                setLog("虚拟局域网已开启，房间号 " + room);
                setLog("朋友要连的地址：localhost:" + playerPort);
            } catch (Exception e) {
                logger.warn("开启虚拟局域网失败", e);
                closeHalfOpenSession();
                Platform.runLater(() -> {
                    vpnHostButton.setDisable(false);
                    vpnJoinButton.setDisable(false);
                    vpnStatusLabel.setText("开启失败：" + e.getMessage());
                });
                setLog("开启失败：" + e.getMessage());
            } finally {
                vpnBusy.set(false);
            }
        }, "vpn-host").start();
    }

    private void startVpnJoin() {
        String err = VpnSession.precheck();
        if (err != null) {
            setLog("虚拟局域网无法加入：" + err);
            return;
        }
        String invite = vpnRoomField.getText() == null ? "" : vpnRoomField.getText().trim();
        if (invite.isEmpty()) {
            setLog("请先粘贴房主发来的邀请");
            return;
        }
        String room = extractRoom(invite);
        if (vpn != null) {
            setLog("虚拟局域网已经连着，请先断开");
            return;
        }
        if (!vpnBusy.compareAndSet(false, true)) {
            setLog("上一个操作还没完成，请稍候");
            return;
        }
        vpnJoinButton.setDisable(true);
        vpnHostButton.setDisable(true);
        setVpnStatus("正在加入房间…");
        final int localMcPort = detectMcPort();
        new Thread(() -> {
            RelayEndpoint used = null;
            try {
                Exception last = null;
                for (RelayEndpoint ep : RELAY_ENDPOINTS) {
                    try {
                        setVpnStatus("正在连接中继 " + ep.label() + "…");
                        VpnSession s = VpnSession.join(ep.host, ep.port, room, invite, relayApiKey(ep),
                                localMcPort, com.mclauncher.multiplayer.vpn.WintunAdapter.ADAPTER_NAME);
                        used = ep;
                        synchronized (MultiplayerPage.this) {
                            if (vpn != null) {
                                s.close();
                                return;
                            }
                            vpn = s;
                        }
                        break;
                    } catch (Exception e) {
                        last = e;
                        logger.warn("中继 {} 加入失败：{}", ep.label(), e.getMessage());
                        if (RELAY_ENDPOINTS.size() > 1) {
                            setLog("中继 " + ep.label() + " 不可用：" + e.getMessage());
                        }
                    }
                }
                if (used == null) {
                    throw last != null ? last : new java.io.IOException("没有可用的中继地址");
                }
                if (RELAY_ENDPOINTS.size() > 1) {
                    setLog("已通过中继 " + used.label() + " 加入");
                }
                Platform.runLater(() -> {
                    stateLabel.setText("已加入房间");
                    addressLabel.setText("在 MC 里连：localhost:" + forwardPort(localMcPort));
                    addressLabel.setVisible(true);
                    addressLabel.setManaged(true);
                    vpnStatusLabel.setText("已加入房间 " + room);
                    vpnStopButton.setDisable(false);
                });
                setLog("已加入虚拟局域网 " + room);
            } catch (Exception e) {
                logger.warn("加入虚拟局域网失败", e);
                closeHalfOpenSession();
                Platform.runLater(() -> {
                    vpnJoinButton.setDisable(false);
                    vpnHostButton.setDisable(false);
                    vpnStatusLabel.setText("加入失败：" + e.getMessage());
                });
                setLog("加入失败：" + e.getMessage());
            } finally {
                vpnBusy.set(false);
            }
        }, "vpn-join").start();
    }

    private void stopVpn() {
        VpnSession s = vpn;
        vpn = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                logger.debug("关闭 VPN 失败: {}", e.getMessage());
            }
            setLog("虚拟局域网已断开");
        }
        stateLabel.setText("就绪");
        addressLabel.setVisible(false);
        addressLabel.setManaged(false);
        vpnStatusLabel.setText("");
        vpnHostButton.setDisable(false);
        vpnJoinButton.setDisable(false);
        vpnStopButton.setDisable(true);
        vpnInviteRow.setVisible(false);
        vpnInviteRow.setManaged(false);
    }

    /**
     * 开启/加入失败后的收尾：选中端点之后、设置状态之前抛出的异常（例如令牌签发失败）
     * 会留下一个半开着的 vpn 会话，必须关掉，否则用户重试时会被「已经连着，请先断开」挡住
     * 而界面上根本没有断开的按钮。
     */
    private void closeHalfOpenSession() {
        VpnSession s;
        synchronized (this) {
            s = vpn;
            vpn = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                logger.debug("关闭半开会话失败: {}", e.getMessage());
            }
        }
        Platform.runLater(() -> {
            vpnStopButton.setDisable(true);
            vpnInviteRow.setVisible(false);
            vpnInviteRow.setManaged(false);
        });
    }

    /** 玩家要连的端口 = 房主局域网端口 + 1（与 VpnSession.FORWARD_OFFSET 一致） */
    private static int forwardPort(int mcPort) {
        return Math.max(1, Math.min(65534, mcPort)) + VpnSession.FORWARD_OFFSET;
    }

    /** 中继的 API 密钥（部署时配置；没配置则为空） */
    private static String relayApiKey() {
        // 常见的双击/引导器启动没有 -D 参数，所以先读环境变量，再读系统属性
        String k = System.getenv("MC_RELAY_API_KEY");
        if (k == null || k.isBlank()) {
            k = System.getProperty("mclauncher.relayApiKey");
        }
        return k == null ? "" : k.trim();
    }

    /**
     * 按端点取密钥：可选 -Dmclauncher.relayApiKeys=key1,key2 与 RELAY_ENDPOINTS 一一对应，
     * 两台中继用不同密钥时不必分开配置。未配置时所有端点共用 relayApiKey()。
     */
    private static String relayApiKey(RelayEndpoint ep) {
        String list = System.getenv("MC_RELAY_API_KEYS");
        if (list == null || list.isBlank()) {
            list = System.getProperty("mclauncher.relayApiKeys");
        }
        if (list != null && !list.isBlank()) {
            String[] parts = list.split(",", -1);
            int i = RELAY_ENDPOINTS.indexOf(ep);
            if (i >= 0 && i < parts.length && !parts[i].isBlank()) {
                return parts[i].trim();
            }
        }
        return relayApiKey();
    }

    /**
     * 邀请串：<code>MCRELAY|&lt;端口&gt;#&lt;房间号&gt;#&lt;口令&gt;</code>
     *
     * 朋友在 MC 里一律连 localhost:&lt;端口&gt;（玩家的启动器会把它转进 VPN），
     * 所以这里只需要带端口，不需要房主的任何公网地址。
     */
    private static String buildInvite(int port, String room, String code) {
        return "MCRELAY|" + port + "#" + (room == null ? "" : room) + "#" + (code == null ? "" : code);
    }

    private void refreshInvite() {
        Runnable r = () -> {
            String inv = currentInvite;
            if (inv != null && vpnInviteField != null) {
                vpnInviteField.setText(inv);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** 从邀请串里取出房间号（兼容只含 #房间号 的旧形式） */
    private static String extractRoom(String invite) {
        String s = invite.startsWith("MCRELAY|") ? invite.substring("MCRELAY|".length()) : invite;
        String[] parts = s.split("#");
        if (parts.length >= 3) {
            return parts[1].trim();
        }
        if (parts.length == 2) {
            return parts[1].trim();
        }
        return s.replaceAll("^.*:", "").trim();
    }

    // ==================== 端口工具 ====================

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
                setLog("端口格式不正确：请输入 1-65535");
            }
            return false;
        }
        portField.setText(String.valueOf(p));
        savePort(config, p);
        return true;
    }

    private List<Path> gameDirs() {
        List<Path> dirs = new ArrayList<>();
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

    private List<Integer> rememberedPorts() {
        List<Integer> ports = new ArrayList<>();
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

    /** 自动检测：扫描游戏目录日志与监听端口 */
    private void detectPortAsync() {
        if (vpn != null) {
            setLog("联机进行中，不会自动改端口");
            return;
        }
        detectPortButton.setDisable(true);
        Thread t = new Thread(() -> {
            int p = detectMcPort();
            Platform.runLater(() -> {
                portField.setText(String.valueOf(p));
                detectPortButton.setDisable(false);
                setLog("检测到本地 MC 端口：" + p + (isPortListening(p) ? "" : "（该端口当前未监听）"));
            });
        }, "port-detect");
        t.setDaemon(true);
        t.start();
    }

    /** 测试本地端口能否连通 */
    private void testLocalPort() {
        Integer p = parsePort(portField.getText());
        if (p == null) {
            setLog("请先填写正确的端口");
            return;
        }
        testButton.setDisable(true);
        Thread t = new Thread(() -> {
            boolean ok = isPortListening(p);
            Platform.runLater(() -> {
                testButton.setDisable(false);
                setLog(ok ? ("端口 " + p + " 可以连接") : ("端口 " + p + " 没有响应"));
            });
        }, "port-test");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isPortListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 猜本地 MC 端口：启动日志 → server.properties → 历史端口 → 25565
     *
     * 日志优先：「对局域网开放」不改 server.properties，真正端口只出现在
     * latest.log 的「本地游戏已在端口 N 上开启」里。
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

    // ==================== 日志 / 状态 ====================

    private void setVpnStatus(String text) {
        Platform.runLater(() -> {
            if (vpnStatusLabel != null) {
                vpnStatusLabel.setText(text);
            }
        });
    }

    private void setLog(String msg) {
        if (logArea == null) {
            return;
        }
        // 很多调用来自后台线程：控件操作必须回 FX 线程
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setLog(msg));
            return;
        }
        logArea.appendText(msg + "\n");
        logArea.setScrollTop(Double.MAX_VALUE);
        if (statusConsumer != null) {
            statusConsumer.accept(msg);
        }
    }
}