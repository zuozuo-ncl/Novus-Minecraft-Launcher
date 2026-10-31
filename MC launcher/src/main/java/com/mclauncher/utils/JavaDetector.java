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

public class JavaDetector {

    private static final Logger logger = LoggerFactory.getLogger(JavaDetector.class);

    public static List<JavaInfo> detectJavaVersions() {
        List<JavaInfo> javaVersions = new ArrayList<>();

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            JavaInfo info = checkJavaInstallation(javaHome);
            if (info != null) {
                javaVersions.add(info);
                logger.info("检测到 JAVA_HOME: {}", javaHome);
            }
        }

        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isEmpty()) {

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

        searchCommonPaths(javaVersions);

        return javaVersions;
    }

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

    public static JavaInfo checkJavaInstallation(String javaHome) {
        try {
            Path homePath = Paths.get(javaHome);
            if (!Files.exists(homePath)) {
                return null;
            }

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

            String version = executeJavaVersion(javaPath.toString());

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

    private static void searchCommonPaths(List<JavaInfo> javaVersions) {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {

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

    private static String executeJavaVersion(String javaPath) throws IOException {
        String output = runVersionCommand(javaPath, 10);
        return output == null ? "" : output.trim();
    }

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

        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream();
                 java.io.Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
                 BufferedReader br = new BufferedReader(r)) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {

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

        try {

            String[] parts = versionString.split("[\"\\s]");
            for (String part : parts) {
                if (part.startsWith("\"") || part.startsWith("'")) {
                    part = part.replaceAll("[\"']", "");
                }

                if (part.matches("1\\.\\d+\\.\\d+.*")) {
                    String[] versionParts = part.split("\\.");
                    if (versionParts.length >= 2) {
                        return Integer.parseInt(versionParts[1]);
                    }
                }

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

    public static class JavaInfo {
        private final String path;
        private final String version;
        private final int majorVersion;
        private final String arch;     
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

    public static JavaInfo findJavaByVersion(List<JavaInfo> javaVersions, int targetVersion) {
        for (JavaInfo info : javaVersions) {
            if (info.getMajorVersion() >= targetVersion) {
                return info;
            }
        }
        return null;
    }

    public static boolean hasSufficientJava(List<JavaInfo> javaVersions, int minVersion) {
        return findJavaByVersion(javaVersions, minVersion) != null;
    }

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
