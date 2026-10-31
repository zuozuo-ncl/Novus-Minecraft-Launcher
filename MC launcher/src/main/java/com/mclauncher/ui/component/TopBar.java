package com.mclauncher.ui.component;

import com.mclauncher.i18n.L;
import com.mclauncher.auth.Account;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class TopBar {

    private final HBox root;
    private final StackPane avatarSlot;
    private final Label usernameLabel;
    private final Label accountTypeLabel;

    public TopBar() {
        root = new HBox(16);
        root.getStyleClass().add("top-bar");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(12, 20, 12, 20));

        Label logoIcon = new Label("⛏");
        logoIcon.setStyle("-fx-font-size: 22px;");

        Label logoText = new Label("Novus Minecraft Launcher");
        logoText.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
        logoText.getStyleClass().add("top-bar-logo");

        HBox logo = new HBox(8, logoIcon, logoText);
        logo.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        avatarSlot = new StackPane();
        avatarSlot.setMinSize(36, 36);
        avatarSlot.setPrefSize(36, 36);
        avatarSlot.setMaxSize(36, 36);

        usernameLabel = new Label(L.t("s_e8041a8c93"));
        usernameLabel.getStyleClass().add("top-bar-username");

        accountTypeLabel = new Label("");
        accountTypeLabel.getStyleClass().add("top-bar-account-type");

        VBox accountText = new VBox(1, usernameLabel, accountTypeLabel);
        accountText.setAlignment(Pos.CENTER_LEFT);

        HBox accountBox = new HBox(10, avatarSlot, accountText);
        accountBox.setAlignment(Pos.CENTER_LEFT);
        accountBox.getStyleClass().add("top-bar-account");
        accountBox.setPadding(new Insets(6, 12, 6, 8));

        root.getChildren().addAll(logo, spacer, accountBox);

        updateAccount(null);
    }

    public Region getContent() {
        return root;
    }

    public void updateAccount(Account account) {
        avatarSlot.getChildren().clear();

        if (account == null) {

            avatarSlot.getChildren().add(AvatarView.create("?", 36));
            usernameLabel.setText(L.t("s_e8041a8c93"));
            accountTypeLabel.setText(L.t("s_dd5c56f107"));
            return;
        }

        String name = account.getUsername();
        avatarSlot.getChildren().add(AvatarView.create(name, 36));

        usernameLabel.setText(name == null || name.isBlank() ? L.t("s_d9c32a4c3d") : name);

        if (account.getType() != null) {
            accountTypeLabel.setText(account.getType().getDisplayName());
        } else {
            accountTypeLabel.setText("");
        }
    }
}
