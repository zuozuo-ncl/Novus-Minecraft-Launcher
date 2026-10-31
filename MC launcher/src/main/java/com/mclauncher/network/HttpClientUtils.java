package com.mclauncher.network;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;

public class HttpClientUtils {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientUtils.class);

    private static final int TIMEOUT = NetworkConstants.API_TIMEOUT_MS;

    private static final String USER_AGENT = NetworkConstants.USER_AGENT;

    private final OkHttpClient client;
    private final Gson gson;

    public HttpClientUtils() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)

                
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    return chain.proceed(original.newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "*/*")
                            .build());
                })
                .build();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("GET 请求失败: " + response.code() + " - " + url);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    public String postJson(String url, Object body) throws IOException {
        String json = gson.toJson(body);
        RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST 请求失败: " + response.code() + " - " + url);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    public String postForm(String url, Map<String, String> formData) throws IOException {
        FormBody.Builder builder = new FormBody.Builder();
        formData.forEach(builder::add);

        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {

                

                throw new IOException("表单提交失败: " + response.code() + " - " + url
                        + (body.isEmpty() ? "" : " - " + body));
            }
            return body;
        }
    }

    public <T> T getJson(String url, Class<T> clazz) throws IOException {
        String json = get(url);
        return gson.fromJson(json, clazz);
    }

    public <T> T getJson(String url, Type type) throws IOException {
        String json = get(url);
        return gson.fromJson(json, type);
    }

    public <T> List<T> getJsonArray(String url, Class<T> elementClass) throws IOException {
        String json = get(url);
        Type listType = new TypeToken<List<T>>() {}.getType();
        return gson.fromJson(json, listType);
    }

    public void download(String url, Path destination, ProgressListener listener) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute();
             InputStream is = response.body().byteStream();
             OutputStream os = Files.exists(destination)
                     ? Files.newOutputStream(destination, StandardOpenOption.APPEND)
                     : Files.newOutputStream(destination)) {

            long totalSize = response.header("Content-Length") != null
                    ? Long.parseLong(response.header("Content-Length")) : -1;
            long downloaded = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                if (listener != null && totalSize > 0) {
                    listener.onProgress(downloaded, totalSize);
                }
            }
        }
    }

    public boolean isAccessible(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    public byte[] downloadBytes(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载失败: " + response.code());
            }
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    public Map<String, String> getHeaders(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .head()
                .build();

        Map<String, String> headers = new HashMap<>();
        try (Response response = client.newCall(request).execute()) {
            for (String name : response.headers().names()) {
                headers.put(name, response.header(name));
            }
        }
        return headers;
    }
}
