package com.mclauncher.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class VersionManifest {

    private static final Logger logger = LoggerFactory.getLogger(VersionManifest.class);

    public static final String FILE_NAME = "versions.json";

    public static final String STARTUP_OK_NAME = "startup.ok";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public String current;

    public String lastGood;

    public long pendingSince;

    public List<Entry> versions = new ArrayList<>();

    public static class Entry {

        public String version;

        public String file;

        public boolean usable;

        public int failures;

        public long lastGoodAt;
    }

    public static Path manifestDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, "NCL_Launcher");
    }

    public static Path manifestPath() {
        return manifestDir().resolve(FILE_NAME);
    }

    public static VersionManifest load() {
        Path p = manifestPath();
        if (!Files.isRegularFile(p)) {
            return new VersionManifest();
        }

        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            VersionManifest m = GSON.fromJson(json,
                    new TypeToken<VersionManifest>() {}.getType());

            if (m == null) {
                logger.warn("版本清单内容为空，使用空白清单");
                return new VersionManifest();
            }
            if (m.versions == null) {
                m.versions = new ArrayList<>();
            }
            return m;

        } catch (Exception e) {
            logger.warn("版本清单解析失败（将重建）: {}", e.getMessage());

            try {
                Files.move(p, p.resolveSibling(FILE_NAME + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignore) {

            }
            return new VersionManifest();
        }
    }

    private transient java.util.Map<String, String> pendingTrusted = new java.util.LinkedHashMap<>();

    public synchronized void recordDownloaded(String version, String fileName,
                                              java.util.Map<String, String> trustedHashes) {
        Entry e = findOrCreate(version, fileName);
        if (e != null) {
            e.usable = false;       
            e.failures = 0;
        }
        if (trustedHashes != null) {
            for (var en : trustedHashes.entrySet()) {
                if (en.getKey() != null && en.getValue() != null) {
                    pendingTrusted.put(en.getKey(), en.getValue().toLowerCase());
                }
            }
        }
        logger.info("记录已下载版本: {} ({})", version, fileName);
    }

    public synchronized void save() {
        Path dir = manifestDir();
        try {
            Files.createDirectories(dir);

            

            com.mclauncher.utils.FileUtils.writeAtomic(manifestPath(), GSON.toJson(this));
            logger.debug("版本清单已保存: {}", manifestPath());
            flushTrusted();
        } catch (Exception e) {
            logger.warn("保存版本清单失败: {}", e.getMessage());
        }
    }

    private void flushTrusted() {
        if (pendingTrusted.isEmpty()) {
            return;
        }
        try {
            Path f = com.mclauncher.launcher.JarIntegrity.appDataDir()
                    .resolve(com.mclauncher.launcher.JarIntegrity.TRUSTED_FILE);
            java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
            if (java.nio.file.Files.exists(f)) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                        .matcher(java.nio.file.Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    map.put(m.group(1), m.group(2).toLowerCase());
                }
            }
            map.putAll(pendingTrusted);
            StringBuilder sb = new StringBuilder("{\n");
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("  \"").append(e.getKey().replace("\"", "")).append("\": \"")
                        .append(e.getValue()).append("\"");
            }
            com.mclauncher.utils.FileUtils.writeAtomic(f, sb.append("\n}\n").toString());
            logger.info("信任表已更新（{} 项）", map.size());
            pendingTrusted.clear();
        } catch (Exception ex) {
            logger.warn("写信任表失败（不影响使用）: {}", ex.getMessage());
        }
    }

    public Entry find(String version) {
        if (version == null) {
            return null;
        }
        for (Entry e : versions) {
            if (version.equals(e.version)) {
                return e;
            }
        }
        return null;
    }

    public Entry findOrCreate(String version, String file) {
        Entry e = find(version);
        if (e == null) {
            e = new Entry();
            e.version = version;
            e.file = file;
            versions.add(e);
        } else if (file != null) {
            e.file = file;
        }
        return e;
    }

    public List<Entry> usableDesc() {
        List<Entry> list = new ArrayList<>();
        for (Entry e : versions) {
            if (e.usable) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparing((Entry e) -> e.version,
                VersionComparator.INSTANCE).reversed());
        return list;
    }

    public List<Entry> allDesc() {
        List<Entry> list = new ArrayList<>(versions);
        list.sort(Comparator.comparing((Entry e) -> e.version,
                VersionComparator.INSTANCE).reversed());
        return list;
    }

    public synchronized void beginAttempt(String version) {
        Entry e = findOrCreate(version, "mc-launcher-" + version + ".jar");

        if (!version.equals(current)) {
            current = version;
        }
        if (pendingSince == 0) {
            pendingSince = Instant.now().toEpochMilli();
        }

        logger.info("开始试用版本 {}（pendingSince={}）", version, pendingSince);
        save();
    }

    public synchronized boolean markGood() {
        if (current == null) {
            return false;
        }
        Entry e = findOrCreate(current, null);
        if (e == null) {
            return false;
        }

        boolean changed = false;

        if (pendingSince != 0) {
            pendingSince = 0;
            changed = true;
        }
        if (!e.usable) {
            e.usable = true;
            changed = true;
        }
        if (e.failures != 0) {
            e.failures = 0;
            changed = true;
        }
        if (!current.equals(lastGood)) {
            lastGood = current;
            changed = true;
        }
        e.lastGoodAt = Instant.now().toEpochMilli();

        if (changed) {
            logger.info("版本 {} 已确认可用（lastGood={}）", current, lastGood);
            save();
        }
        return changed;
    }

    public boolean hasUnfinishedAttempt() {
        return pendingSince != 0;
    }

    public synchronized String rollback() {
        String failed = current;

        if (failed != null) {
            Entry e = findOrCreate(failed, null);
            e.failures = e.failures + 1;
            e.usable = false;
            logger.warn("版本 {} 试用失败（累计 {} 次），标记为不可用",
                    failed, e.failures);
        }

        pendingSince = 0;

        

        

        

        

        

        

        
        List<Entry> candidates = usableDesc();

        String target = null;

        for (Entry e : candidates) {
            if (failed == null
                    || VersionComparator.INSTANCE.compare(e.version, failed) < 0) {
                target = e.version;
                break;
            }
        }

        if (target == null) {
            for (Entry e : candidates) {
                if (!e.version.equals(failed)) {
                    target = e.version;
                    break;
                }
            }
            if (target != null) {
                logger.info("没有更低的可用版本，改用已验证可用的 {}（版本号高于失败的 {}）",
                        target, failed);
            }
        }

        if (target == null) {
            logger.error("没有已知可用的版本可回退，当前版本 {} 也无法确认可用", failed);
            save();
            return null;
        }

        current = target;
        logger.info("已回退: {} -> {}", failed, target);
        save();
        return target;
    }

    public synchronized void syncWithDisk() {
        Path dir = manifestDir();
        if (!Files.isDirectory(dir)) {
            return;
        }

        boolean changed = false;

        try (var stream = Files.newDirectoryStream(dir, "mc-launcher-*.jar")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String ver = name.substring("mc-launcher-".length(),
                        name.length() - ".jar".length());
                if (ver.isBlank() || find(ver) != null) {
                    continue;
                }

                Entry e = new Entry();
                e.version = ver;
                e.file = name;
                e.usable = false;
                versions.add(e);
                changed = true;
                logger.info("清单新增未验证版本: {}", ver);
            }
        } catch (Exception e) {
            logger.debug("扫描 jar 失败: {}", e.getMessage());
        }

        if (changed) {
            save();
        }
    }

    public String preferredVersion() {
        if (current != null && find(current) != null) {
            return current;
        }
        List<Entry> u = usableDesc();
        if (!u.isEmpty()) {
            return u.get(0).version;
        }
        List<Entry> all = allDesc();
        if (!all.isEmpty()) {
            return all.get(0).version;
        }
        return null;
    }

    @Override
    public String toString() {
        return "VersionManifest{current=" + current
                + ", lastGood=" + lastGood
                + ", pendingSince=" + pendingSince
                + ", versions=" + versions.size() + "}";
    }

    public static void writeStartupOk() {
        Path p = manifestDir().resolve(STARTUP_OK_NAME);
        try {
            Files.createDirectories(p.getParent());
            String pid = String.valueOf(ProcessHandle.current().pid());
            Files.writeString(p, pid, StandardCharsets.UTF_8);
            logger.debug("已写启动成功标记: {} -> {}", p, pid);
        } catch (Exception e) {
            logger.warn("写启动成功标记失败（不影响使用）: {}", e.getMessage());
        }
    }

    public static void clearStartupOk() {
        try {
            Files.deleteIfExists(manifestDir().resolve(STARTUP_OK_NAME));
        } catch (Exception e) {
            logger.debug("清除启动成功标记失败: {}", e.getMessage());
        }
    }

    public static final class VersionComparator implements Comparator<String> {
        public static final VersionComparator INSTANCE = new VersionComparator();

        @Override
        public int compare(String a, String b) {
            if (a == null || b == null) {
                return 0;
            }
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int va = i < pa.length ? seg(pa[i]) : 0;
                int vb = i < pb.length ? seg(pb[i]) : 0;
                if (va != vb) {
                    return Integer.compare(va, vb);
                }
            }
            return 0;
        }

        private static int seg(String s) {
            StringBuilder d = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) {
                    d.append(c);
                } else {
                    break;
                }
            }
            try {
                return d.isEmpty() ? 0 : Integer.parseInt(d.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
