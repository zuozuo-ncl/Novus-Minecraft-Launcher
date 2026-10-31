package com.mclauncher.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

public class PlatformUtils {

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static boolean isMacOS() {
        return System.getProperty("os.name").toLowerCase().contains("mac") ||
               System.getProperty("os.name").toLowerCase().contains("darwin");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    public static String getFileSeparator() {
        return isWindows() ? "\\" : "/";
    }

    public static void setExecutable(Path path) throws IOException {
        if (isWindows()) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(path));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {

            path.toFile().setExecutable(true, false);
        }
    }

    public static String getArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return normalizeArch(arch);
    }

    public static String normalizeArch(String arch) {
        if (arch == null || arch.isEmpty()) {
            return "x64";
        }
        switch (arch) {
            case "amd64":
            case "x86_64":
                return "x64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x32";
            case "aarch64":
            case "arm64":
                return "aarch64";
            case "arm":
                return "arm";
            default:
                return arch;
        }
    }

    public static Path getTempDir() {
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }
}
