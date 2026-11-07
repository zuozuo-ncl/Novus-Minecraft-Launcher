package com.mclauncher.test.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** 联机中继的端到端测试（需要先启动 mc-relay-go）。 */
public final class RelayE2E {

    private static int port;
    private static int passed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        port = Integer.parseInt(args[0]);

        run("vpn-probe 返回 pong", () -> {
            String[] rt = createRoom();
            String resp = sendLine("MCRELAY{\"type\":\"vpn-probe\"}\n");
            eq("{\"type\":\"pong\"}", resp.trim(), "probe 应答");

            // 探测之后房间与控制通道照常可用（探测没有副作用）
            Control c = new Control(rt[0], rt[1]);
            ok(c.open(), "探测后控制通道仍可建立");
            c.close();
        });

        run("断线后中继立即回收房间", () -> {
            String[] rt = createRoom();
            Control c = new Control(rt[0], rt[1]);
            ok(c.open(), "初始控制通道建立");
            eq(1, rooms(), "房间数");
            c.close();
            Thread.sleep(300);
            eq(0, rooms(), "断开后房间数");
            Control stale = new Control(rt[0], rt[1]);
            ok(!stale.open(), "原房间号 hello 被拒（触发房主重新建房）");
        });

        run("心跳保活 25 秒", () -> {
            String[] rt = createRoom();
            Control c = new Control(rt[0], rt[1]);
            ok(c.open(), "控制通道建立");
            c.pingLoop(25000);
            eq(1, rooms(), "25 秒后房间仍在");
            c.close();
        });

        if (failures.isEmpty()) {
            System.out.println("\n全部通过：" + passed + " 项");
            System.exit(0);
        }
        System.out.println("\n通过 " + passed + "，失败 " + failures.size());
        failures.forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }

    // ---------------- 用例驱动 ----------------

    interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("  FAIL " + name + " -> " + t);
        }
    }

    private static void ok(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object expect, Object actual, String what) {
        if (!String.valueOf(expect).equals(String.valueOf(actual))) {
            throw new AssertionError(what + "：期望 " + expect + "，实际 " + actual);
        }
    }

    // ---------------- 中继操作 ----------------

    private static int rooms() throws IOException {
        String h = new String(new URL("http://127.0.0.1:" + port + "/health")
                .openStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(h).getAsJsonObject();
        return o.get("rooms").getAsInt();
    }

    private static String[] createRoom() throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/api/host").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.getOutputStream().write("{\"name\":\"e2e\"}".getBytes(StandardCharsets.UTF_8));
        String r = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new String[]{
                r.replaceAll(".*\"room\":\"([^\"]+)\".*", "$1"),
                r.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1")};
    }

    private static String sendLine(String line) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(line.getBytes(StandardCharsets.UTF_8));
            String l = new BufferedReader(new InputStreamReader(s.getInputStream(),
                    StandardCharsets.UTF_8)).readLine();
            return l == null ? "" : l;
        }
    }

    /** 与 VpnSession.openControl 相同的握手 */
    private static final class Control {
        private final String room;
        private final String token;
        private Socket s;

        Control(String room, String token) {
            this.room = room;
            this.token = token;
        }

        boolean open() throws IOException {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            s.getOutputStream().write(("MCRELAY{\"type\":\"hello\",\"room\":\"" + room
                    + "\",\"token\":\"" + token + "\"}\n").getBytes(StandardCharsets.UTF_8));
            s.setSoTimeout(3000);
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));
            String first = r.readLine();
            if (first == null || !first.contains("hello-ok")) {
                s.close();
                return false;
            }
            s.setSoTimeout(0);
            return true;
        }

        /** 与 VpnSession.startControlHeartbeat 相同：每 4 秒一个 ping */
        void pingLoop(long ms) throws Exception {
            long end = System.currentTimeMillis() + ms;
            s.setSoTimeout(2000);
            InputStream in = s.getInputStream();
            while (System.currentTimeMillis() < end) {
                s.getOutputStream().write("{\"type\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                try {
                    in.read(new byte[256]);
                } catch (SocketTimeoutException ignored) {
                    // pong 还没到，继续
                }
                Thread.sleep(4000);
            }
        }

        void close() throws IOException {
            s.close();
        }
    }

    private RelayE2E() {}
}