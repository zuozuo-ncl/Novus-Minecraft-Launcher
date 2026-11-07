package com.mclauncher.network;

/** 网络常量 */
public final class NetworkConstants {

    private NetworkConstants() {}

    /**
     * 统一的 User-Agent
     *
     * 注意：不要改成浏览器 UA，否则 BMCLAPI 会返回 403。
     */
    public static final String USER_AGENT = "MCLauncher/1.0.1 (Minecraft Launcher)";

    /** 通用 Accept 头 */
    public static final String ACCEPT = "*/*";

    /** 连接超时（毫秒） */
    public static final int CONNECT_TIMEOUT_MS = 30_000;

    /** 读取超时（毫秒）—— 大文件下载需要较长时限 */
    public static final int READ_TIMEOUT_MS = 300_000;

    /** 普通 API 请求超时（毫秒） */
    public static final int API_TIMEOUT_MS = 60_000;
}