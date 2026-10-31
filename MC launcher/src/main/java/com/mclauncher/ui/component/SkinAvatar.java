package com.mclauncher.ui.component;

import com.mclauncher.auth.Account;
import com.mclauncher.auth.SkinFetcher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkinAvatar {

    private static final Logger logger = LoggerFactory.getLogger(SkinAvatar.class);

    private static final SkinFetcher FETCHER = new SkinFetcher();

    private final StackPane root;
    private final double size;

    private final boolean square;

    private volatile String currentKey;

    private SkinAvatar(double size, boolean square) {
        this.size = size;
        this.square = square;
        this.root = new StackPane();
        root.setMinSize(size, size);
        root.setPrefSize(size, size);
        root.setMaxSize(size, size);
        root.getStyleClass().add("skin-avatar");

        Rectangle clip = new Rectangle(size, size);
        if (square) {
            clip.setArcWidth(6);
            clip.setArcHeight(6);
        } else {
            clip.setArcWidth(size);
            clip.setArcHeight(size);
        }
        root.setClip(clip);
    }

    public static SkinAvatar circle(double size) {
        return new SkinAvatar(size, false);
    }

    public static SkinAvatar square(double size) {
        return new SkinAvatar(size, true);
    }

    public Node getNode() {
        return root;
    }

    public void bind(Account account) {
        if (account == null) {
            currentKey = null;
            root.getChildren().setAll(AvatarView.create("?", size));
            return;
        }

        String key = accountKey(account);
        currentKey = key;

        root.getChildren().setAll(AvatarView.create(account.getUsername(), size));

        Image cached = FETCHER.peekSkin(account);
        if (cached != null) {
            applySkin(cached, key);
            return;
        }

        Thread t = new Thread(() -> {
            Image skin = null;
            try {
                skin = FETCHER.fetchSkin(account);
            } catch (Exception e) {
                logger.debug("皮肤加载异常: {}", e.getMessage());
            }
            if (skin == null) {
                return;         
            }

            Image full = skin;
            Platform.runLater(() -> applySkin(full, key));
        }, "skin-avatar");
        t.setDaemon(true);
        t.start();
    }

    private void applySkin(Image skin, String key) {

        if (!key.equals(currentKey)) {
            return;
        }

        int px = (int) Math.round(size * 4);
        Image head = SkinFetcher.headAvatar(skin, px, true);
        if (head == null) {
            return;
        }

        ImageView iv = new ImageView(head);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setSmooth(false);       
        iv.setPreserveRatio(true);

        root.getChildren().setAll(iv);
    }

    private static String accountKey(Account a) {
        String u = a.getUuid();
        return u != null && !u.isBlank() ? u : String.valueOf(a.getUsername());
    }
}
