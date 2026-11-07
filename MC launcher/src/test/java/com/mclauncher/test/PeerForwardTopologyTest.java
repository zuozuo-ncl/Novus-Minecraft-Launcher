package com.mclauncher.test;

import com.mclauncher.multiplayer.vpn.VpnSession;

/**
 * 玩家侧本地转发的拓扑测试。
 * 历史上监听口和出站目标是同一个端口（accept 之后连回自己），
 */
public class PeerForwardTopologyTest {

    public static void main(String[] args) {
        System.out.println("—— 玩家本地转发拓扑 ——");

        Checks.run("监听口 != 游戏端口", () -> {
            int gamePort = VpnSession.playerPort(53211);            // 房主局域网端口 + 1
            int listen = VpnSession.peerLocalPort(gamePort);
            Checks.assertTrue(listen != gamePort,
                    "监听口(" + listen + ") 不能等于游戏端口(" + gamePort + ")");
        });

        Checks.run("端口溢出被夹住", () -> {
            Checks.assertTrue(VpnSession.peerLocalPort(65535) <= 65535, "不超过 65535");
            Checks.assertTrue(VpnSession.peerLocalPort(1) > 0, "不小于 1");
        });

        Checks.run("accept 后转发到目标", () -> {
            java.net.ServerSocket target = new java.net.ServerSocket(
                    0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            java.net.ServerSocket srv = new java.net.ServerSocket(
                    0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            try {
                // 目标端：收到连接就回 "HELLO"
                Thread tgt = new Thread(() -> {
                    try (java.net.Socket s = target.accept()) {
                        s.getOutputStream().write("HELLO".getBytes());
                    } catch (java.io.IOException ignored) {
                    }
                });
                tgt.setDaemon(true);
                tgt.start();

                // 转发端：与 startPeerForwarding 相同的拓扑
                Thread fwd = new Thread(() -> {
                    try (java.net.Socket local = srv.accept(); java.net.Socket out = new java.net.Socket()) {
                        out.connect(new java.net.InetSocketAddress("127.0.0.1", target.getLocalPort()));
                        out.getInputStream().transferTo(local.getOutputStream());
                    } catch (java.io.IOException ignored) {
                    }
                });
                fwd.setDaemon(true);
                fwd.start();

                try (java.net.Socket c = new java.net.Socket()) {
                    c.connect(new java.net.InetSocketAddress("127.0.0.1", srv.getLocalPort()), 2000);
                    c.setSoTimeout(3000);
                    byte[] buf = new byte[5];
                    new java.io.DataInputStream(c.getInputStream()).readFully(buf);
                    Checks.assertEquals("HELLO", new String(buf), "收到的数据");
                }
            } finally {
                srv.close();
                target.close();
            }
        });
    }
}