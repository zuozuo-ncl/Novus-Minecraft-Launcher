package com.mclauncher.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/**
 * 版本信息数据模型
 * 而且更关键的是 —— 下载和启动流程其实只需要「原样保留」它，
 */
public class VersionInfo {

    private String id;
    private String type;
    private String time;
    private String releaseTime;
    private String mainClass;
    private String assets;
    private AssetIndex assetIndex;
    private Downloads downloads;
    private List<Library> libraries;

    /** 原样保留的 arguments 对象 */
    private JsonObject arguments;

    /** 老版本（1.12 及以前）的启动参数字符串 */
    private String minecraftArguments;

    private JavaVersion javaVersion;
    private String inheritsFrom;

    /** Forge/Fabric 需要的 jar 字段（指向父版本 JAR） */
    private String jar;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getReleaseTime() { return releaseTime; }
    public void setReleaseTime(String releaseTime) { this.releaseTime = releaseTime; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public String getAssets() { return assets; }
    public void setAssets(String assets) { this.assets = assets; }

    public AssetIndex getAssetIndex() { return assetIndex; }
    public void setAssetIndex(AssetIndex assetIndex) { this.assetIndex = assetIndex; }

    public Downloads getDownloads() { return downloads; }
    public void setDownloads(Downloads downloads) { this.downloads = downloads; }

    public List<Library> getLibraries() { return libraries; }
    public void setLibraries(List<Library> libraries) { this.libraries = libraries; }

    public JsonObject getArguments() { return arguments; }
    public void setArguments(JsonObject arguments) { this.arguments = arguments; }

    public String getMinecraftArguments() { return minecraftArguments; }
    public void setMinecraftArguments(String v) { this.minecraftArguments = v; }

    public JavaVersion getJavaVersion() { return javaVersion; }
    public void setJavaVersion(JavaVersion javaVersion) { this.javaVersion = javaVersion; }

    public String getInheritsFrom() { return inheritsFrom; }
    public void setInheritsFrom(String inheritsFrom) { this.inheritsFrom = inheritsFrom; }

    public String getJar() { return jar; }
    public void setJar(String jar) { this.jar = jar; }

    // ==================== 内部类 ====================

    /**
     * 资源索引
     */
    public static class AssetIndex {
        private String id;
        private String sha1;
        private long size;
        private long totalSize;
        private String url;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSha1() { return sha1; }
        public void setSha1(String sha1) { this.sha1 = sha1; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public long getTotalSize() { return totalSize; }
        public void setTotalSize(long totalSize) { this.totalSize = totalSize; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    /**
     * 版本级下载信息（client / server 等）
     */
    public static class Downloads {
        private DownloadInfo client;
        private DownloadInfo server;

        @SerializedName("client_mappings")
        private DownloadInfo clientMappings;

        @SerializedName("server_mappings")
        private DownloadInfo serverMappings;

        public DownloadInfo getClient() { return client; }
        public void setClient(DownloadInfo client) { this.client = client; }
        public DownloadInfo getServer() { return server; }
        public void setServer(DownloadInfo server) { this.server = server; }
        public DownloadInfo getClientMappings() { return clientMappings; }
        public void setClientMappings(DownloadInfo v) { this.clientMappings = v; }
        public DownloadInfo getServerMappings() { return serverMappings; }
        public void setServerMappings(DownloadInfo v) { this.serverMappings = v; }
    }

    /**
     * 下载信息详情
     */
    public static class DownloadInfo {
        private String sha1;
        private long size;
        private String url;
        private String path;

        public String getSha1() { return sha1; }
        public void setSha1(String sha1) { this.sha1 = sha1; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    /**
     * 库文件信息
     */
    public static class Library {
        private String name;
        private LibraryDownloads downloads;
        private List<Rule> rules;
        private Map<String, String> natives;
        private Extract extract;
        private String url;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public LibraryDownloads getDownloads() { return downloads; }
        public void setDownloads(LibraryDownloads downloads) { this.downloads = downloads; }
        public List<Rule> getRules() { return rules; }
        public void setRules(List<Rule> rules) { this.rules = rules; }
        public Map<String, String> getNatives() { return natives; }
        public void setNatives(Map<String, String> natives) { this.natives = natives; }
        public Extract getExtract() { return extract; }
        public void setExtract(Extract extract) { this.extract = extract; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        /**
         * 获取库文件的 Maven 路径
         * 否则根据 Maven 坐标 name 推导。
         */
        public String getMavenPath() {
            String path = null;
            if (downloads != null && downloads.getArtifact() != null
                    && downloads.getArtifact().getPath() != null
                    && !downloads.getArtifact().getPath().isBlank()) {
                path = downloads.getArtifact().getPath();
            } else {
                path = mavenPathFromName(name);
            }
            return safeRelativePath(path);
        }

        /** 校验并返回安全的相对路径（拒绝绝对路径、反斜杠、":" 盘符与 ".." 段）。 */
        public static String safeRelativePath(String path) {
            if (path == null || path.isBlank()) {
                return null;
            }
            String p = path.trim().replace('\\', '/');
            if (p.startsWith("/") || p.contains(":") || p.indexOf('\0') >= 0) {
                return null;
            }
            for (String seg : p.split("/")) {
                if (seg.equals("..") || seg.equals(".")) {
                    return null;
                }
            }
            // 只允许常规字符，杜绝 @、空格、通配符等被后续解析误用
            if (!p.matches("[A-Za-z0-9._\\-/+]+")) {
                return null;
            }
            return p;
        }
    }

    /**
     * 库文件下载信息
     */
    public static class LibraryDownloads {
        private DownloadInfo artifact;
        private Map<String, DownloadInfo> classifiers;

        public DownloadInfo getArtifact() { return artifact; }
        public void setArtifact(DownloadInfo artifact) { this.artifact = artifact; }
        public Map<String, DownloadInfo> getClassifiers() { return classifiers; }
        public void setClassifiers(Map<String, DownloadInfo> classifiers) { this.classifiers = classifiers; }
    }

    /**
     * 规则（用于判断库文件是否适用于当前平台）
     */
    public static class Rule {
        private String action;  // "allow" 或 "disallow"
        private Os os;
        private Map<String, Boolean> features;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public Os getOs() { return os; }
        public void setOs(Os os) { this.os = os; }
        public Map<String, Boolean> getFeatures() { return features; }
        public void setFeatures(Map<String, Boolean> features) { this.features = features; }

        public static class Os {
            private String name;
            private String arch;
            private String version;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getArch() { return arch; }
            public void setArch(String arch) { this.arch = arch; }
            public String getVersion() { return version; }
            public void setVersion(String version) { this.version = version; }
        }
    }

    /**
     * Natives 解压规则
     */
    public static class Extract {
        private List<String> exclude;

        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> exclude) { this.exclude = exclude; }
    }

    /**
     * Java 版本要求
     */
    public static class JavaVersion {
        private String component;
        private int majorVersion;

        public String getComponent() { return component; }
        public void setComponent(String component) { this.component = component; }
        public int getMajorVersion() { return majorVersion; }
        public void setMajorVersion(int majorVersion) { this.majorVersion = majorVersion; }
    }

    // ==================== 静态工具方法 ====================

    /**
     * 根据 Maven 坐标推导库文件路径
     * 例如 net.minecraftforge:forge:1.20.1-47.0.1 -> net/minecraftforge/forge/1.20.1-47.0.1/forge-1.20.1-47.0.1.jar
     */
    public static String mavenPathFromName(String name) {
        if (name == null) {
            return "";
        }

        // 处理 name 里可能带 @extension 的情况，例如 "foo:bar:1.0@zip"
        String extension = "jar";
        String coord = name;

        int at = coord.lastIndexOf('@');
        if (at > 0) {
            extension = coord.substring(at + 1);
            coord = coord.substring(0, at);
        }

        String[] parts = coord.split(":");
        if (parts.length < 3) {
            return name;
        }

        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? parts[3] : null;

        StringBuilder sb = new StringBuilder();
        sb.append(group).append('/').append(artifact).append('/').append(version)
          .append('/').append(artifact).append('-').append(version);

        if (classifier != null && !classifier.isBlank()) {
            sb.append('-').append(classifier);
        }

        sb.append('.').append(extension);
        return sb.toString();
    }
}