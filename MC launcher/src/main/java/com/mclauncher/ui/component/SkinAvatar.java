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

/**
 * 带皮肤图标的头像组件
 *
 * 与 {@link AvatarView} 的区别：
 * <ul>
 *   <li>先显示用户名首字母占位（立刻可见，不闪空白）</li>
 *   <li>后台线程去皮肤站取皮肤，取到后换成真实头像</li>
 *   <li>取不到就保持首字母占位 —— 绝不显示一个坏图</li>
 * </ul>
 *
 * <b>线程约定</b>：{@link #bind(Account)} 可以从任意线程调用，
 * 内部会把结果切回 FX 线程。
 */
public final class SkinAvatar {

    private static final Logger logger = LoggerFactory.getLogger(SkinAvatar.class);

    /** 共享的皮肤请求器（内部有缓存，多实例复用同一份缓存） */
    private static final SkinFetcher FETCHER = new SkinFetcher();

    private final StackPane root;
    private final double size;
    /** 方形（true）或圆形（false） */
    private final boolean square;

    /** 当前请求的账号标识，用于丢弃过期结果（快速切账号时） */
    private volatile String currentKey;

    private SkinAvatar(double size, boolean square) {
        this.size = size;
        this.square = square;
        this.root = new StackPane();
        root.setMinSize(size, size);
        root.setPrefSize(size, size);
        root.setMaxSize(size, size);
        root.getStyleClass().add("skin-avatar");

        // 圆形裁剪 / 圆角裁剪
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

    /** 圆形头像（用于徽标/状态栏） */
    public static SkinAvatar circle(double size) {
        return new SkinAvatar(size, false);
    }

    /** 方形头像（用于账号卡片） */
    public static SkinAvatar square(double size) {
        return new SkinAvatar(size, true);
    }

    public Node getNode() {
        return root;
    }

    /**
     * 绑定账号并异步加载皮肤
     *
     * @param account 账号；null 表示清空
     */
    public void bind(Account account) {
        if (account == null) {
            currentKey = null;
            root.getChildren().setAll(AvatarView.create("?", size));
            return;
        }

        String key = accountKey(account);
        currentKey = key;

        // 先用首字母占位
        root.getChildren().setAll(AvatarView.create(account.getUsername(), size));

        // 缓存命中：直接显示，无需开线程
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
                return;         // 保持首字母占位
            }

            // 裁剪要在 FX 线程做（PixelReader 读取的是 FX Image）
            Image full = skin;
            Platform.runLater(() -> applySkin(full, key));
        }, "skin-avatar");
        t.setDaemon(true);
        t.start();
    }

    private void applySkin(Image skin, String key) {
        // 期间又切了账号：丢弃旧结果
        if (!key.equals(currentKey)) {
            return;
        }

        // 裁剪用 4 倍尺寸再缩小显示，避免锯齿
        int px = (int) Math.round(size * 4);
        Image head = SkinFetcher.headAvatar(skin, px, true);
        if (head == null) {
            return;
        }

        ImageView iv = new ImageView(head);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setSmooth(false);       // 像素风：不要插值模糊
        iv.setPreserveRatio(true);

        root.getChildren().setAll(iv);
    }

    private static String accountKey(Account a) {
        String u = a.getUuid();
        return u != null && !u.isBlank() ? u : String.valueOf(a.getUsername());
    }
}