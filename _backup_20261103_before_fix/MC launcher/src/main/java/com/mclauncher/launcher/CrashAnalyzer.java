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

/**
 * 游戏崩溃日志分析
 *
 * 玩家看到「游戏崩溃了」时通常无从下手：日志几千行，全是英文堆栈。
 * 这里按一组已知的崩溃模式做匹配，把结果翻译成人话并给出处理建议。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>宁可漏报，不可误报</b>：拿不准的就不说，绝不编造原因。
 *       分析失败时如实告诉用户「没识别出已知模式」，并给出日志路径。</li>
 *   <li><b>一条崩溃可能命中多条规则</b>：按优先级排序，把最可能的放前面。</li>
 *   <li><b>不依赖外部服务</b>：全部本地正则匹配，不联网、不上传日志。</li>
 * </ul>
 *
 * <h3>分析的日志来源</h3>
 * <ol>
 *   <li>{@code <gameDir>/crash-reports/crash-*.txt}（最新一个）—— 游戏自己生成的崩溃报告，信息最全</li>
 *   <li>{@code <gameDir>/logs/latest.log} —— 没有崩溃报告时退而求其次</li>
 * </ol>
 */
public class CrashAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(CrashAnalyzer.class);

    /** 单次分析最多读取的字符数（防止超大日志把内存吃满） */
    private static final int MAX_READ = 2 * 1024 * 1024;

    // ==================== 结果模型 ====================

    /** 一条分析结论 */
    public static class Finding {
        /** 严重程度：影响排序 */
        public enum Level { HIGH, MEDIUM, LOW }

        public final Level level;
        /** 一句话原因 */
        public final String title;
        /** 详细说明（给用户看的） */
        public final String detail;
        /** 处理建议 */
        public final String suggestion;
        /** 命中的原文片段（便于用户核对） */
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

    /** 分析报告 */
    public static class Report {
        /** 是否成功读到日志 */
        public boolean logFound;
        /** 分析用到的日志文件 */
        public Path logSource;
        /** 结论列表（已按级别排序） */
        public final List<Finding> findings = new ArrayList<>();
        /** 原始日志的行数 */
        public int logLines;

        public boolean hasFinding() {
            return !findings.isEmpty();
        }
    }

    // ==================== 规则定义 ====================

    /**
     * 一条检测规则
     *
     * {@code pattern} 在日志里查找；命中则产出一条 Finding。
     */
    private static class Rule {
        final Finding.Level level;
        final Pattern pattern;
        final String title;
        final String detail;
        final String suggestion;

        Rule(Finding.Level level, String regex, String title,
             String detail, String suggestion) {
            // DOTALL：某些堆栈信息跨行
            this.level = level;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            this.title = title;
            this.detail = detail;
            this.suggestion = suggestion;
        }
    }

    /**
     * 规则库
     *
     * **顺序有意义**：越靠前的越具体，命中后优先展示。
     * 修改时请保持「具体规则在前、宽泛规则在后」。
     */
    private static final List<Rule> RULES = Arrays.asList(

        // ---------- 一、内存相关 ----------

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

        // ---------- 二、模组与前置 ----------

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

        // ---------- 三、账号 ----------

        new Rule(Finding.Level.MEDIUM,
                "InvalidCredentialsException|Failed to verify authentication"
                + "|AuthenticationException",
                L.t("s_7fdce8e0da"),
                L.t("s_594b5753d0"),
                L.t("s_41ee4deda8")),

        // ---------- 四、图形与驱动 ----------

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

        // ---------- 五、文件与资源 ----------

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

        // ---------- 六、其它 ----------

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

    // ==================== 主入口 ====================

    /**
     * 分析指定游戏目录的最近一次崩溃
     *
     * @param gameDir 游戏根目录（含 logs/ 和 crash-reports/ 的那一层）
     */
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

        // 按级别排序（HIGH > MEDIUM > LOW）
        report.findings.sort((a, b) -> a.level.compareTo(b.level));

        logger.info("崩溃分析完成: {} 条结论（日志 {}，{} 行）",
                report.findings.size(),
                logFile.getFileName(), report.logLines);

        return report;
    }

    /**
     * 找最近的日志文件
     *
     * 优先 crash-reports/ 下最新的 crash-*.txt（信息最全），
     * 其次 logs/latest.log。
     */
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

    /**
     * 读取日志（限制长度，避免超大文件）
     *
     * 只保留**尾部** —— 崩溃信息总在最后。
     */
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
            // 日志常含非 UTF-8 字节（游戏输出混编码），退回宽松解码
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

    /**
     * 取命中位置所在的一行作为证据
     *
     * 用户看到「日志里写了这个」才容易相信分析结果。
     */
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