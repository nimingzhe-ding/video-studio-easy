package com.videostudio.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 极简 HTTP JSON / multipart 客户端。
 * <p>只依赖 JDK HttpClient：demo 里没必要为了一个 POST 引入 OkHttp。
 */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Http() {
    }

    public record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public static Response postJson(String url, Map<String, String> headers, String json, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(builder.build(), timeout);
    }

    public static Response json(String method,String url,Map<String,String> headers,String json,Duration timeout) {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(builder.build(),timeout);
    }

    public static Response get(String url, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
        headers.forEach(builder::header);
        return send(builder.build(), timeout);
    }

    /** 以 multipart/form-data 上传一个文件并附若干文本字段 */
    public static Response postFile(String url, Map<String, String> headers, Path file, String fileField,
                                    String filename, Map<String, String> fields, Duration timeout) {
        String boundary = "----videostudio" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                body.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + filename + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(Files.readAllBytes(file));
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败: " + file, e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        headers.forEach(builder::header);
        return send(builder.build(), timeout);
    }

    private static Response send(HttpRequest request, Duration timeout) {
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 请求被中断", e);
        }
    }
}
