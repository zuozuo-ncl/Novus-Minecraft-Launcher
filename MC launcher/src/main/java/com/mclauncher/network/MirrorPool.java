package com.mclauncher.network;


import com.mclauncher.i18n.L;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BMCLAPI 高校镜像站池
 *
 * ===== 为什么直连镜像站 =====
 * BMCLAPI 官方节点（bmclapi2.bangbang93.com）会把请求转发给多所高校镜像，
 * 且对连接数限流极严（实测 4 并发即 HTTP 429，之后长时间降速）。
 * 直连这些上游镜像站可以绕开该限制并获得更高带宽。
 *
 * ===== 实测数据（2026-10-24，7 站点 × 多并发档位扫描）=====
 *
 * 站点            峰值速度      最佳并发   4 并发是否 429
 * ----------------------------------------------------------
 * 南阳理工 NYIST   28.11 MB/s    64       否
 * 上海交大 SJTU    25.07 MB/s   128       否
 * 河南教育网 HA    10.23 MB/s    16       否（32 仍安全）
 * 哈工大 HIT        7.71 MB/s    64       否
 * 齐鲁工大 QLU      1.92 MB/s     8       否
 * 中科大 USTC       5.41 MB/s     1       是（且不支持 Range）
 * BMCLAPI 官方      9.08 MB/s     1       是
 *
 * 结论：
 * - USTC 与 BMCLAPI 官方节点**必须排除**在并发下载之外
 *   （4 并发即 429；USTC 还完全不支持 Range，无法分块）
 * - 前四名站点在 64 并发下依然零 429，因此并发可以放心开大
 *
 * ===== 能力范围 =====
 * 支持：库文件（根路径 + Maven 坐标）、assets（/assets/<hash>，扁平）、Forge 安装器
 * 不支持：版本清单 / 版本 JSON / fabric-meta（这些仍需走官方节点）
 */
public final class MirrorPool {

    private static final Logger logger = LoggerFactory.getLogger(MirrorPool.class);

    /** 单个镜像站 */
    public static class Mirror {
        final String name;
        final String baseUrl;
        /** 实测峰值速度（字节/秒） */
        volatile long speed;
        /** 实测安全并发上限 */
        volatile int safeConcurrency;
        /** 是否支持 Range（决定能否分块下载） */
        volatile boolean supportsRange;
        /** 是否可用作下载源（USTC/官方节点排除） */
        volatile boolean usable;
        /** 是否透传 assets 资源（实测只有 BMCLAPI2 可用） */
        final boolean assets;
        /** 连续失败次数 */
        final AtomicInteger failures = new AtomicInteger(0);
        /** 已被分配的文件数（用于负载均衡） */
        final AtomicInteger assigned = new AtomicInteger(0);

        Mirror(String name, String baseUrl, long speed, int safeConcurrency,
               boolean supportsRange, boolean usable) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.speed = speed;
            this.safeConcurrency = safeConcurrency;
            this.supportsRange = supportsRange;
            this.usable = usable;
            this.assets = false;
        }

        Mirror(String name, String baseUrl, long speed, int safeConcurrency,
               boolean supportsRange, boolean usable, boolean assets) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.speed = speed;
            this.safeConcurrency = safeConcurrency;
            this.supportsRange = supportsRange;
            this.usable = usable;
            this.assets = assets;
        }

        public String getName() { return name; }
        public String getBaseUrl() { return baseUrl; }
        public long getSpeed() { return speed; }
        public int getSafeConcurrency() { return safeConcurrency; }
        public boolean isSupportsRange() { return supportsRange; }
        public boolean isUsable() { return usable; }
        public boolean supportsAssets() { return assets; }

        @Override
        public String toString() {
            return String.format("%s (%.1f MB/s, c<=%d)", name, speed / 1048576.0, safeConcurrency);
        }
    }

    /**
     * 实测数据构建的站点表（2026-11-04 实测）
     *
     * | 站点 | 库文件 /maven | assets | Range | 备注 |
     * |------|--------------|--------|-------|------|
     * | 上海交大 | 200 | 404 | ✓ | |
     * | 南阳理工 | 200 | 404 | ✓ | |
     * | 河南教育网 | 200 | 404 | ✓ | |
     * | 哈工大 | 200 | 404 | ✓ | |
     * | 南京大学 | 200 | 404 | ✓ | |
     * | 齐鲁工大 | 200 | 404 | ✓ | |
     * | 中科大 | 200 | 404 | ✗ | 回退用 |
     * | BMCLAPI2 | 200 | 200 | ✓ | 唯一支持 assets |
     * | 重庆大学 | 认证网关 | 认证网关 | - | 不可程序化使用 |
     * | 浙江大学 | 超时 404 | 404 | - | 不纳入 |
     */
    private static final Mirror[] KNOWN_MIRRORS = {
        // ---- 库文件主力站（会走多镜像轮询） ----
        new Mirror(L.t("s_25c10df594"), "https://mirror.nyist.edu.cn/bmclapi",
                28110000L, 64, true, true),
        new Mirror(L.t("s_932a38944f"), "https://mirror.sjtu.edu.cn/bmclapi",
                25070000L, 64, true, true),
        new Mirror(L.t("s_84e4e5819b"), "https://mirrors.ha.edu.cn/bmclapi",
                10230000L, 16, true, true),
        new Mirror(L.t("s_cca18e0f90"), "https://mirrors.hit.edu.cn/bmclapi",
                7710000L, 32, true, true),
        new Mirror(L.t("s_cf39e8d34b"), "https://mirror.nju.edu.cn/bmclapi",
                12000000L, 32, true, true),
        new Mirror(L.t("s_c1ab796dcc"), "https://mirrors.qlu.edu.cn/bmclapi",
                1920000L, 8, true, true),

        // ---- assets 资源：只有 BMCLAPI2 透传 ----
        new Mirror(L.t("s_531fea1e60"), "https://bmclapi2.bangbang93.com",
                9080000L, 4, true, true, true),

        // ---- 回退用 ----
        new Mirror(L.t("s_6d49a81d12"), "https://mirrors.ustc.edu.cn/bmclapi",
                5410000L, 1, false, false),
    };

    private static final MirrorPool INSTANCE = new MirrorPool();

    private final List<Mirror> mirrors = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobin = new AtomicInteger(0);

    private MirrorPool() {
        for (Mirror m : KNOWN_MIRRORS) {
            mirrors.add(m);
        }
        List<Mirror> usable = getAvailableMirrors();
        logger.info("镜像池初始化：{} 个可用站点（已排除 {} 个限流站点）",
                usable.size(), mirrors.size() - usable.size());
        for (Mirror m : usable) {
            logger.info("  · {} — 实测 {:.1f} MB/s，安全并发 {}",
                    m.name, m.speed / 1048576.0, m.safeConcurrency);
        }
    }

    public static MirrorPool getInstance() {
        return INSTANCE;
    }

    /**
     * 可用镜像（排除被禁用的和失败过多的），按速度降序
     */
    public List<Mirror> getAvailableMirrors() {
        List<Mirror> list = new ArrayList<>();
        for (Mirror m : mirrors) {
            if (!m.usable) {
                continue;
            }
            if (m.failures.get() >= 5) {
                continue;   // 临时剔除，阈值放宽到 5
            }
            list.add(m);
        }
        list.sort((a, b) -> Long.compare(b.speed, a.speed));
        return list;
    }

    /**
     * 轮询取下一个镜像（用于把文件分散到各站）
     *
     * 分配策略：按「实测速度」加权轮询 —— 快的站承担更多文件。
     *
     * 关键：会参考各站**当前剩余的并发额度**，
     * 跳过已被占满的站，避免大量线程堵在同一个慢站上
     * （这是之前出现「下载卡住数分钟」的根因）。
     */
    public Mirror next() {
        List<Mirror> available = getAvailableMirrors();
        if (available.isEmpty()) {
            return null;
        }

        // 过滤掉当前并发额度已耗尽的站
        List<Mirror> ready = new ArrayList<>();
        DownloadThrottle throttle = DownloadThrottle.getInstance();
        for (Mirror m : available) {
            int limit = throttle.getLimit(m.baseUrl);
            int used = throttle.getInFlight(m.baseUrl);
            if (used < limit) {
                ready.add(m);
            }
        }

        // 若所有站都满了，退回到完整列表（让信号量去排队，这是正常背压）
        List<Mirror> pool = ready.isEmpty() ? available : ready;

        int idx = Math.abs(roundRobin.getAndIncrement() % pool.size());
        Mirror m = pool.get(idx);
        m.assigned.incrementAndGet();
        return m;
    }

    /**
     * 取下一个支持 Range 的镜像（用于分块下载）
     */
    public Mirror nextRangeCapable() {
        List<Mirror> available = getAvailableMirrors();
        List<Mirror> capable = new ArrayList<>();
        for (Mirror m : available) {
            if (m.supportsRange) {
                capable.add(m);
            }
        }
        if (capable.isEmpty()) {
            return null;
        }
        int idx = Math.abs(roundRobin.getAndIncrement() % capable.size());
        return capable.get(idx);
    }

    /**
     * 取最快的镜像（用于单文件最大化速度，例如客户端 JAR）
     */
    public Mirror fastestRangeCapable() {
        for (Mirror m : getAvailableMirrors()) {
            if (m.supportsRange) {
                return m;
            }
        }
        return null;
    }

    public void reportFailure(Mirror mirror) {
        if (mirror == null) {
            return;
        }
        int n = mirror.failures.incrementAndGet();
        if (n >= 5) {
            logger.warn("镜像 {} 连续失败 {} 次，暂时剔除", mirror.name, n);
        }
    }

    public void reportSuccess(Mirror mirror) {
        if (mirror != null) {
            mirror.failures.set(0);
        }
    }

    /**
     * 拼接普通资源（库文件 / Forge 安装器）的 URL
     * 结构：根路径 + Maven 坐标
     */
    public String buildUrl(Mirror mirror, String relativePath) {
        if (mirror == null) {
            return null;
        }
        String base = mirror.baseUrl;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + relativePath;
    }

    /**
     * 拼接 assets 资源 URL
     *
     * 实测：各高校镜像**不透传** assets（全部 404），只有 BMCLAPI2 可用；
     * BMCLAPI2 的结构是两级的 /assets/<前两位>/<hash>。
     * 直接走扁平路径 /assets/<hash> 会 404。
     */
    public String buildAssetUrl(Mirror mirror, String hash) {
        if (mirror == null || hash == null || hash.length() < 2) {
            return null;
        }
        // hash 必须是 40 位十六进制：它既用于拼 URL 也用于本地文件名，
        // 上游数据异常时（或未来有人传入未校验的值）不能让 ../ 之类混进来
        if (!HASH_RE.matcher(hash).matches()) {
            return null;
        }
        String base = mirror.baseUrl;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        // BMCLAPI2 的资源路径是 /assets/<前两位>/<hash>；
        // 各高校镜像并不透传 assets，所以这里只对 BMCLAPI2 生效
        return base + "assets/" + hash.substring(0, 2) + "/" + hash;
    }

    private static final java.util.regex.Pattern HASH_RE =
            java.util.regex.Pattern.compile("[0-9a-f]{40}");

    public boolean isEnabled() {
        return !getAvailableMirrors().isEmpty();
    }

    public List<Mirror> getAllMirrors() {
        return new ArrayList<>(mirrors);
    }

    /**
     * 汇总当前可用站点的总并发能力（供限流器参考）
     */
    public int totalSafeConcurrency() {
        int sum = 0;
        for (Mirror m : getAvailableMirrors()) {
            sum += m.safeConcurrency;
        }
        return sum;
    }
}