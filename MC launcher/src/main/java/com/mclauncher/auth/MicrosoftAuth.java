package com.mclauncher.auth;


import com.mclauncher.i18n.L;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclauncher.network.HttpClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.*;

/** 微软 OAuth 2.0 登录 */
public class MicrosoftAuth {

    private static final Logger logger = LoggerFactory.getLogger(MicrosoftAuth.class);

    // 微软 OAuth 端点（标准端点，不要修改）
    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_LIVE_AUTH_URL =
            "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    // 作用域
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final HttpClientUtils httpClient;
    private final Gson gson;

    // 用户提供的 Azure Client ID
    private String clientId;

    public MicrosoftAuth() {
        this.httpClient = new HttpClientUtils();
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls()
                .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    /**
     * 设置 Azure Client ID
     * 用户需要在 Azure Portal 注册应用后填写
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * 步骤 1：请求设备代码
     * 返回设备代码和用户验证 URL
     */
    public DeviceCodeResponse requestDeviceCode() throws IOException {
        if (clientId == null || clientId.isEmpty()) {
            throw new IllegalStateException("未配置 Azure Client ID，请在设置中填写");
        }

        Map<String, String> formData = new HashMap<>();
        formData.put("client_id", clientId);
        formData.put("scope", SCOPE);

        String response = httpClient.postForm(DEVICE_CODE_URL, formData);
        DeviceCodeResponse deviceCode = gson.fromJson(response, DeviceCodeResponse.class);

        logger.info("设备代码请求成功，请在 {} 输入代码: {}",
                deviceCode.getVerificationUri(), deviceCode.getUserCode());

        return deviceCode;
    }

    /** 步骤 2：轮询等待用户完成授权 */
    public MicrosoftTokenResponse pollForToken(DeviceCodeResponse deviceCode, StatusCallback callback)
            throws IOException, InterruptedException {

        int interval = deviceCode.getInterval() > 0 ? deviceCode.getInterval() : 5;
        long expiresAt = System.currentTimeMillis() + deviceCode.getExpiresIn() * 1000L;

        if (callback != null) {
            callback.onWaiting(deviceCode.getUserCode(), deviceCode.getVerificationUri());
        }

        while (System.currentTimeMillis() < expiresAt) {
            Thread.sleep(interval * 1000L);

            Map<String, String> formData = new HashMap<>();
            formData.put("client_id", clientId);
            formData.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            formData.put("device_code", deviceCode.getDeviceCode());

            try {
                String response = httpClient.postForm(TOKEN_URL, formData);
                MicrosoftTokenResponse token = gson.fromJson(response, MicrosoftTokenResponse.class);

                if (token.getAccessToken() != null) {
                    logger.info("微软 Token 获取成功");
                    if (callback != null) {
                        callback.onSuccess();
                    }
                    return token;
                }
            } catch (IOException e) {
                // 优先解析响应体里的 JSON error 字段；解析不出来再退回字符串匹配。
                // 注意：HttpClientUtils.postForm 已把响应体拼进异常消息，
                // 否则 HTTP 400 的 authorization_pending 会被当成致命错误。
                String errorCode = extractOAuthError(e.getMessage());

                if ("authorization_pending".equals(errorCode)) {
                    // 用户还未完成授权，继续轮询
                    if (callback != null) {
                        callback.onPending();
                    }
                    continue;
                } else if ("slow_down".equals(errorCode)) {
                    // 需要降低轮询频率（微软要求至少再加 5 秒）
                    interval += 5;
                    continue;
                } else if ("expired_token".equals(errorCode)) {
                    throw new IOException("设备代码已过期，请重新开始登录");
                } else if ("authorization_declined".equals(errorCode)) {
                    throw new IOException("用户取消了授权");
                } else if ("bad_verification_code".equals(errorCode)) {
                    throw new IOException("设备代码无效，请重新开始登录");
                } else if (isTransientOAuthError(e.getMessage())) {
                    // 429 / 5xx / temporarily_unavailable：服务端暂时不可用，
                    // 以前一律当致命错误中止，用户只能从头再来
                    logger.debug("设备码轮询遇到临时错误，稍后重试: {}", e.getMessage());
                    if (callback != null) {
                        callback.onPending();
                    }
                    continue;
                }
                throw e;
            }
        }

        throw new IOException("登录超时，请重试");
    }

    /**
     * 从 HTTP 错误消息中取出 OAuth 错误码。
     * 优先解析末尾的 JSON 的 error 字段；不是合法 JSON 时再退回关键字匹配。
     */
    static String extractOAuthError(String message) {
        if (message == null) {
            return null;
        }
        JsonObject json = parseTrailingJsonObject(message);
        if (json != null && json.has("error") && json.get("error").isJsonPrimitive()) {
            return json.get("error").getAsString();
        }
        for (String code : new String[]{"authorization_pending", "slow_down",
                "expired_token", "authorization_declined", "bad_verification_code"}) {
            if (message.contains(code)) {
                return code;
            }
        }
        return null;
    }

    /** 找到消息中最后一个 '{' 起、到最后一个 '}' 止的子串并解析为对象；失败返回 null。 */
    private static JsonObject parseTrailingJsonObject(String message) {
        int start = message.lastIndexOf('{');
        int end = message.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            var element = JsonParser.parseString(message.substring(start, end + 1));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 步骤 3：Xbox Live 认证
     */
    public String authenticateXboxLive(String msAccessToken) throws IOException {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msAccessToken);

        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        String response = httpClient.postJson(XBOX_LIVE_AUTH_URL, body);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        return json.get("Token").getAsString();
    }

    /**
     * 步骤 4：XSTS 授权
     */
    public XstsResponse authorizeXsts(String xblToken) throws IOException {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");

        com.google.gson.JsonArray userTokens = new com.google.gson.JsonArray();
        userTokens.add(xblToken);
        properties.add("UserTokens", userTokens);

        body.add("Properties", properties);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");

        String response;
        try {
            response = httpClient.postJson(XSTS_AUTH_URL, body);
        } catch (IOException e) {
            // XSTS 失败时 401 响应体里带 XErr，不解析的话用户只会看到「401」
            String friendly = describeXstsError(e.getMessage());
            throw new IOException(friendly != null ? friendly : e.getMessage(), e);
        }
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        XstsResponse xsts = new XstsResponse();
        xsts.setToken(json.get("Token").getAsString());
        xsts.setUhs(json.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString());

        return xsts;
    }

    /** 把 XSTS 的 XErr 翻译成用户能看懂的说明 */
    static String describeXstsError(String message) {
        if (message == null) {
            return null;
        }
        int i = message.indexOf("XErr");
        if (i < 0) {
            return null;
        }
        String tail = message.substring(i);
        StringBuilder digits = new StringBuilder();
        for (int k = 0; k < tail.length(); k++) {
            char c = tail.charAt(k);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        switch (digits.toString()) {
            case "2148916233":
                return "该微软账号还没有 Xbox 档案。请先用这个账号登录一次 https://www.xbox.com 创建档案后再试";
            case "2148916235":
                return "该账号所在地区不支持 Xbox Live / Minecraft，无法登录";
            case "2148916236":
            case "2148916237":
                return "该账号需要先完成年龄验证（家长控制），请到 https://account.xbox.com 处理";
            case "2148916238":
                return "该账号是未成年人账号，必须加入家庭组后才能使用";
            case "2148916227":
                return "该账号已被 Xbox Live 封禁";
            default:
                return null;
        }
    }

    /**
     * 步骤 5：Minecraft 认证
     */
    public String authenticateMinecraft(String xstsToken, String uhs) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

        String response = httpClient.postJson(MC_AUTH_URL, body);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        return json.get("access_token").getAsString();
    }

    /**
     * 步骤 6：获取 Minecraft 档案
     */
    public MinecraftProfile getMinecraftProfile(String mcAccessToken) throws IOException {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(MC_PROFILE_URL)
                .addHeader("Authorization", "Bearer " + mcAccessToken)
                .get()
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    throw new IOException("该账号没有购买 Minecraft");
                }
                throw new IOException("获取档案失败: " + response.code() + " - " + body);
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            MinecraftProfile profile = new MinecraftProfile();
            profile.setId(json.get("id").getAsString());
            profile.setName(json.get("name").getAsString());

            logger.info("获取 Minecraft 档案成功: {}", profile.getName());
            return profile;
        }
    }

    /**
     * 完整的登录流程
     */
    public Account login(DeviceCodeResponse deviceCode, StatusCallback callback)
            throws IOException, InterruptedException {

        if (callback != null) {
            callback.onStatus(L.t("s_f1655cf620"));
        }

        // 1. 获取微软 Token
        MicrosoftTokenResponse msToken = pollForToken(deviceCode, callback);

        if (callback != null) {
            callback.onStatus(L.t("s_52bce24cd2"));
        }

        // 2. Xbox Live 认证
        String xblToken = authenticateXboxLive(msToken.getAccessToken());

        if (callback != null) {
            callback.onStatus(L.t("s_047270341d"));
        }

        // 3. XSTS 授权
        XstsResponse xsts = authorizeXsts(xblToken);

        if (callback != null) {
            callback.onStatus(L.t("s_694878b092"));
        }

        // 4. Minecraft 认证
        String mcAccessToken = authenticateMinecraft(xsts.getToken(), xsts.getUhs());

        if (callback != null) {
            callback.onStatus(L.t("s_135a2a3789"));
        }

        // 5. 获取玩家档案
        MinecraftProfile profile = getMinecraftProfile(mcAccessToken);

        // 6. 创建账号对象
        Account account = Account.createMicrosoft(
                profile.getName(),
                profile.getId(),
                mcAccessToken,
                msToken.getRefreshToken(),
                System.currentTimeMillis() + 24 * 60 * 60 * 1000L  // MC Token 有效期约 24 小时
        );
        account.setClientId(clientId);

        // 账号过期后由 AccountManager 用它自动刷新（见 refreshAccessToken）
        account.setRefresher(a -> {
            try {
                return refresh(a);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        if (callback != null) {
            callback.onStatus(L.t("s_943dc1528f") + profile.getName());
        }

        logger.info("微软登录完成: {}", profile.getName());
        return account;
    }

    /**
     * 用 refresh_token 换新的 Minecraft access token（刷新整条 Xbox 链路）
     *
     * 返回新的 Account 字段，调用方负责加锁并落盘。
     */
    public Account refresh(Account old) throws IOException {
        String rt = old.getRefreshToken();
        String cid = old.getClientId() != null && !old.getClientId().isBlank() ? old.getClientId() : clientId;
        if (rt == null || rt.isBlank() || cid == null || cid.isBlank()) {
            throw new IOException("账号缺少 refresh_token 或 client_id，需要重新登录");
        }
        this.clientId = cid;

        Map<String, String> form = new HashMap<>();
        form.put("client_id", cid);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", rt);
        form.put("scope", SCOPE);

        MicrosoftTokenResponse msToken;
        try {
            String resp = httpClient.postForm(TOKEN_URL, form);
            msToken = gson.fromJson(resp, MicrosoftTokenResponse.class);
        } catch (IOException e) {
            String code = extractOAuthError(e.getMessage());
            if ("invalid_grant".equals(code)) {
                throw new IOException("登录已失效（refresh_token 过期或被撤销），请重新登录");
            }
            throw e;
        }
        if (msToken == null || msToken.getAccessToken() == null) {
            throw new IOException("刷新微软令牌失败：响应中没有 access_token");
        }

        String xbl = authenticateXboxLive(msToken.getAccessToken());
        XstsResponse xsts = authorizeXsts(xbl);
        String mcToken = authenticateMinecraft(xsts.getToken(), xsts.getUhs());

        Account fresh = Account.createMicrosoft(old.getUsername(), old.getUuid(),
                mcToken,
                msToken.getRefreshToken() != null ? msToken.getRefreshToken() : rt,
                System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        fresh.setId(old.getId());
        fresh.setClientId(cid);
        fresh.setSkinSite(old.getSkinSite());
        fresh.setXuid(old.getXuid());
        logger.info("已刷新微软账号令牌: {}", old.getUsername());
        return fresh;
    }

    /** 设备码轮询里可以重试的错误（429 / 5xx / 明确声明 temporarily_unavailable） */
    static boolean isTransientOAuthError(String message) {
        if (message == null) {
            return false;
        }
        String code = extractOAuthError(message);
        if ("temporarily_unavailable".equals(code) || "server_error".equals(code)) {
            return true;
        }
        // HttpClientUtils 把状态码拼在消息里：「表单提交失败: 429 - <url> ...」
        Matcher m = HTTP_STATUS.matcher(message);
        if (m.find()) {
            int status = Integer.parseInt(m.group(1));
            return status == 429 || status >= 500;
        }
        return false;
    }

    /** 从 "表单提交失败: 429 - url" / "POST 请求失败: 503 - url" 里取状态码 */
    private static final Pattern HTTP_STATUS = Pattern.compile("失败: (\\d{3})");

    // ==================== 数据模型 ====================

    /**
     * 设备代码响应
     */
    public static class DeviceCodeResponse {
        private String device_code;
        private String user_code;
        private String verification_uri;
        private int expires_in;
        private int interval;
        private String message;

        public String getDeviceCode() { return device_code; }
        public String getUserCode() { return user_code; }
        public String getVerificationUri() { return verification_uri; }
        public int getExpiresIn() { return expires_in; }
        public int getInterval() { return interval; }
        public String getMessage() { return message; }
    }

    /**
     * 微软 Token 响应
     */
    public static class MicrosoftTokenResponse {
        private String token_type;
        private String scope;
        private int expires_in;
        private String access_token;
        private String refresh_token;

        public String getTokenType() { return token_type; }
        public String getScope() { return scope; }
        public int getExpiresIn() { return expires_in; }
        public String getAccessToken() { return access_token; }
        public String getRefreshToken() { return refresh_token; }
    }

    /**
     * XSTS 响应
     */
    public static class XstsResponse {
        private String token;
        private String uhs;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getUhs() { return uhs; }
        public void setUhs(String uhs) { this.uhs = uhs; }
    }

    /**
     * Minecraft 玩家档案
     */
    public static class MinecraftProfile {
        private String id;
        private String name;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    /**
     * 状态回调接口
     */
    public interface StatusCallback {
        void onWaiting(String userCode, String verificationUri);
        void onPending();
        void onSuccess();
        void onStatus(String status);
    }
}