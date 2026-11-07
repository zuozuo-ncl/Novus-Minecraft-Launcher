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

/**
 * 版本清单（%APPDATA%\NCL_Launcher\versions.json）
 * 因为第 2 步在窗口显示时就已经执行了。
 */
public class VersionManifest {

    private static final Logger logger = LoggerFactory.getLogger(VersionManifest.class);

    /** 清单文件名（与 jar 同目录） */
    public static final String FILE_NAME = "versions.json";

    /** 启动成功标记文件名 */
    public static final String STARTUP_OK_NAME = "startup.ok";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ---------- 数据 ----------

    /** 当前选定的版本（引导器优先启动它） */
    public String current;

    /** 最近一次确认可用的版本 */
    public String lastGood;

    /**
     * 正在试用 current 的起始时刻（毫秒时间戳）
     *
     * 0 表示没有未完成的试用 —— 要么已确认成功，要么根本没在试用。
     */
    public long pendingSince;

    /** 所有已下载版本的记录 */
    public List<Entry> versions = new ArrayList<>();

    /** 单个版本的记录 */
    public static class Entry {
        /** 版本号，如 "1.0.2" */
        public String version;

        /** 文件名（只存名字，不存绝对路径 —— 换机器/换盘符仍可用） */
        public String file;

        /** 是否被验证过可用 */
        public boolean usable;

        /** 连续失败的次数（用于诊断，也可用于「多次失败彻底放弃」策略） */
        public int failures;

        /** 最近一次确认可用的时刻（毫秒），0 表示从未成功 */
        public long lastGoodAt;
    }

    // ---------- 路径 ----------

    /**
     * 清单所在目录：与 jar 同一个地方
     * 而版本清单必须跟着 jar 走（jar 在 %APPDATA%\NCL_Launcher），
     */
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

    // ---------- 读写 ----------

    /**
     * 读取清单；不存在或损坏时返回一个「空白但可用」的实例
     *
     * 不抛异常：清单坏掉不该让启动器打不开，那样用户连回退都做不了。
     */
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
            // 备份坏文件，便于事后排查，但不阻塞启动
            try {
                Files.move(p, p.resolveSibling(FILE_NAME + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignore) {
                // 备份失败无所谓
            }
            return new VersionManifest();
        }
    }

    /**
     * 写入清单（原子替换）
     */
    /** 暂存本次需要同步登记到信任表的 版本->哈希（save 时一并写入） */
    private transient java.util.Map<String, String> pendingTrusted = new java.util.LinkedHashMap<>();

    /** 记录「刚下载的版本」（事务写的入口） */
    public synchronized void recordDownloaded(String version, String fileName,
                                              java.util.Map<String, String> trustedHashes) {
        recordDownloaded(version, fileName, trustedHashes, null);
    }

    /** 记录刚下载的版本，并可指定「current」（待验证的新版本不覆盖当前版本）。 */
    public synchronized void recordDownloaded(String version, String fileName,
                                              java.util.Map<String, String> trustedHashes,
                                              String keepCurrent) {
        Entry e = findOrCreate(version, fileName);
        if (e != null) {
            e.usable = false;       // 待验证：真正跑起来后由 LauncherApp 标为可用
            e.failures = 0;
        }
        if (trustedHashes != null) {
            for (var en : trustedHashes.entrySet()) {
                if (en.getKey() != null && en.getValue() != null) {
                    pendingTrusted.put(en.getKey(), en.getValue().toLowerCase());
                }
            }
        }
        if (keepCurrent != null && !keepCurrent.isBlank()) {
            this.current = keepCurrent;
        }
        logger.info("记录已下载版本: {} ({})", version, fileName);
    }

    public synchronized void save() {
        Path dir = manifestDir();
        try {
            Files.createDirectories(dir);
            // 写临时文件 + ATOMIC_MOVE：中途失败也不会留下半个 JSON。
            // 不能用写 .tmp 再普通 move —— 旧文件同时是引导器的输入，
            // 普通 move 在 Windows 上不是原子的，引导器可能读到空文件。
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

    // ---------- 辅助查询 ----------

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

    /** 已记录且被验证可用的版本，按版本号从高到低 */
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

    /** 全部已记录版本，按版本号从高到低 */
    public List<Entry> allDesc() {
        List<Entry> list = new ArrayList<>(versions);
        list.sort(Comparator.comparing((Entry e) -> e.version,
                VersionComparator.INSTANCE).reversed());
        return list;
    }

    // ---------- 状态机 ----------

    /** 开始试用某个版本（启动时调用） */
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

    /** 标记当前版本可用（界面成功显示后调用） */
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

    /** 上次启动是否「没走完就挂了」 */
    public boolean hasUnfinishedAttempt() {
        return pendingSince != 0;
    }

    /** 回退：把出问题的当前版本标记为不可用，挑下一个可用版本 */
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

        // 挑选回退目标，分两轮：
        // 为什么需要第二轮：失败的版本不一定是最高版本。比如用户手动
        List<Entry> candidates = usableDesc();

        String target = null;

        // 第一轮：更低的可用版本
        for (Entry e : candidates) {
            if (failed == null
                    || VersionComparator.INSTANCE.compare(e.version, failed) < 0) {
                target = e.version;
                break;
            }
        }

        // 第二轮：任意可用版本（排除刚失败的那个）
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

    /**
     * 扫描目录，把磁盘上存在但清单里没记录的 jar 补进来
     *
     * 场景：用户手动拷了 jar 进来，或清单丢失后重建。
     */
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
                // 只登记，不标记 usable —— 没见过它跑起来，不能当作可信版本
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

    /**
     * 从 Manifest 之外的地方提供「当前应该跑哪个版本」的答案
     *
     * 供引导器（C++ 侧读同一个 json）与 Java 侧共用同一套判断规则。
     */
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

    /** 重写 equals 之外的调试用输出 */
    @Override
    public String toString() {
        return "VersionManifest{current=" + current
                + ", lastGood=" + lastGood
                + ", pendingSince=" + pendingSince
                + ", versions=" + versions.size() + "}";
    }

    // ---------- 启动成功标记 ----------

    /** 写「启动成功」标记（在主窗口显示成功后调用） */
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

    /** 清除启动成功标记 */
    public static void clearStartupOk() {
        try {
            Files.deleteIfExists(manifestDir().resolve(STARTUP_OK_NAME));
        } catch (Exception e) {
            logger.debug("清除启动成功标记失败: {}", e.getMessage());
        }
    }

    /**
     * 版本号比较器（语义化，与其它模块规则一致）
     */
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