package com.mclauncher.game;


import com.mclauncher.i18n.L;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.mod.ModrinthClient;
import com.mclauncher.network.BMCLApiClient;
import com.mclauncher.network.Downloader;
import com.mclauncher.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包一键安装
 *
 * 支持的格式：
 * <ul>
 *   <li><b>.mrpack</b>（Modrinth 格式）—— 完整支持</li>
 *   <li><b>.zip</b>（CurseForge / 手动整理的客户端包）—— 尽力支持</li>
 * </ul>
 *
 * <h3>.mrpack 是什么</h3>
 * 它其实就是一个 zip，里面有：
 * <pre>
 *   modrinth.index.json    —— 依赖清单（游戏版本、加载器、要下载的文件）
 *   overrides/             —— 要原样复制进游戏目录的文件
 *   client-overrides/      —— 只在客户端安装的文件
 * </pre>
 * index 里列出的每个文件都带下载地址和 SHA-1/SHA-512。
 *
 * <h3>安装流程</h3>
 * <ol>
 *   <li>解析 index，确定游戏版本与加载器（Fabric / Forge / Quilt / NeoForge）</li>
 *   <li>下载原版游戏</li>
 *   <li>安装加载器（Quilt / NeoForge 暂不支持自动安装 —— 见 {@link #installLoader}）</li>
 *   <li>并发下载 index 里的所有文件到指定位置</li>
 *   <li>解开 overrides / client-overrides</li>
 * </ol>
 *
 * <h3>安全约定（重要）</h3>
 * index 里的 path 是**包作者提供的、不可信的数据**（尤其对于从网上下载的 .zip 包）。
 * 一律经过 {@link #safeResolve} 校验，杜绝 {@code ../../} 之类的路径穿越。
 */
public class ModpackInstaller {

    private static final Logger logger = LoggerFactory.getLogger(ModpackInstaller.class);

    /** 并发下载数：整合包动辄 200+ 个模组，串行下载太慢；太高又会被限流 */
    private static final int PARALLEL_DOWNLOADS = 6;

    /** 进度回调（都在后台线程调用） */
    public interface Progress {
        /** 阶段变化，例如「正在下载原版 1.20.1」 */
        void onStage(String text);

        /** 总进度 0~1 */
        void onPercent(double percent);

        /** 日志 */
        void onLog(String line);
    }

    /** 安装结果 */
    public static class Result {
        public boolean success;
        /** 安装后的版本 ID（用于写入配置 / 启动） */
        public String versionId;
        /** 失败原因；成功但需要用户补做操作时（普通 zip）也会写在这里 */
        public String error;
        public int filesDownloaded;
        public int filesFailed;

        static Result ok(String versionId, int downloaded) {
            Result r = new Result();
            r.success = true;
            r.versionId = versionId;
            r.filesDownloaded = downloaded;
            return r;
        }

        static Result fail(String msg) {
            Result r = new Result();
            r.success = false;
            r.error = msg;
            return r;
        }
    }

    /** 解析出来的 index 内容 */
    public static class Manifest {
        public String name;
        public String versionId;
        public String summary;
        public String gameVersion;
        public String loader;           // fabric / forge / quilt / neoforge
        public String loaderVersion;
        public final List<FileEntry> files = new ArrayList<>();
    }

    /** index 里的一个文件 */
    public static class FileEntry {
        public String path;
        public String url;
        public long size;
        public String sha1;
        public String sha512;
    }

    private final ConfigManager config;
    private final BMCLApiClient apiClient;
    private final ModrinthClient modrinthClient;

    private volatile boolean cancelled;

    public ModpackInstaller(ConfigManager config, BMCLApiClient apiClient,
                            ModrinthClient modrinthClient) {
        this.config = config;
        this.apiClient = apiClient;
        this.modrinthClient = modrinthClient;
    }

    public void cancel() {
        cancelled = true;
    }

    // ==================== 入口 ====================

    /**
     * 安装整合包
     *
     * @param packFile  下载好的 .mrpack / .zip
     * @param targetDir 安装目录（新建的独立游戏目录）
     * @param progress  进度回调（可为 null）
     */
    public Result install(Path packFile, Path targetDir, Progress progress) {
        cancelled = false;
        Progress p = progress != null ? progress : NOOP;

        if (!Files.isRegularFile(packFile)) {
            return Result.fail(L.t("s_1ba02ada6a") + packFile);
        }

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            return Result.fail(L.t("s_490ee0821b") + e.getMessage());
        }

        Manifest manifest = null;
        try {
            manifest = parseMrpack(packFile);
        } catch (IOException e) {
            logger.debug("不是 .mrpack: {}", e.getMessage());
        }

        if (manifest == null) {
            // 不是 mrpack —— 尝试当作普通整合包 zip
            return installPlainZip(packFile, targetDir, p);
        }

        return installMrpack(manifest, packFile, targetDir, p);
    }

    // ==================== .mrpack ====================

    /**
     * 解析 .mrpack 的 modrinth.index.json
     *
     * @return 解析结果；不是 mrpack 时返回 null
     */
    public Manifest parseMrpack(Path mrpack) throws IOException {
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry index = zip.getEntry("modrinth.index.json");
            if (index == null) {
                return null;
            }
            String json = readEntry(zip, index);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            Manifest m = new Manifest();
            m.name = optString(root, "name");
            m.versionId = optString(root, "versionId");
            m.summary = optString(root, "summary");

            JsonObject deps = root.has("dependencies")
                    ? root.getAsJsonObject("dependencies") : new JsonObject();

            // minecraft 字段是游戏版本，其余键是加载器
            if (deps.has("minecraft")) {
                m.gameVersion = deps.get("minecraft").getAsString();
            }
            for (String loaderKey : new String[]{"fabric-loader", "forge", "quilt-loader", "neoforge"}) {
                if (deps.has(loaderKey)) {
                    m.loader = loaderKey.replace("-loader", "");
                    m.loaderVersion = deps.get(loaderKey).getAsString();
                    break;
                }
            }

            JsonArray files = root.has("files") ? root.getAsJsonArray("files") : new JsonArray();
            for (var fe : files) {
                if (!fe.isJsonObject()) {
                    continue;
                }
                JsonObject fo = fe.getAsJsonObject();
                FileEntry e = new FileEntry();
                e.path = optString(fo, "path");
                e.size = fo.has("fileSize") ? fo.get("fileSize").getAsLong() : 0;

                if (fo.has("downloads") && fo.getAsJsonArray("downloads").size() > 0) {
                    e.url = fo.getAsJsonArray("downloads").get(0).getAsString();
                }
                if (fo.has("hashes") && fo.get("hashes").isJsonObject()) {
                    JsonObject h = fo.getAsJsonObject("hashes");
                    e.sha1 = optString(h, "sha1");
                    e.sha512 = optString(h, "sha512");
                }
                if (e.path != null && !e.path.isBlank() && e.url != null && !e.url.isBlank()) {
                    m.files.add(e);
                }
            }

            logger.info("解析整合包: {} | MC {} | {} {} | {} 个文件",
                    m.name, m.gameVersion, m.loader, m.loaderVersion, m.files.size());
            return m;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // index 损坏时抛出，让上层明确报错而不是误判为 zip
            throw new IOException("modrinth.index.json 解析失败: " + e.getMessage(), e);
        }
    }

    private Result installMrpack(Manifest m, Path mrpack, Path targetDir, Progress p) {
        if (m.gameVersion == null || m.gameVersion.isBlank()) {
            return Result.fail(L.t("s_dd9307b143"));
        }

        p.onLog(L.t("s_f935b80c74") + (m.name == null ? packName(mrpack) : m.name));
        p.onLog("Minecraft " + m.gameVersion
                + (m.loader == null ? L.t("s_dea8cacc57") : " + " + m.loader + " " + m.loaderVersion));
        p.onLog(L.t("s_8c23d086b3") + m.files.size() + L.t("s_753bd807cd"));

        // ---------- 1. 原版游戏 ----------
        if (cancelled) {
            return Result.fail(L.t("s_a5ffdc95ee"));
        }
        p.onStage(L.t("s_091a9ff535") + m.gameVersion + " ...");
        p.onPercent(0.02);

        GameDownloader downloader = new GameDownloader(apiClient, targetDir);
        try {
            downloader.setCallback(new GameDownloader.ProgressCallback() {
                @Override
                public void onProgress(int current, int total, String message) {
                    p.onPercent(0.02 + 0.28 * (total > 0 ? (double) current / total : 0));
                    p.onStage(L.t("s_091a9ff535") + m.gameVersion + " ... " + message);
                }

                @Override
                public void onError(String error) {
                    p.onLog(L.t("s_57c83c361c") + error);
                }
            });

            if (!downloader.downloadVersion(m.gameVersion)) {
                return Result.fail(L.t("s_e4deeefc2f") + m.gameVersion + L.t("s_01d770b029"));
            }
        } finally {
            downloader.shutdown();
        }

        // ---------- 2. 加载器 ----------
        String installedVersion = m.gameVersion;
        if (m.loader != null && !m.loader.isBlank()) {
            p.onStage(L.t("s_8b50af569e") + m.loader + " ...");
            p.onPercent(0.32);

            String loaderId = installLoader(targetDir, m.gameVersion, m.loader, m.loaderVersion, p);
            if (loaderId != null) {
                installedVersion = loaderId;
            } else {
                p.onLog(L.t("s_feb9c1fb6f") + loaderLabel(m.loader) + L.t("s_0c294e49d0"));
            }
        }

        // ---------- 3. 整合包文件 ----------
        p.onStage(L.t("s_f4727b0359"));
        p.onPercent(0.35);

        int[] counts = downloadFiles(m, targetDir, p);
        int downloaded = counts[0];
        int failed = counts[1];

        if (cancelled) {
            return Result.fail(L.t("s_a5ffdc95ee"));
        }

        // ---------- 4. overrides ----------
        p.onStage(L.t("s_be8e3d352c"));
        p.onPercent(0.95);
        int extracted;
        try {
            extracted = extractOverrides(mrpack, targetDir);
        } catch (IOException e) {
            logger.error("解压 overrides 失败", e);
            return Result.fail("解压 overrides 失败: " + e.getMessage());
        }
        p.onLog("已解压 " + extracted + " 个配置/资源文件");

        p.onPercent(1.0);
        p.onStage("安装完成");

        if (failed > 0) {
            p.onLog(L.t("s_bdb9a2a05b") + failed + L.t("s_e198c2d600"));
        }

        Result r = Result.ok(installedVersion, downloaded);
        r.filesFailed = failed;
        return r;
    }

    /**
     * 安装加载器
     *
     * 目前只支持 Fabric 和 Forge（复用现有 {@link ModLoaderInstaller}）。
     * Quilt / NeoForge 没有现成的安装器，返回 null 由上层提示用户。
     *
     * @return 安装后的版本 ID；失败或不支持时返回 null
     */
    private String installLoader(Path targetDir, String gameVersion, String loader,
                                 String loaderVersion, Progress p) {
        ModLoaderInstaller installer = new ModLoaderInstaller(apiClient, targetDir);
        try {
            switch (loader) {
                case "fabric": {
                    String ver = loaderVersion;
                    if (ver == null || ver.isBlank()) {
                        var list = apiClient.getFabricVersions(gameVersion);
                        if (list == null || list.isEmpty()) {
                            p.onLog(L.t("s_45097971f8") + gameVersion + L.t("s_d7ae5894a5"));
                            return null;
                        }
                        ver = list.get(0);
                    }
                    String id = installer.installFabric(gameVersion, ver);
                    p.onLog("Fabric " + ver + L.t("s_47180314d3") + id);
                    return id;
                }
                case "forge": {
                    String ver = loaderVersion;
                    if (ver == null || ver.isBlank()) {
                        var list = apiClient.getForgeVersions(gameVersion);
                        if (list == null || list.isEmpty()) {
                            p.onLog(L.t("s_45097971f8") + gameVersion + L.t("s_e21537c29a"));
                            return null;
                        }
                        ver = list.get(0);
                    }
                    String id = installer.installForge(gameVersion, ver);
                    p.onLog("Forge " + ver + L.t("s_47180314d3") + id);
                    return id;
                }
                default:
                    p.onLog(L.t("s_580609f695") + loaderLabel(loader));
                    return null;
            }
        } catch (Exception e) {
            logger.error("加载器安装失败", e);
            p.onLog("[加载器安装失败] " + e.getMessage());
            return null;
        } finally {
            installer.shutdown();
        }
    }

    /**
     * 并发下载 index 里的所有文件
     *
     * @return {成功数, 失败数}
     */
    private int[] downloadFiles(Manifest m, Path targetDir, Progress p) {
        if (m.files.isEmpty()) {
            return new int[]{0, 0};
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int total = m.files.size();

        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, r -> {
            Thread t = new Thread(r, "modpack-dl");
            t.setDaemon(true);
            return t;
        });

        // 每个线程一个 Downloader（Downloader 内部有自己的连接池与线程池）
        ThreadLocal<Downloader> local = ThreadLocal.withInitial(Downloader::new);

        List<Future<?>> futures = new ArrayList<>();
        try {
            for (FileEntry e : m.files) {
                futures.add(pool.submit(() -> {
                    if (cancelled) {
                        return;
                    }
                    Path dest;
                    try {
                        dest = safeResolve(targetDir, e.path);
                    } catch (IOException ex) {
                        logger.warn("拒绝可疑路径 {}: {}", e.path, ex.getMessage());
                        failed.incrementAndGet();
                        return;
                    }

                    boolean ok = downloadOne(local.get(), e, dest);
                    int n = done.incrementAndGet();
                    if (!ok) {
                        failed.incrementAndGet();
                        p.onLog(L.t("s_b852ccb861") + e.path);
                    }
                    // 35% ~ 95% 留给文件下载
                    p.onPercent(0.35 + 0.60 * n / total);
                    p.onStage(L.t("s_4416e98e8b") + n + " / " + total);
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // 单个文件失败已在任务内部统计
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        return new int[]{done.get() - failed.get(), failed.get()};
    }

    /**
     * 下载单个文件（先镜像、再原始地址；带 SHA-1 校验）
     */
    private boolean downloadOne(Downloader dl, FileEntry e, Path dest) {
        try {
            Files.createDirectories(dest.getParent());
        } catch (IOException ex) {
            logger.warn("创建目录失败 {}: {}", dest, ex.getMessage());
            return false;
        }

        // 已存在且校验通过就跳过 —— 整合包典型 200+ 个模组，重装时能省很多时间
        if (Files.isRegularFile(dest) && matchesHash(dest, e)) {
            return true;
        }

        String mirrored = modrinthClient != null
                ? modrinthClient.toMirrorFileUrl(e.url) : e.url;

        Downloader.DownloadResult r = dl.download(mirrored, dest, null);
        if (!r.isSuccess() && !mirrored.equals(e.url)) {
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
                // 删不掉就让 Downloader 覆盖
            }
            r = dl.download(e.url, dest, null);
        }
        if (!r.isSuccess()) {
            return false;
        }

        if (!matchesHash(dest, e)) {
            logger.warn("文件校验失败，已删除: {}", e.path);
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
                // 忽略
            }
            return false;
        }
        return true;
    }

    /** 校验 SHA-1（缺失时退回 SHA-512；都没有则信任） */
    private boolean matchesHash(Path file, FileEntry e) {
        try {
            if (e.sha1 != null && !e.sha1.isBlank()) {
                return e.sha1.equalsIgnoreCase(FileUtils.sha1(file));
            }
            if (e.sha512 != null && !e.sha512.isBlank()) {
                return e.sha512.equalsIgnoreCase(sha512(file));
            }
            // 没给哈希：至少确认大小一致
            return e.size <= 0 || Files.size(file) == e.size;
        } catch (Exception ex) {
            return false;
        }
    }

    private String sha512(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 解开 overrides/ 与 client-overrides/
     *
     * @return 写出的文件数
     */
    private int extractOverrides(Path mrpack, Path targetDir) throws IOException {
        int count = 0;
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName().replace('\\', '/');

                String rel = null;
                if (name.startsWith("overrides/")) {
                    rel = name.substring("overrides/".length());
                } else if (name.startsWith("client-overrides/")) {
                    rel = name.substring("client-overrides/".length());
                }
                if (rel == null || rel.isEmpty()) {
                    continue;
                }

                Path dest;
                try {
                    dest = safeResolve(targetDir, rel);
                } catch (IOException ex) {
                    logger.warn("跳过可疑的 overrides 路径 {}: {}", name, ex.getMessage());
                    continue;
                }

                if (ze.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(ze)) {
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }

            // server-overrides 属于服务端，客户端不装
        }
        return count;
    }

    // ==================== 普通 zip ====================

    /**
     * 普通整合包 zip（CurseForge 客户端包 / 手动整理的包）
     *
     * 这类包没有统一清单，只能尽力而为：
     * <ul>
     *   <li>mods/ 里的 jar 原样解开</li>
     *   <li>config/、resourcepacks/、shaderpacks/ 等原样解开</li>
     *   <li>manifest.json（CurseForge 格式）存在时读出游戏版本与加载器</li>
     * </ul>
     * 原版与原加载器**不会**自动下载 —— 包内通常不含 assets，
     * 且 CurseForge 的文件不允许第三方重新分发。安装后提示用户去「下载」页补齐。
     */
    private Result installPlainZip(Path zipFile, Path targetDir, Progress p) {
        int count = 0;
        String gameVersion = null;
        String loader = null;

        p.onStage(L.t("s_33a5a20340"));
        p.onLog(L.t("s_d21c4cc33c"));

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            // CurseForge manifest.json
            ZipEntry mf = zip.getEntry("manifest.json");
            if (mf != null) {
                try {
                    JsonObject root = JsonParser.parseString(readEntry(zip, mf)).getAsJsonObject();
                    if (root.has("minecraft")) {
                        JsonObject mc = root.getAsJsonObject("minecraft");
                        gameVersion = optString(mc, "version");
                        if (mc.has("modLoaders")) {
                            for (var l : mc.getAsJsonArray("modLoaders")) {
                                JsonObject lo = l.getAsJsonObject();
                                if (lo.has("primary") && lo.get("primary").getAsBoolean()) {
                                    String id = optString(lo, "id");   // 例如 forge-47.1.0
                                    int dash = id.indexOf('-');
                                    loader = dash > 0 ? id.substring(0, dash) : id;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    p.onLog(L.t("s_01d0505a09") + e.getMessage());
                }
            }

            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName().replace('\\', '/');

                // CurseForge 包把内容放在 overrides/ 下；手动整理的包直接是 mods/ 等
                String rel = name.startsWith("overrides/")
                        ? name.substring("overrides/".length()) : name;

                // 跳过包自身的元数据
                if (rel.startsWith(".") || rel.equals("manifest.json")
                        || rel.equals("modrinth.index.json")) {
                    continue;
                }
                // 跳过服务端专属内容
                if (rel.startsWith("server/") || rel.startsWith("server-overrides/")) {
                    continue;
                }
                // modlist.html / minecraftinstance.json 之类是包管理器的说明文件
                if (rel.equals("modlist.html") || rel.equals("minecraftinstance.json")) {
                    continue;
                }
                if (rel.isBlank()) {
                    continue;
                }

                Path dest;
                try {
                    dest = safeResolve(targetDir, rel);
                } catch (IOException ex) {
                    logger.warn("跳过可疑路径 {}: {}", name, ex.getMessage());
                    continue;
                }

                if (ze.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(ze)) {
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        } catch (IOException e) {
            logger.error("解压整合包失败", e);
            return Result.fail("解压失败（文件可能已损坏）: " + e.getMessage());
        }

        p.onLog("已解压 " + count + " 个文件");
        if (gameVersion != null) {
            p.onLog("包信息: Minecraft " + gameVersion
                    + (loader == null ? "" : " + " + loader));
        }

        p.onPercent(1.0);
        p.onStage(L.t("s_75e34bfeb6"));

        // plain zip 没有安装出可启动的版本 —— versionId 留空
        Result r = Result.ok(null, 0);
        r.error = L.t("s_0b37942cf1");
        return r;
    }

    // ==================== 工具 ====================

    /**
     * 把包内相对路径解析到目标目录下，拒绝逃逸出目标目录的路径
     *
     * from 网上下载的整合包里 path 完全由作者决定，是**不可信输入**：
     * 一个 {@code ../../AppData/Roaming/Microsoft/Windows/Start Menu/...} 就能
     * 往启动项里写文件。必须逐段校验。
     */
    static Path safeResolve(Path base, String relative) throws IOException {
        String rel = relative.replace('\\', '/');
        // 绝对路径、盘符、UNC 一律拒绝
        if (rel.startsWith("/") || rel.matches("^[A-Za-z]:.*") || rel.startsWith("//")) {
            throw new IOException("绝对路径");
        }
        Path resolved = base.resolve(rel).normalize();
        Path baseNorm = base.toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(baseNorm)) {
            throw new IOException("路径越界: " + relative);
        }
        // 路径中不能含 ".."（normalize 后已消除，但显式再查一次更稳妥）
        for (String seg : rel.split("/")) {
            if (seg.equals("..")) {
                throw new IOException("包含 ..");
            }
        }
        return resolved;
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String packName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String loaderLabel(String loader) {
        if (loader == null) {
            return L.t("s_455e832fd9");
        }
        return switch (loader) {
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "quilt" -> "Quilt";
            case "neoforge" -> "NeoForge";
            default -> loader;
        };
    }

    private static final Progress NOOP = new Progress() {
        @Override
        public void onStage(String text) {
        }

        @Override
        public void onPercent(double percent) {
        }

        @Override
        public void onLog(String line) {
        }
    };
}