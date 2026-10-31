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

public class RelayTunnel {

    private static final Logger logger = LoggerFactory.getLogger(RelayTunnel.class);

    public static final String MAGIC = "MCRELAY";

    public enum State { IDLE, CONNECTING, ONLINE, CLOSED }

    public interface Listener {
        void onState(State state, String message);

        void onAddress(String host, int port);

        void onPlayers(int count);

        default void onJoinFailed(int id, String from, String reason) {}
    }

    private final RelayServers.Server server;

    private volatile String relayHost;
    private final int relayHttpPort;

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

    public interface JoinApprover {

        boolean approve(int id, String remoteAddress);
    }

    private volatile JoinApprover joinApprover;

    private static final long RELAY_CONFIRM_WINDOW_MS = 25_000;
    private final java.util.Map<Integer, Long> pendingSince = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, String> pendingFrom = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.Set<Socket> dataSockets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    public void setLocalPort(int port) {
        if (port > 0 && port <= 65535) {
            this.localPort = port;
        }
    }

    public int getLocalPort() {
        return localPort;
    }

    public int getPlayers() {
        return players.get();
    }

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

    public void stop() {
        running = false;
        closeQuietly(control);

        for (Socket s : dataSockets) {
            closeQuietly(s);
        }
        dataSockets.clear();
        Thread t = controlThread;
        if (t != null) {
            t.interrupt();
        }

        roomId = null;
        token = null;
        setState(State.CLOSED, "已断开联机");
        connecting.set(false);
    }

    private void runLoop() {
        while (running) {
            try {
                if (roomId == null) {
                    requestRoom();
                }
                connectControl();

                

                
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

                roomId = null;
            }
        }
    }

    private void requestRoom() throws IOException {
        setState(State.CONNECTING, "正在向「" + server.name + "」申请房间...");

        RelayServers.Room r = RelayServers.request(server, roomName);
        roomId = r.roomId;
        token = r.token;
        publicPort = r.port;

        relayHost = r.endpoint.host;

        logger.info("联机房间已创建: {}:{} (room={}, server={})",
                relayHost, publicPort, roomId, server.name);
        if (listener != null) {
            listener.onAddress(relayHost, publicPort);
        }
    }

    private void connectControl() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(relayHost, publicPort), 15000);
        s.setTcpNoDelay(true);
        control = s;

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

                        return;
                    }
                    logger.info("新玩家接入 #{}（{}）", id, from);
                    openDataChannel(id);
                }
                case "pong", "hello" -> {  }
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
                    Thread.sleep(10000);
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

    private static boolean defaultApprove(String from) {
        if (from == null) {
            return false;
        }
        String a = from.startsWith("::ffff:") ? from.substring(7) : from;
        return a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")
                || a.startsWith("127.") || a.startsWith("10.")
                || a.startsWith("192.168.") || a.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

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

                logger.info("玩家 #{}（{}）等待确认超过 {} 秒，中继已断开其连接，跳过",
                        id, from, RELAY_CONFIRM_WINDOW_MS / 1000);
                if (listener != null) listener.onJoinFailed(id, from, "timeout");
                return;
            }

            

            

            

            
            local.connect(new InetSocketAddress(localHost, targetPort), 8000);
            local.setTcpNoDelay(true);

            remote.connect(new InetSocketAddress(relayHost, publicPort), 15000);
            remote.setTcpNoDelay(true);

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

            

            

            
            Thread up = pump(local, remote, "relay-up-" + id);
            Thread down = pump(remote, local, "relay-down-" + id);

            Thread cleanup = new Thread(() -> {
                joinQuietly(up);
                joinQuietly(down);
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
                logger.info("玩家 #{} 数据通道已关闭", id);
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

                logger.warn("玩家 #{} 数据通道失败（本地端口 {}）: {}", id, targetPort, e.getMessage());
            } else {

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

    private Thread pump(Socket fromSock, Socket toSock, String name) {
        InputStream from;
        OutputStream to;
        try {
            from = fromSock.getInputStream();
            to = toSock.getOutputStream();
        } catch (IOException e) {
            return null;
        }
        Runnable body = () -> {
            byte[] buf = new byte[16 * 1024];
            try {
                int n;
                while ((n = from.read(buf)) >= 0) {
                    to.write(buf, 0, n);
                    to.flush();
                }

                try {
                    toSock.shutdownOutput();
                } catch (IOException ignored) {

                }
            } catch (IOException ignored) {

                closeQuietly(fromSock);
                closeQuietly(toSock);
            }
        };
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

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

            }
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join();
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

    public static boolean isLocalPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
