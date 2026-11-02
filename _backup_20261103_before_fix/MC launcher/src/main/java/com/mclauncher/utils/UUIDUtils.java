package com.mclauncher.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * UUID 生成工具类
 * 用于生成 Minecraft 风格的 UUID
 */
public class UUIDUtils {

    private UUIDUtils() {}

    /**
     * 生成随机 UUID
     */
    public static UUID randomUUID() {
        return UUID.randomUUID();
    }

    /**
     * 生成 Minecraft 风格的离线 UUID
     * 使用 "OfflinePlayer:" + 用户名作为输入
     */
    public static UUID generateOfflineUUID(String username) {
        String input = "OfflinePlayer:" + username;
        byte[] bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(bytes);

            // Minecraft UUID 格式处理
            hash[6] &= 0x0f;  // 清掉版本位
            hash[6] |= 0x30;  // 设置为版本 3 (MD5 hash)
            hash[8] &= 0x3f;  // 清掉变体位
            hash[8] |= 0x80;  // 设置为变体 1

            long mostSigBits = 0;
            long leastSigBits = 0;

            for (int i = 0; i < 8; i++) {
                mostSigBits = (mostSigBits << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                leastSigBits = (leastSigBits << 8) | (hash[i] & 0xff);
            }

            return new UUID(mostSigBits, leastSigBits);
        } catch (java.security.NoSuchAlgorithmException e) {
            // 降级到随机 UUID
            return randomUUID();
        }
    }

    /**
     * 生成 Minecraft 风格的玩家 ID（8位十六进制）
     */
    public static String generatePlayerId(String username) {
        UUID uuid = generateOfflineUUID(username);
        // 取 UUID 的前8位作为玩家 ID
        long mostSig = uuid.getMostSignificantBits();
        return Long.toHexString(mostSig & 0xFFFFFFFFL);
    }

    /**
     * 将 UUID 格式化为 Minecraft 风格（带连字符）
     */
    public static String formatUUID(UUID uuid) {
        return uuid.toString();
    }

    /**
     * 将 UUID 格式化为无连字符形式
     */
    public static String formatUUIDNoDash(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /**
     * 从无连字符的字符串解析 UUID
     */
    public static UUID parseUUID(String uuidStr) {
        if (uuidStr.contains("-")) {
            return UUID.fromString(uuidStr);
        }
        // 移除可能的连字符
        String clean = uuidStr.replaceAll("-", "");
        return UUID.fromString(
                clean.substring(0, 8) + "-" +
                clean.substring(8, 12) + "-" +
                clean.substring(12, 16) + "-" +
                clean.substring(16, 20) + "-" +
                clean.substring(20, 32)
        );
    }

    /**
     * 验证 UUID 格式是否有效
     */
    public static boolean isValidUUID(String uuidStr) {
        try {
            parseUUID(uuidStr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 生成批量 UUID
     */
    public static List<UUID> generateBatch(int count) {
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            uuids.add(randomUUID());
        }
        return uuids;
    }
}
