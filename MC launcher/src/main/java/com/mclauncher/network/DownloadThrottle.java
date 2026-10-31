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

public final class DownloadThrottle {

    private static final Logger logger = LoggerFactory.getLogger(DownloadThrottle.class);

    private static final DownloadThrottle INSTANCE = new DownloadThrottle();

    private static final int PER_HOST_DEFAULT = 8;

    private static final int PER_HOST_MIN = 1;

    private static final int PER_HOST_MAX = 64;

    private static final int GLOBAL_MAX = 128;

    private final Semaphore globalSemaphore = new Semaphore(GLOBAL_MAX, true);

    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();

    private DownloadThrottle() {}

    public static DownloadThrottle getInstance() {
        return INSTANCE;
    }

    private static class HostState {
        final String host;
        final AtomicInteger limit = new AtomicInteger(PER_HOST_DEFAULT);
        volatile Semaphore semaphore = new Semaphore(PER_HOST_DEFAULT, true);
        final AtomicLong cooldownUntil = new AtomicLong(0);
        final AtomicInteger successStreak = new AtomicInteger(0);

        final AtomicInteger throttleCount = new AtomicInteger(0);

        HostState(String host, int initialLimit) {
            this.host = host;
            int v = Math.max(PER_HOST_MIN, Math.min(PER_HOST_MAX, initialLimit));
            this.limit.set(v);
            this.semaphore = new Semaphore(v, true);
        }
    }

    public static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

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

    public void acquire(String url, long timeoutMillis) throws InterruptedException {
        HostState st = stateFor(url);

        long until = st.cooldownUntil.get();
        long now = System.currentTimeMillis();
        if (until > now) {
            long wait = Math.min(until - now, timeoutMillis);
            logger.debug("[{}] 限流冷却中，等待 {} ms", st.host, wait);
            Thread.sleep(wait);

        }

        if (!globalSemaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new InterruptedException(
                    "等待全局下载许可超时（" + timeoutMillis + "ms）");
        }

        try {
            if (!st.semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {

                globalSemaphore.release();
                throw new InterruptedException(
                        "[" + st.host + "] 等待下载许可超时（" + timeoutMillis + "ms）");
            }
        } catch (InterruptedException e) {
            globalSemaphore.release();
            throw e;
        }
    }

    public void acquire(String url) throws InterruptedException {
        acquire(url, Long.MAX_VALUE);
    }

    public void release(String url) {
        HostState st = hosts.get(hostOf(url));
        if (st != null) {
            st.semaphore.release();
        }
        globalSemaphore.release();
    }

    public void reportThrottled(String url, int statusCode) {
        HostState st = stateFor(url);
        st.successStreak.set(0);
        int count = st.throttleCount.incrementAndGet();

        int oldLimit = st.limit.get();
        int newLimit = Math.max(PER_HOST_MIN, oldLimit - 1);

        if (newLimit != oldLimit) {
            st.limit.set(newLimit);
            st.semaphore = new Semaphore(newLimit, true);
            logger.warn("[{}] 检测到限流（HTTP {}，累计 {} 次），本机并发 {} -> {}",
                    st.host, statusCode, count, oldLimit, newLimit);
        } else {
            logger.warn("[{}] 检测到限流（HTTP {}，累计 {} 次），已是最低并发 {}",
                    st.host, statusCode, count, newLimit);
        }

        long cooldown = Math.min(30_000L, 2_000L * Math.min(count, 15));
        st.cooldownUntil.set(Math.max(st.cooldownUntil.get(),
                System.currentTimeMillis() + cooldown));
        logger.info("[{}] 进入冷却 {} ms", st.host, cooldown);
    }

    public void reportSuccess(String url) {
        HostState st = stateFor(url);
        int streak = st.successStreak.incrementAndGet();

        if (streak >= 50) {
            st.successStreak.set(0);
            int oldLimit = st.limit.get();
            if (oldLimit < PER_HOST_MAX) {
                int newLimit = oldLimit + 1;
                st.limit.set(newLimit);
                st.semaphore = new Semaphore(newLimit, true);
                logger.info("[{}] 下载稳定，本机并发 {} -> {}", st.host, oldLimit, newLimit);
            }
        }
    }

    public int getLimit(String url) {
        return stateFor(url).limit.get();
    }

    public int getInFlight(String url) {
        HostState st = hosts.get(hostOf(url));
        if (st == null) {
            return 0;
        }

        return Math.max(0, st.limit.get() - st.semaphore.availablePermits());
    }

    public int getCurrentLimit() {

        int max = PER_HOST_DEFAULT;
        for (HostState st : hosts.values()) {
            max = Math.max(max, st.limit.get());
        }
        return max;
    }

    public boolean isThrottled(String url) {
        return stateFor(url).cooldownUntil.get() > System.currentTimeMillis();
    }

    public boolean isAnyDegraded() {
        for (HostState st : hosts.values()) {
            if (st.limit.get() < PER_HOST_DEFAULT || isThrottled(st.host)) {
                return true;
            }
        }
        return false;
    }

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

    public void reset() {
        hosts.clear();
        logger.info("限流状态已重置");
    }

    public int getGlobalMax() {
        return GLOBAL_MAX;
    }
}
