package com.mclauncher.ui.component;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public final class AvatarView {

    private AvatarView() {}

    private static final String[] PALETTE = {
            "#3B82F6",  
            "#8B5CF6",  
            "#EC4899",  
            "#F59E0B",  
            "#10B981",  
            "#06B6D4",  
            "#EF4444",  
            "#6366F1",  
    };

    public static Node create(String username, double size) {
        String initial = initialOf(username);
        String color = colorFor(username);

        Circle circle = new Circle(size / 2);
        circle.setFill(Color.web(color));

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

    public static String initialOf(String username) {
        if (username == null || username.isBlank()) {
            return "?";
        }
        String s = username.trim();

        int cp = s.codePointAt(0);
        String first = new String(Character.toChars(cp));
        return first.toUpperCase();
    }

    public static String colorFor(String username) {
        if (username == null || username.isBlank()) {
            return PALETTE[0];
        }

        int idx = Math.abs(username.hashCode()) % PALETTE.length;
        return PALETTE[idx];
    }
}
