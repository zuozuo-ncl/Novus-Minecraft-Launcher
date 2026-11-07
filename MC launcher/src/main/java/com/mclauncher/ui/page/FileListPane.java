package com.mclauncher.ui.page;


import com.mclauncher.i18n.L;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 可复用的文件列表组件 */
public class FileListPane {

    private static final Logger logger = LoggerFactory.getLogger(FileListPane.class);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path baseDir;
    private final String emptyHint;
    private final Consumer<String> statusConsumer;

    /** 是否允许进入子目录（世界/光影按目录展示时不需要） */
    private final boolean allowNavigate;

    /** 只显示目录（如世界、光影包） */
    private final boolean dirsOnly;

    /** 只显示指定扩展名的文件；null 表示不过滤 */
    private final String[] extensions;

    private VBox root;
    private VBox listContainer;
    private Label pathLabel;
    private Path currentDir;

    /** 内嵌弹窗宿主（由 GameSettingsPage 注入）；为空时降级不做确认 */
    private com.mclauncher.ui.component.ModalHost modalHost;

    public void setModalHost(com.mclauncher.ui.component.ModalHost host) {
        this.modalHost = host;
    }

    public FileListPane(Path baseDir, String emptyHint, Consumer<String> statusConsumer,
                        boolean allowNavigate, boolean dirsOnly, String... extensions) {
        this.baseDir = baseDir;
        this.emptyHint = emptyHint;
        this.statusConsumer = statusConsumer;
        this.allowNavigate = allowNavigate;
        this.dirsOnly = dirsOnly;
        this.extensions = extensions;
        this.currentDir = baseDir;
    }

    public Region getContent() {
        if (root == null) {
            root = build();
        }
        return root;
    }

    public void refresh() {
        if (listContainer == null) {
            return;
        }
        listFiles();
    }

    private VBox build() {
        VBox box = new VBox(10);

        // 顶部：当前路径 + 操作按钮
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);

        pathLabel = new Label();
        pathLabel.getStyleClass().add("text-muted");
        pathLabel.setWrapText(true);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        Button refreshBtn = new Button(L.t("s_38108eaa1d"));
        refreshBtn.getStyleClass().addAll("button", "button-sm");
        refreshBtn.setOnAction(e -> refresh());

        Button openDirBtn = new Button(L.t("s_3120403417"));
        openDirBtn.getStyleClass().addAll("button", "button-sm");
        openDirBtn.setOnAction(e -> openInExplorer(currentDir));

        top.getChildren().addAll(pathLabel, refreshBtn, openDirBtn);

        listContainer = new VBox(2);

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(240);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(top, scroll);
        return box;
    }

    /**
     * 列出文件
     */
    private void listFiles() {
        Path dir = currentDir;
        pathLabel.setText(L.t("s_7eb0b0f759") + dir.toString());

        CompletableFuture.supplyAsync(() -> {
            List<FileEntry> entries = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path p : stream) {
                        boolean isDir = Files.isDirectory(p);

                        if (dirsOnly && !isDir) {
                            continue;
                        }
                        if (!isDir && extensions != null && extensions.length > 0) {
                            String name = p.getFileName().toString().toLowerCase();
                            boolean matched = false;
                            for (String ext : extensions) {
                                if (name.endsWith(ext.toLowerCase())) {
                                    matched = true;
                                    break;
                                }
                            }
                            if (!matched) {
                                continue;
                            }
                        }

                        FileEntry e = new FileEntry();
                        e.path = p;
                        e.name = p.getFileName().toString();
                        e.directory = isDir;
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p,
                                    BasicFileAttributes.class);
                            e.size = isDir ? -1 : attrs.size();
                            e.modified = attrs.lastModifiedTime().toInstant();
                        } catch (IOException ex) {
                            e.size = -1;
                            e.modified = Instant.EPOCH;
                        }
                        entries.add(e);
                    }
                } catch (IOException e) {
                    logger.debug("读取目录失败: {}", e.getMessage());
                }
            }

            // 目录在前，然后按名称排序
            entries.sort((a, b) -> {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            });
            return entries;
        }).thenAccept(entries -> Platform.runLater(() -> renderEntries(entries)));
    }

    private void renderEntries(List<FileEntry> entries) {
        listContainer.getChildren().clear();

        // 返回上级
        if (allowNavigate && currentDir != null && baseDir != null
                && !currentDir.equals(baseDir)) {
            listContainer.getChildren().add(buildRow("⬆  ..", L.t("s_2b0dc6143c"), null,
                    () -> {
                        currentDir = currentDir.getParent();
                        listFiles();
                    }, false));
        }

        if (entries.isEmpty()) {
            Label empty = new Label(emptyHint);
            empty.getStyleClass().add("text-muted");
            empty.setPadding(new Insets(16));
            listContainer.getChildren().add(empty);
            return;
        }

        for (FileEntry e : entries) {
            String meta = e.directory
                    ? L.t("s_46ecac2910")
                    : com.mclauncher.utils.FileUtils.formatFileSizeShort(e.size)
                      + "  ·  " + DATE_FMT.format(e.modified);

            listContainer.getChildren().add(buildRow(
                    (e.directory ? "📁  " : "📄  ") + e.name,
                    meta,
                    e,
                    () -> {
                        if (e.directory) {
                            if (allowNavigate) {
                                currentDir = e.path;
                                listFiles();
                            } else {
                                openInExplorer(e.path);
                            }
                        } else {
                            openInExplorer(e.path.getParent());
                        }
                    },
                    true
            ));
        }
    }

    /**
     * 构造一行
     */
    private HBox buildRow(String title, String meta, FileEntry entry,
                          Runnable onDoubleClick, boolean showActions) {
        HBox row = new HBox(10);
        row.getStyleClass().add("filerow");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));

        VBox textBox = new VBox(2);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label nameLabel = new Label(title);
        nameLabel.getStyleClass().add("filerow-label");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().add("text-muted");
        metaLabel.setStyle("-fx-font-size: 11px;");

        textBox.getChildren().addAll(nameLabel, metaLabel);

        row.getChildren().add(textBox);

        // 双击打开
        row.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                onDoubleClick.run();
            }
        });

        if (showActions && entry != null) {
            Button delBtn = new Button(L.t("s_3755f56f2f"));
            delBtn.getStyleClass().addAll("button", "button-sm");
            delBtn.setStyle("-fx-background-color: #E05D5D; -fx-text-fill: white;");
            delBtn.setOnAction(e -> confirmDelete(entry));
            row.getChildren().add(delBtn);
        }

        return row;
    }

    /**
     * 确认删除
     */
    private void confirmDelete(FileEntry entry) {
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_3c06abe116"),
                L.t("s_5df8a7e91e") + entry.name + "」",
                entry.directory
                        ? L.t("s_8621a7f8f0")
                        : null,
                L.t("s_41d0a2222b"),
                L.t("s_3755f56f2f"),
                () -> {
                    CompletableFuture.runAsync(() -> {
                        try {
                            deleteRecursively(entry.path);
                            Platform.runLater(() -> {
                                if (statusConsumer != null) {
                                    statusConsumer.accept(L.t("s_782de21686") + entry.name);
                                }
                                listFiles();
                            });
                        } catch (IOException e) {
                            logger.error("删除失败", e);
                            Platform.runLater(() ->
                                    com.mclauncher.ui.component.MessageDialog.error(
                                            modalHost, L.t("s_72250c5922"), e.getMessage()));
                        }
                    });
                });
    }

    /**
     * 递归删除
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(path)) {
                for (Path child : s) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }

    /**
     * 在系统文件管理器中打开
     */
    private void openInExplorer(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(path.toFile());
                } else {
                    // 后备方案
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        Runtime.getRuntime().exec(new String[]{"explorer", path.toString()});
                    } else if (os.contains("mac")) {
                        Runtime.getRuntime().exec(new String[]{"open", path.toString()});
                    } else {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", path.toString()});
                    }
                }
            } catch (Exception e) {
                logger.warn("打开文件夹失败: {}", e.getMessage());
            }
        });
    }

    /** 文件条目 */
    private static class FileEntry {
        Path path;
        String name;
        boolean directory;
        long size;
        Instant modified;
    }
}