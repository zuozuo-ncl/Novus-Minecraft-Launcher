package com.mclauncher.network;


import com.mclauncher.i18n.L;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载限流器（按站点独立限流 + 全局总量控制）
 * ===== 为什么按站点独立 =====
 */
public final class DownloadThrottle {

    private static final Logger logger = LoggerFactory.getLogger(DownloadThrottle.class);

    private static final DownloadThrottle INSTANCE = new DownloadThrottle();

    /**
     * 每个 host 的初始并发额度
     * 这个值会尽量从 {@link MirrorPool} 的实测数据里取该站的安全并发；
     */
    private static final int PER_HOST_DEFAULT = 8;
    /** 每个 host 的最低额度（被限流时可降到 1） */
    private static final int PER_HOST_MIN = 1;
    /** 每个 host 的最高额度（实测 SJTU 128 并发无碍，这里封顶 64） */
    private static final int PER_HOST_MAX = 64;

    /**
     * 全局并发总上限。
     * 跨 5 个可用镜像站分散，因此可以设得比较高。
     */
    private static final int GLOBAL_MAX = 128;

    /** 全局信号量：控制总连接数 */
    private final Semaphore globalSemaphore = new Semaphore(GLOBAL_MAX, true);

    /** 各 host 的状态 */
    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();

    private DownloadThrottle() {}

    public static DownloadThrottle getInstance() {
        return INSTANCE;
    }

    /**
     * 单个 host 的限流状态
     */
    private static class HostState {
        final String host;
        /** 当前并发额度。信号量本身**不替换**：只按额度增减许可。 */
        final AtomicInteger limit = new AtomicInteger(PER_HOST_DEFAULT);
        final Semaphore semaphore;
        final AtomicLong cooldownUntil = new AtomicLong(0);
        final AtomicInteger successStreak = new AtomicInteger(0);
        /** 累计被限流次数，用于诊断 */
        final AtomicInteger throttleCount = new AtomicInteger(0);
        /** 连续失败次数（连接失败/404），达到阈值后暂时不再被选中 */
        final AtomicInteger failures = new AtomicInteger(0);

        HostState(String host, int initialLimit) {
            this.host = host;
            int v = Math.max(PER_HOST_MIN, Math.min(PER_HOST_MAX, initialLimit));
            this.limit.set(v);
            this.semaphore = new Semaphore(v, true);
        }

        /**
         * 把额度调整到 newLimit，并同步信号量里的许可数。
         */
        void adjustLimit(int newLimit, boolean grow) {
            int old = limit.getAndSet(newLimit);
            int delta = Math.abs(newLimit - old);
            if (delta == 0) {
                return;
            }
            if (grow) {
                semaphore.release(delta);
            } else {
                // 回收差额许可。正在持锁的请求稍后 release 时，多出来的许可
                // 会留在信号量里 —— 用 drain 的方式把多余许可扣掉，
                // 保证「可用许可数 <= limit」这个不变式。
                semaphore.tryAcquire(delta);
            }
        }
    }

    /**
     * 从 URL 提取 host（作为限流分组键）
     */
    public static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 为某个 host 推导初始额度
     * 优先用 MirrorPool 里实测的安全并发值，
     */
    private static int initialLimitFor(String host) {
        try {
            for (MirrorPool.Mirror m : MirrorPool.getInstance().getAllMirrors()) {
                String mHost = java.net.URI.create(m.getBaseUrl()).getHost();
                if (host.equals(mHost)) {
                    return m.getSafeConcurrency();
                }
            }
        } catch (Exception ignored) {
        }
        return PER_HOST_DEFAULT;
    }

    private HostState stateFor(String url) {
        String h = hostOf(url);
        return hosts.computeIfAbsent(h, host -> {
            int init = initialLimitFor(host);
            logger.info("[{}] 初始化并发额度 = {}", host, init);
            return new HostState(host, init);
        });
    }

    // ==================== 许可申请 ====================

    /** 申请下载许可（会阻塞直到该 host 有空位或超时） */
    public void acquire(String url, long timeoutMillis) throws InterruptedException {
        HostState st = stateFor(url);

        // 1. 等该 host 的冷却结束（带上限）
        long until = st.cooldownUntil.get();
        long now = System.currentTimeMillis();
        if (until > now) {
            long wait = Math.min(until - now, timeoutMillis);
            logger.debug("[{}] 限流冷却中，等待 {} ms", st.host, wait);
            Thread.sleep(wait);
            // 冷却结束后继续往下（允许排队等信号量）
        }

        // 2. 先占全局名额，再占该 host 名额
        if (!globalSemaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new InterruptedException(
                    "等待全局下载许可超时（" + timeoutMillis + "ms）");
        }

        try {
            if (!st.semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
                // 没抢到该站名额，释放全局名额
                globalSemaphore.release();
                throw new InterruptedException(
                        "[" + st.host + "] 等待下载许可超时（" + timeoutMillis + "ms）");
            }
        } catch (InterruptedException e) {
            globalSemaphore.release();
            throw e;
        }
    }

    /**
     * 无超时申请（兼容旧调用点）
     */
    public void acquire(String url) throws InterruptedException {
        acquire(url, Long.MAX_VALUE);
    }

    /**
     * 释放下载许可
     */
    public void release(String url) {
        HostState st = hosts.get(hostOf(url));
        if (st != null) {
            st.semaphore.release();
        }
        globalSemaphore.release();
    }

    // ==================== 反馈 ====================

    /**
     * 报告遇到限流（429/403）
     *
     * 只降低这一个 host 的额度，不影响其它站。
     */
    public void reportThrottled(String url, int statusCode) {
        HostState st = stateFor(url);
        st.successStreak.set(0);
        int count = st.throttleCount.incrementAndGet();

        int oldLimit = st.limit.get();
        int newLimit = Math.max(PER_HOST_MIN, oldLimit - 1);

        if (newLimit != oldLimit) {
            st.adjustLimit(newLimit, false);
            logger.warn("[{}] 检测到限流（HTTP {}，累计 {} 次），本机并发 {} -> {}",
                    st.host, statusCode, count, oldLimit, newLimit);
        } else {
            logger.warn("[{}] 检测到限流（HTTP {}，累计 {} 次），已是最低并发 {}",
                    st.host, statusCode, count, newLimit);
        }

        // 冷却：随累计限流次数递增，上限 30 秒
        long cooldown = Math.min(30_000L, 2_000L * Math.min(count, 15));
        st.cooldownUntil.set(Math.max(st.cooldownUntil.get(),
                System.currentTimeMillis() + cooldown));
        logger.info("[{}] 进入冷却 {} ms", st.host, cooldown);
    }

    /**
     * 报告一次失败：把排除期拉长，避免一直往一个坏站点上撞
     */
    public void reportFailure(String url) {
        HostState st = stateFor(url);
        st.failures.incrementAndGet();
    }

    /** 某 host 是否刚连续失败，暂不应再选它 */
    public boolean isBadHost(String url) {
        HostState st = hosts.get(hostOf(url));
        return st != null && st.failures.get() >= 3;
    }

    /**
     * 报告一次成功（用于缓慢恢复该 host 的额度）
     */
    public void reportSuccess(String url) {
        HostState st = stateFor(url);
        st.failures.set(0);
        int streak = st.successStreak.incrementAndGet();

        // 每 20 次成功 +1：老版本恢复要慢得像摊上事儿，会把一次限流吃上半天
        if (streak >= 20) {
            st.successStreak.set(0);
            int oldLimit = st.limit.get();
            if (oldLimit < PER_HOST_MAX) {
                int newLimit = oldLimit + 1;
                st.adjustLimit(newLimit, true);
                logger.info("[{}] 下载稳定，本机并发 {} -> {}", st.host, oldLimit, newLimit);
            }
        }
    }

    // ==================== 查询 ====================

    /**
     * 获取某个 host 当前的并发额度
     */
    public int getLimit(String url) {
        return stateFor(url).limit.get();
    }

    /**
     * 获取某个 host 当前正在进行中的请求数
     *
     * 用于分配器避开已被占满的站点，避免线程堆积。
     */
    public int getInFlight(String url) {
        HostState st = hosts.get(hostOf(url));
        if (st == null) {
            return 0;
        }
        // Semaphore.availablePermits() = 剩余可用名额
        return Math.max(0, st.limit.get() - st.semaphore.availablePermits());
    }

    /**
     * 全局默认额度（用于不指定 host 的场合，如分块下载预估）
     */
    public int getCurrentLimit() {
        // 返回当前最宽松的 host 额度，作为分块数的参考
        int max = PER_HOST_DEFAULT;
        for (HostState st : hosts.values()) {
            max = Math.max(max, st.limit.get());
        }
        return max;
    }

    /**
     * 某 host 是否正在冷却
     */
    public boolean isThrottled(String url) {
        String h = hostOf(url);
        HostState st = hosts.get(h);
        if (st == null) {
            st = hosts.get(url);     // describe()/isAnyDegraded() 传的是 host 本身
        }
        return st != null && st.cooldownUntil.get() > System.currentTimeMillis();
    }

    /** 清理长时间没有活动的 host 状态。 */
    /**
     * 是否存在任何被限流的 host（用于 UI 提示）
     */
    public boolean isAnyDegraded() {
        for (HostState st : hosts.values()) {
            if (st.limit.get() < PER_HOST_DEFAULT || isThrottled(st.host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成诊断摘要（用于日志/设置页展示）
     */
    public String describe() {
        if (hosts.isEmpty()) {
            return L.t("s_8801a646a4");
        }
        StringBuilder sb = new StringBuilder();
        hosts.forEach((host, st) -> {
            sb.append(host)
              .append(L.t("s_e342b08df3")).append(st.limit.get())
              .append(L.t("s_db6c49e1fb")).append(st.throttleCount.get());
            if (isThrottled(host)) {
                sb.append(L.t("s_3f3790bbd7"));
            }
            sb.append("\n");
        });
        return sb.toString().trim();
    }

    /**
     * 重置全部状态
     */
    public void reset() {
        hosts.clear();
        logger.info("限流状态已重置");
    }

    /**
     * 获取全局并发上限（供 UI 显示）
     */
    public int getGlobalMax() {
        return GLOBAL_MAX;
    }
}