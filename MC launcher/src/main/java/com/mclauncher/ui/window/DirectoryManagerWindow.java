package com.mclauncher.ui.window;

import com.mclauncher.i18n.L;
import com.mclauncher.config.ConfigManager;
import com.mclauncher.config.GameDirectory;
import com.mclauncher.utils.DirectoryUtils;
import com.mclauncher.utils.FileUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import com.mclauncher.ui.component.ModalHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class DirectoryManagerWindow {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryManagerWindow.class);

    private final ModalHost modalHost;
    private final ConfigManager config;
    private final Consumer<String> onChanged;

    private VBox listContainer;
    private Label summaryLabel;
    private Label hintLabel;

    private List<GameDirectory> directories;

    public DirectoryManagerWindow(ModalHost modalHost, ConfigManager config, Consumer<String> onChanged) {
        this.modalHost = modalHost;
        this.config = config;
        this.onChanged = onChanged;
    }

    public void show() {
        directories = config.getGameDirectories();
        BorderPane content = buildUI();

        modalHost.show(L.t("s_a0be294e14"), content, null);

        refreshList();
    }

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("bg-main");

        root.setPrefSize(720, 420);

        root.setMinHeight(0);

        VBox header = new VBox(8);
        header.setPadding(new Insets(14, 20, 6, 20));

        Label sub = new Label(L.t("s_6f4d15af25"));
        sub.getStyleClass().add("page-subtitle");

        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        summaryLabel = new Label();
        summaryLabel.getStyleClass().add("text-secondary");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button addBtn = new Button(L.t("s_30fe5b9553"));
        addBtn.getStyleClass().add("button-primary");
        addBtn.setOnAction(e -> addDirectory());

        actionRow.getChildren().addAll(summaryLabel, sp, addBtn);

        header.getChildren().addAll(sub, actionRow);

        listContainer = new VBox(8);
        listContainer.setPadding(new Insets(4));

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        

        scroll.setPrefHeight(250);
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox center = new VBox(scroll);
        center.setPadding(new Insets(0, 20, 8, 20));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox footerBox = new VBox(8);
        footerBox.setPadding(new Insets(0, 20, 12, 20));

        hintLabel = new Label(
                L.t("s_ce55420323")
                + L.t("s_fd0d56d0c1"));
        hintLabel.getStyleClass().add("text-muted");
        hintLabel.setWrapText(true);

        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        Button closeBtn = new Button(L.t("s_6c14bd7f6f"));
        closeBtn.getStyleClass().add("button");
        closeBtn.setOnAction(e -> modalHost.close());
        footer.getChildren().add(closeBtn);

        footerBox.getChildren().addAll(hintLabel, footer);

        root.setTop(header);
        root.setCenter(center);
        root.setBottom(footerBox);
        return root;
    }

    private void refreshList() {
        directories = config.getGameDirectories();
        String activeId = config.getActiveDirectoryId();

        listContainer.getChildren().clear();

        summaryLabel.setText(directories.isEmpty()
                ? L.t("s_4eed672dc3")
                : L.t("s_497064be1f") + directories.size() + L.t("s_c4577ea2c4"));

        if (directories.isEmpty()) {
            VBox empty = new VBox(8);
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(40));
            Label l1 = new Label(L.t("s_a02c8ec96a"));
            l1.getStyleClass().add("text-secondary");
            Label l2 = new Label(L.t("s_4978a00efb"));
            l2.getStyleClass().add("text-muted");
            l2.setWrapText(true);
            empty.getChildren().addAll(l1, l2);
            listContainer.getChildren().add(empty);
            return;
        }

        for (GameDirectory d : directories) {
            boolean active = d.getId() != null && d.getId().equals(activeId);
            listContainer.getChildren().add(buildRow(d, active));
        }
    }

    private HBox buildRow(GameDirectory d, boolean active) {
        HBox row = new HBox(12);
        row.getStyleClass().add("card");
        row.setPadding(new Insets(14));
        row.setAlignment(Pos.CENTER_LEFT);

        RadioButton radio = new RadioButton();
        radio.setSelected(active);
        radio.setOnAction(e -> {
            if (!radio.isSelected()) {
                return;
            }
            config.setActiveDirectoryId(d.getId());
            logger.info("切换激活目录: {}", d.getRootPathString());
            if (onChanged != null) {
                onChanged.accept("当前目录: " + d.getDisplayName());
            }
            refreshList();
        });

        VBox textBox = new VBox(3);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox nameRow = new HBox(8);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(d.getDisplayName());
        nameLabel.getStyleClass().add("card-title");

        if (active) {
            Label badge = new Label(L.t("s_297318c276"));
            badge.setStyle("-fx-background-color: #3B82F6; -fx-text-fill: white; "
                    + "-fx-padding: 2 8 2 8; -fx-background-radius: 10; "
                    + "-fx-font-size: 11px;");
            nameRow.getChildren().addAll(nameLabel, badge);
        } else {
            nameRow.getChildren().add(nameLabel);
        }

        Label rootLabel = new Label(L.t("s_31e86b0c9d") + d.getRootPathString());
        rootLabel.getStyleClass().add("text-muted");
        rootLabel.setStyle("-fx-font-size: 12px;");

        Label storageLabel = new Label();
        storageLabel.setStyle("-fx-font-size: 12px;");
        updateStorageLabel(storageLabel, d);

        textBox.getChildren().addAll(nameRow, rootLabel, storageLabel);

        

        

        MenuButton actions = new MenuButton("⋮");
        actions.getStyleClass().add("menu-dots");
        actions.setFocusTraversable(false);

        MenuItem openItem = new MenuItem(L.t("s_3120403417"));
        openItem.setOnAction(e -> openFolder(d.getRootPath()));

        MenuItem renameItem = new MenuItem(L.t("s_1cd80fd7a8"));
        renameItem.setOnAction(e -> rename(d));

        MenuItem removeItem = new MenuItem(L.t("s_4e4edc033d"));
        removeItem.setOnAction(e -> remove(d));

        actions.getItems().addAll(openItem, renameItem,
                new SeparatorMenuItem(), removeItem);

        row.getChildren().addAll(radio, textBox, actions);
        return row;
    }

    private void updateStorageLabel(Label label, GameDirectory d) {
        Path root = d.getRootPath();

        if (!d.existsOnDisk()) {
            label.setText(L.t("s_4fd081b96e"));
            label.setStyle(label.getStyle() + "-fx-text-fill: #F59E0B;");
            return;
        }

        Path meta = d.getLauncherDataPath();

        if (!java.nio.file.Files.isDirectory(meta)) {
            label.setText(L.t("s_ad1b0eb7fb") + meta + L.t("s_88b2f0f27c"));
            label.getStyleClass().add("text-muted");
            return;
        }

        label.setText(L.t("s_ad1b0eb7fb") + meta);
        label.getStyleClass().add("text-muted");

        CompletableFuture.supplyAsync(() -> DirectoryUtils.calculateSize(meta))
                .thenAccept(size -> Platform.runLater(() ->
                        label.setText(L.t("s_ad1b0eb7fb") + meta
                                + L.t("s_4eaa62db90") + FileUtils.formatFileSize(size))));
    }

    private void addDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(L.t("s_6b496b310e"));

        GameDirectory active = config.getActiveDirectory();
        if (active != null && active.existsOnDisk()) {
            chooser.setInitialDirectory(active.getRootPath().toFile());
        }

        File selected = chooser.showDialog(null);
        if (selected == null) {
            return;
        }

        Path root = selected.toPath();

        String error = DirectoryUtils.validateRootDirectory(root);
        if (error != null) {
            showAlert(Alert.AlertType.ERROR, L.t("s_5602bb851c"), error);
            return;
        }

        for (GameDirectory d : config.getGameDirectories()) {
            if (d.getRootPathString() != null
                    && d.getRootPathString().equalsIgnoreCase(root.toString())) {
                showAlert(Alert.AlertType.INFORMATION, L.t("s_a867d42ddf"),
                        L.t("s_25a74350db"));
                return;
            }
        }

        String name = root.getFileName() != null
                ? root.getFileName().toString() : root.toString();

        GameDirectory added = config.addGameDirectory(root.toString(), name);
        if (added == null) {
            showAlert(Alert.AlertType.ERROR, L.t("s_9d563ebbbd"), L.t("s_82d7144e33"));
            return;
        }

        try {
            prepareStorage(added);
        } catch (Exception e) {
            logger.warn("创建数据文件夹失败: {}", e.getMessage());
            showAlert(Alert.AlertType.WARNING, "警告",
                    "目录已添加，但创建数据文件夹失败：\n" + e.getMessage());
        }

        if (onChanged != null) {
            onChanged.accept("已添加目录: " + added.getDisplayName());
        }
        refreshList();
    }

    private void prepareStorage(GameDirectory d) throws Exception {
        Path meta = d.getLauncherDataPath();

        DirectoryUtils.ensureHiddenTree(meta, "cache", "logs");

        logger.info("已准备启动器数据目录: {} (隐藏={})",
                meta, DirectoryUtils.isHidden(meta));
    }

    private void rename(GameDirectory d) {
        com.mclauncher.ui.component.MessageDialog.prompt(
                modalHost,
                L.t("s_de6b3360f5"),
                L.t("s_7f1c1d70fc"),
                L.t("s_1be7ae4fc2"),
                d.getDisplayName(),
                name -> {
                    config.renameGameDirectory(d.getId(), name);
                    if (onChanged != null) {
                        onChanged.accept(L.t("s_f50abb8996") + name);
                    }
                    refreshList();
                });
    }

    private void remove(GameDirectory d) {
        com.mclauncher.ui.component.MessageDialog.confirm(
                modalHost,
                L.t("s_050ff5c725"),
                L.t("s_4b7c1e2501") + d.getDisplayName() + "」",
                L.t("s_dd3468dd98"),
                L.t("s_d0d7c5b6a8") + d.getRootPathString() + L.t("s_077ffdd249") + d.getLauncherDataPath() + L.t("s_ff98f68533"),
                L.t("s_2f752c005e"),
                () -> {
                    config.removeGameDirectory(d.getId());
                    if (onChanged != null) {
                        onChanged.accept(L.t("s_dd0641471e") + d.getDisplayName());
                    }
                    refreshList();
                });
    }

    private void openFolder(Path path) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!java.nio.file.Files.exists(path)) {
                    java.nio.file.Files.createDirectories(path);
                    DirectoryUtils.hide(path);
                }
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop()
                                .isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(path.toFile());
                } else {
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

    private void showAlert(Alert.AlertType type, String title, String content) {
        if (type == Alert.AlertType.ERROR) {
            com.mclauncher.ui.component.MessageDialog.error(modalHost, title, content);
        } else if (type == Alert.AlertType.WARNING) {
            com.mclauncher.ui.component.MessageDialog.warn(modalHost, title, content);
        } else {
            com.mclauncher.ui.component.MessageDialog.info(modalHost, title, content);
        }
    }
}
