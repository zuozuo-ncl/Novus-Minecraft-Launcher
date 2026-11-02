package com.mclauncher.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * 线程工具类
 * 提供线程池和异步任务管理功能
 */
public class ThreadUtils {

    private static final Logger logger = LoggerFactory.getLogger(ThreadUtils.class);

    /**
     * 安全地执行 Runnable，捕获并记录异常
     */
    public static void runSafely(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            logger.error("线程执行异常", e);
        }
    }

    /**
     * 在新线程中执行任务
     */
    public static Thread startThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 延迟执行任务
     */
    public static void delayExecute(long delayMs, Runnable task) {
        ThreadUtils.startThread(() -> {
            try {
                Thread.sleep(delayMs);
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "delay-" + System.currentTimeMillis());
    }

    /**
     * 周期性执行任务
     */
    public static void periodicExecute(long periodMs, Runnable task) {
        ThreadUtils.startThread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    task.run();
                    Thread.sleep(periodMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "periodic-" + System.currentTimeMillis());
    }

    /**
     * 等待线程完成，带超时
     */
    public static boolean waitForThread(Thread thread, long timeoutMs) {
        try {
            thread.join(timeoutMs);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 创建并启动一个后台线程
     */
    public static Thread backgroundTask(Runnable task) {
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.setName("background-" + System.currentTimeMillis());
        t.start();
        return t;
    }

    /**
     * 执行回调（如果在 FX 线程上则直接执行，否则调度到 FX 线程）
     */
    public static void runOnFxThread(Runnable task) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            task.run();
        } else {
            javafx.application.Platform.runLater(task);
        }
    }

    /**
     * 批量执行任务并收集结果
     */
    public static <T> List<T> batchExecute(List<Callable<T>> tasks) {
        List<T> results = new ArrayList<>();
        for (Callable<T> task : tasks) {
            try {
                results.add(task.call());
            } catch (Exception e) {
                logger.error("批量任务执行失败", e);
                results.add(null);
            }
        }
        return results;
    }
}
