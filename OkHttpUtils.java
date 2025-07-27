package org.dromara.recognize.utils;

import okhttp3.*;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class OkHttpUtils {


    public static OkHttpClient createSimpleOkHttpClient(int callTimeout) {
        return new OkHttpClient.Builder()
                .callTimeout(callTimeout, TimeUnit.SECONDS).build();
    }


    public static Response execute(Request request, OkHttpClient client) throws IOException {
        return client.newCall(request).execute();
    }


    /**
     * GET 请求（支持自定义 OkHttpClient）
     */
    public static String get(String url, OkHttpClient client) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return Objects.requireNonNull(response.body()).string();
        }
    }

    /**
     * POST 请求（JSON 格式，使用默认的 OkHttpClient）
     */
    public static String postJson(String url, String json) throws IOException {
        return postJson(url, json, new OkHttpClient());
    }

    /**
     * POST 请求（JSON 格式，支持自定义 OkHttpClient）
     */
    public static String postJson(String url, String json, OkHttpClient client) throws IOException {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return Objects.requireNonNull(response.body()).string();
        }
    }


    /**
     * POST 请求（表单格式，支持自定义 OkHttpClient）
     */
    public static String postForm(String url, Map<String, String> formData, OkHttpClient client) throws IOException {
        FormBody.Builder builder = new FormBody.Builder();
        formData.forEach(builder::add);
        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            return Objects.requireNonNull(response.body()).string();
        }
    }

}
