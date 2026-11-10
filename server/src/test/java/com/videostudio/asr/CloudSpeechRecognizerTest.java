package com.videostudio.asr;

import com.sun.net.httpserver.HttpServer;
import com.videostudio.common.ApiException;
import com.videostudio.common.Json;
import com.videostudio.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云端 ASR 的两种请求形态与失败处理。
 *
 * <p>用本地 HttpServer 而不是 Mockito：这里要断言的是「真实发出的 HTTP 请求长什么样」
 * （content-type、payload 结构、data URI 前缀），把 HttpClient mock 掉就等于跳过了要验证的东西。
 */
class CloudSpeechRecognizerTest {

    @TempDir
    Path temp;

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("fixture-key");
        properties.setAudioModel("qwen3-asr-flash");
        return properties;
    }

    private Path audio() throws Exception {
        Path file = temp.resolve("clip.wav");
        Files.writeString(file, "RIFF-fixture-WAVE");
        return file;
    }

    private HttpServer server(String response, int status,
                              AtomicReference<String> request, AtomicReference<String> contentType) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void dashScopeUsesAudioDataUriAndChatResponse() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("{\"choices\":[{\"message\":{\"content\":\" 语音识别内容 \"}}]}",
                200, request, contentType);
        try {
            var result = new CloudSpeechRecognizer(properties(), url(server, "/chat/completions")).transcribe(audio());

            assertEquals("语音识别内容", result.text());
            assertEquals("application/json", contentType.get());
            var payload = Json.tree(request.get());
            assertEquals("qwen3-asr-flash", payload.path("model").asText());
            assertFalse(payload.path("stream").asBoolean());
            var block = payload.path("messages").path(0).path("content").path(0);
            assertEquals("input_audio", block.path("type").asText());
            assertTrue(block.path("input_audio").path("data").asText().startsWith("data:audio/wav;base64,"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void queryStringDoesNotChangeTheSelectedShape() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = server("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                200, request, new AtomicReference<>());
        try {
            new CloudSpeechRecognizer(properties(), url(server, "/chat/completions?api-version=1")).transcribe(audio());
            assertTrue(request.get().contains("input_audio"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void existingMultipartServicesRemainUsable() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("{\"text\":\"识别结果\"}", 200, request, contentType);
        try {
            var properties = properties();
            properties.setAudioModel("compatible-asr");
            var result = new CloudSpeechRecognizer(properties, url(server, "/audio/transcriptions")).transcribe(audio());

            assertEquals("识别结果", result.text());
            assertTrue(contentType.get().startsWith("multipart/form-data"));
            assertTrue(request.get().contains("name=\"file\""));
            assertTrue(request.get().contains("compatible-asr"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectedRequestDoesNotLeakResponseBody() throws Exception {
        HttpServer server = server("api_key=do-not-expose-response", 401,
                new AtomicReference<>(), new AtomicReference<>());
        try {
            var error = assertThrows(ApiException.class,
                    () -> new CloudSpeechRecognizer(properties(), url(server, "/chat/completions")).transcribe(audio()));
            assertEquals(400, error.status());
            assertFalse(error.getMessage().contains("do-not-expose-response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorIsReportedAsBadGateway() throws Exception {
        HttpServer server = server("upstream unavailable", 503,
                new AtomicReference<>(), new AtomicReference<>());
        try {
            var error = assertThrows(ApiException.class,
                    () -> new CloudSpeechRecognizer(properties(), url(server, "/chat/completions")).transcribe(audio()));
            assertEquals(502, error.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void wrongResponseSchemaIsNotSilentlyTreatedAsSilence() throws Exception {
        // 端点配错时返回的是别的结构；当成空文本会让整份报告悄悄丢掉语音证据
        HttpServer server = server("{\"text\":\"incorrect chat schema\"}", 200,
                new AtomicReference<>(), new AtomicReference<>());
        try {
            var error = assertThrows(ApiException.class,
                    () -> new CloudSpeechRecognizer(properties(), url(server, "/chat/completions")).transcribe(audio()));
            assertEquals(502, error.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyAudioCannotMakeABillableRequest() throws Exception {
        Path file = temp.resolve("empty.wav");
        Files.createFile(file);
        var recognizer = new CloudSpeechRecognizer(properties(), "http://127.0.0.1:1/chat/completions");

        assertEquals(413, assertThrows(ApiException.class, () -> recognizer.transcribe(file)).status());
    }
}
