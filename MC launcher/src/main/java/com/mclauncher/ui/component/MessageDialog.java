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

public final class MessageDialog {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(MessageDialog.class);

    private static final double MESSAGE_MAX_WIDTH = 460;
    private static final double PROMPT_WIDTH = 420;

    private static final double MESSAGE_MIN_WIDTH = 300;

    private MessageDialog() {}

    public static void info(ModalHost host, String title, String message) {
        showSimple(host, title, message, Kind.INFO, null);
    }

    public static void error(ModalHost host, String title, String message) {
        showSimple(host, title, message, Kind.ERROR, null);
    }

    public static void warn(ModalHost host, String title, String message) {
        showSimple(host, title, message, Kind.WARN, null);
    }

    public static void info(ModalHost host, String title, String message, Runnable onClosed) {
        showSimple(host, title, message, Kind.INFO, onClosed);
    }

    public static void error(ModalHost host, String title, String message, Runnable onClosed) {
        showSimple(host, title, message, Kind.ERROR, onClosed);
    }

    public static void confirm(ModalHost host, String title, String header,
                               String message, Runnable onConfirm) {
        confirm(host, title, header, message, null, L.t("s_f526c89937"), onConfirm);
    }

    public static void confirm(ModalHost host, String title, String header, String message,
                               String detail, String okText, Runnable onConfirm) {
        if (host == null) {
            return;
        }

        VBox content = buildBody(message, detail, Kind.QUESTION, header);

        HBox buttons = buildButtonRow(

                

                new ButtonSpec(L.t("s_4d0b4688c7"), "button", () -> host.close()),
                new ButtonSpec(okText == null ? L.t("s_f526c89937") : okText, "button-primary", onConfirm));

        content.getChildren().add(buttons);

        host.show(title, content, null);
    }

    public static void confirmOrCancel(ModalHost host, String title, String header,
                                       String message, String okText, String cancelText,
                                       Runnable onConfirm, Runnable onCancel) {
        if (host == null) {
            return;
        }

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
        field.setOnAction(e -> submit.run());   

        content.getChildren().add(buttons);

        host.show(title, content, null);

        javafx.application.Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
    }

    public static void infoWithLink(ModalHost host, String title, String message,
                                    String linkText, String linkUrl) {
        if (host == null) {
            return;
        }

        VBox content = buildBody(message, null, Kind.INFO, null);

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

    private static VBox buildBody(String message, String detail, Kind kind, String header) {
        VBox body = new VBox(8);
        body.setPadding(new Insets(16, 18, 14, 18));

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

        Region bar = new Region();
        bar.setMinWidth(4);
        bar.setPrefWidth(4);
        bar.setMaxWidth(4);
        bar.getStyleClass().addAll("dialog-accent", accentClass(kind));

        HBox wrap = new HBox(0, bar, body);
        HBox.setHgrow(body, Priority.ALWAYS);

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

                

                

                b.setDisable(true);
                logger.warn("按钮「{}」没有绑定动作，已禁用", spec.text);
            }
            row.getChildren().add(b);
        }
        return row;
    }
}
