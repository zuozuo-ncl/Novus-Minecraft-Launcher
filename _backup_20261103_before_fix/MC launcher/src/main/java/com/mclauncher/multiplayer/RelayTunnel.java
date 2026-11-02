package com.mclauncher.multiplayer;

import com.mclauncher.i18n.L;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 联机隧道 —— 房主端
 *
 * 让**没有公网 IP** 的玩家也能开服给别人玩。
 *
 * <h3>原理</h3>
 * NAT 只拦入站、不拦出站，所以由本机主动连到中转服务器：
 * <pre>
 *   本机 MC:25565 ← 数据通道 ← 中转 ← 朋友
 *                 控制通道 ↗
 * </pre>
 *
 * <h3>两条连接</h3>
 * <ul>
 *   <li><b>控制通道</b>：长期保持，用来保活并接收「有新玩家」通知</li>
 *   <li><b>数据通道</b>：每个朋友一条，纯字节流转发到本地 MC</li>
 * </ul>
 * 两条都以魔数 {@code MCRELAY} 开头（同一行内紧跟 JSON），
 * 中转据此把它们和玩家连接区分开。
 *
 * <h3>线程模型</h3>
 * 控制通道一个线程（读信令 + 定时心跳）；
 * 每个数据通道两个线程（上行/下行各一）。玩家最多几人，线程数可控。
 */
public class RelayTunnel {

    private static final Logger logger = LoggerFactory.getLogger(RelayTunnel.class);

    /** 与中转服务器的协议魔数 */
    public static final String MAGIC = "MCRELAY";

    /** 隧道状态 */
    public enum State { IDLE, CONNECTING, ONLINE, CLOSED }

    /** 状态回调（都在后台线程调用） */
    public interface Listener {
        void onState(State state, String message);
        /** 拿到公网地址时回调：host:port */
        void onAddress(String host, int port);
        /** 在线人数变化 */
        void onPlayers(int count);

        /**
         * 房主已同意，但没能把这个玩家接到本地游戏。
         *
         * @param reason "local"   = 本地 MC 连不上（没开服 / 端口不对）
         *               "timeout" = 确认得太晚，中继已断开玩家的等待连接
         */
        default void onJoinFailed(int id, String from, String reason) {}
    }

    private final RelayServers.Server server;
    /** 实际使用的入口主机（申请房间后确定，可能与列表里的第一个不同） */
    private volatile String relayHost;
    private final int relayHttpPort;
    /** 本地 MC 端口：允许中途改（玩家在房间开着时重新「对局域网开放」会换端口） */
    private volatile int localPort;
    private final String localHost;
    private final String roomName;
    private final Listener listener;

    private volatile State state = State.IDLE;
    private volatile boolean running = false;

    private String roomId;
    private String token;
    private int publicPort;

    private Socket control;
    private Thread controlThread;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger players = new AtomicInteger();

    /**
     * 房主侧「批准新玩家」回调（在后台线程调用，可以阻塞几秒）。
     *
     * 中转的房间号会出现在公开列表里，端口也能被猜到：不加这一层，
     * 任何人都能连上房间端口直接接管玩家的数据通道。所以新玩家接入时
     * 必须由房主确认（UI 弹框 / 自动放行名单）才会建立数据通道。
     */
    public interface JoinApprover {
        /** @return true 允许接入 */
        boolean approve(int id, String remoteAddress);
    }

    private volatile JoinApprover joinApprover;

    /**
     * 收到「有人申请加入」的时刻（玩家 id -> 毫秒时间戳）。
     *
     * 中继对等待中的玩家有 30 秒超时：超时会直接断掉玩家的连接，房主这边
     * 再批准也找不到对应玩家（数据通道被中继丢弃）。房主超过 25 秒才点
     * 「允许」时，这里直接告诉用户「确认得太晚，请让对方重新加入」，
     * 而不是建立一条注定断掉的通道。
     */
    private static final long RELAY_CONFIRM_WINDOW_MS = 25_000;
    private final java.util.Map<Integer, Long> pendingSince = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, String> pendingFrom = new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前已建立的数据通道 socket，stop() 时要一并关闭，否则玩家会挂在半死连接上 */
    private final java.util.Set<Socket> dataSockets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();


    /** 用默认服务器构造 */
    public RelayTunnel(String relayHost, int relayHttpPort,
                       String localHost, int localPort,
                       String roomName, Listener listener) {
        this(RelayServers.ALL[0], localHost, localPort, roomName, listener);
        this.relayHost = relayHost;
    }

    public RelayTunnel(RelayServers.Server server,
                       String localHost, int localPort,
                       String roomName, Listener listener) {
        this.server = server;
        this.relayHost = server.first() != null ? server.first().host : "";
        this.relayHttpPort = server.first() != null ? server.first().port : 0;
        this.localHost = localHost;
        this.localPort = localPort;
        this.roomName = roomName;
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public String getPublicAddress() {
        return publicPort > 0 ? relayHost + ":" + publicPort : null;
    }

    /**
     * 修改本地 MC 端口，对后续新玩家立即生效（已在线的连接不受影响）。
     *
     * 每次变化都会记一条日志。端口曾经被界面上的监听器悄悄改掉过
     * （用户在框里填 55555，隧道实际连的却是自动检测出来的另一个值），
     * 有了这条日志就能直接看出是谁、什么时候改的。
     */
    public void setLocalPort(int port) {
        if (port > 0 && port <= 65535) {
            int old = this.localPort;
            this.localPort = port;
            if (old != port) {
                logger.info("本地 MC 端口变更: {} -> {}", old, port);
            }
        }
    }

    public int getLocalPort() {
        return localPort;
    }

    public int getPlayers() {
        return players.get();
    }

    // ==================== 生命周期 ====================

    /**
     * 开始联机
     *
     * 会申请房间并建立控制通道；成功后通过 listener 通知公网地址。
     */
    public void start() {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        running = true;
        controlThread = new Thread(this::runLoop, "relay-tunnel");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    public void setJoinApprover(JoinApprover approver) {
        this.joinApprover = approver;
    }

    /** 停止联机（房间会被中转回收） */
    public void stop() {
        running = false;
        closeQuietly(control);
        // 数据通道是独立 socket，不关掉的话玩家会一直挂着
        for (Socket s : dataSockets) {
            closeQuietly(s);
        }
        dataSockets.clear();
        Thread t = controlThread;
        if (t != null) {
            t.interrupt();
        }
        // 下次 start() 必须重新申请房间：旧房间会被中继在控制通道断开时回收
        roomId = null;
        token = null;
        setState(State.CLOSED, "已断开联机");
        connecting.set(false);
    }

    // ==================== 主循环 ====================

    private void runLoop() {
        while (running) {
            try {
                if (roomId == null) {
                    requestRoom();
                }
                connectControl();
                // connectControl 正常返回说明控制通道已断开。
                // 中继在控制通道断开时会立刻回收整个房间，旧的 roomId/token
                // 已经失效；继续拿它重连只会被中继反复踢掉，永远恢复不了。
                // 所以这里必须清掉房间号，下一轮重新申请。
                if (!running) break;
                roomId = null;
                token = null;
                setState(State.CONNECTING, "控制通道断开，5 秒后重新申请房间...");
                sleepQuietly(5000);
            } catch (Exception e) {
                if (!running) break;
                logger.warn("联机隧道异常: {}", e.getMessage());
                setState(State.CONNECTING, "连接中转失败：" + e.getMessage());
                sleepQuietly(5000);
                // 房间可能已失效，下次重新申请
                roomId = null;
            }
        }
    }

    /** ① 申请房间 */
    private void requestRoom() throws IOException {
        setState(State.CONNECTING, "正在向「" + server.name + "」申请房间...");

        RelayServers.Room r = RelayServers.request(server, roomName);
        roomId = r.roomId;
        token = r.token;
        publicPort = r.port;
        // 用实际成功的那个入口做控制/数据通道
        relayHost = r.endpoint.host;

        logger.info("联机房间已创建: {}:{} (room={}, server={})",
                relayHost, publicPort, roomId, server.name);
        if (listener != null) {
            listener.onAddress(relayHost, publicPort);
        }
    }

    /** ② 建立控制通道并维持 */
    private void connectControl() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(relayHost, publicPort), 15000);
        s.setTcpNoDelay(true);
        control = s;

        // 握手：魔数 + JSON 放在同一行
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("room", roomId);
        hello.addProperty("token", token);
        writeLine(s, MAGIC + hello);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while (running && (line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            handleSignal(line);
        }
        closeQuietly(s);
        if (state == State.ONLINE) {
            players.set(0);
            if (listener != null) listener.onPlayers(0);
        }
    }

    private void handleSignal(String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";
            switch (type) {
                case "hello-ok" -> {
                    setState(State.ONLINE, "联机已开启");
                    logger.info("控制通道就绪，公网端口 {}", publicPort);
                    startHeartbeat();
                }
                case "join" -> {
                    int id = msg.get("id").getAsInt();
                    String from = msg.has("from") ? msg.get("from").getAsString() : "未知地址";
                    pendingSince.put(id, System.currentTimeMillis());
                    pendingFrom.put(id, from);
                    JoinApprover approver = joinApprover;
                    boolean allow;
                    try {
                        allow = approver != null ? approver.approve(id, from) : defaultApprove(from);
                    } catch (Exception e) {
                        logger.warn("玩家 #{} 接入确认异常，默认拒绝: {}", id, e.getMessage());
                        allow = false;
                    }
                    if (!allow) {
                        logger.info("已拒绝玩家 #{}（{}）", id, from);
                        pendingSince.remove(id);
                        pendingFrom.remove(id);
                        // 不建数据通道：中转会在 PENDING_TIMEOUT 后回收该连接
                        return;
                    }
                    logger.info("新玩家接入 #{}（{}）", id, from);
                    openDataChannel(id);
                }
                case "pong", "hello" -> { /* 忽略 */ }
                default -> logger.debug("未知信令: {}", type);
            }
        } catch (Exception e) {
            logger.debug("解析信令失败: {} ({})", line, e.getMessage());
        }
    }

    private void startHeartbeat() {
        Thread hb = new Thread(() -> {
            Socket s = control;
            while (running && s != null && !s.isClosed()) {
                try {
                    // 5 秒一次：不少家用路由器/运营商 NAT 的 UDP/TCP 空闲表项
                    // 只有 30~60 秒，10 秒间隔在部分网络下会被提前掐断
                    Thread.sleep(5000);
                    if (s.isClosed()) break;
                    writeLine(s, "{\"type\":\"ping\"}");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    break;
                }
            }
        }, "relay-heartbeat");
        hb.setDaemon(true);
        hb.start();
    }

    /**
     * 默认接入策略：只放行本机 / 内网地址。
     *
     * 公网部署请用 setJoinApprover 接一个「新玩家接入」确认框或白名单，
     * 否则房间端口对任何知道端口的人都是敞开的。
     */
    private static boolean defaultApprove(String from) {
        if (from == null) {
            return false;
        }
        String a = from.startsWith("::ffff:") ? from.substring(7) : from;
        return a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")
                || a.startsWith("127.") || a.startsWith("10.")
                || a.startsWith("192.168.") || a.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** ③ 为某个玩家开数据通道（连接与握手在控制线程上完成，转发立即交给独立线程） */
    private void openDataChannel(int id) {
        final int targetPort = localPort;
        final String from = pendingFrom.remove(id);
        final Long askedAt = pendingSince.remove(id);
        final String who = from != null ? from : "未知地址";
        Socket remote = new Socket();
        Socket local = new Socket();
        boolean established = false;
        try {
            if (askedAt != null && System.currentTimeMillis() - askedAt > RELAY_CONFIRM_WINDOW_MS) {
                // 中继那边 30 秒就断开等待中的玩家，这边再建通道也是死路
                logger.info("玩家 #{}（{}）等待确认超过 {} 秒，中继已断开其连接，跳过",
                        id, from, RELAY_CONFIRM_WINDOW_MS / 1000);
                if (listener != null) listener.onJoinFailed(id, from, "timeout");
                return;
            }

            // 顺序：先连本地 MC，再建数据通道，然后直接双向盲转发。
            //
            // 不在 Java 端「读首段数据」：中继对玩家 socket 做了 pause()，
            // 数据一直停在内核缓冲区里，数据通道一建好就会推过来，不需要
            // 房主这边先拔一块出来。多一次阻塞读只会让本地 MC 晚连上，
            // 没有任何收益。
            //
            // 先连本地 MC 的好处：本地连不上（没开服 / 端口填错）时直接放弃，
            // 不建立一条注定断掉的数据通道。
            // 探测本地 MC 是否真的在跑、是什么版本。
            // 用一个独立连接走 status：临时连一下、问完就关，不影响下面真正建立的数据通道。
            // 这样日志里能直接看到「本地 MC 到底是 1.20.4 还是根本没开」，
            // 不用再靠猜。
            try {
                String ver = LocalPortScanner.probeVersion(targetPort);
                if (ver == null) {
                    logger.warn("玩家 #{}：本地 {}:{} 没有响应 Minecraft status（不是 MC 服务端？）",
                            id, localHost, targetPort);
                } else {
                    logger.info("玩家 #{}：本地 MC 就绪，版本 {}", id, ver.isEmpty() ? "?" : ver);
                }
            } catch (Exception ignored) { }

            local.connect(new InetSocketAddress(localHost, targetPort), 8000);
            local.setTcpNoDelay(true);
            // 保活：Windows 默认 2 小时才探测，这里配合下面的心跳作为主保活手段
            try { local.setKeepAlive(true); } catch (Exception ignored) { }

            remote.connect(new InetSocketAddress(relayHost, publicPort), 15000);
            remote.setTcpNoDelay(true);
            try { remote.setKeepAlive(true); } catch (Exception ignored) { }

            JsonObject data = new JsonObject();
            data.addProperty("type", "data");
            data.addProperty("room", roomId);
            data.addProperty("token", token);
            data.addProperty("id", id);
            writeLine(remote, MAGIC + data);

            dataSockets.add(local);
            dataSockets.add(remote);
            established = true;

            players.incrementAndGet();
            if (listener != null) listener.onPlayers(players.get());
            logger.info("玩家 #{} 已接入本地 {}:{}", id, localHost, targetPort);

            // 两个方向各开一个线程，控制线程立即返回继续读控制通道。
            //
            // 之前 remote -> local 这一泵是**在控制线程上同步跑**的：第一个玩家
            // 在线期间控制线程被永久堵死，后面的 "join" 根本没机会被读取，
            // 只能等中继 30 秒超时；心跳的 pong 也读不到。数据通道与
            // 控制通道是两条不同的 TCP 连接，各读各的不会互相抢数据。
            final long openedAt = System.currentTimeMillis();
            Thread up = pump(local, remote, "relay-up-" + id);
            Thread down = pump(remote, local, "relay-down-" + id);

            // 两个方向都结束后再统一清理：关 socket、减在线人数
            Thread cleanup = new Thread(() -> {
                joinQuietly(up, 15000);
                joinQuietly(down, 15000);
                dataSockets.remove(local);
                dataSockets.remove(remote);
                closeQuietly(local);
                closeQuietly(remote);
                int now = players.decrementAndGet();
                if (now < 0) {
                    players.set(0);
                    now = 0;
                }
                if (listener != null) listener.onPlayers(now);
                logger.info("玩家 #{} 数据通道已关闭（存活 {} ms）",
                        id, System.currentTimeMillis() - openedAt);
            }, "relay-cleanup-" + id);
            cleanup.setDaemon(true);
            cleanup.start();
        } catch (SocketTimeoutException e) {
            if (!local.isConnected()) {
                logger.warn("玩家 #{} 连接超时（本地 MC 开了吗？端口 {}）", id, targetPort);
                if (listener != null) listener.onJoinFailed(id, who, "local");
            } else {
                logger.warn("玩家 #{} 连接中继超时: {}", id, e.getMessage());
            }
        } catch (Exception e) {
            if (local.isConnected()) {
                // 本地已连上，是中继侧出的问题
                logger.warn("玩家 #{} 数据通道失败（本地端口 {}）: {}", id, targetPort, e.getMessage());
            } else {
                // 本地 MC 没连上：多半是游戏还没开「对局域网开放」/ 端口不对
                logger.warn("玩家 #{} 连不上本地游戏（端口 {}）：{}", id, targetPort, e.getMessage());
                if (listener != null) listener.onJoinFailed(id, who, "local");
            }
        } finally {
            if (!established) {
                closeQuietly(local);
                closeQuietly(remote);
            }
        }
    }

    /**
     * 单向搬运字节（套接字版本，转发两个 Socket 之间的一个方向）。
     */
    private Thread pump(Socket fromSock, Socket toSock, String name) {
        try {
            return pump(fromSock.getInputStream(), toSock.getOutputStream(), name, fromSock, toSock);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 通用转发：把 in 读到的字节原样写入 out。
     *
     * 设计要点（都是踩过坑的，别改回去）：
     * <ul>
     *   <li><b>纯透传</b>：不解析、不拆包、不合并，MC 的包边界由它自己处理；</li>
     *   <li><b>EOF 不做半关闭</b>：以前读到 EOF 会 shutdownOutput()，但在 MC
     *       服务端先发 FIN 的场景下，客户端会以为「服务端不再发送数据」，
     *       直接报「无效会话」。现在只结束本方向，另一端结束时由 cleanup 关闭；</li>
     *   <li><b>只有真实 IO 错误才关两端</b>：否则另一条泵会永久阻塞在 read 上。</li>
     * </ul>
     *
     * @param fromSock 仅用于异常时关闭；可为 null
     * @param toSock   仅用于异常时关闭；可为 null
     */
    private Thread pump(InputStream in, OutputStream out, String name,
                        Socket fromSock, Socket toSock) {
        Runnable body = () -> {
            // 32 KB：单次读写吞吐与内存的折中。
            // 不做 flush()：SocketOutputStream 自身无缓冲，flush 是空操作。
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            long startedAt = System.currentTimeMillis();
            String reason;
            // 前 64 字节的十六进制：用于核对 MC 协议包是否正确透传。
            // 前几个包的长度都很短（握手 20 字节、Login Start 10 字节、
            // Login Success 几十字节），这一段足以看出包类型与长度是否符合预期。
            StringBuilder head = new StringBuilder();
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (head.length() < 128) {
                        for (int i = 0; i < n && head.length() < 128; i++) {
                            head.append(String.format("%02x", buf[i]));
                        }
                    }
                    out.write(buf, 0, n);
                    total += n;
                }
                // 读到 EOF：**不要** shutdownOutput()。
                //
                // 以前这里会对 toSock 做半关闭，理由是「一端说完了好让另一端收尾」。
                // 但 MC 的登录/游戏阶段里，服务端可能先发 FIN 再等客户端收尾，
                // 此时把客户端方向也半关闭，客户端会认为「服务端不再发送数据」
                // 而直接报「无效会话」。这里只结束本方向的转发，
                // 另一端什么时候结束由它自己的 read 决定，最终由 cleanup 统一关闭。
                reason = "对端关闭(EOF)";
            } catch (IOException e) {
                // 真实的 IO 错误（Connection reset / Broken pipe）：两端都关，
                // 否则另一条泵线程会一直阻塞在 read 上
                reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                closeQuietly(in);
                closeQuietly(out);
                closeQuietly(fromSock);
                closeQuietly(toSock);
            }
            logger.info("[{}] 结束：{}，转发 {} 字节，耗时 {} ms，首段 {}",
                    name, reason, total, System.currentTimeMillis() - startedAt, head);
        };
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ==================== 工具 ====================

    private void setState(State s, String msg) {
        state = s;
        if (listener != null) {
            listener.onState(s, msg);
        }
    }

    private static void writeLine(Socket s, String line) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /** 等线程结束，最多 maxMs（防止清理线程永远挂着） */
    private static void joinQuietly(Thread t, long maxMs) {
        if (t == null) return;
        try {
            t.join(maxMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检查本地端口上有没有东西在监听（MC 是否已开服）
     */
    public static boolean isLocalPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}