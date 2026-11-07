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

    /** 执行 {@code <java> -version} 并返回合并后的输出；超时或无法启动返回 null。 */
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
                 // java -version 用的是系统 ANSI 代码页（中文系统上是 GBK），
                 // 用 UTF-8 解码会得到乱码，部分本地化输出还会让版本号解析失败
                 java.io.Reader r = new java.io.InputStreamReader(in,
                         java.nio.charset.Charset.defaultCharset());
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

    private static int parseMajorVersion(String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            return 0;
        }
        // openjdk version "21.0.2" 2024-01-16 / java version "1.8.0_292"
        if (QUOTED_VERSION != null) {
            java.util.regex.Matcher m = QUOTED_VERSION.matcher(versionString);
            if (m.find()) {
                return majorOf(m.group(1));
            }
        }
        // 没有引号：openjdk 25-ea 2025-xx / 1.8.0_292（取第一行里第一个版本串）
        for (String line : versionString.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher mm = BARE_VERSION.matcher(t);
            if (mm.find()) {
                return majorOf(mm.group());
            }
        }
        return 0;
    }

    /** 引号里的版本号。 */
    private static final java.util.regex.Pattern QUOTED_VERSION = buildQuoted();
    private static final java.util.regex.Pattern BARE_VERSION = buildBare();

    private static java.util.regex.Pattern buildQuoted() {
        char q = '"';
        try {
            return java.util.regex.Pattern.compile(
                    "version" + "\\s*" + q + "([^" + q + "]+)" + q);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.util.regex.Pattern buildBare() {
        try {
            return java.util.regex.Pattern.compile(
                    "(?:(?<=openjdk )|(?<=java )|(?<=jdk ))"
                            + "([0-9]+(?:[._][0-9]+)*(?:-[a-zA-Z0-9.]+)?)");
        } catch (Exception e) {
            return null;
        }
    }

    /** "1.8.0_292" -> 8，"17.0.1" -> 17，"21" -> 21，"25-ea" -> 25 */
    private static int majorOf(String ver) {
        String v = ver.trim();
        String[] parts = v.split("[._-]");
        try {
            if (parts.length >= 2 && "1".equals(parts[0])) {
                return Integer.parseInt(parts[1]);
            }
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
