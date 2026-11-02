package com.mclauncher.multiplayer.vpn;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Wintun 虚拟网卡（WireGuard 官方驱动）
 *
 * <p>Wintun 是一个单文件驱动（wintun.dll，约 400KB），不需要安装、不需要
 * 修改注册表。调用它在系统里「创建」一块虚拟网卡，之后：
 * <ul>
 *   <li>用 {@code WintunReceivePacket} 读网卡里的 IP 包</li>
 *   <li>用 {@code WintunAllocateSendPacket} / {@code SendPacket} 往网卡里写 IP 包</li>
 * </ul>
 * 网卡的 IP 地址、路由需要另用 {@code netsh interface ip} 配置。
 *
 * <p><b>首次使用需要管理员权限</b>（安装驱动 + 创建网卡）。之后网卡会保留，
 * 非管理员也能打开它（WintunOpenAdapter）。
 *
 * <p>本类只做「驱动层」：不处理 IP 包内容，也不做加密/转发。
 */
public final class WintunAdapter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WintunAdapter.class);

    /** 网卡名称（Wintun 支持最多 128 字符） */
    public static final String ADAPTER_NAME = "NCL-VPN";
    /** 驱动池名称：同一台机器上多个程序各用各的池，互不干扰 */
    private static final String POOL_NAME = "NCL";

    // ---------------------------------------------------------------
    //  JNA 绑定
    // ---------------------------------------------------------------
    private interface Wintun extends Library {
        Pointer WintunCreateAdapter(WString name, WString tunnelType, Pointer requestedGuid);
        Pointer WintunOpenAdapter(WString name);
        void WintunCloseAdapter(Pointer adapter);
        void WintunDeleteDriver();
        Pointer WintunStartSession(Pointer adapter, int capacity);
        void WintunEndSession(Pointer session);
        Pointer WintunGetReadWaitEvent(Pointer session);
        Pointer WintunReceivePacket(Pointer session, IntByReference packetSize);
        void WintunReleaseReceivePacket(Pointer session, Pointer packet);
        Pointer WintunAllocateSendPacket(Pointer session, int packetSize);
        void WintunSendPacket(Pointer session, Pointer packet);
        WString WintunGetAdapterLuid(Pointer adapter);
        void WintunGetRunningDriverVersion(IntByReference version);
    }

    private static Wintun lib;
    private static Path extractedDll;

    private final String name;
    private Pointer adapter;
    /** volatile：close() 可能来自另一线程，转发循环要能立刻看到 null */
    private volatile Pointer session;
    /** 网卡 GUID（Wintun 自己生成的），用于 netsh 配置 */
    private String luid;

    private WintunAdapter(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    // ---------------------------------------------------------------
    //  驱动加载
    // ---------------------------------------------------------------
    /**
     * 把 jar 里的 wintun.dll 解压到临时目录并加载。
     *
     * 为什么不直接 System.load 资源：JNA 需要文件路径，而 jar 内是压缩流。
     * 解压到带版本号的临时目录，已存在就复用（避免每次启动都写盘）。
     */
    private static synchronized Wintun loadLibrary() throws IOException {
        if (lib != null) {
            return lib;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String dir = arch.contains("aarch64") || arch.contains("arm64") ? "arm64"
                : arch.contains("64") ? "amd64" : "x86";
        String res = "/native/" + dir + "/wintun.dll";

        Path target = Path.of(System.getProperty("java.io.tmpdir"), "ncl-wintun-0.14.1",
                "wintun-" + dir + ".dll");
        if (!Files.isRegularFile(target) || Files.size(target) < 100_000) {
            Files.createDirectories(target.getParent());
            try (InputStream in = WintunAdapter.class.getResourceAsStream(res)) {
                if (in == null) {
                    throw new IOException("jar 内缺少驱动：" + res);
                }
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        extractedDll = target;
        // 用绝对路径加载：避免搜索路径里混进别的 wintun.dll
        System.setProperty("jna.library.path", target.getParent().toString());
        lib = Native.load(target.toAbsolutePath().toString(), Wintun.class);
        logger.info("Wintun 驱动已加载: {}", target);
        return lib;
    }

    /** 已加载的库；热路径（收发包）直接用它，避免重复 IO */
    private static Wintun libOrNull() {
        if (lib != null) {
            return lib;
        }
        try {
            return loadLibrary();
        } catch (IOException e) {
            return null;
        }
    }

    /** 驱动版本（0x00MMmmmppp 格式） */
    public static String driverVersion() {
        try {
            IntByReference v = new IntByReference();
            loadLibrary().WintunGetRunningDriverVersion(v);
            int x = v.getValue();
            return "%d.%d".formatted((x >> 16) & 0xFFFF, x & 0xFFFF);
        } catch (Throwable t) {
            return "未加载";
        }
    }

    // ---------------------------------------------------------------
    //  创建 / 打开
    // ---------------------------------------------------------------
    /**
     * 创建（或打开已存在的）虚拟网卡。
     *
     * @throws VpnPermissionException 没有管理员权限
     */
    public static WintunAdapter openOrCreate() throws IOException {
        return openOrCreate(ADAPTER_NAME);
    }

    /** 用指定名称创建/打开网卡（测试与多实例场景用） */
    public static WintunAdapter openOrCreate(String name) throws IOException {
        WintunAdapter a = new WintunAdapter(name);
        Wintun w = loadLibrary();

        // 先尝试打开已存在的（非管理员也能打开）
        Pointer p = null;
        try {
            p = w.WintunOpenAdapter(new WString(name));
        } catch (Throwable ignored) {
            // 不存在
        }
        if (p == null) {
            logger.info("创建虚拟网卡 {}", name);
            p = w.WintunCreateAdapter(new WString(name), new WString(POOL_NAME), null);
            if (p == null) {
                int err = Native.getLastError();
                if (err == 5) {
                    throw new VpnPermissionException("创建虚拟网卡需要管理员权限（错误 5）");
                }
                throw new IOException("创建虚拟网卡失败，错误码 " + err);
            }
        } else {
            logger.info("打开已有的虚拟网卡 {}", name);
        }
        a.adapter = p;
        try {
            a.luid = w.WintunGetAdapterLuid(p).toString();
        } catch (Throwable t) {
            a.luid = "?";
        }
        return a;
    }

    // ---------------------------------------------------------------
    //  会话（数据通道）
    // ---------------------------------------------------------------
    /** 开启收发会话。capacity 是环形缓冲区大小（字节），至少 0x20000 */
    public void startSession(int capacityBytes) throws IOException {
        session = loadLibrary().WintunStartSession(adapter, capacityBytes);
        if (session == null) {
            throw new IOException("启动 Wintun 会话失败，错误码 " + Native.getLastError());
        }
        logger.info("Wintun 会话已启动，缓冲区 {} KB", capacityBytes / 1024);
    }

    /**
     * 读一个 IP 包；没有数据时返回 null（非阻塞）。
     *
     * 会话已关闭时返回 null。注意：close() 与 receive() 之间存在竞态窗口，
     * 捕获错误 code 保证不因竞态崩溃（驱动返回 ERROR_NO_MORE_ITEMS 表示暂无数据）。
     */
    public byte[] receive() {
        Pointer s = session;
        Wintun w = libOrNull();
        if (s == null || w == null) {
            return null;
        }
        IntByReference size = new IntByReference();
        Pointer p;
        try {
            p = w.WintunReceivePacket(s, size);
        } catch (Throwable e) {
            // 会话刚被关闭
            return null;
        }
        if (p == null) {
            return null;
        }
        try {
            return p.getByteArray(0, size.getValue());
        } finally {
            try {
                w.WintunReleaseReceivePacket(s, p);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    public boolean isOpen() {
        return session != null;
    }

    /** 发送一个 IP 包；缓冲区满时返回 false（调用方稍后重试） */
    public boolean send(byte[] packet) {
        if (session == null) {
            return false;
        }
        Wintun w = libOrNull();
        if (w == null) {
            return false;
        }
        Pointer p = w.WintunAllocateSendPacket(session, packet.length);
        if (p == null) {
            return false;
        }
        p.write(0, packet, 0, packet.length);
        w.WintunSendPacket(session, p);
        return true;
    }

    /** 读数据时用的事件句柄（可 WaitForSingleObject 等待，避免忙等） */
    public Pointer readWaitEvent() {
        Wintun w = libOrNull();
        return (session == null || w == null) ? null : w.WintunGetReadWaitEvent(session);
    }

    public String luid() {
        return luid;
    }

    // ---------------------------------------------------------------
    @Override
    public void close() {
        Wintun w = libOrNull();
        if (session != null && w != null) {
            w.WintunEndSession(session);
            session = null;
        }
        if (adapter != null && w != null) {
            w.WintunCloseAdapter(adapter);
            adapter = null;
        }
    }

    /** 删除驱动（一般不需要调用：驱动可以常驻） */
    public static void deleteDriver() {
        try {
            loadLibrary().WintunDeleteDriver();
        } catch (Throwable ignored) {
        }
    }

    /** 权限不足 */
    public static class VpnPermissionException extends IOException {
        public VpnPermissionException(String msg) {
            super(msg);
        }
    }
}