package com.mclauncher.launcher;

import com.mclauncher.i18n.L;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CrashAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(CrashAnalyzer.class);

    private static final int MAX_READ = 2 * 1024 * 1024;

    public static class Finding {

        public enum Level { HIGH, MEDIUM, LOW }

        public final Level level;

        public final String title;

        public final String detail;

        public final String suggestion;

        public final String evidence;

        public Finding(Level level, String title, String detail,
                       String suggestion, String evidence) {
            this.level = level;
            this.title = title;
            this.detail = detail;
            this.suggestion = suggestion;
            this.evidence = evidence;
        }
    }

    public static class Report {

        public boolean logFound;

        public Path logSource;

        public final List<Finding> findings = new ArrayList<>();

        public int logLines;

        public boolean hasFinding() {
            return !findings.isEmpty();
        }
    }

    private static class Rule {
        final Finding.Level level;
        final Pattern pattern;
        final String title;
        final String detail;
        final String suggestion;

        Rule(Finding.Level level, String regex, String title,
             String detail, String suggestion) {

            this.level = level;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            this.title = title;
            this.detail = detail;
            this.suggestion = suggestion;
        }
    }

    private static final List<Rule> RULES = Arrays.asList(

        new Rule(Finding.Level.HIGH,
                "java\\.lang\\.OutOfMemoryError:\\s*Java heap space",
                L.t("s_0179dfad08"),
                L.t("s_d2055a406e"),
                L.t("s_ad48d3e60c")),

        new Rule(Finding.Level.HIGH,
                "java\\.lang\\.OutOfMemoryError:\\s*(Metaspace|Compressed class space)",
                L.t("s_0863d66522"),
                L.t("s_e0122aa5a8"),
                L.t("s_d8c5224c4a")),

        new Rule(Finding.Level.HIGH,
                "OutOfMemoryError|GC overhead limit exceeded",
                L.t("s_f00dd88266"),
                L.t("s_5ee6c040ea"),
                L.t("s_46ccd34140")),

        new Rule(Finding.Level.HIGH,
                "java\\.lang\\.StackOverflowError",
                L.t("s_73976d3c17"),
                L.t("s_375580df63"),
                L.t("s_765a26ea7b")),

        new Rule(Finding.Level.HIGH,
                "Could not reserve enough space for .* object heap"
                + "|Invalid maximum heap size|Too small maximum heap",
                L.t("s_558aee139b"),
                L.t("s_ca5bb53a01"),
                L.t("s_21ab9257dc")),

        new Rule(Finding.Level.HIGH,
                "net\\.minecraftforge\\.fml\\.common\\.LoaderExceptionModCrash"
                + "|There was a severe problem during mod loading",
                L.t("s_86dccea82a"),
                L.t("s_e7403dda72"),
                L.t("s_192f0cd0ff")),

        new Rule(Finding.Level.HIGH,
                "java\\.lang\\.NoClassDefFoundError:\\s*(net/|org/|com/)\\S+",
                L.t("s_4590c89f7d"),
                L.t("s_ed90dfd500"),
                L.t("s_9f085af854")),

        new Rule(Finding.Level.HIGH,
                "java\\.lang\\.ClassNotFoundException:\\s*(net/|org/|com/)\\S+",
                L.t("s_8abb040cb4"),
                L.t("s_948730c3cd"),
                L.t("s_e5157c9db8")),

        new Rule(Finding.Level.HIGH,
                "Incompatible mod set|Mod .* requires .* which is missing"
                + "|requires version .* of .* but .* is installed",
                L.t("s_3675f8a03c"),
                L.t("s_776cb0b9ad"),
                L.t("s_228ca8f0fa")),

        new Rule(Finding.Level.HIGH,
                "Duplicate mods found|Found a duplicate mod",
                L.t("s_aaf82ce6fc"),
                L.t("s_6a45d84240"),
                L.t("s_b88267fda6")),

        new Rule(Finding.Level.HIGH,
                "UnsupportedClassVersionError|has been compiled by a more recent version",
                L.t("s_42906a1758"),
                L.t("s_78489c63f2"),
                L.t("s_021b2c8450")),

        new Rule(Finding.Level.MEDIUM,
                "java\\.lang\\.NoSuchMethodError"
                + "|java\\.lang\\.NoSuchFieldError",
                L.t("s_468ab9af73"),
                L.t("s_0bf6af05ca"),
                L.t("s_6315c1a2c8")),

        new Rule(Finding.Level.MEDIUM,
                "InvalidCredentialsException|Failed to verify authentication"
                + "|AuthenticationException",
                L.t("s_7fdce8e0da"),
                L.t("s_594b5753d0"),
                L.t("s_41ee4deda8")),

        new Rule(Finding.Level.HIGH,
                "EXCEPTION_ACCESS_VIOLATION.*(nvoglv|ig\\d|aticfx|amdvlk)",
                L.t("s_d350ea4e4e"),
                L.t("s_8c4623ad2a"),
                L.t("s_ec5a99dd35")),

        new Rule(Finding.Level.MEDIUM,
                "org\\.lwjgl\\.opengl\\.OpenGLException|GLFW error|Failed to create GLFW window",
                L.t("s_4237facdc9"),
                L.t("s_242a5e4555"),
                L.t("s_06837fee7f")),

        new Rule(Finding.Level.MEDIUM,
                "The game crashed whilst (rendering|initializing).*"
                + "|Pixel format not accelerated",
                L.t("s_d8ee2b2d88"),
                L.t("s_8831014b4e"),
                L.t("s_11a7278a2b")),

        new Rule(Finding.Level.MEDIUM,
                "java\\.io\\.FileNotFoundException.*resources"
                + "|Missing required resource|missing asset",
                L.t("s_e8afc63660"),
                L.t("s_17958ca0c9"),
                L.t("s_60522843c8")),

        new Rule(Finding.Level.MEDIUM,
                "java\\.util\\.zip\\.ZipException|invalid LOC header|Error reading zip",
                L.t("s_d0043a940b"),
                L.t("s_4a23adadcd"),
                L.t("s_a02b07d214")),

        new Rule(Finding.Level.MEDIUM,
                "java\\.nio\\.file\\.AccessDeniedException",
                L.t("s_db7a9eb66e"),
                L.t("s_ae520793c8"),
                L.t("s_002080e332")),

        new Rule(Finding.Level.LOW,
                "NoClassDefFoundError: com/sun/jna|UnsatisfiedLinkError.*jna",
                L.t("s_337af6dfd5"),
                L.t("s_ea0ce20e90"),
                L.t("s_119d138887")),

        new Rule(Finding.Level.LOW,
                "java\\.lang\\.UnsupportedOperationException"
                + ".*?\\bat\\s+(?:net|com|org)\\.\\S+\\.(?!minecraft)",
                L.t("s_f3becc4128"),
                L.t("s_9374956add"),
                L.t("s_7a780a4738"))
    );

    public Report analyze(Path gameDir) {
        Report report = new Report();

        Path logFile = findLatestLog(gameDir);
        if (logFile == null) {
            return report;
        }

        report.logFound = true;
        report.logSource = logFile;

        String text = readSafely(logFile);
        if (text == null || text.isEmpty()) {
            return report;
        }
        report.logLines = text.split("\n").length;

        for (Rule rule : RULES) {
            Matcher m = rule.pattern.matcher(text);
            if (m.find()) {
                String evidence = extractEvidence(text, m.start());
                report.findings.add(new Finding(rule.level, rule.title,
                        rule.detail, rule.suggestion, evidence));
            }
        }

        report.findings.sort((a, b) -> a.level.compareTo(b.level));

        logger.info("崩溃分析完成: {} 条结论（日志 {}，{} 行）",
                report.findings.size(),
                logFile.getFileName(), report.logLines);

        return report;
    }

    private Path findLatestLog(Path gameDir) {
        Path crashDir = gameDir.resolve("crash-reports");
        if (Files.isDirectory(crashDir)) {
            try (var stream = Files.newDirectoryStream(crashDir, "crash-*.txt")) {
                Path newest = null;
                long newestTime = 0;
                for (Path p : stream) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > newestTime) {
                        newestTime = t;
                        newest = p;
                    }
                }
                if (newest != null) {
                    return newest;
                }
            } catch (IOException e) {
                logger.debug("扫描 crash-reports 失败: {}", e.getMessage());
            }
        }

        Path latest = gameDir.resolve("logs").resolve("latest.log");
        return Files.isRegularFile(latest) ? latest : null;
    }

    private String readSafely(Path file) {
        try {
            long size = Files.size(file);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() > MAX_READ) {
                text = text.substring(text.length() - MAX_READ);
            }
            logger.debug("读取日志 {} ({}) 字节", file.getFileName(), size);
            return text;
        } catch (Exception e) {

            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_READ) {
                    bytes = Arrays.copyOfRange(bytes, bytes.length - MAX_READ, bytes.length);
                }
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e2) {
                logger.warn("读取日志失败: {}", e2.getMessage());
                return null;
            }
        }
    }

    private String extractEvidence(String text, int hitPos) {
        int start = text.lastIndexOf('\n', hitPos);
        start = start < 0 ? 0 : start + 1;

        int end = text.indexOf('\n', hitPos);
        if (end < 0) {
            end = Math.min(text.length(), hitPos + 200);
        }

        String line = text.substring(start, Math.min(end, start + 300)).trim();
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }
}
