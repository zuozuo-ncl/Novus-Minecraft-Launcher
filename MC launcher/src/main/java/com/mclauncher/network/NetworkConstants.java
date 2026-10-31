package com.mclauncher.network;

public final class NetworkConstants {

    private NetworkConstants() {}

    public static final String USER_AGENT = "MCLauncher/1.0.1 (Minecraft Launcher)";

    public static final String ACCEPT = "*/*";

    public static final int CONNECT_TIMEOUT_MS = 30_000;

    public static final int READ_TIMEOUT_MS = 300_000;

    public static final int API_TIMEOUT_MS = 60_000;
}
