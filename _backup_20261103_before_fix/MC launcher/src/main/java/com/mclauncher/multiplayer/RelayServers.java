package com.mclauncher.multiplayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 联机中转服务器
 *
 * 界面只列「服务器」，不列具体地址 —— 一台服务器可以配多个入口端口，
 * 启动器自动挑一个能用的。
 *
 * <h3>两种接入方式</h3>
 * <ul>
 *   <li><b>HTTP</b>：直接暴露端口，用 JSON 申请房间</li>
 *   <li><b>二进制</b>：纯 TCP，没有 HTTP 关键字。前面挂 WAF 的环境只能用它
 *       （实测：发 {@code GET /hub} 会被 WAF 回 403）</li>
 * </ul>
 * AUTO 模式先试二进制，失败再退回 HTTP。
 */
public final class RelayServers {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(RelayServers.class);

    /** 接入方式 */
    public enum Mode { HTTP, BINARY, AUTO }

    /** 一个入口地址 */
    public static final class Endpoint {
        public final String host;
        public final int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /**
     * 一台中转服务器
     *
     * 界面只展示 name；endpoints 是它可用的入口，用户看不到。
     */
    public static final class Server {
        public final String id;
        public final String name;
        public final List<Endpoint> endpoints = new ArrayList<>();
        public final Mode mode;

        /** 最近一次测得的延迟（毫秒）；-1 表示未测/不可用 */
        private volatile int latencyMs = -1;

        public Server(String id, String name, Mode mode) {
            this.id = id;
            this.name = name;
            this.mode = mode;
        }

        public Server add(String host, int port) {
            endpoints.add(new Endpoint(host, port));
            return this;
        }

        public Endpoint first() {
            return endpoints.isEmpty() ? null : endpoints.get(0);
        }

        public int getLatencyMs() {
            return latencyMs;
        }

        void setLatencyMs(int ms) {
            latencyMs = ms;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 内置服务器列表 —— 只列「服务器」，不列地址
     */
    public static final Server[] ALL = {
            new Server("node1", "节点1", Mode.AUTO)
                    .add("39.106.60.15", 8080),
    };

    private RelayServers() {}

    public static Server byId(String id) {
        for (Server s : ALL) {
            if (s.id.equals(id)) return s;
        }
        return ALL[0];
    }

    // ==================== 申请房间 ====================

    /**
     * 申请一个房间
     *
     * 依次尝试该服务器下的每个入口，第一个成功的就用它。
     */
    public static Room request(Server server, String roomName) throws IOException {
        IOException last = null;
        for (Endpoint ep : server.endpoints) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Room r = requestEndpoint(ep, server.mode, roomName);
                    logger.info("申请房间成功: {} -> {}:{}", server.name, ep.host, r.port);
                    return r;
                } catch (IOException e) {
                    last = e;
                    logger.debug("入口 {} 申请失败({}): {}", ep, attempt + 1, e.getMessage());
                }
            }
        }
        throw last != null ? last : new IOException("该服务器没有可用入口");
    }

    private static Room requestEndpoint(Endpoint ep, Mode mode, String roomName) throws IOException {
        switch (mode) {
            case BINARY:
                return requestBinary(ep, roomName);
            case HTTP:
                return requestHttp(ep, roomName);
            default:
                // AUTO：WAF 环境下 HTTP 必被拦，二进制是通用方式；
                // 只认 HTTP 的旧服务端会回 400，这时再回退。
                try {
                    return requestBinary(ep, roomName);
                } catch (IOException e) {
                    logger.debug("二进制申请失败（{}），回退 HTTP", ep);
                    return requestHttp(ep, roomName);
                }
        }
    }

    /** 申请结果 */
    public static final class Room {
        public final String roomId;
        public final String token;
        public final int port;
        /** 实际使用的入口（控制通道也从这里连） */
        public final Endpoint endpoint;

        Room(String roomId, String token, int port, Endpoint endpoint) {
            this.roomId = roomId;
            this.token = token;
            this.port = port;
            this.endpoint = endpoint;
        }
    }

    // ==================== 延迟测试 ====================

    /**
     * 测试所有服务器的延迟（并行，最多等 3 秒）
     *
     * 结果写进各 Server 的 latencyMs。
     */
    public static void measureLatencyAsync(Runnable onDone) {
        Thread t = new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, ALL.length));
            try {
                List<Future<Integer>> fs = new ArrayList<>();
                for (Server s : ALL) {
                    fs.add(pool.submit(() -> measureOne(s)));
                }
                for (int i = 0; i < ALL.length; i++) {
                    try {
                        ALL[i].setLatencyMs(fs.get(i).get(3500, TimeUnit.MILLISECONDS));
                    } catch (Exception e) {
                        ALL[i].setLatencyMs(-1);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            if (onDone != null) onDone.run();
        }, "relay-latency");
        t.setDaemon(true);
        t.start();
    }

    /** 测一台服务器：连它的第一个入口，算三次握手耗时 */
    private static int measureOne(Server s) {
        Endpoint ep = s.first();
        if (ep == null) {
            return -1;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            try (Socket sock = new Socket()) {
                long t0 = System.nanoTime();
                sock.connect(new InetSocketAddress(ep.host, ep.port), 2500);
                int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
                best = Math.min(best, ms);
            } catch (IOException e) {
                // 这一轮失败，继续
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    // ==================== 二进制 ====================

    private static Room requestBinary(Endpoint ep, String roomName) throws IOException {
        byte[] name = roomName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 60) {
            name = java.util.Arrays.copyOf(name, 60);
        }

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ep.host, ep.port), 10000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);

            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.write("MCRQ".getBytes(StandardCharsets.US_ASCII));
            out.writeShort(name.length);
            out.write(name);
            out.flush();

            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] magic = new byte[4];
            in.readFully(magic);
            String m = new String(magic, StandardCharsets.US_ASCII);

            if ("MCRO".equals(m)) {
                byte[] room = new byte[8];
                byte[] token = new byte[32];
                in.readFully(room);
                in.readFully(token);
                int port = in.readInt();
                return new Room(new String(room, StandardCharsets.US_ASCII).trim(),
                        new String(token, StandardCharsets.US_ASCII).trim(), port, ep);
            }
            if ("MCRE".equals(m)) {
                int len = in.readUnsignedShort();
                byte[] msg = new byte[len];
                in.readFully(msg);
                throw new IOException(new String(msg, StandardCharsets.UTF_8));
            }
            throw new IOException("无法识别的响应: " + m);
        }
    }

    // ==================== HTTP ====================

    private static Room requestHttp(Endpoint ep, String roomName) throws IOException {
        String url = "http://" + ep.host + ":" + ep.port + "/api/host";
        String json = "{\"name\":\"" + escapeJson(roomName) + "\",\"maxPlayers\":8}";

        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (var os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            var is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            return new Room(o.get("room").getAsString(),
                    o.get("token").getAsString(),
                    o.get("port").getAsInt(), ep);
        } finally {
            c.disconnect();
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}