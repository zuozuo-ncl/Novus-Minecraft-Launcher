package com.mclauncher.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 检测工具类
 * 负责检测系统中已安装的 Java 环境
 */
public class JavaDetector {

    private static final Logger logger = LoggerFactory.getLogger(JavaDetector.class);

    /**
     * 检测系统已安装的 Java 版本
     * 优先使用 JAVA_HOME，其次搜索常见路径
     */
    public static List<JavaInfo> detectJavaVersions() {
        List<JavaInfo> javaVersions = new ArrayList<>();

        // 1. 检查 JAVA_HOME
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            JavaInfo info = checkJavaInstallation(javaHome);
            if (info != null) {
                javaVersions.add(info);
                logger.info("检测到 JAVA_HOME: {}", javaHome);
            }
        }

        // 2. 检查当前 JVM
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isEmpty()) {
            // 检查是否已经添加
            boolean alreadyAdded = false;
            for (JavaInfo info : javaVersions) {
                if (info.path.toString().equals(currentJavaHome)) {
                    alreadyAdded = true;
                    break;
                }
            }
            if (!alreadyAdded) {
                JavaInfo info = checkJavaInstallation(currentJavaHome);
                if (info != null) {
                    javaVersions.add(info);
                    logger.info("检测到当前 JVM: {}", currentJavaHome);
                }
            }
        }

        // 3. 搜索常见安装路径
        searchCommonPaths(javaVersions);

        return javaVersions;
    }

    /**
     * 从 java -version 输出中检测架构
     */
    private static String detectArch(String output) {
        if (output.contains("aarch64") || output.contains("arm64")) {
            return "aarch64";
        }
        if (output.contains("64") || output.contains("x86_64") || output.contains("amd64")) {
            return "x64";
        }
        if (output.contains("32") || output.contains("x86")) {
            return "x32";
        }
        return "x64";
    }

    /**
     * 检查 Java 安装信息
     */
    public static JavaInfo checkJavaInstallation(String javaHome) {
        try {
            Path homePath = Paths.get(javaHome);
            if (!Files.exists(homePath)) {
                return null;
            }

            // 检查 java.exe 或 java 可执行文件
            String javaExe;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                javaExe = "bin\\java.exe";
            } else {
                javaExe = "bin/java";
            }

            Path javaPath = homePath.resolve(javaExe);
            if (!Files.exists(javaPath) || !Files.isExecutable(javaPath)) {
                return null;
            }

            // 执行 java -version 获取版本信息
            String version = executeJavaVersion(javaPath.toString());

            // 解析版本号
            int majorVersion = parseMajorVersion(version);

            String arch = detectArch(version);
            return new JavaInfo(
                    homePath.toAbsolutePath().toString(),
                    version,
                    majorVersion,
                    arch,
                    javaPath.toAbsolutePath().toString()
            );
        } catch (Exception e) {
            logger.debug("检查 Java 安装失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 搜索常见 Java 安装路径
     */
    private static void searchCommonPaths(List<JavaInfo> javaVersions) {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows 常见路径
            String[] winPaths = {
                    "C:\\Program Files\\Java",
                    "C:\\Program Files (x86)\\Java",
                    "C:\\Java",
                    System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Java"
            };

            for (String basePath : winPaths) {
                java.nio.file.Path path = java.nio.file.Paths.get(basePath);
                if (Files.exists(path) && Files.isDirectory(path)) {
                    try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                             Files.newDirectoryStream(path, "jdk*")) {
                        for (java.nio.file.Path jdkDir : stream) {
                            JavaInfo info = checkJavaInstallation(jdkDir.toAbsolutePath().toString());
                            if (info != null) {
                                javaVersions.add(info);
                            }
                        }
                    } catch (IOException e) {
                        logger.debug("扫描 Java 目录失败: {}", e.getMessage());
                    }
                }
            }
        } else if (os.contains("mac")) {
            // macOS 常见路径
            String[] macPaths = {
                    "/Library/Java/JavaVirtualMachines",
                    System.getProperty("user.home") + "/Library/Java/JavaVirtualMachines"
            };

            for (String basePath : macPaths) {
                java.nio.file.Path path = java.nio.file.Paths.get(basePath);
                if (Files.exists(path) && Files.isDirectory(path)) {
                    try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                             Files.newDirectoryStream(path, "*/Contents/Home")) {
                        for (java.nio.file.Path jdkDir : stream) {
                            JavaInfo info = checkJavaInstallation(jdkDir.toAbsolutePath().toString());
                            if (info != null) {
                                javaVersions.add(info);
                            }
                        }
                    } catch (IOException e) {
                        logger.debug("扫描 Java 目录失败: {}", e.getMessage());
                    }
                }
            }
        } else {
            // Linux 常见路径
            String[] linuxPaths = {
                    "/usr/lib/jvm",
                    "/usr/local/lib/jvm",
                    System.getProperty("user.home") + "/.sdkman/candidates/java/current"
            };

            for (String basePath : linuxPaths) {
                java.nio.file.Path path = java.nio.file.Paths.get(basePath);
                if (Files.exists(path) && Files.isDirectory(path)) {
                    try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                             Files.newDirectoryStream(path)) {
                        for (java.nio.file.Path jdkDir : stream) {
                            JavaInfo info = checkJavaInstallation(jdkDir.toAbsolutePath().toString());
                            if (info != null) {
                                javaVersions.add(info);
                            }
                        }
                    } catch (IOException e) {
                        logger.debug("扫描 Java 目录失败: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 执行 java -version 命令获取版本信息
     */
    private static String executeJavaVersion(String javaPath) throws IOException {
        String output = runVersionCommand(javaPath, 10);
        return output == null ? "" : output.trim();
    }

    /**
     * 执行 {@code <java> -version} 并返回合并后的输出；超时或无法启动返回 null。
     *
     * 输出在独立线程里读、当前线程只负责计时：读循环等 EOF 是没有超时的，
     * 如果 java.exe 挂起（被杀软拦截、对损坏的 exe），先读后 waitFor 的写法
     * 会永久阻塞在 readLine 上，waitFor 的超时根本轮不到执行。
     *
     * @param timeoutSeconds 超时秒数
     */
    public static String runVersionCommand(String javaExe, int timeoutSeconds) {
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-version");
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            logger.debug("无法启动 java -version: {}", e.getMessage());
            return null;
        }

        StringBuilder output = new StringBuilder();
        // 守护线程：即使子进程卡住，也不会阻止 JVM 退出
        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream();
                 java.io.Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
                 BufferedReader br = new BufferedReader(r)) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程被强杀时读到异常属于正常路径
            }
        }, "java-version-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.debug("java -version 超时: {}", javaExe);
                return null;
            }
            // 进程已退出，给读线程一点时间把管道里剩余的输出读干净
            reader.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
        synchronized (output) {
            return output.toString();
        }
    }

    /**
     * 从版本字符串中解析主版本号
     * 例如："1.8.0_292" -> 8, "17.0.1" -> 17, "11.0.11" -> 11
     */
    private static int parseMajorVersion(String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            return 0;
        }

        try {
            // 匹配版本号格式：1.x.x 或 x.x.x
            String[] parts = versionString.split("[\"\\s]");
            for (String part : parts) {
                if (part.startsWith("\"") || part.startsWith("'")) {
                    part = part.replaceAll("[\"']", "");
                }

                // 处理 "1.8.x" 格式
                if (part.matches("1\\.\\d+\\.\\d+.*")) {
                    String[] versionParts = part.split("\\.");
                    if (versionParts.length >= 2) {
                        return Integer.parseInt(versionParts[1]);
                    }
                }

                // 处理 "17.x.x" 或 "11.x.x" 格式
                if (part.matches("\\d+\\.\\d+\\.\\d+.*")) {
                    String[] versionParts = part.split("\\.");
                    if (versionParts.length >= 1) {
                        return Integer.parseInt(versionParts[0]);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("解析版本号失败: {}", e.getMessage());
        }

        return 0;
    }

    /**
     * Java 安装信息类
     */
    public static class JavaInfo {
        private final String path;
        private final String version;
        private final int majorVersion;
        private final String arch;     // x64 / x32 / aarch64
        private final String javaExecutable;

        public JavaInfo(String path, String version, int majorVersion, String arch,
                        String javaExecutable) {
            this.path = path;
            this.version = version;
            this.majorVersion = majorVersion;
            this.arch = arch;
            this.javaExecutable = javaExecutable;
        }

        public String getPath() { return path; }
        public String getVersion() { return version; }
        public int getMajorVersion() { return majorVersion; }
        public String getArch() { return arch; }
        public String getJavaExecutable() { return javaExecutable; }

        @Override
        public String toString() {
            return "JavaInfo{path='" + path + "', version='" + version + "', major=" + majorVersion + '}';
        }
    }

    /**
     * 查找指定版本的 Java
     */
    public static JavaInfo findJavaByVersion(List<JavaInfo> javaVersions, int targetVersion) {
        for (JavaInfo info : javaVersions) {
            if (info.getMajorVersion() >= targetVersion) {
                return info;
            }
        }
        return null;
    }

    /**
     * 检查是否有满足最低版本的 Java
     */
    public static boolean hasSufficientJava(List<JavaInfo> javaVersions, int minVersion) {
        return findJavaByVersion(javaVersions, minVersion) != null;
    }

    /**
     * 获取最佳 Java 选择（最高版本）
     */
    public static JavaInfo getBestJava(List<JavaInfo> javaVersions) {
        if (javaVersions == null || javaVersions.isEmpty()) {
            return null;
        }

        JavaInfo best = null;
        for (JavaInfo info : javaVersions) {
            if (best == null || info.getMajorVersion() > best.getMajorVersion()) {
                best = info;
            }
        }
        return best;
    }
}
