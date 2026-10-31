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

public final class RestartUtils {

    private static final Logger logger = LoggerFactory.getLogger(RestartUtils.class);

    private RestartUtils() {}

    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public static Result restartWith(Path newJar) {
        if (newJar == null || !Files.isRegularFile(newJar)) {
            return new Result(false, L.t("s_75b22229f5") + newJar);
        }

        String javaw = resolveJavaw();
        if (javaw == null) {
            return new Result(false, L.t("s_08f546b4c5"));
        }

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

            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process p = pb.start();
            logger.info("新版本进程已启动 (PID={})，当前进程即将退出", p.pid());

            

            return new Result(true, L.t("s_1643c63eab"));

        } catch (Exception e) {
            logger.error("启动新版本失败", e);
            return new Result(false, "启动新版本失败: " + e.getMessage());
        }
    }

    public static void exitCurrent() {
        logger.info("退出当前进程以完成重启");
        javafx.application.Platform.exit();

        
        System.exit(0);
    }

    private static String resolveJavaw() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }

        boolean windows = System.getProperty("os.name", "")
                .toLowerCase().contains("win");

        Path bin = Paths.get(javaHome, "bin");

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
