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
import java.util.concurrent.*;

public class MicrosoftAuth {

    private static final Logger logger = LoggerFactory.getLogger(MicrosoftAuth.class);

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

    private static final String SCOPE = "XboxLive.signin offline_access";

    private final HttpClientUtils httpClient;
    private final Gson gson;

    private String clientId;

    public MicrosoftAuth() {
        this.httpClient = new HttpClientUtils();
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls()
                .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientId() {
        return clientId;
    }

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

                

                String errorCode = extractOAuthError(e.getMessage());

                if ("authorization_pending".equals(errorCode)) {

                    if (callback != null) {
                        callback.onPending();
                    }
                    continue;
                } else if ("slow_down".equals(errorCode)) {

                    interval += 5;
                    continue;
                } else if ("expired_token".equals(errorCode)) {
                    throw new IOException("设备代码已过期，请重新开始登录");
                } else if ("authorization_declined".equals(errorCode)) {
                    throw new IOException("用户取消了授权");
                } else if ("bad_verification_code".equals(errorCode)) {
                    throw new IOException("设备代码无效，请重新开始登录");
                }
                throw e;
            }
        }

        throw new IOException("登录超时，请重试");
    }

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

        String response = httpClient.postJson(XSTS_AUTH_URL, body);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        XstsResponse xsts = new XstsResponse();
        xsts.setToken(json.get("Token").getAsString());
        xsts.setUhs(json.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString());

        return xsts;
    }

    public String authenticateMinecraft(String xstsToken, String uhs) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

        String response = httpClient.postJson(MC_AUTH_URL, body);
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();

        return json.get("access_token").getAsString();
    }

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

    public Account login(DeviceCodeResponse deviceCode, StatusCallback callback)
            throws IOException, InterruptedException {

        if (callback != null) {
            callback.onStatus(L.t("s_f1655cf620"));
        }

        MicrosoftTokenResponse msToken = pollForToken(deviceCode, callback);

        if (callback != null) {
            callback.onStatus(L.t("s_52bce24cd2"));
        }

        String xblToken = authenticateXboxLive(msToken.getAccessToken());

        if (callback != null) {
            callback.onStatus(L.t("s_047270341d"));
        }

        XstsResponse xsts = authorizeXsts(xblToken);

        if (callback != null) {
            callback.onStatus(L.t("s_694878b092"));
        }

        String mcAccessToken = authenticateMinecraft(xsts.getToken(), xsts.getUhs());

        if (callback != null) {
            callback.onStatus(L.t("s_135a2a3789"));
        }

        MinecraftProfile profile = getMinecraftProfile(mcAccessToken);

        Account account = Account.createMicrosoft(
                profile.getName(),
                profile.getId(),
                mcAccessToken,
                msToken.getRefreshToken(),
                System.currentTimeMillis() + 24 * 60 * 60 * 1000L  
        );
        account.setClientId(clientId);

        if (callback != null) {
            callback.onStatus(L.t("s_943dc1528f") + profile.getName());
        }

        logger.info("微软登录完成: {}", profile.getName());
        return account;
    }

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

    public static class XstsResponse {
        private String token;
        private String uhs;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getUhs() { return uhs; }
        public void setUhs(String uhs) { this.uhs = uhs; }
    }

    public static class MinecraftProfile {
        private String id;
        private String name;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public interface StatusCallback {
        void onWaiting(String userCode, String verificationUri);
        void onPending();
        void onSuccess();
        void onStatus(String status);
    }
}
