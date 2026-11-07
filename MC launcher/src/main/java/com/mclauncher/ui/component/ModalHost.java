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

/**
 * 内嵌式弹窗宿主
 * 用法：
 */
public class ModalHost {

    private static final Logger logger = LoggerFactory.getLogger(ModalHost.class);

    /** 遮罩层：平时不可见、不拦截事件 */
    private final StackPane root;

    /** 已打开的弹窗栈（栈顶是最上层） */
    private final Deque<Panel> stack = new ArrayDeque<>();

    public ModalHost() {
        root = new StackPane();
        root.getStyleClass().add("modal-overlay");
        root.setVisible(false);
        root.setManaged(false);
        // 遮罩负责铺满内容区、卡片靠自己收缩 —— 它必须能占满，不能被压成内容高度
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // 点击遮罩空白处 = 关闭（点击面板内部不关闭）
        // 只有「可关闭」的弹窗才响应，否则下载进度这类面板会被误关
        root.setOnMouseClicked(e -> {
            Object target = e.getTarget();
            Panel top = stack.peek();
            boolean blank = target == root
                    || (top != null && target == top.shell);
            if (blank && isTopClosable()) {
                close();
            }
        });

        // ESC 关闭最上层（同样尊重 closable）
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && isTopClosable()) {
                close();
                e.consume();
            }
        });
        root.setFocusTraversable(true);
    }

    /** 最上层弹窗是否允许通过遮罩 / ESC 关闭 */
    private boolean isTopClosable() {
        Panel top = stack.peek();
        return top == null || top.closable;
    }

    /**
     * 遮罩节点（由 MainWindow 放入内容区之上的层级）
     */
    public Node getNode() {
        return root;
    }

    /**
     * 是否正在显示弹窗
     */
    public boolean isShowing() {
        return !stack.isEmpty();
    }

    /** 把遮罩重新提到内容层之上 */
    public void bringToFront() {
        if (root.getParent() instanceof Pane parent) {
            root.toFront();
        }
        root.setVisible(true);
        root.setManaged(true);
    }

    /**
     * 动态切换最上层弹窗的「可否关闭」
     * 只能放开、不能收紧 —— 已经画出来的 ✕ 按钮不会因此消失。
     */
    public void setClosable(boolean closable) {
        Panel top = stack.peek();
        if (top == null) {
            return;
        }
        top.closable = closable;
    }

    /** 弹出一个面板 */
    public void show(String title, Region content, Runnable onClose) {
        show(title, content, onClose, true);
    }

    /** 弹出一个面板 */
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

        // 提到最前：否则可能被页面内容盖住，看起来像"卡住"
        root.toFront();
        root.requestFocus();

        // 让 overlay 铺满父容器，保证它一定覆盖整个内容区
        root.setMaxWidth(Double.MAX_VALUE);
        root.setMaxHeight(Double.MAX_VALUE);

        // 淡入 + 轻微放大
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

    /**
     * 关闭最上层弹窗
     */
    /**
     * 关闭最上层弹窗（必须在 JavaFX 线程调用）
     * 后台线程误调时改投 runLater：弹窗栈与场景图的修改必须和点击事件串行，
     */
    public void close() {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            javafx.application.Platform.runLater(this::close);
            return;
        }
        Panel panel = stack.poll();
        if (panel == null) {
            return;
        }

        // 回调（在移除之前调用，便于回调里再做刷新）
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
            // 还有下层，把焦点还给新的栈顶
            root.toFront();
        }

        logger.debug("弹窗关闭，剩余层数 {}", stack.size());
    }

    /** 关闭指定层的弹窗（按引用移除，不只关栈顶）。 */
    public boolean closePanel(javafx.scene.layout.Region content) {
        if (content == null || stack.isEmpty()) {
            return false;
        }
        if (!javafx.application.Platform.isFxApplicationThread()) {
            // 本面板仍在栈里：调用方认为「已收起」，点击事件应立刻被挡住，
            // 真正的移除投递到 FX 线程执行
            removeNodeNow(content);
            javafx.application.Platform.runLater(() -> closePanel(content));
            return true;
        }
        // ArrayDeque 的 iterator 是「栈顶 -> 栈底」，先关上面的层，再关目标层
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

    /** 把某个内容的弹窗节点立刻移出场景图（只用于跨线程的临时隐藏） */
    private void removeNodeNow(javafx.scene.layout.Region content) {
        for (Panel p : stack) {
            if (p.content == content) {
                root.getChildren().remove(p.node);
                if (stack.size() == 1) {
                    root.setVisible(false);
                    root.setManaged(false);
                }
                return;
            }
        }
    }

    /** 指定内容的面板当前是否正显示 */
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

    /**
     * 关闭全部弹窗
     */
    public void closeAll() {
        while (!stack.isEmpty()) {
            close();
        }
    }

    /** 内容里是否有可滚动区域（ScrollPane / ListView / TextArea 之类） */
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

    /**
     * 单个弹窗的内部结构
     */
    private class Panel {
        final Node node;
        final VBox card;
        /** 面板外层（居中容器）。用于判断鼠标是否点在空白区 */
        Region shell;
        final Runnable onClose;
        final Region content;
        /** 是否允许关闭（遮罩/ESC/✕）。下载进度面板会动态放开 */
        boolean closable;

        Panel(String title, Region content, Runnable onClose, boolean closable) {
            this.onClose = onClose;
            this.content = content;
            this.closable = closable;

            // 标题栏
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

            // 标题栏拖动（虽然是内嵌的，保留视觉反馈）
            titleBar.setOnMouseClicked(e -> root.requestFocus());

            // 内容
            content.getStyleClass().add("modal-content");
            VBox.setVgrow(content, Priority.ALWAYS);
            // 内容可压缩：VBox 分配高度时先满足标题栏等不可压缩子节点的 min，
            content.setMinHeight(0);
            if (!hasScrollable(content)) {
                content.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            }

            // 卡片
            card = new VBox();
            card.getStyleClass().add("modal-card");
            card.getChildren().addAll(titleBar, content);

            // 限制尺寸：留出边距，避免铺满整屏；并且不超过当前窗口 ——
            // 否则固定 900x680 的卡片在 1000x600 的窗口里会把底部按钮挤出可视区。
            javafx.scene.Scene scene = root.getScene();
            //
            // 只设 max 不够：中间行被强制按 PREF 高度居中后，卡片会缩成
            // 「内容最小高度」（只有标题栏），整块内容被压没。所以同时给 min 设上
            // 「与 max 相同的上限」，让卡片在窗口内取尽可能大的高度；
            // 真正的收缩交给卡片**内部**的可滚动区域。
            if (scene != null) {
                final double marginW = 64;   // 外层左右 padding 24*2 + 余量
                final double marginH = 88;   // 外层上下 padding 24*2 + 标题栏余量
                javafx.beans.binding.DoubleBinding maxW = javafx.beans.binding.Bindings
                        .min(900.0, scene.widthProperty().subtract(marginW));
                javafx.beans.binding.DoubleBinding maxH = javafx.beans.binding.Bindings
                        .min(680.0, scene.heightProperty().subtract(marginH));
                card.maxWidthProperty().bind(maxW);
                card.maxHeightProperty().bind(maxH);
                // 宽度下限固定 360（小提示框不会被拉宽到 900，见下方说明），
                // 窗口比它还窄时以窗口为准
                card.minWidthProperty().bind(javafx.beans.binding.Bindings
                        .min(360.0, maxW));
                card.minHeightProperty().bind(maxH);
            } else {
                card.setMaxWidth(900);
                card.setMaxHeight(680);
                card.setMinHeight(680);
            }

            // 外层：居中 + 内边距
            // 尺寸要点（下方注释附实测数据）：卡片必须**同时**按内容
            Region leftSpacer = new Region();
            Region rightSpacer = new Region();
            HBox.setHgrow(leftSpacer, Priority.ALWAYS);
            HBox.setHgrow(rightSpacer, Priority.ALWAYS);

            HBox centerRow = new HBox(leftSpacer, card, rightSpacer);
            centerRow.setAlignment(Pos.CENTER);
            centerRow.setMaxWidth(Double.MAX_VALUE);
            // 关键：锁住高度，否则外层 StackPane 会把它连同 card 一起拉满
            centerRow.setMaxHeight(Region.USE_PREF_SIZE);

            StackPane wrapper = new StackPane(centerRow);
            wrapper.setAlignment(Pos.CENTER);
            wrapper.setPadding(new Insets(24));
            wrapper.setMaxWidth(Double.MAX_VALUE);
            wrapper.setMaxHeight(Double.MAX_VALUE);
            // 注意：不能让 wrapper 的 min 变成 0 —— 弹窗靠「wrapper 的 min 撑开
            // 注意：这里**不能**设 pickOnBounds(false)，

            this.shell = wrapper;
            node = wrapper;
        }
    }
}