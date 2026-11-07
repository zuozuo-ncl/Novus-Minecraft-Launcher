package com.mclauncher.ui.window;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.i18n.L;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「意见反馈」弹窗。
 * <h3>为什么不在客户端发邮件</h3>
 */
public final class FeedbackDialog {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackDialog.class);

    /** 反馈接口所在服务器：与版本接口同一组，按顺序尝试 */
    private static final String[] API_BASES = {
            "https://version-interface.us.ci",
            "https://api.rxfmprc89.nyat.app:57294",
    };

    /** 与服务端一致的篇幅上限（按码点计） */
    private static final int MAX_CONTENT = 2000;

    private static final Gson GSON = new Gson();

    private final com.mclauncher.ui.component.ModalHost modalHost;

    public FeedbackDialog(com.mclauncher.ui.component.ModalHost modalHost) {
        this.modalHost = modalHost;
    }

    /** 弹出反馈窗口 */
    public void show() {
        if (modalHost == null) {
            logger.warn("没有可用的弹窗宿主，无法打开意见反馈");
            return;
        }

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 16, 24));
        root.setMinWidth(460);
        root.setMaxWidth(600);

        // ---- 类型 ----
        Label typeLabel = new Label(L.t("feedback.type"));
        typeLabel.getStyleClass().add("card-title");

        ComboBox<TypeItem> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(
                new TypeItem("bug", L.t("feedback.type_bug")),
                new TypeItem("suggestion", L.t("feedback.type_suggestion")),
                new TypeItem("other", L.t("feedback.type_other")));
        typeCombo.getSelectionModel().select(0);
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(TypeItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label);
            }
        });
        typeCombo.setButtonCell(typeCombo.getCellFactory().call(null));

        // ---- 详细描述 ----
        Label contentLabel = new Label(L.t("feedback.content"));
        contentLabel.getStyleClass().add("card-title");

        TextArea contentArea = new TextArea();
        contentArea.setPromptText(L.t("feedback.content_hint"));
        contentArea.setWrapText(true);
        contentArea.setPrefRowCount(8);
        contentArea.setMaxWidth(Double.MAX_VALUE);

        Label counter = new Label();
        counter.getStyleClass().add("text-muted");
        updateCounter(counter, contentArea.getText());

        contentArea.textProperty().addListener((obs, oldText, newText) -> {
            String s = newText == null ? "" : newText;
            int len = codePoints(s);
            // 超过上限时不截断用户输入（会丢内容），只提示并禁用提交
            updateCounter(counter, s);
            if (len > MAX_CONTENT) {
                counter.getStyleClass().setAll("status-fail");
            }
        });

        // ---- 联系方式（选填）----
        Label contactLabel = new Label(L.t("feedback.contact"));
        contactLabel.getStyleClass().add("card-title");

        TextField contactField = new TextField();
        contactField.setPromptText(L.t("feedback.contact_hint"));
        contactField.setMaxWidth(Double.MAX_VALUE);

        // ---- 底部：状态 + 按钮 ----
        Label status = new Label();
        status.getStyleClass().add("text-muted");
        status.setWrapText(true);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(20, 20);
        spinner.setMinSize(20, 20);
        spinner.setVisible(false);
        spinner.setManaged(false);

        Button cancelBtn = new Button(L.t("feedback.cancel"));
        cancelBtn.getStyleClass().add("button");

        Button submitBtn = new Button(L.t("feedback.submit"));
        submitBtn.getStyleClass().add("button-primary");

        HBox footer = new HBox(10, spinner, status, cancelBtn, submitBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(status, Priority.ALWAYS);
        status.setMaxWidth(Double.MAX_VALUE);

        root.getChildren().addAll(
                typeLabel, typeCombo,
                contentLabel, contentArea, counter,
                contactLabel, contactField,
                new Separator(),
                footer);

        // ---- 提交 ----
        // 按「传入的 busy」设置控件状态；以前是从 spinner 可见性反推，
        // 顺序一乱（先隐藏 spinner 再 setBusy）就会把控件又解锁
        java.util.function.Consumer<Boolean> setBusy = busy -> {
            submitBtn.setDisable(busy);
            cancelBtn.setDisable(busy);
            typeCombo.setDisable(busy);
            contentArea.setDisable(busy);
            contactField.setDisable(busy);
        };

        submitBtn.setOnAction(e -> {
            String content = contentArea.getText() == null ? "" : contentArea.getText().trim();
            TypeItem type = typeCombo.getSelectionModel().getSelectedItem();

            if (content.isEmpty()) {
                status.setText(L.t("feedback.err_empty"));
                status.getStyleClass().setAll("status-fail");
                contentArea.requestFocus();
                return;
            }
            if (codePoints(content) > MAX_CONTENT) {
                status.setText(L.t("feedback.err_too_long", MAX_CONTENT));
                status.getStyleClass().setAll("status-fail");
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type == null ? "other" : type.code);
            payload.put("content", content);
            payload.put("contact", contactField.getText() == null
                    ? "" : contactField.getText().trim());
            payload.put("systemInfo", collectSystemInfo());
            String json = GSON.toJson(payload);

            spinner.setVisible(true);
            spinner.setManaged(true);
            status.getStyleClass().setAll("text-muted");
            status.setText(L.t("feedback.submitting"));
            setBusy.accept(true);

            new Thread(() -> {
                try {
                    String message = postFeedback(json);
                    Platform.runLater(() -> {
                        // 成功后直接关掉面板，用消息框确认 —— 不必让用户再点一次关闭
                        modalHost.close();
                        com.mclauncher.ui.component.MessageDialog.info(
                                modalHost, L.t("feedback.done_title"), message);
                    });
                } catch (Exception ex) {
                    logger.warn("提交反馈失败: {}", ex.getMessage());
                    Platform.runLater(() -> {
                        spinner.setVisible(false);
                        spinner.setManaged(false);
                        setBusy.accept(false);
                        status.getStyleClass().setAll("status-fail");
                        status.setText(L.t("feedback.err_failed")
                                + " " + (ex.getMessage() == null ? "" : ex.getMessage()));
                    });
                }
            }, "feedback-submit").start();
        });

        cancelBtn.setOnAction(e -> modalHost.close());

        // 提交中不允许直接关掉（否则请求还在后台跑，用户却以为取消了）
        modalHost.show(L.t("feedback.title"), root, null, true);
    }

    // ---------------- 文案辅助 ----------------

    private static void updateCounter(Label counter, String text) {
        int len = codePoints(text == null ? "" : text);
        counter.setText(L.t("feedback.counter") + " " + len + " / " + MAX_CONTENT);
        if (len > MAX_CONTENT) {
            counter.getStyleClass().setAll("status-fail");
        } else {
            counter.getStyleClass().setAll("text-muted");
        }
    }

    private static int codePoints(String s) {
        return Character.codePointCount(s, 0, s.length());
    }

    /** 下拉框条目：显示名 + 发给服务端的类型码 */
    private static final class TypeItem {
        final String code;
        final String label;

        TypeItem(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ---------------- 网络 ----------------

    /** 提交反馈，返回给用户看的成功/失败说明。 */
    private static String postFeedback(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        IOException last = null;

        for (String base : API_BASES) {
            HttpURLConnection conn = null;
            try {
                URL url = URI.create(base + "/api/feedback").toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                String resp = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

                if (code == 200) {
                    String id = "";
                    try {
                        JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
                        if (o.has("data") && o.get("data").isJsonObject()) {
                            JsonObject d = o.getAsJsonObject("data");
                            if (d.has("id")) {
                                id = d.get("id").getAsString();
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // 没有编号也照常提示成功
                    }
                    return id.isEmpty() ? L.t("feedback.ok")
                            : L.t("feedback.ok") + " " + L.t("feedback.ok_id", id);
                }

                String serverMsg = jsonMessage(resp);
                if (code == 429) {
                    throw new IOException(serverMsg.isEmpty() ? L.t("feedback.err_rate") : serverMsg);
                }
                if (code == 502) {
                    // 服务端已落本地日志，只是邮件发送失败
                    return serverMsg.isEmpty() ? L.t("feedback.ok_recorded") : serverMsg;
                }
                throw new IOException(serverMsg.isEmpty()
                        ? L.t("feedback.err_http", code) : serverMsg);
            } catch (IOException e) {
                last = e;
                logger.debug("反馈接口 {} 失败: {}", base, e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        // 注意：4xx 是服务端明确拒绝（内容问题），换地址也没用，直接抛给用户；
        // 只有网络层失败才值得继续尝试下一个地址。这里无法区分得太细，
        // 但服务端返回的业务错误文案会被原样展示。
        throw last != null ? last : new IOException(L.t("feedback.err_network"));
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String jsonMessage(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("error") && o.get("error").isJsonPrimitive()) {
                return o.get("error").getAsString();
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON
        }
        return json.length() > 200 ? json.substring(0, 200) : json;
    }

    // ---------------- 系统信息 ----------------

    /**
     * 自动收集的系统环境。
     *
     * 只放能帮助定位问题的客观信息，不包含用户名、路径、账号等隐私数据。
     */
    private static Map<String, String> collectSystemInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        put(info, "os", "os.name");
        put(info, "osVersion", "os.version");
        put(info, "arch", "os.arch");
        put(info, "java", "java.version");
        put(info, "javaVendor", "java.vendor");
        try {
            info.put("launcherVersion",
                    com.mclauncher.config.ConfigManager.staticLauncherVersion());
        } catch (Exception ignored) {
            // 版本号拿不到不影响反馈
        }
        return info;
    }

    private static void put(Map<String, String> m, String key, String property) {
        try {
            String v = System.getProperty(property, "");
            if (v != null && !v.isBlank()) {
                m.put(key, v);
            }
        } catch (Exception ignored) {
            // 某些受限环境里 getProperty 可能抛 SecurityException
        }
    }
}