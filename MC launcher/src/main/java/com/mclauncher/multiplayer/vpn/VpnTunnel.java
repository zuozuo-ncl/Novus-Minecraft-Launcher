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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VPN 隧道的客户端（房主与玩家共用同一套帧协议）
 *
 * <h3>帧格式（小端 u32 长度前缀，与 mc-relay/vpn-room.js 一致）</h3>
 * <pre>
 *   房主 → 服务器：  u32 len | u8 uuidLen | uuid | ipPacket
 *   服务器 → 房主：  u32 len | u8 uuidLen | uuid | ipPacket
 *   玩家 ↔ 服务器：  u32 len | ipPacket
 * </pre>
 *
 * <p><b>ipPacket 是裸 IPv4 报文</b>（首字节 0x4X）。Wintun 的接收缓冲区和发送
 * 缓冲区都是完整 IP 包，**没有**任何额外前缀 —— 早期代码按「4 字节合成帧头」
 * 猜测 IP 头偏移，导致包被发到错误目的地址。
 *
 * <p>帧内长度上限 64KB（IPv4 报文本身最大 65535，服务器按此校验）；
 * 单个以太网 MTU 之内的包直接整包发送，超过 MTU 的发到网卡时驱动会自动分片。
 *
 * <p>房主按「目标 IP」决定这个包发给哪个玩家；玩家只管收发，不关心 uuid。
 */
public final class VpnTunnel implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(VpnTunnel.class);
    private static final String MAGIC = "MCRELAY";
    /** 整套 IP 包 + uuid + 长度前缀的帧上限；IPv4 报文最大 65535 */
    private static final int MAX_FRAME = 65535 + 64;
    private static final int UUID_MAX = 40;
    /** 发送队列：网卡 → 网络 方向的积压上限，超过说明网络跟不上，丢包比 OOM 好 */
    private static final int TX_QUEUE_MAX = 512;

    public enum Role { HOST, PEER }

    /** 房主分配的虚拟网段（与 VpnNetworkConfig 一致） */
    private static final String SUBNET_PREFIX = "10.77.0.";

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
    /** 房主侧：目的地是 10.77.0.1（本机 MC）的包交给它，而不写回 Wintun */
    private volatile java.util.function.Consumer<byte[]> gatewaySink;
    private volatile boolean stopping;

    /** 房主侧：虚拟 IP -> 玩家 uuid */
    private final ConcurrentHashMap<String, String> ipToUuid = new ConcurrentHashMap<>();
    /** 房主侧：已登记的玩家 uuid（收到未知 uuid 的帧直接丢弃） */
    private final java.util.Set<String> knownUuids =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 房主侧：新玩家的接入口令。
     *
     * 玩家第一次为本机 MC 建立连接时，房主为他向中继申请 join token 与
     * 虚拟 IP（10.77.0.N），玩家连上 10.77.0.1:端口 时就带着这个口令加入。
     * 不再有「所有玩家都抢 seat=1 → 都是 10.77.0.2」的问题。
     */
    private final ConcurrentHashMap<String, Peer> pendingPeers = new ConcurrentHashMap<>();

    /** 一个待接入的玩家（房主侧） */
    public static final class Peer {
        public final String code;
        public final String token;
        public final String uuid;
        public final String ip;
        public final long createdAt = System.currentTimeMillis();

        Peer(String code, String token, String uuid, String ip) {
            this.code = code;
            this.token = token;
            this.uuid = uuid;
            this.ip = ip;
        }
    }

    /** 发送队列的元素：一个 IP 包（玩家为裸包，房主为「uuid 前缀 + 包」） */
    private final LinkedBlockingQueue<byte[]> txQueue = new LinkedBlockingQueue<>(TX_QUEUE_MAX);

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

    /** 房主登记：某个玩家分到的虚拟 IP（中继通过控制通道下发后调用） */
    public void registerPeer(String peerUuid, String virtualIp) {
        if (peerUuid == null || virtualIp == null) {
            return;
        }
        // 同一 UUID 换了 IP 时先清掉旧映射
        ipToUuid.values().removeIf(peerUuid::equals);
        ipToUuid.put(virtualIp, peerUuid);
        knownUuids.add(peerUuid);
        logger.info("登记玩家 {} -> {}", peerUuid, virtualIp);
    }

    public void unregisterPeer(String peerUuid) {
        knownUuids.remove(peerUuid);
        ipToUuid.values().removeIf(peerUuid::equals);
    }

    /** 房主记住一个待接入玩家的口令 -> 玩家地址 */
    void putPendingPeer(Peer p) {
        pendingPeers.put(p.code, p);
        // 5 分钟没接入就忘掉，避免无限增长
        pendingPeers.entrySet().removeIf(e ->
                System.currentTimeMillis() - e.getValue().createdAt > 5 * 60_000L);
    }

    public Peer takePendingPeer(String code) {
        if (code == null) {
            return null;
        }
        return pendingPeers.get(code);   // 口令可以重连多次？不允许：加入成功后由调用方 remove
    }

    public void forgetPeer(String code) {
        if (code != null) {
            pendingPeers.remove(code);
        }
    }

    /** 房间号 */
    public String roomId() { return roomId; }

    public long bytesIn() { return rx.get(); }
    public long bytesOut() { return tx.get(); }
    public boolean isRunning() { return running.get(); }
    public String uuid() { return uuid; }

    public void setGatewaySink(java.util.function.Consumer<byte[]> sink) {
        this.gatewaySink = sink;
    }

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
        } else {
            hello.addProperty("token", token);   // 玩家的 token 由房主申请
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

    /** 往发送队列里放一个包；队列满直接丢弃（UDP 语义，丢包比 OOM 好） */
    private void enqueue(byte[] payload) {
        if (!txQueue.offer(payload)) {
            logger.debug("VPN 发送队列已满，丢弃一个包（{} 字节）", payload.length);
        }
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
                    if (ul <= 0 || ul > UUID_MAX || frame.length < 1 + ul + 20) {
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
                if (!isIpv4(ip)) {
                    logger.debug("丢弃非 IPv4 的 VPN 帧（{} 字节）", ip.length);
                    continue;
                }
                if (gatewaySink != null && role == Role.HOST && ip.length >= 20
                        && ip[16] == 10 && ip[17] == 77 && ip[18] == 0 && ip[19] == 1) {
                    // 目的地是本机虚拟地址：交给房主侧的 u 层网关（写进本机 MC），
                    // 而不是再塞回 Wintun —— 否则包会绕回虚拟网卡、发不到本机 MC
                    try {
                        gatewaySink.accept(ip);
                        continue;
                    } catch (RuntimeException ignored) {
                        // 网关不可用就按普通包处理
                    }
                }
                if (!adapter.send(ip)) {
                    // 发送环满：驱动侧没有空间，丢包（MC 是 TCP，会重传）
                    logger.debug("Wintun 发送环已满，丢弃一个入站包");
                }
            }
        } catch (Exception e) {
            if (running.get() && !stopping) {
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
            while (running.get()) {
                // 优先把队列里积压的发出去
                byte[] payload = txQueue.poll();
                if (payload == null) {
                    byte[] ip = adapter.receive();
                    if (ip == null) {
                        // 网卡暂时没包：短暂让步，避免空转吃满 CPU
                        Thread.sleep(1);
                        continue;
                    }
                    payload = buildOutgoing(ip);
                    if (payload == null) {
                        continue;
                    }
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get() && !stopping) {
                logger.warn("VPN 发送线程结束: {}", e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    /**
     * 把一个网卡上收到的包变成要发出去的 payload；不需要发时返回 null。
     *
     * 玩家：原样发送；房主：按目标 IP 找到玩家 uuid，前面加 uuid 前缀。
     */
    private byte[] buildOutgoing(byte[] ip) {
        if (!isIpv4(ip)) {
            return null;
        }
        if (role == Role.PEER) {
            return ip;   // 包括 N 大于 9 的新玩家，都只发裸包
        }
        String peer = uuidForPacket(ip);
        if (peer == null) {
            // 目标 IP 不属于任何已登记的玩家（广播 / 未知）：丢弃
            return null;
        }
        byte[] ub = peer.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + ub.length + ip.length];
        payload[0] = (byte) ub.length;
        System.arraycopy(ub, 0, payload, 1, ub.length);
        System.arraycopy(ip, 0, payload, 1 + ub.length, ip.length);
        return payload;
    }

    /**
     * 从 Wintun 的包里读目的 IPv4 地址，反查玩家 uuid。
     *
     * Wintun 的收发缓冲区就是完整的 IP 报文（首字节 0x4X），没有额外前缀。
     */
    private String uuidForPacket(byte[] p) {
        if (!isIpv4(p) || p.length < 20) {
            return null;
        }
        String dst = SUBNET_PREFIX + (p[16] & 0xFF);
        return ipToUuid.get(dst);
    }

    /** 首字节高 4 位是 4，且总长合理 */
    static boolean isIpv4(byte[] p) {
        if (p == null || p.length < 20 || p.length > 65535) {
            return false;
        }
        if (((p[0] >> 4) & 0x0F) != 4) {
            return false;
        }
        int ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || ihl > p.length) {
            return false;
        }
        int total = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        return total > 0 && total <= p.length;
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
        stopping = true;
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