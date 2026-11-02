package com.mclauncher.network;

/**
 * 网络常量
 *
 * 关于 User-Agent 的重要说明：
 * BMCLAPI（及其背后的高校镜像节点，如中科大、南大等）会对
 * 「伪装成浏览器的 User-Agent」返回 403 Forbidden，
 * 例如 `Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/120...` 会被拒绝。
 *
 * 正确做法是使用一个能表明自身身份的 UA（这也是 BMCLAPI 协议所期望的
 * ——让镜像站知道请求来自哪个启动器）。带联系方式的 UA 更佳。
 */
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