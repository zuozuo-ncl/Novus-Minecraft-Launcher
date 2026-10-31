package com.mclauncher.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UUIDUtils {

    private UUIDUtils() {}

    public static UUID randomUUID() {
        return UUID.randomUUID();
    }

    public static UUID generateOfflineUUID(String username) {
        String input = "OfflinePlayer:" + username;
        byte[] bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(bytes);

            hash[6] &= 0x0f;  
            hash[6] |= 0x30;  
            hash[8] &= 0x3f;  
            hash[8] |= 0x80;  

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

            return randomUUID();
        }
    }

    public static String generatePlayerId(String username) {
        UUID uuid = generateOfflineUUID(username);

        long mostSig = uuid.getMostSignificantBits();
        return Long.toHexString(mostSig & 0xFFFFFFFFL);
    }

    public static String formatUUID(UUID uuid) {
        return uuid.toString();
    }

    public static String formatUUIDNoDash(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    public static UUID parseUUID(String uuidStr) {
        if (uuidStr.contains("-")) {
            return UUID.fromString(uuidStr);
        }

        String clean = uuidStr.replaceAll("-", "");
        return UUID.fromString(
                clean.substring(0, 8) + "-" +
                clean.substring(8, 12) + "-" +
                clean.substring(12, 16) + "-" +
                clean.substring(16, 20) + "-" +
                clean.substring(20, 32)
        );
    }

    public static boolean isValidUUID(String uuidStr) {
        try {
            parseUUID(uuidStr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static List<UUID> generateBatch(int count) {
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            uuids.add(randomUUID());
        }
        return uuids;
    }
}
