package com.mclauncher.ui.component;


import com.mclauncher.i18n.L;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 内嵌式消息对话框
 * 为什么要自己写：
 * 用法（确认框，带回调）：
 */
public final class MessageDialog {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(MessageDialog.class);

    /**
     * 尺寸策略
     * 一句话的提示就只占一小块，不要顶着一个 620 宽的大白框。
     */
    private static final double MESSAGE_MAX_WIDTH = 460;
    private static final double PROMPT_WIDTH = 420;

    /**
     * 消息框的最小宽度
     *
     * 太窄的卡片配上下方两个按钮会显得局促，也给一个下限保证观感。
     */
    private static final double MESSAGE_MIN_WIDTH = 300;

    private MessageDialog() {}

    // ==================== 提示 ====================

    /**
     * 提示框（只有「知道了」）
     */
    public static void info(ModalHost host, String title, String message) {
        showSimple(host, title, message, Kind.INFO, null);
    }

    /**
     * 错误框（只有「知道了」）
     */
    public static void error(ModalHost host, String title, String message) {
        showSimple(host, title, message, Kind.ERROR, null);
    }

    /**
     * 警告框（只有「知道了」）
     */
    public static void warn(ModalHost host, String title, String message) {
        showSimple(host, title, message, Kind.WARN, null);
    }

    /**
     * 提示框，关闭后执行回调
     */
    public static void info(ModalHost host, String title, String message, Runnable onClosed) {
        showSimple(host, title, message, Kind.INFO, onClosed);
    }

    /**
     * 错误框，关闭后执行回调
     */
    public static void error(ModalHost host, String title, String message, Runnable onClosed) {
        showSimple(host, title, message, Kind.ERROR, onClosed);
    }

    /**
     * 确认框
     *
     * @param onConfirm 点「确定」后执行；点「取消」/关闭 / ESC / 点遮罩都不执行
     */
    public static void confirm(ModalHost host, String title, String header,
                               String message, Runnable onConfirm) {
        confirm(host, title, header, message, null, L.t("s_f526c89937"), onConfirm);
    }

    /** 确认框（自定义副标题说明与确认按钮文字） */
    public static void confirm(ModalHost host, String title, String header, String message,
                               String detail, String okText, Runnable onConfirm) {
        if (host == null) {
            return;
        }

        VBox content = buildBody(message, detail, Kind.QUESTION, header);

        HBox buttons = buildButtonRow(
                // 取消按钮必须绑定「关闭弹窗」，否则点击无任何反应。
                // 早期这里传了 null，而 buildButtonRow 会跳过 null 动作，
                // 导致 8 处确认框的取消按钮全是死的。
                new ButtonSpec(L.t("s_4d0b4688c7"), "button", () -> host.close()),
                new ButtonSpec(okText == null ? L.t("s_f526c89937") : okText, "button-primary", onConfirm));

        content.getChildren().add(buttons);

        host.show(title, content, null);
    }

    /**
     * 确认框，带「已确认」回调与「取消」回调
     * 注意：按 ESC / 点遮罩 / 点标题栏 ✕ 都算「取消」，会走 onCancel。
     */
    public static void confirmOrCancel(ModalHost host, String title, String header,
                                       String message, String okText, String cancelText,
                                       Runnable onConfirm, Runnable onCancel) {
        if (host == null) {
            return;
        }

        // 用一个数组当可变标志位：弹窗关闭回调只能拿到最终结果
        final boolean[] confirmed = {false};

        VBox content = buildBody(message, null, Kind.QUESTION, header);

        HBox buttons = buildButtonRow(
                new ButtonSpec(cancelText == null ? L.t("s_4d0b4688c7") : cancelText, "button", () -> {
                    confirmed[0] = false;
                    host.close();
                }),
                new ButtonSpec(okText == null ? L.t("s_f526c89937") : okText, "button-primary", () -> {
                    confirmed[0] = true;
                    host.close();
                }));

        content.getChildren().add(buttons);

        host.show(title, content, () -> {
            if (confirmed[0]) {
                if (onConfirm != null) {
                    onConfirm.run();
                }
            } else if (onCancel != null) {
                onCancel.run();
            }
        });
    }

    // ==================== 输入 ====================

    /** 输入框（替代 TextInputDialog） */
    public static void prompt(ModalHost host, String title, String header, String fieldLabel,
                              String defaultValue, Consumer<String> onOk) {
        if (host == null) {
            return;
        }

        VBox content = new VBox(10);
        content.setPadding(new Insets(16, 18, 14, 18));
        content.setPrefWidth(PROMPT_WIDTH);
        content.setMaxWidth(PROMPT_WIDTH);

        if (header != null && !header.isBlank()) {
            Label head = new Label(header);
            head.getStyleClass().add("card-title");
            head.setWrapText(true);
            content.getChildren().add(head);
        }

        TextField field = new TextField(defaultValue == null ? "" : defaultValue);
        field.setMaxWidth(Double.MAX_VALUE);

        Label errLabel = new Label(L.t("s_ca898456b2"));
        errLabel.getStyleClass().add("status-fail");
        errLabel.setVisible(false);
        errLabel.setManaged(false);

        if (fieldLabel != null && !fieldLabel.isBlank()) {
            Label fl = new Label(fieldLabel);
            fl.getStyleClass().add("text-secondary");
            content.getChildren().addAll(fl, field, errLabel);
        } else {
            content.getChildren().addAll(field, errLabel);
        }

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        Button cancelBtn = new Button(L.t("s_4d0b4688c7"));
        cancelBtn.getStyleClass().add("button");
        cancelBtn.setOnAction(e -> host.close());

        Button okBtn = new Button(L.t("s_f526c89937"));
        okBtn.getStyleClass().addAll("button", "button-primary");

        buttons.getChildren().addAll(cancelBtn, okBtn);

        Runnable submit = () -> {
            String v = field.getText() == null ? "" : field.getText().trim();
            if (v.isEmpty()) {
                // 空值不关闭，并给出明确原因（只描红框用户不知道哪里错了）
                if (!field.getStyleClass().contains("input-error")) {
                    field.getStyleClass().add("input-error");
                }
                errLabel.setVisible(true);
                errLabel.setManaged(true);
                field.requestFocus();
                return;
            }
            host.close();
            if (onOk != null) {
                onOk.accept(v);
            }
        };
        okBtn.setOnAction(e -> submit.run());
        field.setOnAction(e -> submit.run());   // 回车即确定

        content.getChildren().add(buttons);

        host.show(title, content, null);

        // 弹出后自动聚焦并全选，用户可直接改写
        javafx.application.Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
    }

    // ==================== 带链接的提示 ====================

    /** 带「可点击链接」的提示框 */
    public static void infoWithLink(ModalHost host, String title, String message,
                                    String linkText, String linkUrl) {
        if (host == null) {
            return;
        }

        VBox content = buildBody(message, null, Kind.INFO, null);

        // 在按钮行上方插入链接
        javafx.scene.control.Hyperlink link =
                new javafx.scene.control.Hyperlink(linkText);
        link.getStyleClass().add("link-button");
        link.setOnAction(e -> com.mclauncher.utils.UrlOpener.open(linkUrl));

        javafx.scene.layout.HBox linkRow =
                new javafx.scene.layout.HBox(link);
        linkRow.setPadding(new Insets(4, 0, 0, 0));

        content.getChildren().add(linkRow);

        HBox buttons = buildButtonRow(
                new ButtonSpec(L.t("s_cb63c62e50"), "button-primary", () -> host.close()));

        content.getChildren().add(buttons);

        host.show(title, content, null);
    }

    // ==================== 内部实现 ====================

    /** 图标语义（仅用于选择左侧色条颜色，不用图片资源） */
    private enum Kind { INFO, WARN, ERROR, QUESTION }

    private static void showSimple(ModalHost host, String title, String message,
                                   Kind kind, Runnable onClosed) {
        if (host == null) {
            return;
        }

        VBox content = buildBody(message, null, kind, null);

        HBox buttons = buildButtonRow(
                new ButtonSpec(L.t("s_cb63c62e50"), "button-primary", () -> host.close()));

        content.getChildren().add(buttons);

        host.show(title, content, onClosed);
    }

    /** 构建弹窗主体：标题 + 正文（+ 可选的次要说明） */
    private static VBox buildBody(String message, String detail, Kind kind, String header) {
        VBox body = new VBox(8);
        body.setPadding(new Insets(16, 18, 14, 18));
        // 不设 prefWidth：让卡片按实际文字宽度收缩
        body.setMinWidth(MESSAGE_MIN_WIDTH);
        body.setMaxWidth(MESSAGE_MAX_WIDTH);

        if (header != null && !header.isBlank()) {
            Label h = new Label(header);
            h.getStyleClass().add("modal-header-text");
            h.setWrapText(true);
            h.setMaxWidth(Double.MAX_VALUE);
            body.getChildren().add(h);
        }

        if (message != null && !message.isBlank()) {
            Label m = new Label(message);
            m.getStyleClass().add("modal-message-text");
            m.setWrapText(true);
            m.setMaxWidth(Double.MAX_VALUE);
            body.getChildren().add(m);
        }

        if (detail != null && !detail.isBlank()) {
            Label d = new Label(detail);
            d.getStyleClass().add("text-muted");
            d.setWrapText(true);
            d.setMaxWidth(Double.MAX_VALUE);
            body.getChildren().add(d);
        }

        // 语义色条
        Region bar = new Region();
        bar.setMinWidth(4);
        bar.setPrefWidth(4);
        bar.setMaxWidth(4);
        bar.getStyleClass().addAll("dialog-accent", accentClass(kind));

        HBox wrap = new HBox(0, bar, body);
        HBox.setHgrow(body, Priority.ALWAYS);

        // 用 VBox 包一层，保证返回值类型统一（调用方要往里加按钮行）
        VBox outer = new VBox(wrap);
        return outer;
    }

    private static String accentClass(Kind kind) {
        switch (kind) {
            case ERROR:    return "dialog-accent-error";
            case WARN:     return "dialog-accent-warn";
            case QUESTION: return "dialog-accent-question";
            case INFO:
            default:       return "dialog-accent-info";
        }
    }

    /** 一个按钮的描述 */
    private static final class ButtonSpec {
        final String text;
        final String styleClass;
        final Runnable action;

        ButtonSpec(String text, String styleClass, Runnable action) {
            this.text = text;
            this.styleClass = styleClass;
            this.action = action;
        }
    }

    /**
     * 右下角按钮行：取消在左、确认在右（符合桌面软件惯例）
     */
    private static HBox buildButtonRow(ButtonSpec... specs) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(4, 0, 0, 0));

        for (ButtonSpec spec : specs) {
            Button b = new Button(spec.text);
            b.getStyleClass().add("button");
            if (spec.styleClass != null && !spec.styleClass.isBlank()) {
                b.getStyleClass().add(spec.styleClass);
            }
            if (spec.action != null) {
                b.setOnAction(e -> spec.action.run());
            } else {
                // 兜底：没给动作的按钮直接禁用。
                //
                // 绝不能留下一个「可点但没反应」的按钮 —— 早期 confirm() 的
                // 取消按钮就是这样（action 传 null 被静默跳过），用户点了
                // 弹窗不动，以为界面卡死。禁用至少是诚实的外观。
                b.setDisable(true);
                logger.warn("按钮「{}」没有绑定动作，已禁用", spec.text);
            }
            row.getChildren().add(b);
        }
        return row;
    }
}