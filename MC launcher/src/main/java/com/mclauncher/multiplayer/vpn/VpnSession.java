package com.mclauncher.multiplayer.vpn;

import com.mclauncher.multiplayer.vpn.VpnTunnel.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 一键开房间 / 加入房间（VPN 模式） */
public final class VpnSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VpnSession.class);

    public static final int FORWARD_OFFSET = 1;

    /**
     * 玩家侧「本地转发监听口」相对游戏端口的偏移。
     * 监听口把连接接住再经 VPN 出站。监听口不能等于 P —— 否则 accept 之后
     */
    public static final int PEER_PORT_OFFSET = 10000;

    private final WintunAdapter adapter;
    /** 当前隧道。房主侧断线后会被替换成新建的隧道，所以不能是 final */
    private volatile VpnTunnel tunnel;
    private final String role;
    private final String address;
    private volatile boolean closed;

    /** 房主：控制通道线程 */
    private Thread controlThread;
    /** 房主：控制通道心跳线程 */
    private Thread controlHeartbeat;
    /** 房主：中继地址（重连时用） */
    private volatile String relayHost;
    private volatile int relayPort;
    /** 房主：房间号与 token（重连时用；房间被回收后需要重新建房） */
    private volatile String roomId;
    private volatile String roomToken;
    private volatile String hostUuid;
    /** 房主：watchdog 正在重连中，避免多个线程同时重建 */
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 房主：连续重连失败次数（超过上限就放弃，交给用户手动重开） */
    private final java.util.concurrent.atomic.AtomicInteger reconnectFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 房主：控制通道出站请求的响应队列 */
    private final LinkedBlockingQueue<String[]> controlOutbox = new LinkedBlockingQueue<>();
    private volatile OutputStream controlOut;
    /** 当前控制通道的 socket（用于判断某个读循环是不是「过期的旧线程」） */
    private volatile Socket controlSocket;

    /** 房主：玩家要连的端口 = 局域网端口 + 1（本机 MC 自己占用局域网端口） */
    private volatile int gameForwardPort;
    /** 房主：本地玩家入口监听（玩家 MC 连进来时触发） */
    private java.net.ServerSocket forwardServer;
    private ExecutorService forwardPool;
    private final AtomicInteger forwardSeq = new AtomicInteger();

    /** 房主侧网关：玩家经 VPN 发往本机 MC 的 TCP 连接。 */
    private static final class GatewayConn {
        final String srcIp;
        final int srcPort;
        final Socket toMc;
        final java.io.OutputStream out;
        GatewayConn(String srcIp, int srcPort, Socket toMc) throws IOException {
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.toMc = toMc;
            this.out = toMc.getOutputStream();
        }
        void close() {
            try { toMc.close(); } catch (IOException ignored) { }
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, GatewayConn> gatewayConns =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 本机 MC 监听的局域网端口（玩家要连的目标） */
    private volatile int localMcPort;
    /** 虚拟地址 10.77.0.1 在房主隧道里的登记名：代表「本机 MC」这一端 */
    private static final String GATEWAY_UUID = "ncl-local-gateway";

    /** 玩家：本地端口转发监听 */
    private ServerSocket peerServer;

    private VpnSession(WintunAdapter adapter, VpnTunnel tunnel, String role, String address) {
        this.adapter = adapter;
        this.tunnel = tunnel;
        this.role = role;
        this.address = address;
    }

    public String role() { return role; }
    public String roomId() { return tunnel == null ? "" : tunnel.roomId(); }
    public String address() { return address; }
    public boolean isRunning() { return tunnel != null && tunnel.isRunning(); }
    public long bytesIn() { return tunnel == null ? 0 : tunnel.bytesIn(); }
    public long bytesOut() { return tunnel == null ? 0 : tunnel.bytesOut(); }

    // ---------------------------------------------------------------
    //  当前活动会话（供 UI / GameLauncher 查询）
    // ---------------------------------------------------------------
    private static final java.util.concurrent.atomic.AtomicReference<VpnSession> ACTIVE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** 当前是否有 VPN 会话（房主或玩家） */
    public static VpnSession active() {
        VpnSession s = ACTIVE.get();
        return (s != null && !s.closed) ? s : null;
    }

    /** 当前是否有「房主」会话 */
    public static VpnSession activeHost() {
        VpnSession s = active();
        return (s != null && "host".equals(s.role)) ? s : null;
    }

    /** 房主：游戏进程的本地转发口。 */
    public int forwardGatewayPort() {
        ServerSocket ss = forwardServer;
        if (ss != null && ss.isBound()) {
            return ss.getLocalPort();
        }
        return 0;
    }

    public static int playerPort(int localMcPort) {
        return Math.max(1, Math.min(65534, localMcPort)) + FORWARD_OFFSET;
    }

    // ---------------------------------------------------------------
    //  前置检查
    // ---------------------------------------------------------------
    public static String precheck() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return "虚拟局域网目前只支持 Windows";
        }
        if (!VpnNetworkConfig.isAdmin()) {
            return "需要以管理员身份运行启动器（创建虚拟网卡需要）";
        }
        return null;
    }

    // ---------------------------------------------------------------
    //  房主
    // ---------------------------------------------------------------
    public static VpnSession host(String relayHost, int relayPort, String roomName,
                                  int localMcPort, String apiKey) throws IOException {
        return host(relayHost, relayPort, roomName, localMcPort, apiKey, WintunAdapter.ADAPTER_NAME);
    }

    public static VpnSession host(String relayHost, int relayPort, String roomName,
                                  int localMcPort, String apiKey,
                                  String adapterName) throws IOException {
        String err = precheck();
        if (err != null) {
            throw new IOException(err);
        }
        WintunAdapter ad = WintunAdapter.openOrCreate(adapterName);
        boolean ok = false;
        VpnTunnel t = null;
        try {
            ad.startSession(0x40000);
            VpnNetworkConfig.setAddress(adapterName, VpnNetworkConfig.HOST_IP);
            VpnNetworkConfig.routeSubnetToAdapter(adapterName);

            String[] roomToken = requestRoom(relayHost, relayPort, roomName, apiKey);
            String uuid = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);

            // VPN 帧握手优先走中继的 HTTP 端口（中继会把 MCRELAY 首行分流给 VPN 处理），
            // 这样经穿透映射时只需暴露一个端口；老版本中继走不通时退回「端口 + 1」
            t = new VpnTunnel(relayHost, vpnHandshakePort(relayHost, relayPort), roomToken[0],
                    roomToken[1], Role.HOST, uuid, ad);
            t.start();

            VpnSession s = new VpnSession(ad, t, "host", VpnNetworkConfig.HOST_IP);
            s.startHostSide(relayHost, relayPort, roomToken[0], roomToken[1], apiKey,
                    Math.max(1, Math.min(65534, localMcPort)));
            ACTIVE.set(s);
            ok = true;
            logger.info("VPN 房主已开启：房间 {}，玩家连 {}:{}", roomToken[0],
                    VpnNetworkConfig.HOST_IP, playerPort(localMcPort));
            return s;
        } finally {
            if (!ok) {
                if (t != null) {
                    try { t.close(); } catch (Exception ignored) { }
                }
                try { VpnNetworkConfig.clearRoutes(); } catch (Exception ignored) { }
                try { ad.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void startHostSide(String relayHost, int relayPort, String roomId, String roomToken,
                               String apiKey, int localMcPort) {
        this.gameForwardPort = playerPort(localMcPort);
        this.localMcPort = localMcPort;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.roomId = roomId;
        this.roomToken = roomToken;
        this.hostUuid = tunnel.uuidValue();

        // 1) 本地玩家入口
        try {
            forwardServer = new ServerSocket();
            forwardServer.setReuseAddress(true);
            forwardServer.bind(new InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), localMcPort), 50);
            forwardPool = Executors.newCachedThreadPool(r -> {
                Thread th = new Thread(r, "vpn-host-entrant");
                th.setDaemon(true);
                return th;
            });
            Thread acc = new Thread(this::acceptEntrants, "vpn-host-accept");
            acc.setDaemon(true);
            acc.start();
        } catch (IOException e) {
            logger.info("房主本地玩家入口未能监听 127.0.0.1:{}（{}），需要手动生成邀请",
                    localMcPort, e.getMessage());
        }

        // 2) 控制通道
        controlThread = new Thread(() -> runHostControl(relayHost, relayPort, roomId, roomToken),
                "vpn-host-control");
        controlThread.setDaemon(true);
        controlThread.start();

        // 房主控制通道的心跳：中继每 5 秒期望一个新行，错过 3 次就回收房间。
        // 以前这里没有心跳 —— 房主不签发邀请、也没有玩家进出时，控制通道
        // 会因中继的读超时而断开，随后房间被回收；而房主界面上仍然显示
        // 「房间已开启」，邀请也一直能复制，但另一端永远找不到这个房间。
        startControlHeartbeat();

        // 2.5) 隧道把「发往 10.77.0.1」的包交给网关（按四元组写进对应玩家的本机 MC 连接）
        tunnel.setGatewaySink(this::routeGatewayPacket);
        // 隧道被中继断开时自动重建（见 reconnectHost）
        // 隧道被中继断开时自动重建（见 reconnectHost）。
        // 必须捕获「当时的那个隧道」而不是在回调里读字段：否则重连替换 tunnel 后，
        // 旧隧道延迟触发的回调会读到新隧道，恒等成立，引发额外的重建。
        final VpnTunnel initial = tunnel;
        initial.setOnLost(() -> onTunnelLost(initial));
    }

    /** 控制通道心跳：每 4 秒一个 ping，中继 3 次未收到就回收房间 */
    private void startControlHeartbeat() {
        controlHeartbeat = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    return;
                }
                if (closed) {
                    return;
                }
                java.io.OutputStream o = controlOut;
                if (o == null) {
                    continue;   // 控制通道还没建立（或刚断开），下一轮再试
                }
                try {
                    synchronized (this) {
                        o.write("{\"type\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
                        o.flush();
                    }
                } catch (IOException e) {
                    logger.debug("控制通道心跳发送失败: {}", e.getMessage());
                    controlOut = null;
                }
            }
        }, "vpn-host-heartbeat");
        controlHeartbeat.setDaemon(true);
        controlHeartbeat.start();
    }

    private void acceptEntrants() {
        while (!closed) {
            try {
                Socket local = forwardServer.accept();
                local.setTcpNoDelay(true);
                forwardPool.submit(() -> onEntrant(local));
            } catch (IOException e) {
                if (closed || forwardServer.isClosed()) {
                    return;
                }
            }
        }
    }

    /**
     * 房主自己的 MC 客户端连进局域网端口。
     * 真实生成的邀请码只在这里产生。**不要**拿这条连接去连别人：
     */
    private void onEntrant(Socket local) {
        try {
            VpnTunnel.Peer peer = requestNewPeer();
            if (peer == null) {
                local.close();
                logger.warn("申请邀请失败：控制通道不可用（房间可能正在重建）");
                return;
            }
            if (inviter != null) {
                inviter.accept(peer);
            }
            logger.info("房主侧有玩家客户端接入（来自 {}），已生成邀请 code={}，虚拟地址 {}",
                    local.getRemoteSocketAddress(), peer.code, peer.ip);
        } catch (Exception e) {
            logger.debug("玩家接入失败: {}", e.getMessage());
        } finally {
            // 这条本地连接只用于触发「开一张邀请」，真正的数据走 VPN，留着就是句柄泄漏
            closeQuietly(local);
        }
    }

    public interface PeerListener {
        void accept(VpnTunnel.Peer peer);

        /**
         * 房间已失效（控制通道重建失败，或连续失败次数用完）。
         * 默认空实现：老调用方不需要关心。
         */
        default void onRoomLost(String reason) {}
    }

    private volatile PeerListener inviter;

    public void setPeerListener(PeerListener l) {
        this.inviter = l;
    }

    public VpnTunnel.Peer requestNewPeer() throws IOException, InterruptedException {
        return requestJoinToken();
    }

    /** 房主：向中继要一枚 join token；返回 null 表示失败 */
    private VpnTunnel.Peer requestJoinToken() throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            int id = forwardSeq.incrementAndGet();
            boolean sent = false;
            synchronized (this) {
                // 锁内重新读取：控制通道重连期间 controlOut 会被替换，
                // 用锁外读到的旧流写会抛 Broken pipe
                OutputStream o = controlOut;
                if (o != null) {
                    try {
                        o.write(("{\"type\":\"vpn-token\",\"id\":" + id + "}\n")
                                .getBytes(StandardCharsets.UTF_8));
                        o.flush();
                        sent = true;
                    } catch (IOException e) {
                        logger.warn("发送邀请请求失败: {}", e.getMessage());
                    }
                }
            }
            if (!sent) {
                Thread.sleep(200);
                continue;
            }
            String key = "req:" + id;
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                String[] m = controlOutbox.poll(200, TimeUnit.MILLISECONDS);
                if (m == null) {
                    continue;
                }
                if (!key.equals(m[0])) {
                    dispatch(m);
                    continue;
                }
                if ("error".equals(m[1])) {
                    logger.warn("中继拒绝签发玩家邀请: {}", m[2]);
                    return null;
                }
                VpnTunnel.Peer peer = new VpnTunnel.Peer(
                        m.length > 4 ? m[4] : "", m[1], m[2], m[3]);
                tunnel.putPendingPeer(peer);
                return peer;
            }
        }
        return null;
    }

    private void dispatch(String[] m) {
        if ("peer".equals(m[0])) {
            tunnel.registerPeer(m[1], m[2]);
        } else if ("peer-left".equals(m[0])) {
            tunnel.unregisterPeer(m[1]);
        }
    }

    /** 房主控制通道：hello → 收发信令。 */
    private void runHostControl(String relayHost, int relayPort, String roomId, String token) {
        if (!openControl(roomId, token)) {
            if (!closed) {
                onControlLost("控制通道建立失败");
            }
        }
    }

    /**
     * 控制通道读循环（初次连接与重连共用）。
     *
     * 调用前 openControl 已经读过第一行 hello-ok；这里只处理之后的消息。
     */
    private void controlLoop(BufferedReader r, Socket c) {
        try {
            String line;
            while (!closed && (line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    com.google.gson.JsonObject m = com.google.gson.JsonParser.parseString(line)
                            .getAsJsonObject();
                    String type = m.has("type") ? m.get("type").getAsString() : "";
                    switch (type) {
                        case "vpn-token" -> controlOutbox.put(new String[]{
                                "req:" + m.get("id").getAsLong(),
                                m.get("token").getAsString(),
                                m.get("uuid").getAsString(),
                                m.get("ip").getAsString(),
                                m.has("code") ? m.get("code").getAsString() : ""});
                        case "vpn-token-error" -> controlOutbox.put(new String[]{
                                "req:" + m.get("id").getAsLong(),
                                "error", m.has("error") ? m.get("error").getAsString() : "未知错误"});
                        case "vpn-peer" -> dispatch(new String[]{"peer",
                                m.get("uuid").getAsString(), m.get("ip").getAsString()});
                        case "vpn-peer-left" -> dispatch(new String[]{"peer-left",
                                m.get("uuid").getAsString()});
                        default -> { /* pong 等 */ }
                    }
                } catch (Exception e) {
                    logger.debug("控制通道消息解析失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            if (!closed) {
                logger.warn("VPN 控制通道断开: {}", e.getMessage());
            }
        } finally {
            if (c != null) {
                try { c.close(); } catch (IOException ignored) { }
            }
            // 只有「仍然是当前通道」的读循环退出才意味着房间失联。
            // 重连时 openControl 会替换 controlSocket 并起新线程；旧线程此时
            // 可能还在收尾，不能让它把刚建好的新通道当成「又断了」。
            synchronized (this) {
                if (controlSocket != c) {
                    return;
                }
                controlSocket = null;
                controlOut = null;
            }
            // 控制通道没了，中继会在 15 秒内回收房间 —— 主动重连，
            // 而不是让界面继续显示一个已经不存在的房间
            if (!closed) {
                onControlLost("控制通道断开");
            }
        }
    }

    // ---------------------------------------------------------------
    //  断线重建（房主）
    // ---------------------------------------------------------------

    /** 控制通道断开 */
    private void onControlLost(String reason) {
        reconnectHost(reason);
    }

    /** 隧道断开（由 VpnTunnel.setOnLost 回调）。 */
    private void onTunnelLost(VpnTunnel source) {
        if (source != tunnel) {
            logger.debug("忽略旧隧道的断开回调");
            return;
        }
        reconnectHost("VPN 隧道断开");
    }

    /** 房主：控制通道或隧道断开后重建。 */
    private void reconnectHost(String reason) {
        if (closed || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        logger.info("VPN 房间失联（{}），开始重建…", reason);
        Thread t = new Thread(() -> {
            try {
                long delay = 1000;
                while (!closed) {
                    if (reconnectFailures.get() >= 5) {
                        logger.warn("VPN 房间重建连续失败 {} 次，停止重试（{}）",
                                reconnectFailures.get(), reason);
                        notifyRoomLost("房间重建失败，请重新开房");
                        return;
                    }
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (closed) {
                        return;
                    }
                    if (tryReconnectOnce()) {
                        reconnectFailures.set(0);
                        logger.info("VPN 房间 {} 已恢复", roomId);
                        return;
                    }
                    reconnectFailures.incrementAndGet();
                    delay = Math.min(delay * 2, 8000);
                }
            } finally {
                reconnecting.set(false);
            }
        }, "vpn-host-reconnect");
        t.setDaemon(true);
        t.start();
    }

    /** 尝试重建一次：先沿用原房间，失败再重新建房。成功返回 true */
    private boolean tryReconnectOnce() {
        if (openControl(roomId, roomToken)) {
            VpnTunnel cur = tunnel;
            if (cur != null && cur.isRunning()) {
                return true;
            }
            return reopenTunnel();
        }

        // 房间已不存在：重新建房
        try {
            String[] rt = requestRoom(relayHost, relayPort, "我的联机房间", null);
            roomId = rt[0];
            roomToken = rt[1];
            logger.info("原房间已失效，已重新建房：{}", roomId);
            return openControl(roomId, roomToken) && reopenTunnel();
        } catch (IOException e) {
            logger.warn("重新建房失败: {}", e.getMessage());
            return false;
        }
    }

    /** 建立（或重建）控制通道；成功返回 true。 */
    private boolean openControl(String rid, String token) {
        Socket c = null;
        try {
            c = new Socket();
            c.connect(new InetSocketAddress(relayHost, relayPort), 8000);
            c.setTcpNoDelay(true);
            OutputStream o = c.getOutputStream();
            // 房间号与 token 都是服务器生成的十六进制串，不含需要转义的字符
            o.write(("MCRELAY{\"type\":\"hello\",\"room\":\"" + rid
                    + "\",\"token\":\"" + token + "\"}\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            c.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8));
            String first = r.readLine();
            if (first == null || !first.contains("hello-ok")) {
                // 中继用一行 {"error":"房间不存在或已关闭"} 说明原因后关闭
                logger.warn("VPN 房间 {} 握手被拒: {}", rid, first);
                closeQuietly(c);
                return false;
            }
            c.setSoTimeout(0);

            synchronized (this) {
                Socket old = controlSocket;
                controlSocket = c;
                controlOut = o;
                if (old != null) {
                    try { old.close(); } catch (IOException ignored) { }
                }
            }
            controlOutbox.clear();

            Socket finalC = c;
            controlThread = new Thread(() -> controlLoop(r, finalC), "vpn-host-control");
            controlThread.setDaemon(true);
            controlThread.start();
            startControlHeartbeat();
            return true;
        } catch (IOException e) {
            logger.warn("控制通道重建失败（房间 {}）: {}", rid, e.getMessage());
            closeQuietly(c);
            return false;
        }
    }

    /** 重建隧道（沿用当前房间号与 token） */
    private boolean reopenTunnel() {
        try {
            int port = vpnHandshakePort(relayHost, relayPort);
            VpnTunnel nt = new VpnTunnel(relayHost, port, roomId, roomToken,
                    Role.HOST, hostUuid, adapter);
            nt.setGatewaySink(this::routeGatewayPacket);
            final VpnTunnel created = nt;
            nt.setOnLost(() -> onTunnelLost(created));
            nt.start();
            VpnTunnel prev = tunnel;
            tunnel = nt;
            if (prev != null && prev != nt) {
                try { prev.close(); } catch (Exception ignored) { }
            }
            logger.info("VPN 隧道已重连（房间 {}）", roomId);
            return true;
        } catch (IOException e) {
            logger.warn("VPN 隧道重连失败: {}", e.getMessage());
            return false;
        }
    }

    private void notifyRoomLost(String reason) {
        PeerListener l = inviter;
        if (l != null) {
            try {
                l.onRoomLost(reason);
            } catch (RuntimeException e) {
                logger.debug("房间失效回调异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 房主：游戏进程转发器接住一个来自 VPN 的 TCP 连接。
     * 调用方（GameLauncher）已经把本机 MC 写回来的字节回放给玩家，
     */
    public void registerGatewayConn(String srcIp, int srcPort, Socket sock) {
        if (!"host".equals(role) || sock == null || srcIp == null) {
            if (sock != null) {
                closeQuietly(sock);
            }
            return;
        }
        String key = gatewayKey(srcIp, srcPort);
        GatewayConn conn;
        try {
            conn = new GatewayConn(srcIp, srcPort, sock);
        } catch (IOException e) {
            logger.debug("网关连接登记失败: {}", e.getMessage());
            closeQuietly(sock);
            return;
        }
        GatewayConn old = gatewayConns.put(key, conn);
        if (old != null) {
            // 同一四元组重新建立：旧的那条已经没用了
            old.close();
        }
        logger.info("VPN 网关登记连接 {}:{} -> 本机 MC", srcIp, srcPort);
    }

    private static String gatewayKey(String ip, int port) {
        return ip + ":" + port;
    }

    /** 网关收到的 IP 包 → 按四元组写进对应的本机 MC 连接。 */
    private void routeGatewayPacket(byte[] ip) {
        try {
            if (ip.length < 20 || ((ip[0] >> 4) & 0x0F) != 4) {
                return;
            }
            if ((ip[9] & 0xFF) != 6) {          // 只接 TCP
                return;
            }
            int ihl = (ip[0] & 0x0f) * 4;
            int tot = ((ip[2] & 0xff) << 8) | (ip[3] & 0xff);
            int end = Math.min(tot, ip.length);
            if (ihl + 20 > end) {               // TCP 头不完整
                return;
            }
            String srcIp = (ip[12] & 0xFF) + "." + (ip[13] & 0xFF) + "."
                    + (ip[14] & 0xFF) + "." + (ip[15] & 0xFF);
            int srcPort = ((ip[ihl] & 0xFF) << 8) | (ip[ihl + 1] & 0xFF);
            int flags = ip[ihl + 13] & 0xFF;
            GatewayConn conn = gatewayConns.get(gatewayKey(srcIp, srcPort));
            if (conn == null) {
                // 新连接：真实链路里它先经过 GameLauncher 的本地转发口登记，
                // 这里只可能是还没登记完或玩家已断开 —— 丢弃，不乱写别人的流。
                logger.debug("网关收到未登记连接的数据 {}:{}，丢弃", srcIp, srcPort);
                return;
            }
            boolean fin = (flags & 0x01) != 0;
            boolean rst = (flags & 0x04) != 0;
            if (end > ihl + 20) {
                conn.out.write(ip, ihl + 20, end - ihl - 20);
                conn.out.flush();
            }
            if (fin || rst) {
                // 只关属于这个四元组的那条
                gatewayConns.remove(gatewayKey(srcIp, srcPort), conn);
                conn.close();
            }
        } catch (IOException e) {
            logger.debug("网关写本机 MC 失败，关闭该连接: {}", e.getMessage());
        } catch (RuntimeException ignored) {
            // 畸形包直接丢弃
        }
    }

    /** 关闭全部网关连接（会话结束时调用） */
    private void closeAllGatewayConns() {
        for (java.util.Iterator<java.util.Map.Entry<String, GatewayConn>> it =
                 gatewayConns.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, GatewayConn> e = it.next();
            e.getValue().close();
            it.remove();
        }
    }

    // ---------------------------------------------------------------
    //  玩家
    // ---------------------------------------------------------------

    public static VpnSession join(String relayHost, int relayPort, String roomId,
                                  String invite, int gameMcPort) throws IOException {
        return join(relayHost, relayPort, roomId, invite, "", gameMcPort, WintunAdapter.ADAPTER_NAME);
    }

    /**
     * 玩家：本地转发要在本机另绑的端口。
     * 转发监听必须换一个端口，否则会和「出站目标」撞在一起。
     */
    public static int peerLocalPort(int gameMcPort) {
        int p = Math.max(1, Math.min(65534, gameMcPort)) + PEER_PORT_OFFSET;
        if (p > 65535) {
            p = PEER_PORT_OFFSET;
        }
        return p;
    }

    public static VpnSession join(String relayHost, int relayPort, String roomId,
                                  String invite, String apiKey, int gameMcPort,
                                  String adapterName) throws IOException {
        String err = precheck();
        if (err != null) {
            throw new IOException(err);
        }
        String targetHost = VpnNetworkConfig.HOST_IP;
        int targetPort = playerPort(Math.max(1, Math.min(65534, gameMcPort)));
        String code = "";
        if (invite != null && !invite.isBlank()) {
            String s = invite.trim();
            if (s.startsWith("MCRELAY|")) {
                s = s.substring("MCRELAY|".length());
            }
            // 新版格式：端口#房间号#口令；三部分
            // 兼容旧格式：host:port#room#code —— 新代码不会再产生，但本机旧 jar 还在跑
            String[] parts = s.split("#");
            String hp = parts[0].trim();
            if (parts.length >= 3) {
                // 三段：端口#房间号#口令
                try {
                    int p = Integer.parseInt(hp);
                    if (p > 0 && p <= 65535) {
                        // 确实是「端口」，覆盖默认
                        targetPort = p;
                        code = parts[parts.length - 1].trim();
                    }
                } catch (NumberFormatException ignored) {
                    // 不是数字：按旧格式「host:port」处理
                    int colon = hp.lastIndexOf(':');
                    if (colon > 0) {
                        targetHost = hp.substring(0, colon).trim();
                        try {
                            targetPort = Integer.parseInt(hp.substring(colon + 1).trim());
                        } catch (NumberFormatException ignored2) {
                            // 保持默认
                        }
                    }
                    code = parts[parts.length - 1].trim();
                }
            } else if (parts.length == 2) {
                code = parts[parts.length - 1].trim();
            }
        }
        if (code.isEmpty()) {
            throw new IOException("邀请缺少口令：请让房主点「复制邀请」把整串发给你");
        }

        WintunAdapter ad = null;
        VpnTunnel t = null;
        boolean ok = false;
        try {
            ad = WintunAdapter.openOrCreate(adapterName);
            ad.startSession(0x40000);

            String[] own = claimPeer(relayHost, relayPort, roomId, code);
            VpnNetworkConfig.setAddress(adapterName, own[1]);
            VpnNetworkConfig.routeSubnetToAdapter(adapterName);

            t = new VpnTunnel(relayHost, vpnHandshakePort(relayHost, relayPort), roomId,
                    own[0], Role.PEER, own[2], ad);
            t.start();

            VpnSession s = new VpnSession(ad, t, "peer", targetHost);
            s.startPeerForwarding(targetHost, targetPort);
            ACTIVE.set(s);
            ok = true;
            logger.info("VPN 玩家已加入：房间 {}，本机 {}，房主 {}:{}", roomId, own[1], targetHost, targetPort);
            return s;
        } finally {
            if (!ok) {
                if (t != null) {
                    try { t.close(); } catch (Exception ignored) { }
                }
                try { VpnNetworkConfig.clearRoutes(); } catch (Exception ignored) { }
                if (ad != null) {
                    try { ad.close(); } catch (Exception ignored) { }
                }
                logger.warn("VPN 加入失败，已清理网卡与路由");
            }
        }
    }

    private void startPeerForwarding(String targetHost, int targetPort) {
        int listenPort = peerLocalPort(targetPort);
        try {
            peerServer = new ServerSocket();
            peerServer.setReuseAddress(true);
            // 只监听回环：外部扫描不到
            peerServer.bind(new InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), listenPort), 50);
            Thread acc = new Thread(() -> {
                while (!closed) {
                    java.net.Socket local = null;
                    java.net.Socket out = null;
                    try {
                        local = peerServer.accept();
                        local.setTcpNoDelay(true);
                        // 出站目标是房主的虚拟地址 + 游戏端口（= 玩家在 MC 里要填的端口），
                        // 不是本机监听口 —— 以前两者是同一个数，accept 之后又连回自己。
                        out = new java.net.Socket();
                        out.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                        out.setTcpNoDelay(true);
                        newPumpThreads(local, out);
                    } catch (IOException e) {
                        closeQuietly(local);
                        closeQuietly(out);
                        if (closed || peerServer.isClosed()) {
                            return;
                        }
                        logger.debug("玩家本地转发 accept 失败: {}", e.getMessage());
                    }
                }
            }, "vpn-peer-accept");
            acc.setDaemon(true);
            acc.start();
            logger.info("玩家已在 127.0.0.1:{} 监听，MC 里填 localhost:{}（经 VPN 连房主 {}:{}）",
                    listenPort, listenPort, targetHost, targetPort);
        } catch (IOException e) {
            logger.warn("玩家本地端口转发启动失败（可改为在 MC 里直接填 {}:{}）: {}",
                    targetHost, targetPort, e.getMessage());
        }
    }

    private static String[] claimPeer(String relayHost, int relayPort, String roomId, String code)
            throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(relayHost, relayPort), 15000);
            s.setSoTimeout(15000);
            OutputStream o = s.getOutputStream();
            com.google.gson.JsonObject req = new com.google.gson.JsonObject();
            req.addProperty("type", "vpn-claim");
            req.addProperty("room", roomId);
            req.addProperty("code", code);
            o.write(("MCRELAY" + req + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) {
                throw new IOException("中继没有响应（它可能是旧版中继，没有 VPN 帧端口）");
            }
            if (!line.trim().startsWith("{")) {
                // 跑到了 HTTP 端口上：说明中继没有把这条连接当成 VPN 握手
                throw new IOException("中继返回的不是 VPN 应答（" + preview(line)
                        + "）。常见原因：中继是旧版（端口转发），或 VPN 帧端口 "
                        + (relayPort + 1) + " 没有开放。");
            }
            com.google.gson.JsonObject resp;
            try {
                resp = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("中继应答格式不正确：" + preview(line), e);
            }
            if (resp.has("error")) {
                throw new IOException("加入失败：" + resp.get("error").getAsString());
            }
            return new String[]{resp.get("token").getAsString(),
                    resp.get("ip").getAsString(),
                    resp.get("uuid").getAsString()};
        }
    }

    /**
     * 加入失败时给出可操作的说明（界面直接显示这句话，不要抛 Java 异常类名）。
     */
    private static IOException joinError(String detail, Throwable cause) {
        return new IOException(detail, cause);
    }

    /** 截取一段文本用于错误提示（去掉换行，最多 120 字符） */    private static String preview(String s) {
        if (s == null) {
            return "(空)";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }

    // ---------------------------------------------------------------
    //  数据泵
    // ---------------------------------------------------------------
    private static void pump(Socket from, Socket to) {
        byte[] buf = new byte[32 * 1024];
        try {
            java.io.InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            try { to.shutdownOutput(); } catch (IOException ignored) { }
        } catch (IOException ignored) {
            // 任一端断开
        }
    }

    private static void newPumpThreads(Socket aSock, Socket bSock) {
        Thread a = new Thread(() -> {
            pump(aSock, bSock);
            closeQuietly(aSock);
            closeQuietly(bSock);
        }, "vpn-pump-a");
        Thread b = new Thread(() -> {
            pump(bSock, aSock);
            closeQuietly(aSock);
            closeQuietly(bSock);
        }, "vpn-pump-b");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    /** 选出 VPN 帧握手要连的端口。 */
    private static int vpnHandshakePort(String host, int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), 5000);
            probe.setTcpNoDelay(true);
            // 必须用无副作用的探测类型。以前这里直接发 vpn-host / vpn-join：
            // 中继会真的接管那条连接 —— 房主侧会把真正的 VPN 主机顶掉
            // （旧连接的 defer 随后把房间回收），玩家侧会消费掉 join token
            // 导致真正握手「token 无效」。探测完还立即断开，等于每次开房都
            // 在中继上制造一次断开事件。
            String line = "MCRELAY{\"type\":\"" + VpnTunnel.PROBE_TYPE + "\"}\n";
            probe.getOutputStream().write(line.getBytes(StandardCharsets.UTF_8));
            probe.getOutputStream().flush();
            probe.setSoTimeout(3000);
            java.io.InputStream in = probe.getInputStream();
            int first = in.read();
            if (first == '{') {
                logger.info("中继 {}:{} 的 HTTP 端口接受 VPN 握手", host, port);
                return port;
            }
        } catch (IOException e) {
            logger.debug("检测中继 HTTP 端口的 VPN 分流失败（{}:{}）：{}", host, port, e.getMessage());
        }
        logger.info("中继 {}:{} 不支持在 HTTP 端口上做 VPN 握手，退回端口 {}", host, port, port + 1);
        return port + 1;
    }

    // ---------------------------------------------------------------
    private static String[] requestRoom(String host, int port, String name, String apiKey)
            throws IOException {
        String url = "http://" + host + ":" + port + "/api/host";
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (apiKey != null && !apiKey.isBlank()) {
                c.setRequestProperty("X-Api-Key", apiKey);
            }
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("name", name);
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (var o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            java.io.InputStream bodyStream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = bodyStream == null ? "" : new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
            if (code != 200) {
                // 中继没回响应体时（例如穿透层直接断开），rc 里是空串，不要抛 NPE
                String hint = resp.isBlank() ? "（无响应内容）" : resp;
                if (code == 401 || code == 403) {
                    hint += " —— API_KEY 与中继不一致（用 -Dmclauncher.relayApiKey=... 指定中继的密钥）";
                }
                throw new IOException("申请房间失败 HTTP " + code + ": " + hint);
            }
            if (resp.isBlank()) {
                throw new IOException("中继返回了空响应（HTTP 200 但没有内容），请检查中继地址 "
                        + host + ":" + port + " 是否正确、端口是否映射到中继的 8080");
            }
            com.google.gson.JsonObject o;
            try {
                o = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("中继返回了无法解析的内容（可能不是本启动器的联机中继）："
                        + preview(resp), e);
            }
            // 旧版（端口转发）中继会返回 port 字段：它没有 VPN 帧端口，连不上
            if (o.has("port")) {
                throw new IOException("当前联机中继仍是旧版（端口转发），"
                        + "本启动器只支持虚拟局域网，请先把中继升级到新版：\n" + host + ":" + port);
            }
            if (!o.has("room") || !o.has("token")) {
                throw new IOException("中继应答缺少 room/token：" + preview(resp));
            }
            return new String[]{o.get("room").getAsString(), o.get("token").getAsString()};
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------
    @Override
    public void close() {
        closed = true;
        closeAllGatewayConns();
        ACTIVE.compareAndSet(this, null);
        try {
            if (forwardServer != null) {
                forwardServer.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (peerServer != null) {
                peerServer.close();
            }
        } catch (IOException ignored) {
        }
        if (forwardPool != null) {
            forwardPool.shutdownNow();
        }
        if (controlThread != null) {
            controlThread.interrupt();
        }
        if (controlHeartbeat != null) {
            controlHeartbeat.interrupt();
        }
        // 重连线程在 sleep 中检查 closed；interrupt 让它在退避期间也能立刻退出
        synchronized (this) {
            if (controlSocket != null) {
                try { controlSocket.close(); } catch (IOException ignored) { }
                controlSocket = null;
            }
            controlOut = null;
        }
        try {
            if (tunnel != null) {
                tunnel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            VpnNetworkConfig.clearRoutes();
        } catch (Exception ignored) {
        }
        try {
            if (adapter != null) {
                adapter.close();
            }
        } catch (Exception ignored) {
        }
    }
}