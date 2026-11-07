package com.mclauncher.multiplayer.vpn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 联机中继的端点列表。
 * <h3>为什么不能写死在代码里</h3>
 */
public final class RelayEndpoints {

    private static final Logger logger = LoggerFactory.getLogger(RelayEndpoints.class);

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final String FILENAME = "relay-endpoints.json";

    /** 内置默认（主站 → 备站） */
    private static final String[] BUILTIN = {"39.106.60.15", "45.64.229.50:41181"};

    /** 一个中继端点 */
    public static final class Endpoint {
        public final String host;
        public final int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String label() {
            return port == DEFAULT_HTTP_PORT ? host : host + ":" + port;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Endpoint e && host.equals(e.host) && port == e.port;
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private RelayEndpoints() {}

    /** 解析结果：端点列表（非空） */
    public static List<Endpoint> load(Path dataDir) {
        return load(System.getProperty("mclauncher.relayHosts"), dataDir);
    }

    /** 可注入版本，便于测试。 */
    public static List<Endpoint> load(String systemOverride, Path dataDir) {
        if (systemOverride != null && !systemOverride.isBlank()) {
            List<Endpoint> list = parseCommaList(systemOverride);
            if (!list.isEmpty()) {
                logger.info("中继端点来自 -Dmclauncher.relayHosts: {}", list);
                return List.copyOf(list);
            }
            logger.warn("-Dmclauncher.relayHosts 内容无效，改用默认列表: {}", systemOverride);
        }

        if (dataDir != null) {
            Path file = dataDir.resolve(FILENAME);
            if (Files.isRegularFile(file)) {
                try {
                    List<Endpoint> list = parseJson(Files.readString(file, StandardCharsets.UTF_8));
                    if (!list.isEmpty()) {
                        logger.info("中继端点来自 {}: {}", file, list);
                        return List.copyOf(list);
                    }
                    logger.warn("{} 里没有有效的端点，改用默认列表", file);
                } catch (IOException | RuntimeException e) {
                    // 文件坏了不能让人联机不了：退回默认列表
                    logger.warn("读取 {} 失败（{}），改用默认列表", file, e.getMessage());
                }
            }
        }

        List<Endpoint> list = parseCommaList(String.join(",", BUILTIN));
        logger.info("中继端点使用内置默认: {}", list);
        return List.copyOf(list);
    }

    /**
     * 解析 "host[:port],host2[:port]" 形式的列表。
     *
     * 容忍：前后空白、空项、端口非法（该项整条跳过）、主机名带端口号。
     */
    public static List<Endpoint> parseCommaList(String text) {
        List<Endpoint> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String item : text.split(",")) {
            Endpoint e = parseOne(item.trim());
            if (e != null) {
                out.add(e);
            } else if (!item.isBlank()) {
                logger.warn("忽略无效的中继端点: {}", item.trim());
            }
        }
        return out;
    }

    /** 解析 relay-endpoints.json。 */
    public static List<Endpoint> parseJson(String json) {
        List<Endpoint> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonElement root = JsonParser.parseString(json);
        JsonArray arr;
        if (root.isJsonObject() && root.getAsJsonObject().has("endpoints")) {
            arr = root.getAsJsonObject().getAsJsonArray("endpoints");
        } else if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else {
            return out;
        }
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                Endpoint e = parseOne(el.getAsString());
                if (e != null) {
                    out.add(e);
                }
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("host")) {
                    continue;
                }
                String host = o.get("host").getAsString();
                int port = o.has("port") && o.get("port").isJsonPrimitive()
                        ? o.get("port").getAsInt() : DEFAULT_HTTP_PORT;
                Endpoint e = parseOne(host + ":" + port);
                if (e != null) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** 解析单个端点；非法返回 null */
    static Endpoint parseOne(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        s = s.trim();
        if (s.indexOf('/') >= 0 || s.indexOf(' ') >= 0) {
            return null;       // 不允许 "http://..." 或带空白的写法
        }
        int colon = s.lastIndexOf(':');
        if (colon < 0) {
            return validHost(s) ? new Endpoint(s, DEFAULT_HTTP_PORT) : null;
        }
        String host = s.substring(0, colon).trim();
        String portText = s.substring(colon + 1).trim();
        if (!validHost(host)) {
            return null;
        }
        try {
            int p = Integer.parseInt(portText);
            return (p > 0 && p <= 65535) ? new Endpoint(host, p) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 主机名/IP：只允许字母数字、点、连字符 */
    private static boolean validHost(String host) {
        return host != null && !host.isEmpty() && host.matches("[A-Za-z0-9.\\-]+");
    }
}