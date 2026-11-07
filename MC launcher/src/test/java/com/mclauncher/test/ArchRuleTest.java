package com.mclauncher.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.launcher.GameLauncher;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * {@link GameLauncher} 的 OS 架构规则测试（isLibraryAllowed / evaluateRules）。
 * 覆盖的重点是 ARM64：曾经用 os.arch.contains("64") 判断，ARM64 机器上
 */
public class ArchRuleTest {

    private static final Method EVAL;
    private static final Method LIB;
    private static final GameLauncher GL;

    static {
        try {
            EVAL = GameLauncher.class.getDeclaredMethod("evaluateRules", JsonArray.class);
            EVAL.setAccessible(true);
            LIB = GameLauncher.class.getDeclaredMethod("isLibraryAllowed", JsonObject.class);
            LIB.setAccessible(true);
            Constructor<GameLauncher> c = GameLauncher.class.getDeclaredConstructor(
                    com.mclauncher.config.ConfigManager.class, java.nio.file.Path.class);
            c.setAccessible(true);
            GL = c.newInstance(null, java.nio.file.Path.of("."));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) {
        System.out.println("—— GameLauncher 架构规则 ——");

        // {规则里的 arch, 期望在 amd64 / aarch64 / x86 机器上的结果}
        Object[][] cases = {
                {"x86", false, false, true},
                {"x86_64", true, false, false},
                {"amd64", true, false, false},
                {"arm64", false, true, false},
                {"aarch64", false, true, false},
        };
        String[] machines = {"amd64", "aarch64", "x86"};

        for (Object[] row : cases) {
            String want = (String) row[0];
            for (int i = 0; i < machines.length; i++) {
                String machine = machines[i];
                boolean expect = (Boolean) row[i + 1];
                String name = "arch=" + want + " @ " + machine;
                Checks.run(name, () -> {
                    String old = System.getProperty("os.arch");
                    System.setProperty("os.arch", machine);
                    try {
                        boolean e = evaluate(want);
                        boolean l = libraryAllowed(want);
                        Checks.assertEquals(expect, e, "evaluateRules");
                        Checks.assertEquals(expect, l, "isLibraryAllowed 与 evaluateRules 一致");
                    } finally {
                        if (old != null) {
                            System.setProperty("os.arch", old);
                        }
                    }
                });
            }
        }

        Checks.run("无 os.arch 的规则匹配所有机器", () -> {
            JsonArray rules = JsonParser.parseString("[{\"action\":\"allow\"}]").getAsJsonArray();
            Checks.assertEquals(true, EVAL.invoke(GL, rules), "evaluateRules");
        });

        Checks.run("除 os.arch 外的规则照常生效", () -> {
            JsonArray rules = JsonParser.parseString(
                    "[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}},"
                            + "{\"action\":\"disallow\",\"os\":{\"name\":\"linux\"}}]").getAsJsonArray();
            boolean expected = System.getProperty("os.name", "").toLowerCase().contains("win");
            Checks.assertEquals(expected, EVAL.invoke(GL, rules), "按 os.name 决定");
        });

    }

    private static boolean evaluate(String arch) throws Exception {
        JsonArray rules = JsonParser.parseString(
                "[{\"action\":\"allow\",\"os\":{\"name\":\"windows\",\"arch\":\"" + arch + "\"}}]")
                .getAsJsonArray();
        return (Boolean) EVAL.invoke(GL, rules);
    }

    private static boolean libraryAllowed(String arch) throws Exception {
        JsonObject lib = JsonParser.parseString(
                "{\"name\":\"group:artifact:1.0\",\"rules\":[{\"action\":\"allow\","
                        + "\"os\":{\"name\":\"windows\",\"arch\":\"" + arch + "\"}}]}")
                .getAsJsonObject();
        return (Boolean) LIB.invoke(GL, lib);
    }
}