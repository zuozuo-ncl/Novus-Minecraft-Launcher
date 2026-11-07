package com.mclauncher.multiplayer.vpn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 netsh / route 给 Wintun 网卡配 IP 与路由。
 * <h3>为什么必须配路由</h3>
 */
public final class VpnNetworkConfig {

    private static final Logger logger = LoggerFactory.getLogger(VpnNetworkConfig.class);

    /** 虚拟网段 */
    public static final String SUBNET = "10.77.0.0";
    public static final String MASK = "255.255.255.0";
    /** 房主固定用 .1 */
    public static final String HOST_IP = "10.77.0.1";

    private VpnNetworkConfig() {}

    /** 是否管理员 */
    public static boolean isAdmin() {
        try {
            Process p = new ProcessBuilder("net", "session").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    //  地址
    // ---------------------------------------------------------------
    public static void setAddress(String ip) throws IOException {
        setAddress(WintunAdapter.ADAPTER_NAME, ip);
    }

    public static void setAddress(String adapter, String ip) throws IOException {
        runQuietly("netsh", "interface", "ip", "set", "address",
                "name=" + adapter, "source=static",
                "address=" + ip, "mask=" + MASK);
        // 只保留 IPv4：IPv6 在虚拟网段里没有对端，开着只会刷链路本地组播
        runQuietly("netsh", "interface", "ipv6", "set", "interface",
                "interface=" + adapter, "state=disabled");
        logger.info("虚拟网卡 {} 地址已设为 {}", adapter, ip);
    }

    // ---------------------------------------------------------------
    //  路由
    // ---------------------------------------------------------------
    /** 让整个虚拟网段走指定网卡。 */
    public static void routeSubnetToAdapter(String adapterName) throws IOException {
        int idx = interfaceIndex(adapterName);
        if (idx <= 0) {
            throw new IOException("找不到网卡 " + adapterName + " 的接口索引");
        }
        runQuietly("route", "delete", SUBNET, "mask", MASK);
        run("route", "add", SUBNET, "mask", MASK, "0.0.0.0", "if", String.valueOf(idx),
                "metric", "1");
        logger.info("已添加路由 {}/24 -> 网卡 {}（索引 {}）", SUBNET, adapterName, idx);
    }

    public static void clearRoutes() {
        runQuietly("route", "delete", SUBNET, "mask", MASK);
    }

    /** 取网卡索引（netsh 输出里第一列） */
    public static int interfaceIndex(String adapterName) {
        try {
            Process p = new ProcessBuilder("netsh", "interface", "ipv4", "show", "interfaces")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), charset());
            p.waitFor(10, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String[] cols = line.trim().split("\\s{2,}");
                if (cols.length >= 5 && cols[0].matches("\\d+")
                        && cols[cols.length - 1].startsWith(adapterName)) {
                    return Integer.parseInt(cols[0]);
                }
            }
        } catch (Exception e) {
            logger.debug("查询网卡索引失败: {}", e.getMessage());
        }
        return -1;
    }

    /** 当前网卡地址（诊断用） */
    public static List<String> addresses(String adapterName) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("netsh", "interface", "ip", "show", "address",
                    "name=" + adapterName).redirectErrorStream(true).start();
            out.add(new String(p.getInputStream().readAllBytes(), charset()).trim());
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            out.add("查询失败: " + e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------
    private static Charset charset() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? Charset.forName("GBK") : StandardCharsets.UTF_8;
    }

    private static void run(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            String out = new String(p.getInputStream().readAllBytes(), charset());
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("命令超时: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new IOException("命令失败(" + p.exitValue() + "): "
                        + String.join(" ", cmd) + " -> " + out.trim());
            }
            logger.debug("{} -> {}", String.join(" ", cmd), out.trim());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断: " + String.join(" ", cmd), e);
        }
    }

    private static void runQuietly(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(15, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理类命令失败无所谓
        }
    }
}