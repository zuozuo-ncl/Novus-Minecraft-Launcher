package com.mclauncher.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LoggingConfigurator {

    private static final String PROP_LOG_DIR = "mc.launcher.logdir";

    private LoggingConfigurator() {}

    public static Path configure() {
        Path logDir = resolveLogDir();

        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {

            try {
                logDir = Files.createTempDirectory("mc-launcher-logs");
            } catch (IOException ignored) {
                logDir = Path.of(System.getProperty("java.io.tmpdir", "."));
            }
        }

        System.setProperty(PROP_LOG_DIR, logDir.toAbsolutePath().toString());

        return logDir;
    }

    private static Path resolveLogDir() {
        try {
            Path home = resolveLauncherHome();
            return home.resolve(".mc_launcher").resolve("logs");
        } catch (Exception e) {
            return Path.of(System.getProperty("java.io.tmpdir", "."), "mc-launcher-logs");
        }
    }

    private static Path resolveLauncherHome() {

        String override = System.getProperty("mc.launcher.home");
        if (override == null || override.isBlank()) {
            override = System.getenv("MC_LAUNCHER_HOME");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        try {
            var uri = LoggingConfigurator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path p = Path.of(uri);

            if (java.nio.file.Files.isRegularFile(p)) {
                Path parent = p.getParent();
                if (parent != null) {

                    Path root = findProjectRoot(parent);
                    if (root != null && parent.getFileName() != null
                            && "target".equals(parent.getFileName().toString())) {
                        return root;
                    }
                    return parent;
                }
            } else {
                Path root = findProjectRoot(p);
                if (root != null) {
                    return root;
                }
            }
        } catch (Exception ignored) {
        }

        return Path.of(System.getProperty("user.home"));
    }

    private static Path findProjectRoot(Path start) {
        Path cursor = start;
        for (int i = 0; i < 8 && cursor != null; i++) {
            if (java.nio.file.Files.exists(cursor.resolve("pom.xml"))
                    || java.nio.file.Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    public static Path getLogFile() {
        String dir = System.getProperty(PROP_LOG_DIR);
        Path base = dir != null ? Path.of(dir) : resolveLogDir();
        return base.resolve("launcher.log");
    }
}
