package com.mclauncher.test.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端 → 服务端的反馈提交契约测试（需要先启动带 SMTP 配置的服务端）。 */
public final class FeedbackE2E {

    private static int passed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:15003";

        // 服务端限流：每 IP 每分钟 3 条。全部用例合并成 3 个请求。
        run("bug：完整字段 + 中文换行，返回 200", () -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "bug");
            payload.put("content", "联机时一直卡在正在登录\n复现：1. 开房 2. 好友加入\nE2E-MARKER-中文");
            payload.put("contact", "QQ 123456");
            payload.put("systemInfo", systemInfo());
            JsonObject result = postOk(base, payload);
            ok(result.has("success") && result.get("success").getAsBoolean(), "success=true");
            ok(result.getAsJsonObject("data").has("id"), "返回反馈编号 " + result);
        });

        run("suggestion 类型被接受", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "suggestion");
            p.put("content", "希望支持多账号同时登录");
            p.put("contact", "");
            p.put("systemInfo", systemInfo());
            postOk(base, p);
        });

        run("other 类型 + 空联系方式被接受", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "other");
            p.put("content", "其他反馈");
            p.put("contact", "");
            p.put("systemInfo", systemInfo());
            postOk(base, p);
        });

        if (failures.isEmpty()) {
            System.out.println("\n全部通过：" + passed + " 项");
            System.exit(0);
        }
        System.out.println("\n通过 " + passed + "，失败 " + failures.size());
        failures.forEach(f -> System.out.println("  - " + f));
        System.exit(1);
    }

    private static Map<String, String> systemInfo() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("os", System.getProperty("os.name", ""));
        m.put("osVersion", System.getProperty("os.version", ""));
        m.put("arch", System.getProperty("os.arch", ""));
        m.put("java", System.getProperty("java.version", ""));
        return m;
    }

    private static JsonObject postOk(String base, Map<String, Object> payload) throws Exception {
        String json = new com.google.gson.Gson().toJson(payload);
        HttpURLConnection conn = connect(base, json);
        try {
            int code = conn.getResponseCode();
            String body = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            eq(200, code, "HTTP 状态（body=" + body + "）");
            return JsonParser.parseString(body).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection connect(String base, String json) throws Exception {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) URI.create(base + "/api/feedback").toURL().openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b);
        }
        return c;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream i = in) {
            return new String(i.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("  FAIL " + name + " -> " + t);
        }
    }

    private static void ok(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void eq(Object expect, Object actual, String what) {
        if (!String.valueOf(expect).equals(String.valueOf(actual))) {
            throw new AssertionError(what + "：期望 " + expect + "，实际 " + actual);
        }
    }

    private FeedbackE2E() {}
}