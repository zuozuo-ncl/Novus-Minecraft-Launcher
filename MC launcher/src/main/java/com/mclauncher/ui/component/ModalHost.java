package com.mclauncher.ui.component;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

public class ModalHost {

    private static final Logger logger = LoggerFactory.getLogger(ModalHost.class);

    private final StackPane root;

    private final Deque<Panel> stack = new ArrayDeque<>();

    public ModalHost() {
        root = new StackPane();
        root.getStyleClass().add("modal-overlay");
        root.setVisible(false);
        root.setManaged(false);

        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        

        

        

        root.setOnMouseClicked(e -> {
            Object target = e.getTarget();
            Panel top = stack.peek();
            boolean blank = target == root
                    || (top != null && target == top.shell);
            if (blank && isTopClosable()) {
                close();
            }
        });

        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && isTopClosable()) {
                close();
                e.consume();
            }
        });
        root.setFocusTraversable(true);
    }

    private boolean isTopClosable() {
        Panel top = stack.peek();
        return top == null || top.closable;
    }

    public Node getNode() {
        return root;
    }

    public boolean isShowing() {
        return !stack.isEmpty();
    }

    public void bringToFront() {
        if (root.getParent() instanceof Pane parent) {
            root.toFront();
        }
        root.setVisible(true);
        root.setManaged(true);
    }

    public void setClosable(boolean closable) {
        Panel top = stack.peek();
        if (top == null) {
            return;
        }
        top.closable = closable;
    }

    public void show(String title, Region content, Runnable onClose) {
        show(title, content, onClose, true);
    }

    public void show(String title, Region content, Runnable onClose, boolean closable) {
        if (content == null) {
            logger.warn("弹窗内容为空，已忽略: {}", title);
            return;
        }
        if (root.getScene() == null) {
            logger.warn("弹窗宿主尚未挂到窗口上，弹窗可能不可见: {}", title);
        }
        Panel panel = new Panel(title, content, onClose, closable);
        stack.push(panel);

        root.getChildren().add(panel.node);
        root.setVisible(true);
        root.setManaged(true);

        root.toFront();
        root.requestFocus();

        root.setMaxWidth(Double.MAX_VALUE);
        root.setMaxHeight(Double.MAX_VALUE);

        FadeTransition fade = new FadeTransition(Duration.millis(140), root);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        ScaleTransition scale = new ScaleTransition(Duration.millis(140), panel.card);
        scale.setFromX(0.96);
        scale.setFromY(0.96);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.play();

        logger.debug("弹窗打开: {}（当前层数 {}）", title, stack.size());
    }

    public void close() {
        Panel panel = stack.poll();
        if (panel == null) {
            return;
        }

        if (panel.onClose != null) {
            try {
                panel.onClose.run();
            } catch (Exception e) {
                logger.debug("弹窗关闭回调异常: {}", e.getMessage());
            }
        }

        root.getChildren().remove(panel.node);

        if (stack.isEmpty()) {
            root.setVisible(false);
            root.setManaged(false);
        } else {

            root.toFront();
        }

        logger.debug("弹窗关闭，剩余层数 {}", stack.size());
    }

    public boolean closePanel(javafx.scene.layout.Region content) {
        if (content == null || stack.isEmpty()) {
            return false;
        }

        Panel target = null;
        for (Panel panel : stack) {
            if (panel.content == content) {
                target = panel;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        while (!stack.isEmpty() && stack.peek() != target) {
            close();
        }
        close();
        return true;
    }

    public boolean isShowingPanel(javafx.scene.layout.Region content) {
        if (content == null) {
            return false;
        }
        for (Panel panel : stack) {
            if (panel.content == content) {
                return true;
            }
        }
        return false;
    }

    public void closeAll() {
        while (!stack.isEmpty()) {
            close();
        }
    }

    private static boolean hasScrollable(javafx.scene.Node node) {
        if (node instanceof javafx.scene.control.ScrollPane
                || node instanceof javafx.scene.control.ListView
                || node instanceof javafx.scene.control.TableView
                || node instanceof javafx.scene.control.TreeView
                || node instanceof javafx.scene.control.TextArea) {
            return true;
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                if (hasScrollable(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private class Panel {
        final Node node;
        final VBox card;

        Region shell;
        final Runnable onClose;
        final Region content;

        boolean closable;

        Panel(String title, Region content, Runnable onClose, boolean closable) {
            this.onClose = onClose;
            this.content = content;
            this.closable = closable;

            HBox titleBar = new HBox(10);
            titleBar.getStyleClass().add("modal-title-bar");
            titleBar.setAlignment(Pos.CENTER_LEFT);
            titleBar.setPadding(new Insets(14, 16, 14, 18));

            Label titleLabel = new Label(title == null ? "" : title);
            titleLabel.getStyleClass().add("modal-title");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            titleBar.getChildren().addAll(titleLabel, spacer);

            if (closable) {
                Button closeBtn = new Button("✕");
                closeBtn.getStyleClass().add("modal-close-button");
                closeBtn.setOnAction(e -> close());
                titleBar.getChildren().add(closeBtn);
            }

            titleBar.setOnMouseClicked(e -> root.requestFocus());

            content.getStyleClass().add("modal-content");
            VBox.setVgrow(content, Priority.ALWAYS);

            

            

            
            content.setMinHeight(0);
            if (!hasScrollable(content)) {
                content.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            }

            card = new VBox();
            card.getStyleClass().add("modal-card");
            card.getChildren().addAll(titleBar, content);

            

            

            

            

            

            
            javafx.scene.Scene scene = root.getScene();

            

            

            if (scene != null) {
                final double marginW = 64;   
                final double marginH = 88;   
                javafx.beans.binding.DoubleBinding maxW = javafx.beans.binding.Bindings
                        .min(900.0, scene.widthProperty().subtract(marginW));
                javafx.beans.binding.DoubleBinding maxH = javafx.beans.binding.Bindings
                        .min(680.0, scene.heightProperty().subtract(marginH));
                card.maxWidthProperty().bind(maxW);
                card.maxHeightProperty().bind(maxH);

                
                card.minWidthProperty().bind(javafx.beans.binding.Bindings
                        .min(360.0, maxW));
                card.minHeightProperty().bind(maxH);
            } else {
                card.setMaxWidth(900);
                card.setMaxHeight(680);
                card.setMinHeight(680);
            }

            

            

            

            

            

            

            

            

            Region leftSpacer = new Region();
            Region rightSpacer = new Region();
            HBox.setHgrow(leftSpacer, Priority.ALWAYS);
            HBox.setHgrow(rightSpacer, Priority.ALWAYS);

            HBox centerRow = new HBox(leftSpacer, card, rightSpacer);
            centerRow.setAlignment(Pos.CENTER);
            centerRow.setMaxWidth(Double.MAX_VALUE);

            centerRow.setMaxHeight(Region.USE_PREF_SIZE);

            StackPane wrapper = new StackPane(centerRow);
            wrapper.setAlignment(Pos.CENTER);
            wrapper.setPadding(new Insets(24));
            wrapper.setMaxWidth(Double.MAX_VALUE);
            wrapper.setMaxHeight(Double.MAX_VALUE);

            

            

            

            this.shell = wrapper;
            node = wrapper;
        }
    }
}
