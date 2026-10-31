package com.mclauncher.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

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

    private JsonObject arguments;

    private String minecraftArguments;

    private JavaVersion javaVersion;
    private String inheritsFrom;

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

            if (!p.matches("[A-Za-z0-9._\\-/+]+")) {
                return null;
            }
            return p;
        }
    }

    public static class LibraryDownloads {
        private DownloadInfo artifact;
        private Map<String, DownloadInfo> classifiers;

        public DownloadInfo getArtifact() { return artifact; }
        public void setArtifact(DownloadInfo artifact) { this.artifact = artifact; }
        public Map<String, DownloadInfo> getClassifiers() { return classifiers; }
        public void setClassifiers(Map<String, DownloadInfo> classifiers) { this.classifiers = classifiers; }
    }

    public static class Rule {
        private String action;  
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

    public static class Extract {
        private List<String> exclude;

        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> exclude) { this.exclude = exclude; }
    }

    public static class JavaVersion {
        private String component;
        private int majorVersion;

        public String getComponent() { return component; }
        public void setComponent(String component) { this.component = component; }
        public int getMajorVersion() { return majorVersion; }
        public void setMajorVersion(int majorVersion) { this.majorVersion = majorVersion; }
    }

    public static String mavenPathFromName(String name) {
        if (name == null) {
            return "";
        }

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
