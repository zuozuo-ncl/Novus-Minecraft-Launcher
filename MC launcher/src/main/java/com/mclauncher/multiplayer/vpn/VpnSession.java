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

/**
 * 一键开房间 / 加入房间（VPN 模式）
 *
 * <h3>房主</h3>
 * <ol>
 *   <li>申请房间 → 建 VPN 长连接（vpn-host）→ 开控制通道（hello）</li>
 *   <li>本机 MC 的局域网端口为 P。房主自己的 MC 客户端连进来时，
 *       启动器为这位玩家申请 join token + 虚拟 IP，把邀请码发给房主界面。</li>
 *   <li>朋友经 VPN 连 10.77.0.N:P+1 → 包到房主 → 房主转发器配对并交付给 MC。</li>
 * </ol>
 *
 * <h3>玩家</h3>
 * <ol>
 *   <li>粘贴邀请 → 用口令向中继换取 token/uuid/IP</li>
 *   <li>配虚拟网卡 10.77.0.N → 建 VPN 长连接（vpn-join + token）</li>
 *   <li>在 127.0.0.1:P+1 监听：本机 MC 每条连接经 VPN 转到房主</li>
 * </ol>
 *
 * <p>虚拟地址与 uuid 都由<b>服务器</b>分配，客户端不再自报、不再固定 seat。
 */
public final class VpnSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VpnSession.class);

    public static final int FORWARD_OFFSET = 1;

    private final WintunAdapter adapter;
    private final VpnTunnel tunnel;
    private final String role;
    private final String address;
    private volatile boolean closed;

    /** 房主：控制通道线程 */
    private Thread controlThread;
    /** 房主：控制通道出站请求的响应队列 */
    private final LinkedBlockingQueue<String[]> controlOutbox = new LinkedBlockingQueue<>();
    private volatile OutputStream controlOut;

    /** 房主：玩家要连的端口 = 局域网端口 + 1（本机 MC 自己占用局域网端口） */
    private volatile int gameForwardPort;
    /** 房主：本地玩家入口监听（玩家 MC 连进来时触发） */
    private java.net.ServerSocket forwardServer;
    private ExecutorService forwardPool;
    private final AtomicInteger forwardSeq = new AtomicInteger();

    /**
     * 房主侧网关：玩家经 VPN 发往本机 MC 的 TCP 连接。
     *
     * 以前是「一个全局 pending 队列 + 一个全局 socket 队列」，按到达顺序配对，
     * 结果多位玩家同时在线时 A 的数据会被写进 B 的 MC 连接。现在严格按四元组寻址：
     *
     *   key = 源虚拟IP:源端口  （玩家 MC 发起连接时的本地端口）
     *
     * 同一个玩家的新连接到来时才建立一条到 127.0.0.1:mcPort 的新连接并登记 key；
     * 之后的字节只写回它自己那条。FIN 只结束属于自己的那条。
     */
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

    /**
     * 房主：游戏进程的本地转发口。
     *
     * 优先返回执行了 bind 的实际端口（局域网端口被占用时，启动器改绑系统分配端口，
     * 真正的端口在这里暴露出来，由 GameLauncher 使用）。
     */
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

            t = new VpnTunnel(relayHost, relayPort, roomToken[0], roomToken[1], Role.HOST, uuid, ad);
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

        // 2.5) 隧道把「发往 10.77.0.1」的包交给网关（按四元组写进对应玩家的本机 MC 连接）
        tunnel.setGatewaySink(this::routeGatewayPacket);
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
     *
     * 真实生成的邀请码只在这里产生。**不要**拿这条连接去连别人：
     * 它是要加入某个已有房间的房主 MC 客户端，服务它的是该房间的 peer 列表。
     */
    private void onEntrant(Socket local) {
        try {
            VpnTunnel.Peer peer = requestNewPeer();
            if (peer == null) {
                local.close();
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
            OutputStream o = controlOut;
            if (o == null) {
                Thread.sleep(200);
                continue;
            }
            int id = forwardSeq.incrementAndGet();
            synchronized (this) {
                o.write(("{\"type\":\"vpn-token\",\"id\":" + id + "}\n")
                        .getBytes(StandardCharsets.UTF_8));
                o.flush();
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

    /** 房主控制通道：hello → 收发信令 */
    private void runHostControl(String relayHost, int relayPort, String roomId, String token) {
        Socket c = null;
        try {
            c = new Socket();
            c.connect(new InetSocketAddress(relayHost, relayPort), 15000);
            c.setTcpNoDelay(true);
            OutputStream o = c.getOutputStream();
            o.write(("MCRELAY{\"type\":\"hello\",\"room\":\"" + roomId
                    + "\",\"token\":\"" + token + "\"}\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            controlOut = o;

            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getInputStream(), StandardCharsets.UTF_8));
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
            controlOut = null;
            if (c != null) {
                try { c.close(); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * 房主：游戏进程转发器接住一个来自 VPN 的 TCP 连接。
     *
     * 调用方（GameLauncher）已经把本机 MC 写回来的字节回放给玩家，
     * 并把这条连到本机 MC 的 socket 交过来。这里按「来源虚拟 IP + 源端口」
     * 登记，之后该玩家发往 10.77.0.1 的字节只写进这一条。
     *
     * @param srcIp   玩家虚拟 IP（10.77.0.N）
     * @param srcPort 玩家 MC 发起连接时使用的本地端口
     * @param sock    已连到本机 MC 的 socket
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

    /**
     * 网关收到的 IP 包 → 按四元组写进对应的本机 MC 连接。
     *
     * 只处理 TCP（ip[9] == 6）。之前不看协议号，玩家的 UDP 探测包会被当成
     * TCP 流写进 MC，直接把连接写坏。
     */
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

            t = new VpnTunnel(relayHost, relayPort, roomId, own[0], Role.PEER, own[2], ad);
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
        try {
            peerServer = new ServerSocket();
            peerServer.setReuseAddress(true);
            peerServer.bind(new InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), targetPort), 50);
            Thread acc = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket local = peerServer.accept();
                        local.setTcpNoDelay(true);
                        Socket out = new Socket();
                        out.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                        out.setTcpNoDelay(true);
                        newPumpThreads(local, out);
                    } catch (IOException e) {
                        if (closed) {
                            return;
                        }
                        logger.debug("玩家本地转发 accept 失败: {}", e.getMessage());
                    }
                }
            }, "vpn-peer-accept");
            acc.setDaemon(true);
            acc.start();
            logger.info("玩家已在 127.0.0.1:{} 监听，MC 里填 localhost:{}", targetPort, targetPort);
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
            String resp = new String((code >= 400 ? c.getErrorStream() : c.getInputStream()).readAllBytes(),
                    StandardCharsets.UTF_8);
            if (code != 200) {
                throw new IOException("申请房间失败 HTTP " + code + ": " + resp);
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