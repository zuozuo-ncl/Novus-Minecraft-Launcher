package com.mclauncher.test;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简测试框架。
 * <h3>为什么不用 JUnit</h3>
 */
public final class Checks {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private Checks() {}

    public interface Case {
        void run() throws Exception;
    }

    /** 运行一条用例；抛异常即失败 */
    public static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("  FAIL " + name + " -> " + t);
        }
    }

    public static void assertTrue(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    public static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + "：期望 " + expected + "，实际 " + actual);
        }
    }

    public static void assertNotNull(Object o, String what) {
        if (o == null) {
            throw new AssertionError(what + "：为 null");
        }
    }

    /** 断言某个调用会抛指定异常 */
    public static <T extends Throwable> T assertThrows(Class<T> type, Case c, String what) {
        try {
            c.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError(what + "：抛出了 " + t.getClass().getName()
                    + "，期望 " + type.getName() + "（" + t.getMessage() + "）");
        }
        throw new AssertionError(what + "：没有抛出异常");
    }

    public static int passed() {
        return passed;
    }

    public static List<String> failures() {
        return List.copyOf(failures);
    }

    public static int summarize() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + passed + " 项");
            return 0;
        }
        System.out.println("通过 " + passed + "，失败 " + failures.size());
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        return 1;
    }
}