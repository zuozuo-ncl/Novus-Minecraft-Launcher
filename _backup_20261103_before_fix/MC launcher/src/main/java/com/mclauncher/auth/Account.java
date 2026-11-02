package com.mclauncher.auth;


import com.mclauncher.i18n.L;
import com.mclauncher.utils.UUIDUtils;

import java.util.UUID;

/**
 * 账号信息
 * 支持离线账号和正版账号
 */
public class Account {

    public enum Type {
        OFFLINE("s_c1c38438c5"),
        MICROSOFT("s_51ac065f18");

        /** 文案键；在 getDisplayName() 里才解析，避免枚举初始化早于文案加载 */
        private final String labelKey;

        Type(String labelKey) {
            this.labelKey = labelKey;
        }

        public String getDisplayName() { return L.t(labelKey); }
    }

    private String id;
    private String username;
    private String uuid;
    private Type type;
    private String accessToken;
    private String refreshToken;
    private long expiresAt;
    private String xuid;
    private String clientId;

    /**
     * 皮肤站
     *
     * null / "" / "auto"   = 按默认顺序（LittleSkin → Blessing Skin → Mojang）
     * "mojang"             = 只查 Mojang 官方（正版账号）
     * 其它（URL 或域名）     = 该站点的 Yggdrasil API，例如 https://littleskin.cn
     */
    private String skinSite;

    public Account() {
        this.id = UUIDUtils.randomUUID().toString();
    }

    /**
     * 创建离线账号
     */
    public static Account createOffline(String username) {
        Account account = new Account();
        account.username = username;
        account.type = Type.OFFLINE;
        account.uuid = UUIDUtils.formatUUIDNoDash(UUIDUtils.generateOfflineUUID(username));
        account.accessToken = "offline_token_" + System.currentTimeMillis();
        account.expiresAt = Long.MAX_VALUE;
        return account;
    }

    /**
     * 创建微软账号
     */
    public static Account createMicrosoft(String username, String uuid, String accessToken,
                                          String refreshToken, long expiresAt) {
        Account account = new Account();
        account.username = username;
        account.type = Type.MICROSOFT;
        account.uuid = uuid.replace("-", "");
        account.accessToken = accessToken;
        account.refreshToken = refreshToken;
        account.expiresAt = expiresAt;
        return account;
    }

    /**
     * 检查 Token 是否过期
     */
    public boolean isTokenExpired() {
        return type == Type.MICROSOFT && System.currentTimeMillis() > expiresAt;
    }

    /**
     * 检查账号是否有效
     */
    public boolean isValid() {
        if (type == Type.OFFLINE) {
            return username != null && !username.isEmpty();
        }
        return accessToken != null && !accessToken.isEmpty() && !isTokenExpired();
    }

    /**
     * 获取格式化的 UUID（带连字符）
     */
    public String getFormattedUuid() {
        if (uuid == null || uuid.isEmpty()) {
            return "";
        }
        if (uuid.contains("-")) {
            return uuid;
        }
        return uuid.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5");
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public Type getType() { return type; }

    public String getSkinSite() { return skinSite; }

    public void setSkinSite(String skinSite) { this.skinSite = skinSite; }
    public void setType(Type type) { this.type = type; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public String getXuid() { return xuid; }
    public void setXuid(String xuid) { this.xuid = xuid; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    @Override
    public String toString() {
        return username + " (" + type.getDisplayName() + ")";
    }
}