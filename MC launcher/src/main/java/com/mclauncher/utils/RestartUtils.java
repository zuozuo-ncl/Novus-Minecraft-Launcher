package com.mclauncher.utils;


import com.mclauncher.i18n.L;
import com.mclauncher.network.UpdateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动器重启工具
 * 为什么不能「就地重启」：
 */
public final class RestartUtils {

    private static final Logger logger = LoggerFactory.getLogger(RestartUtils.class);

    private RestartUtils() {}

    /**
     * 重启结果
     */
    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /**
     * 启动一个新进程来运行指定的 jar，成功后退出当前进程
     * 注意：本方法在**成功时不会返回**（内部会 System.exit）。
     */
    public static Result restartWith(Path newJar) {
        if (newJar == null || !Files.isRegularFile(newJar)) {
            return new Result(false, L.t("s_75b22229f5") + newJar);
        }

        String javaw = resolveJavaw();
        if (javaw == null) {
            return new Result(false, L.t("s_08f546b4c5"));
        }

        // 工作目录沿用当前进程的，保证相对路径的资源仍能正确解析
        String workDir = System.getProperty("user.dir");

        List<String> command = new ArrayList<>();
        command.add(javaw);
        command.add("-jar");
        command.add(newJar.toAbsolutePath().toString());

        logger.info("准备重启到新版本: jar={}, java={}, workDir={}",
                newJar, javaw, workDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(new File(workDir));
            }
            // 不继承本进程的标准流：否则新进程的日志会与当前进程的输出混在一起
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process p = pb.start();
            logger.info("新版本进程已启动 (PID={})，当前进程即将退出", p.pid());

            // 先把当前进程的退出流程走完（保存配置等），再真正退出。
            // 这里交给调用方在合适的时机调用 exitCurrent()，
            // 本方法只负责把新进程拉起来。
            return new Result(true, L.t("s_1643c63eab"));

        } catch (Exception e) {
            logger.error("启动新版本失败", e);
            return new Result(false, "启动新版本失败: " + e.getMessage());
        }
    }

    /** 退出当前进程 */
    public static void exitCurrent() {
        logger.info("退出当前进程以完成重启");
        javafx.application.Platform.exit();
        // Platform.exit() 只是请求 JavaFX 收尾；若仍有非守护线程存活，
        // JVM 不会退出。这里强制兜底，保证用户点的「重启」真的重启。
        System.exit(0);
    }

    /** 找到当前 JVM 对应的 javaw / java 可执行文件 */
    private static String resolveJavaw() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }

        boolean windows = System.getProperty("os.name", "")
                .toLowerCase().contains("win");

        Path bin = Paths.get(javaHome, "bin");

        // Windows 优先 javaw（无控制台窗口）；其余平台用 java
        List<String> candidates = windows
                ? List.of("javaw.exe", "java.exe")
                : List.of("java");

        for (String name : candidates) {
            Path exe = bin.resolve(name);
            if (Files.isRegularFile(exe)) {
                return exe.toAbsolutePath().toString();
            }
        }

        logger.warn("在 {} 下未找到可用的 java 可执行文件", bin);
        return null;
    }

    /** 在启动器数据目录里找出「当前已下载的最新版本 jar」 */
    public static Path findLatestDownloadedJar() {
        Path dir = UpdateClient.getAppDataDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }

        Path best = null;
        String bestVer = null;

        try (var stream = Files.newDirectoryStream(dir, "mc-launcher-*.jar")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String ver = name.substring("mc-launcher-".length(),
                        name.length() - ".jar".length());
                if (bestVer == null || compareVersions(ver, bestVer) > 0) {
                    bestVer = ver;
                    best = p;
                }
            }
        } catch (Exception e) {
            logger.debug("扫描已下载 jar 失败: {}", e.getMessage());
        }

        return best;
    }

    /**
     * 语义化版本比较（与 C++ 引导器的 CompareVersions 规则保持一致）
     *
     * 逐段比数字，段数不同时短的补 0：1.2 > 1.1.9，1.0.1 > 1.0
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);

        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    /** 取段内的数字部分，"1-beta" -> 1 */
    private static int parseSegment(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}