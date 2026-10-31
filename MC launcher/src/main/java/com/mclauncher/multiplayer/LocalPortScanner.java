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

public final class LocalPortScanner {

    private static final Logger logger = LoggerFactory.getLogger(LocalPortScanner.class);

    public static final int EPHEMERAL_MIN = 49152;
    public static final int EPHEMERAL_MAX = 65535;

    private LocalPortScanner() {}

    public record Found(int port, String version, String source) {
        @Override
        public String toString() {
            return port + (version == null || version.isBlank() ? "" : "（" + version + "）");
        }
    }

    public static List<Found> scan(List<Path> gameDirs, List<Integer> rememberedPorts,
                                   List<Path> logDirs) {
        Set<Integer> candidates = new LinkedHashSet<>();
        if (rememberedPorts != null) {
            for (Integer p : rememberedPorts) {
                if (p != null && p > 0 && p <= 65535) {
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
        if (logDirs != null) {
            for (Path dir : logDirs) {
                Integer p = readPortFromLog(dir);
                if (p != null) {
                    candidates.add(p);
                }
            }
        }

        List<Found> found = new ArrayList<>();
        List<Integer> probed = new ArrayList<>(candidates);

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

    public static boolean isTcpOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String mcStatusVersion(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            s.setSoTimeout(1500);
            java.io.OutputStream out = s.getOutputStream();

            String host = "127.0.0.1";
            java.io.ByteArrayOutputStream hs = new java.io.ByteArrayOutputStream();
            writeVarInt(hs, 0x00);                    
            writeVarInt(hs, -1);                      
            byte[] hb = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, hb.length);
            hs.write(hb);
            hs.write((port >> 8) & 0xFF);             
            hs.write(port & 0xFF);
            writeVarInt(hs, 1);                       
            byte[] hp = hs.toByteArray();
            writeVarInt(out, hp.length);
            out.write(hp);
            out.flush();

            out.write(new byte[]{0x01, 0x00});        
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

            int i = 0;
            i += varIntSize(body, i);                 
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
            return version != null && version.has("name") ? version.get("name").getAsString() : "";
        } catch (Exception e) {
            logger.debug("端口 {} 不是 Minecraft 服务端: {}", port, e.getMessage());
            return null;
        }
    }

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

        }
        return null;
    }

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
            java.util.regex.Pattern zh = java.util.regex.Pattern.compile("端口\\s*(\\d{2,5})");
            java.util.regex.Pattern en = java.util.regex.Pattern.compile("[Ll]ocal game hosted on port\\s+(\\d{2,5})");
            for (int i = lines.size() - 1, n = 0; i >= 0 && n < 400; i--, n++) {
                java.util.regex.Matcher m = zh.matcher(lines.get(i));

                
                boolean hit = m.find();
                if (!hit) {
                    m = en.matcher(lines.get(i));
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

        }
        return null;
    }

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
