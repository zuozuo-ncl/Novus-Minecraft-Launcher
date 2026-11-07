package com.mclauncher.test;

import com.mclauncher.multiplayer.vpn.RelayEndpoints;
import java.util.List;

/** {@link RelayEndpoints} 的中继端点解析测试 */
public class RelayEndpointsTest {

    public static void main(String[] args) throws Exception {
        System.out.println("—— RelayEndpoints ——");

        Checks.run("无配置 → 内置默认", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.load(null, null);
            Checks.assertEquals(2, d.size(), "项数");
            Checks.assertEquals("39.106.60.15", d.get(0).host, "第一项 host");
            Checks.assertEquals(8080, d.get(0).port, "第一项 port");
            Checks.assertEquals("45.64.229.50:41181", d.get(1).label(), "第二项 label");
        });

        Checks.run("-D 覆盖内置默认", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.load("a.example:9000,b.example", null);
            Checks.assertEquals(2, d.size(), "项数");
            Checks.assertEquals("a.example", d.get(0).host, "host");
            Checks.assertEquals(9000, d.get(0).port, "port");
            Checks.assertEquals(8080, d.get(1).port, "无端口=8080");
        });

        Checks.run("无效 -D → 退回内置默认", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.load("https://bad", null);
            Checks.assertTrue(d.size() == 2, "项数>=2");
        });

        Checks.run("空逗号被跳过", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.parseCommaList("a,,b");
            Checks.assertEquals(2, d.size(), "项数");
        });

        Checks.run("非法端口被跳过", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.parseCommaList(
                    "bad::1,ok:8080,portz:abc,ok2:8081,huge:70000");
            Checks.assertEquals(2, d.size(), "项数（" + d + "）");
        });

        Checks.run("JSON 数组写法", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.parseJson(
                    "[{\"host\":\"x\",\"port\":1234},{\"host\":\"y\"}]");
            Checks.assertEquals(2, d.size(), "项数");
            Checks.assertEquals(1234, d.get(0).port, "有 port");
            Checks.assertEquals(8080, d.get(1).port, "无 port → 8080");
        });

        Checks.run("JSON 字符串数组", () -> {
            List<RelayEndpoints.Endpoint> d = RelayEndpoints.parseJson(
                    "[\"x:4321\",\"y\"]");
            Checks.assertEquals(2, d.size(), "项数");
            Checks.assertEquals(4321, d.get(0).port, "有端口");
            Checks.assertEquals(8080, d.get(1).port, "无端口");
        });

        // parseOne 是包内可见，这里通过公开入口验证同一套校验规则
        Checks.run("拒绝带 scheme 的端点", () -> {
            Checks.assertEquals(0, RelayEndpoints.parseCommaList("https://x.example").size(), "scheme");
            Checks.assertEquals(0, RelayEndpoints.parseCommaList("  ").size(), "空白");
            Checks.assertEquals(0, RelayEndpoints.parseCommaList(null).size(), "null");
        });

    }
}