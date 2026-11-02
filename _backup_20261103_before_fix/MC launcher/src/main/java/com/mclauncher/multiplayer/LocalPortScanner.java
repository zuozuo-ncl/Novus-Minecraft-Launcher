package com.mclauncher.multiplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地 Minecraft 开服端口探测
 *
 * 以前的联机页把端口写死成 25565（最多再读一次 server.properties），
 * 但抄作业常见的是「对局域网开放」，游戏会**随机挑一个五位数端口**，
 * 或者玩家在 server.properties 里改过端口。这里把几种来源都试一遍：
 *
 * <ol>
 *   <li>当前游戏目录下的 server.properties（单机存档也写在里面）</li>
 *   <li>所有游戏目录（每个目录一份 server.properties）</li>
 *   <li>历史记录（上次用过的端口）</li>
 *   <li>解析启动日志里的「本地游戏已在端口 XXXXX 上公开」/ "Local game hosted on port XXXXX"</li>
 *   <li>端口扫描兜底：127.0.0.1 上的 25565 与 49152-65535</li>
 * </ol>
 *
 * 只有当端口上真的能完成一次 Minecraft 握手（回包里有 JSON status）
 * 才认为是「我的世界」的服务端，避免把别的程序误认成游戏。
 */
public final class LocalPortScanner {

    private static final Logger logger = LoggerFactory.getLogger(LocalPortScanner.class);

    /** 对局域网开放的默认端口区间（游戏从 49152 起随机挑） */
    public static final int EPHEMERAL_MIN = 49152;
    public static final int EPHEMERAL_MAX = 65535;

    private LocalPortScanner() {}

    /** 扫描结果 */
    public record Found(int port, String version, String source) {
        @Override
        public String toString() {
            return port + (version == null || version.isBlank() ? "" : "（" + version + "）");
        }
    }

    /** status 握手拿到的版本信息 */
    public record StatusInfo(String name, int protocol) {}

    /**
     * 服务端是否强制正版验证（login 探测结果）
     *
     * <ul>
     *   <li>{@link #ONLINE}：服务端回 Encryption Request(0x01)，即 online-mode=true，
     *       离线账号在加密验证阶段必然被踢；单人存档的「对局域网开放」就是这种；</li>
     *   <li>{@link #OFFLINE}：服务端回 Login Success(0x02)，即 online-mode=false，
     *       离线账号可以正常加入；</li>
     *   <li>{@link #UNKNOWN}：连不上 / 被断开 / 协议异常，无法判定，调用方不应据此
     *       拦截用户，按原流程走。</li>
     * </ul>
     */
    public enum OnlineMode { ONLINE, OFFLINE, UNKNOWN }

    /**
     * 扫描可能的本地 MC 端口。
     *
     * @param gameDirs       要读取 server.properties 的游戏目录（可为空）
     * @param rememberedPorts 历史用过的端口，优先探测
     * @param logDirs        在这些目录下找最新的 latest.log（可为空）
     * @return 按优先级排序的候选端口；scanned=true 表示做了端口扫描，
     *         found 为空时可根据它决定要不要提示用户手动填写
     */
    public static List<Found> scan(List<Path> gameDirs, List<Integer> rememberedPorts,
                                   List<Path> logDirs) {
        // 优先级：latest.log 里「已在端口 N 上开启」 > server.properties 的 server-port
        //        > 历史用过的端口
        //
        // 「对局域网开放」不会改 server.properties，那份文件只反映独立服务端的配置；
        // 单人存档开局域网用的端口只出现在 latest.log 里。反过来，只要日志里
        // 有这句话，就是最近一次真正开服的端口，可信度最高。
        Set<Integer> candidates = new LinkedHashSet<>();
        if (logDirs != null) {
            for (Path dir : logDirs) {
                Integer p = readPortFromLog(dir);
                if (p != null) {
                    candidates.add(p);
                }
            }
        }
        if (gameDirs != null) {
            for (Path dir : gameDirs) {
                Integer p = readServerPort(dir);
                if (p != null) {
                    candidates.add(p);
                }
            }
        }
        if (rememberedPorts != null) {
            for (Integer p : rememberedPorts) {
                if (p != null && p > 0 && p <= 65535) {
                    candidates.add(p);
                }
            }
        }

        List<Found> found = new ArrayList<>();
        List<Integer> probed = new ArrayList<>(candidates);
        // 先把已知候选试完（快），再做全区间扫描
        for (int port : probed) {
            Found f = probe(port, "候选");
            if (f != null) {
                found.add(f);
            }
        }
        if (!found.isEmpty()) {
            return found;
        }

        if (probe(25565, "默认") != null) {
            found.add(new Found(25565, probeVersion(25565), "默认"));
            return found;
        }
        for (int port = EPHEMERAL_MIN; port <= EPHEMERAL_MAX; port++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (candidates.contains(port)) {
                continue;
            }
            if (LocalPortScanner.isTcpOpen(port) && isMinecraft(port)) {
                found.add(new Found(port, probeVersion(port), "扫描"));
            }
        }
        found.sort(Comparator.comparingInt(Found::port));
        return found;
    }

    /** 只探测指定端口，返回 MC 版本号，失败返回 null */
    public static String probeVersion(int port) {
        Found f = probe(port, "");
        return f == null ? null : f.version();
    }

    private static Found probe(int port, String source) {
        if (!isTcpOpen(port)) {
            return null;
        }
        String version = mcStatusVersion(port);
        if (version == null) {
            return null;
        }
        return new Found(port, version, source);
    }

    private static boolean isMinecraft(int port) {
        return mcStatusVersion(port) != null;
    }

    /** 只判断 TCP 端口能不能连上（不看内容） */
    public static boolean isTcpOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 走一遍 Minecraft 状态协议（status），成功时返回版本名。
     *
     * 握手包 + 状态请求 + 1 字节 ping，然后在 1.5 秒内读到 JSON 且能解析出 version.name。
     */
    public static String mcStatusVersion(int port) {
        StatusInfo info = mcStatusInfo(port);
        return info == null ? null : info.name();
    }

    /**
     * 走一遍 Minecraft 状态协议（status），返回版本名与协议号。
     *
     * 协议号会在 login 探测里作为握手的 protocol version：必须用服务端自己的
     * 协议号，版本对不上时服务端会直接回 Login Disconnect 而不是
     * Encryption Request / Login Success，探测就失效了。
     */
    public static StatusInfo mcStatusInfo(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            s.setSoTimeout(1500);
            java.io.OutputStream out = s.getOutputStream();

            String host = "127.0.0.1";
            java.io.ByteArrayOutputStream hs = new java.io.ByteArrayOutputStream();
            writeVarInt(hs, 0x00);                    // packet id: handshake
            writeVarInt(hs, -1);                      // protocol version: -1 = 只要状态
            byte[] hb = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, hb.length);
            hs.write(hb);
            hs.write((port >> 8) & 0xFF);             // port（big endian）
            hs.write(port & 0xFF);
            writeVarInt(hs, 1);                       // next state: status
            byte[] hp = hs.toByteArray();
            writeVarInt(out, hp.length);
            out.write(hp);
            out.flush();

            out.write(new byte[]{0x01, 0x00});        // status request
            out.flush();

            java.io.InputStream in = s.getInputStream();
            int len = readVarInt(in);
            if (len <= 0 || len > 1 << 20) {
                return null;
            }
            byte[] body = in.readNBytes(len);
            if (body.length != len) {
                return null;
            }
            // body = varint(packetId=0) + varint(strLen) + json
            int i = 0;
            i += varIntSize(body, i);                 // packet id
            int strLen = readVarIntAt(body, i);
            i += varIntSize(body, i);
            if (strLen < 0 || i + strLen > body.length) {
                return null;
            }
            String json = new String(body, i, strLen, StandardCharsets.UTF_8);
            var root = com.google.gson.JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            var version = root.getAsJsonObject().getAsJsonObject("version");
            if (version == null || !version.has("name")) {
                return null;
            }
            String name = version.get("name").getAsString();
            int protocol = version.has("protocol") ? version.get("protocol").getAsInt() : 0;
            return new StatusInfo(name, protocol);
        } catch (Exception e) {
            logger.debug("端口 {} 不是 Minecraft 服务端: {}", port, e.getMessage());
            return null;
        }
    }

    /**
     * 对本地端口做一次完整 login 探测，判断服务端是否强制正版验证。
     *
     * 流程：先用 status 拿到服务端自己的协议号，再新建一条连接发
     * Handshake(next state=login) + Login Start，然后读第一个回包：
     *
     * <ul>
     *   <li>Encryption Request(0x01) → {@link OnlineMode#ONLINE}（online-mode=true）；</li>
     *   <li>Login Success(0x02)      → {@link OnlineMode#OFFLINE}（online-mode=false）；</li>
     *   <li>Set Compression(0x03)    → 旧版（1.8~1.20.1）离线服务端登录阶段的首包，
     *       压缩在登录成功前协商，在线模式下首包一定是 0x01，所以 0x03 只会出现在
     *       离线服务端；继续读下一帧并按压缩格式拆包确认；</li>
     *   <li>Login Disconnect(0x00) / 其它 / 异常 → {@link OnlineMode#UNKNOWN}。</li>
     * </ul>
     *
     * 探测结束就关闭连接，不完成后续登录，对正在运行的服务端没有副作用。
     */
    public static OnlineMode probeOnlineMode(int port) {
        StatusInfo info = mcStatusInfo(port);
        if (info == null) {
            return OnlineMode.UNKNOWN;
        }
        int protocol = info.protocol();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            s.setSoTimeout(2000);
            java.io.OutputStream out = s.getOutputStream();

            // ---- Handshake：next state = 2 (login) ----
            java.io.ByteArrayOutputStream hs = new java.io.ByteArrayOutputStream();
            writeVarInt(hs, 0x00);                    // packet id: handshake
            writeVarInt(hs, protocol);                // 服务端自己的协议号，避免版本不匹配被断开
            byte[] hb = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, hb.length);
            hs.write(hb);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 2);                       // next state: login
            byte[] hp = hs.toByteArray();
            writeVarInt(out, hp.length);
            out.write(hp);

            // ---- Login Start (0x00) ----
            // 不同版本字段不同，缺字段时服务端会一直等数据，探测超时：
            //   ≤1.18.2 (proto≤758)：仅 name
            //   1.19~1.19.2 (759/760)：name + 无签名(false) + 无 UUID(false)
            //   1.19.3 (761)：name + 无 UUID(false)
            //   ≥1.19.4 (proto≥762)：name + UUID(16 字节)
            java.io.ByteArrayOutputStream ls = new java.io.ByteArrayOutputStream();
            writeVarInt(ls, 0x00);
            writeString(ls, "NCLProbe");
            if (protocol == 759 || protocol == 760) {
                ls.write(0x00);                       // has signature data = false
                ls.write(0x00);                       // has player UUID = false
            } else if (protocol == 761) {
                ls.write(0x00);                       // has player UUID = false
            } else if (protocol >= 762) {
                ls.write(new byte[16]);               // player UUID（全 0 即可，探测马上断开）
            }
            byte[] lp = ls.toByteArray();
            writeVarInt(out, lp.length);
            out.write(lp);
            out.flush();

            java.io.InputStream in = s.getInputStream();
            byte[] frame = readFrame(in);
            int packetId = readVarIntAt(frame, 0);
            int idSize = varIntSize(frame, 0);
            if (packetId == 0x03) {
                // Set Compression：后面跟着 threshold，再读一帧按压缩格式解析
                int threshold = readVarIntAt(frame, idSize);
                return readCompressedLogin(in, threshold);
            }
            return mapLoginPacket(packetId);
        } catch (Exception e) {
            logger.debug("端口 {} login 探测失败: {}", port, e.getMessage());
            return OnlineMode.UNKNOWN;
        }
    }

    /** 压缩开启后的 login 回包解析 */
    private static OnlineMode readCompressedLogin(java.io.InputStream in, int threshold)
            throws IOException {
        byte[] frame = readFrame(in);
        int dataLen = readVarIntAt(frame, 0);
        int dlSize = varIntSize(frame, 0);
        byte[] packetBytes;
        if (dataLen == 0) {
            // 数据长度为 0 表示本帧未压缩
            packetBytes = java.util.Arrays.copyOfRange(frame, dlSize, frame.length);
        } else {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(frame, dlSize, frame.length - dlSize);
            byte[] inflated = new byte[dataLen];
            try {
                int got = inflater.inflate(inflated);
                if (got != dataLen) {
                    inflater.end();
                    return OnlineMode.UNKNOWN;
                }
            } catch (java.util.zip.DataFormatException e) {
                inflater.end();
                return OnlineMode.UNKNOWN;
            }
            inflater.end();
            packetBytes = inflated;
        }
        return mapLoginPacket(readVarIntAt(packetBytes, 0));
    }

    /** login 阶段回包 ID → 探测结果 */
    private static OnlineMode mapLoginPacket(int packetId) {
        return switch (packetId) {
            case 0x01 -> OnlineMode.ONLINE;           // Encryption Request
            case 0x02 -> OnlineMode.OFFLINE;          // Login Success
            default -> OnlineMode.UNKNOWN;            // Disconnect(0x00) 等
        };
    }

    /** 读一个完整帧（varint 长度 + 帧体），短帧/超长都抛异常 */
    private static byte[] readFrame(java.io.InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len <= 0 || len > 1 << 20) {
            throw new IOException("非法帧长度 " + len);
        }
        byte[] body = in.readNBytes(len);
        if (body.length != len) {
            throw new IOException("帧体不完整 " + body.length + "/" + len);
        }
        return body;
    }

    /** 写一个 UTF-8 字符串（varint 长度前缀） */
    private static void writeString(java.io.ByteArrayOutputStream out, String value)
            throws IOException {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    /** 从 server.properties 读 server-port；文件不存在返回 null */
    public static Integer readServerPort(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        Path props = gameDir.resolve("server.properties");
        if (!Files.isRegularFile(props)) {
            return null;
        }
        try {
            for (String line : Files.readString(props, StandardCharsets.UTF_8).split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("server-port=")) {
                    int p = Integer.parseInt(t.substring("server-port=".length()).trim());
                    return (p > 0 && p <= 65535) ? p : null;
                }
            }
        } catch (Exception ignored) {
            // 读不到就算了
        }
        return null;
    }

    /**
     * 从最新一份 logs/latest.log 里找「对局域网开放」时打印的端口：
     *   中文：「本地游戏已在端口 53211 上开启」（旧版是「上公开」）
     *   英文： "Local game hosted on port 53211"
     *
     * <b>必须匹配整句，不能只匹配「端口 N」。</b>
     * 日志里还有大量「端口 56803」这类字样，例如：
     *   zuozuo (/127.0.0.1:56803) lost connection: 连接中断
     * 那里的 56803 是<b>别人连进来时用的出口端口</b>，不是本地开服端口。
     * 早期版本用「端口\s*(\d+)」宽匹配并倒序扫描，会把最后一条断线记录里的
     * 对端端口当成开服端口，联机时房主一直去连那个不存在的本地端口。
     */
    public static Integer readPortFromLog(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        Path log = gameDir.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            java.util.regex.Pattern zh = java.util.regex.Pattern.compile(
                    "本地游戏已在端口\\s*\\[?(\\d{2,5})\\]?\\s*上(?:开启|公开)");
            java.util.regex.Pattern en = java.util.regex.Pattern.compile(
                    "[Ll]ocal game hosted on port\\s+(\\d{2,5})");
            for (int i = lines.size() - 1, n = 0; i >= 0 && n < 800; i--, n++) {
                String line = lines.get(i);
                java.util.regex.Matcher m = zh.matcher(line);
                // 注意：find() 会推进匹配位置，不能「先 find() 判断再 find() 取值」
                boolean hit = m.find();
                if (!hit) {
                    m = en.matcher(line);
                    hit = m.find();
                }
                if (hit) {
                    int p = Integer.parseInt(m.group(1));
                    if (p > 0 && p <= 65535) {
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
            // 日志可能正在被写，读失败就算了
        }
        return null;
    }

    // ---------- VarInt 小工具 ----------

    private static void writeVarInt(java.io.OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(java.io.InputStream in) throws IOException {
        int value = 0;
        int pos = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("EOF");
            }
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) {
                return value;
            }
            pos += 7;
            if (pos > 35) {
                throw new IOException("VarInt 过长");
            }
        }
    }

    private static int readVarIntAt(byte[] buf, int offset) {
        int value = 0;
        int pos = 0;
        while (offset + pos / 7 < buf.length) {
            int b = buf[offset + pos / 7] & 0xFF;
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) {
                return value;
            }
            pos += 7;
            if (pos > 35) {
                return -1;
            }
        }
        return -1;
    }

    private static int varIntSize(byte[] buf, int offset) {
        int n = 0;
        while (offset + n < buf.length && n < 5) {
            n++;
            if ((buf[offset + n - 1] & 0x80) == 0) {
                break;
            }
        }
        return n;
    }
}
