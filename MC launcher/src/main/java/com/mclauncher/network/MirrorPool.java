package com.mclauncher.network;

import com.mclauncher.i18n.L;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class MirrorPool {

    private static final Logger logger = LoggerFactory.getLogger(MirrorPool.class);

    public static class Mirror {
        final String name;
        final String baseUrl;

        volatile long speed;

        volatile int safeConcurrency;

        volatile boolean supportsRange;

        volatile boolean usable;

        final AtomicInteger failures = new AtomicInteger(0);

        final AtomicInteger assigned = new AtomicInteger(0);

        Mirror(String name, String baseUrl, long speed, int safeConcurrency,
               boolean supportsRange, boolean usable) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.speed = speed;
            this.safeConcurrency = safeConcurrency;
            this.supportsRange = supportsRange;
            this.usable = usable;
        }

        public String getName() { return name; }
        public String getBaseUrl() { return baseUrl; }
        public long getSpeed() { return speed; }
        public int getSafeConcurrency() { return safeConcurrency; }
        public boolean isSupportsRange() { return supportsRange; }
        public boolean isUsable() { return usable; }

        @Override
        public String toString() {
            return String.format("%s (%.1f MB/s, c<=%d)", name, speed / 1048576.0, safeConcurrency);
        }
    }

    private static final Mirror[] KNOWN_MIRRORS = {

            new Mirror(L.t("s_25c10df594"), "https://mirror.nyist.edu.cn/bmclapi",
                    28110000L, 64, true, true),
            new Mirror(L.t("s_932a38944f"), "https://mirror.sjtu.edu.cn/bmclapi",
                    25070000L, 64, true, true),
            new Mirror(L.t("s_84e4e5819b"), "https://mirrors.ha.edu.cn/bmclapi",
                    10230000L, 16, true, true),
            new Mirror(L.t("s_cca18e0f90"), "https://mirrors.hit.edu.cn/bmclapi",
                    7710000L, 32, true, true),
            new Mirror(L.t("s_c1ab796dcc"), "https://mirrors.qlu.edu.cn/bmclapi",
                    1920000L, 8, true, true),

            
            new Mirror(L.t("s_6d49a81d12"), "https://mirrors.ustc.edu.cn/bmclapi",
                    5410000L, 1, false, false),
            new Mirror(L.t("s_531fea1e60"), "https://bmclapi2.bangbang93.com/maven",
                    9080000L, 1, true, false),
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

    public List<Mirror> getAvailableMirrors() {
        List<Mirror> list = new ArrayList<>();
        for (Mirror m : mirrors) {
            if (!m.usable) {
                continue;
            }
            if (m.failures.get() >= 5) {
                continue;   
            }
            list.add(m);
        }
        list.sort((a, b) -> Long.compare(b.speed, a.speed));
        return list;
    }

    public Mirror next() {
        List<Mirror> available = getAvailableMirrors();
        if (available.isEmpty()) {
            return null;
        }

        List<Mirror> ready = new ArrayList<>();
        DownloadThrottle throttle = DownloadThrottle.getInstance();
        for (Mirror m : available) {
            int limit = throttle.getLimit(m.baseUrl);
            int used = throttle.getInFlight(m.baseUrl);
            if (used < limit) {
                ready.add(m);
            }
        }

        List<Mirror> pool = ready.isEmpty() ? available : ready;

        int idx = Math.abs(roundRobin.getAndIncrement() % pool.size());
        Mirror m = pool.get(idx);
        m.assigned.incrementAndGet();
        return m;
    }

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

    public String buildAssetUrl(Mirror mirror, String hash) {
        if (mirror == null || hash == null || hash.length() < 2) {
            return null;
        }
        String base = mirror.baseUrl;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + "assets/" + hash;
    }

    public boolean isEnabled() {
        return !getAvailableMirrors().isEmpty();
    }

    public List<Mirror> getAllMirrors() {
        return new ArrayList<>(mirrors);
    }

    public int totalSafeConcurrency() {
        int sum = 0;
        for (Mirror m : getAvailableMirrors()) {
            sum += m.safeConcurrency;
        }
        return sum;
    }
}
