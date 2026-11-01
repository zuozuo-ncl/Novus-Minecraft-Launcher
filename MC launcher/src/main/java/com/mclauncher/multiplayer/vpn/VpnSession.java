package com.mclauncher.multiplayer.vpn;

import com.mclauncher.multiplayer.vpn.VpnTunnel.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;

/**
 * 一键开房间 / 加入房间（VPN 模式）
 *
 * <p>房主：
 * <ol>
 *   <li>{@link #host()} 创建虚拟网卡、配 10.77.0.1、加路由</li>
 *   <li>向中继申请房间（复用 RelayServers.request）</li>
 *   <li>建立一条 vpn-host 长连接，把网卡上的包按目标 IP 转发给对应玩家</li>
 * </ol>
 *
 * <p>玩家：
 * <ol>
 *   <li>{@link #join(String, String)} 创建虚拟网卡、配 10.77.0.N、加路由</li>
 *   <li>建立一条 vpn-join 长连接</li>
 * </ol>
 *
 * <p>之后 MC 里直接填 房主的虚拟 IP（10.77.0.1:端口）即可。
 */
public final class VpnSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VpnSession.class);

    /** 玩家端虚拟 IP 从 .2 开始分配，最多 8 个 */
    private static final int PEER_IP_MIN = 2;
    private static final int PEER_IP_MAX = 9;

    private final WintunAdapter adapter;
    private final VpnTunnel tunnel;
    private final String role;
    private final String address;   // 房主：公网可达地址；玩家：房主虚拟 IP

    private VpnSession(WintunAdapter adapter, VpnTunnel tunnel, String role, String address) {
        this.adapter = adapter;
        this.tunnel = tunnel;
        this.role = role;
        this.address = address;
    }

    public String role() { return role; }
    /** 房间号（房主开房后才有意义） */
    public String roomId() { return tunnel == null ? "" : tunnel.roomId(); }
    /** 房主：别人该连的地址（10.77.0.1）；玩家：房主地址 */
    public String address() { return address; }
    public boolean isRunning() { return tunnel != null && tunnel.isRunning(); }
    public long bytesIn() { return tunnel == null ? 0 : tunnel.bytesIn(); }
    public long bytesOut() { return tunnel == null ? 0 : tunnel.bytesOut(); }

    // ---------------------------------------------------------------
    //  前置检查
    // ---------------------------------------------------------------
    /** 当前环境能否开 VPN（Windows + 管理员） */
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
    /**
     * 开一个 VPN 房间。
     *
     * @param relayHost 中继地址
     * @param relayPort 中继 HTTP 端口
     * @param roomName  房间名（会显示在中继日志里）
     */
    public static VpnSession host(String relayHost, int relayPort, String roomName) throws IOException {
        return host(relayHost, relayPort, roomName, WintunAdapter.ADAPTER_NAME);
    }

    /** 指定网卡名（同机多实例测试用） */
    public static VpnSession host(String relayHost, int relayPort, String roomName,
                                  String adapterName) throws IOException {
        String err = precheck();
        if (err != null) {
            throw new IOException(err);
        }
        WintunAdapter ad = WintunAdapter.openOrCreate(adapterName);
        ad.startSession(0x40000);
        VpnNetworkConfig.setAddress(adapterName, VpnNetworkConfig.HOST_IP);
        VpnNetworkConfig.routeSubnetToAdapter(adapterName);

        // 向中继申请房间：复用现有 HTTP 接口
        String[] roomToken = requestRoom(relayHost, relayPort, roomName);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        VpnTunnel t = new VpnTunnel(relayHost, relayPort, roomToken[0], roomToken[1],
                Role.HOST, uuid, ad);

        // 房主按「目标虚拟 IP」把包分给对应玩家。这里先一次性登记 8 个可能的位置，
        // 玩家连上后中继会带 uuid，宿主端再按实际 uuid 精确登记（见 registerPeer）。
        for (int i = PEER_IP_MIN; i <= PEER_IP_MAX; i++) {
            t.registerPeer("peer-" + i, "10.77.0." + i);
        }

        t.start();
        logger.info("VPN 房主已开启：房间 {}，别人连 {}:端口", roomToken[0], VpnNetworkConfig.HOST_IP);
        return new VpnSession(ad, t, "host", VpnNetworkConfig.HOST_IP);
    }

    // ---------------------------------------------------------------
    //  玩家
    // ---------------------------------------------------------------
    /**
     * 加入房间。
     *
     * @param roomId    房主给的四位房间号
     * @param seat      第几个玩家（1..8），决定分到的虚拟 IP
     */
    public static VpnSession join(String relayHost, int relayPort, String roomId, int seat)
            throws IOException {
        return join(relayHost, relayPort, roomId, seat, WintunAdapter.ADAPTER_NAME);
    }

    /** 指定网卡名（同机多实例测试用） */
    public static VpnSession join(String relayHost, int relayPort, String roomId, int seat,
                                  String adapterName) throws IOException {
        String err = precheck();
        if (err != null) {
            throw new IOException(err);
        }
        int n = Math.max(PEER_IP_MIN, Math.min(PEER_IP_MAX, PEER_IP_MIN + seat - 1));
        String myIp = "10.77.0." + n;

        WintunAdapter ad = null;
        VpnTunnel t = null;
        boolean ok = false;
        try {
            ad = WintunAdapter.openOrCreate(adapterName);
            ad.startSession(0x40000);
            VpnNetworkConfig.setAddress(adapterName, myIp);
            VpnNetworkConfig.routeSubnetToAdapter(adapterName);

            // uuid 必须唯一：同一房间里两个玩家都用 "peer-1" 的话，
            // 房主端会把他们的包混在一起。
            String uuid = "peer-" + n + "-" + randomId();
            t = new VpnTunnel(relayHost, relayPort, roomId, "", Role.PEER, uuid, ad);
            t.start();
            ok = true;
            logger.info("VPN 玩家已加入：房间 {}，本机 {}", roomId, myIp);
            return new VpnSession(ad, t, "peer", VpnNetworkConfig.HOST_IP);
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

    // ---------------------------------------------------------------
    /** 8 位随机十六进制，用于区分不同实例的玩家与房主 */
    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 调中继的 /api/host 申请房间，返回 {roomId, token} */
    private static String[] requestRoom(String host, int port, String name) throws IOException {
        String url = "http://" + host + ":" + port + "/api/host";
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String body = "{\"name\":\"" + name.replace("\"", "") + "\"}";
            byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (var o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            String resp = new String(c.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (code != 200) {
                throw new IOException("申请房间失败 HTTP " + code + ": " + resp);
            }
            var o = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
            return new String[]{o.get("room").getAsString(), o.get("token").getAsString()};
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------
    @Override
    public void close() {
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