package com.mclauncher.ui.component;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/** 头像组件 */
public final class AvatarView {

    private AvatarView() {}

    /** 一组柔和但足够分辨的底色 */
    private static final String[] PALETTE = {
            "#3B82F6",  // 主题蓝
            "#8B5CF6",  // 紫
            "#EC4899",  // 粉
            "#F59E0B",  // 橙
            "#10B981",  // 绿
            "#06B6D4",  // 青
            "#EF4444",  // 红
            "#6366F1",  // 靛
    };

    /** 创建头像节点 */
    public static Node create(String username, double size) {
        String initial = initialOf(username);
        String color = colorFor(username);

        // 圆形底
        Circle circle = new Circle(size / 2);
        circle.setFill(Color.web(color));

        // 首字母
        Label letter = new Label(initial);
        letter.setTextFill(Color.WHITE);
        letter.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, size * 0.42));

        StackPane pane = new StackPane(circle, letter);
        pane.setAlignment(Pos.CENTER);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);

        return pane;
    }

    /**
     * 取首字母（中文取第一个字，英文取第一个字母并大写）
     */
    public static String initialOf(String username) {
        if (username == null || username.isBlank()) {
            return "?";
        }
        String s = username.trim();
        // 取第一个码点，避免把代理对（emoji）切坏
        int cp = s.codePointAt(0);
        String first = new String(Character.toChars(cp));
        return first.toUpperCase();
    }

    /**
     * 根据用户名稳定地选一个颜色
     */
    public static String colorFor(String username) {
        if (username == null || username.isBlank()) {
            return PALETTE[0];
        }
        // 用绝对哈希保证非负且稳定
        int idx = Math.abs(username.hashCode()) % PALETTE.length;
        return PALETTE[idx];
    }
}