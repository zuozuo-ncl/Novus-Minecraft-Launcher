package com.mclauncher.multiplayer.vpn;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VPN 隧道的客户端（房主与玩家共用同一套帧协议）
 *
 * <h3>帧格式（与小端 u32 长度前缀，与 mc-relay/vpn-tunnel.js 一致）</h3>
 * <pre>
 *   房主 → 服务器：  u32 len | u8 uuidLen | uuid | ipPacket
 *   服务器 → 房主：  u32 len | u8 uuidLen | uuid | ipPacket
 *   玩家 ↔ 服务器：  u32 len | ipPacket
 * </pre>
 *
 * <p>房主按「目标 IP」决定这个包发给哪个玩家；玩家只管收发，不关心 uuid。
 */
public final class VpnTunnel implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VpnTunnel.class);
    private static final String MAGIC = "MCRELAY";
    private static final int MAX_FRAME = 64 * 1024;
    private static final int UUID_MAX = 40;

    public enum Role { HOST, PEER }

    private final String relayHost;
    private final int relayPort;
    private final String roomId;
    private final String token;
    private final Role role;
    private final String uuid;
    private final WintunAdapter adapter;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong rx = new AtomicLong();
    private final AtomicLong tx = new AtomicLong();
    private volatile Socket sock;
    private volatile OutputStream out;

    /** 房主侧：虚拟 IP -> 玩家 uuid */
    private final ConcurrentHashMap<String, String> ipToUuid = new ConcurrentHashMap<>();
    /** 房主侧：已登记的玩家 uuid（收到未知 uuid 的帧直接丢弃） */
    private final java.util.Set<String> knownUuids =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public VpnTunnel(String relayHost, int relayPort, String roomId, String token,
                     Role role, String uuid, WintunAdapter adapter) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.roomId = roomId;
        this.token = token;
        this.role = role;
        this.uuid = uuid;
        this.adapter = adapter;
    }

    /** 房主登记：某个玩家分到的虚拟 IP */
    public void registerPeer(String peerUuid, String virtualIp) {
        ipToUuid.put(virtualIp, peerUuid);
        knownUuids.add(peerUuid);
        logger.info("登记玩家 {} -> {}", peerUuid, virtualIp);
    }

    /** 房间号 */
    public String roomId() { return roomId; }

    public long bytesIn() { return rx.get(); }
    public long bytesOut() { return tx.get(); }
    public boolean isRunning() { return running.get(); }

    // ---------------------------------------------------------------
    //  启动
    // ---------------------------------------------------------------
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(relayHost, relayPort), 15000);
        s.setTcpNoDelay(true);
        this.sock = s;

        JsonObject hello = new JsonObject();
        hello.addProperty("type", role == Role.HOST ? "vpn-host" : "vpn-join");
        hello.addProperty("room", roomId);
        hello.addProperty("uuid", uuid);
        if (role == Role.HOST) {
            hello.addProperty("token", token);
        }
        OutputStream o = s.getOutputStream();
        o.write((MAGIC + hello + "\n").getBytes(StandardCharsets.UTF_8));
        o.flush();
        this.out = o;

        Thread recv = new Thread(this::receiveLoop, "vpn-recv");
        recv.setDaemon(true);
        recv.start();

        Thread send = new Thread(this::sendLoop, "vpn-send");
        send.setDaemon(true);
        send.start();

        logger.info("VPN 隧道已连接 {}:{} 房间={} 角色={}", relayHost, relayPort, roomId, role);
    }

    // ---------------------------------------------------------------
    //  收：中继 -> 本地网卡
    // ---------------------------------------------------------------
    private void receiveLoop() {
        try (InputStream in = sock.getInputStream()) {
            byte[] lenBuf = new byte[4];
            while (running.get()) {
                readFully(in, lenBuf);
                int len = (lenBuf[0] & 0xFF) | ((lenBuf[1] & 0xFF) << 8)
                        | ((lenBuf[2] & 0xFF) << 16) | ((lenBuf[3] & 0xFF) << 24);
                if (len <= 0 || len > MAX_FRAME) {
                    logger.warn("VPN 收到非法帧长度 {}", len);
                    break;
                }
                byte[] frame = new byte[len];
                readFully(in, frame);
                rx.addAndGet(len);

                byte[] ip;
                if (role == Role.HOST) {
                    // 跳过 uuid 前缀
                    int ul = frame[0] & 0xFF;
                    if (ul <= 0 || ul > UUID_MAX || frame.length < 1 + ul) {
                        continue;
                    }
                    String from = new String(frame, 1, ul, StandardCharsets.UTF_8);
                    if (!knownUuids.contains(from)) {
                        // 未知玩家：可能是旧连接残留，直接丢弃而不是写进网卡
                        logger.debug("丢弃未知来源的 VPN 帧: {}", from);
                        continue;
                    }
                    ip = Arrays.copyOfRange(frame, 1 + ul, frame.length);
                } else {
                    ip = frame;
                }
                adapter.send(ip);
            }
        } catch (Exception e) {
            if (running.get()) {
                logger.warn("VPN 接收线程结束: {}", e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    // ---------------------------------------------------------------
    //  发：本地网卡 -> 中继
    // ---------------------------------------------------------------
    private void sendLoop() {
        try {
            byte[] lenBuf = new byte[4];
            int idle = 0;
            while (running.get()) {
                byte[] ip = adapter.receive();
                if (ip == null) {
                    // 网卡暂时没包：短暂让步，避免空转吃满 CPU
                    if (++idle >= 4) {
                        Thread.sleep(1);
                        idle = 0;
                    }
                    continue;
                }
                idle = 0;

                byte[] payload;
                if (role == Role.HOST) {
                    String peer = uuidForPacket(ip);
                    if (peer == null) {
                        // 目标 IP 不属于任何已登记的玩家（广播 / 未知）：丢弃
                        continue;
                    }
                    byte[] ub = peer.getBytes(StandardCharsets.UTF_8);
                    payload = new byte[1 + ub.length + ip.length];
                    payload[0] = (byte) ub.length;
                    System.arraycopy(ub, 0, payload, 1, ub.length);
                    System.arraycopy(ip, 0, payload, 1 + ub.length, ip.length);
                } else {
                    payload = ip;
                }

                int n = payload.length;
                lenBuf[0] = (byte) (n & 0xFF);
                lenBuf[1] = (byte) ((n >> 8) & 0xFF);
                lenBuf[2] = (byte) ((n >> 16) & 0xFF);
                lenBuf[3] = (byte) ((n >> 24) & 0xFF);
                OutputStream o = out;
                if (o == null) {
                    break;
                }
                synchronized (this) {
                    o.write(lenBuf);
                    o.write(payload);
                    o.flush();
                }
                tx.addAndGet(payload.length);
            }
        } catch (Exception e) {
            if (running.get()) {
                logger.warn("VPN 发送线程结束: {}", e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    /**
     * 从 Wintun 包的目标地址反查玩家 uuid。
     *
     * <b>Wintun 的包不是裸 IP 报文</b>：前面有 4 字节合成帧头，
     * 随后才是 IPv4 头（原版 WireGuard 的 wg_packet 结构）。所以
     * IP 头起点是 offset 4，源/目的地址分别在 +12 / +16。
     */
    private String uuidForPacket(byte[] p) {
        int off = ipHeaderOffset(p);
        if (off < 0) {
            return null;
        }
        String dst = (p[off + 16] & 0xFF) + "." + (p[off + 17] & 0xFF) + "."
                + (p[off + 18] & 0xFF) + "." + (p[off + 19] & 0xFF);
        return ipToUuid.get(dst);
    }

    /**
     * 找 IPv4 头的起点。
     *
     * Wintun 交给我们的缓冲区前面有 4 字节合成帧头（含版本/协议/长度），
     * 但不同驱动版本的布局略有差异。这里按「哪个位置能读出一个合理的
     * version=4 + IHL>=5」来判定，找不到返回 -1。
     */
    private static int ipHeaderOffset(byte[] p) {
        for (int off = 0; off <= 8 && off + 20 <= p.length; off++) {
            int ver = (p[off] >> 4) & 0x0F;
            int ihl = p[off] & 0x0F;
            if (ver == 4 && ihl >= 5 && off + ihl * 4 <= p.length) {
                return off;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------
    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("连接已关闭");
            }
            off += n;
        }
    }

    private void shutdown() {
        running.set(false);
        closeQuietly(sock);
    }

    @Override
    public void close() {
        shutdown();
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}