package com.videostudio.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostudio.common.Http;
import com.videostudio.common.Json;
import com.videostudio.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 云端语音识别。支持两种请求形态，按配置的 URL 自动选择：
 *
 * <ol>
 *   <li><b>OpenAI 兼容 /audio/transcriptions</b>：multipart 上传 file + model，
 *       响应取 {@code text}。适用于提供该端点的网关。</li>
 *   <li><b>OpenAI 兼容 /chat/completions</b>：把音频 base64 成 {@code input_audio} 内容块，
 *       响应取 {@code choices[0].message.content}。DashScope 的 qwen3-asr-flash 走这条。</li>
 * </ol>
 *
 * <p>为什么必须两种都支持：DashScope 的 compatible-mode <b>没有</b> /audio/transcriptions
 * （实测 404），语音模型只暴露在 chat 接口上。而项目原先只实现了第一种，
 * 配置成 DashScope 时无论换哪个模型都会失败。
 */
@Component
public class CloudSpeechRecognizer implements SpeechRecognizer {

    private static final Logger log = LoggerFactory.getLogger(CloudSpeechRecognizer.class);

    private final AiProperties ai;
    private final String url;

    public CloudSpeechRecognizer(AiProperties ai, @Value("${ai.asr-url:}") String url) {
        this.ai = ai;
        this.url = url;
    }

    public boolean available() {
        return ai.cloudEnabled() && url != null && !url.isBlank();
    }

    @Override
    public String engine() {
        return "cloud-asr";
    }

    @Override
    public Result transcribe(Path audio) {
        if (!available()) {
            throw new IllegalStateException("云端 ASR 未配置（缺少 ai.asr-url 或 API key）");
        }
        Duration timeout = Duration.ofSeconds(Math.max(60, ai.getTimeoutSeconds()));
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + ai.getApiKey().strip(),
                "Accept", "application/json");
        return usesChatCompletions() ? viaChatCompletions(audio, headers, timeout)
                : viaTranscriptions(audio, headers, timeout);
    }

    /** 目标端点是不是 chat/completions；路径里出现它就走音频内容块那条路 */
    private boolean usesChatCompletions() {
        return url.contains("/chat/completions");
    }

    private Result viaTranscriptions(Path audio, Map<String, String> headers, Duration timeout) {
        Map<String, String> fields = Map.of("model", ai.getAudioModel());
        Http.Response response = Http.postFile(url, headers, audio, "file",
                audio.getFileName().toString(), fields, timeout);
        if (!response.ok()) {
            throw failure(response);
        }
        String text = Json.tree(response.body()).path("text").asText("");
        return new Result(text.strip(), java.util.List.of(), "cloud-asr");
    }

    private Result viaChatCompletions(Path audio, Map<String, String> headers, Duration timeout) {
        String dataUri;
        try {
            byte[] bytes = Files.readAllBytes(audio);
            dataUri = "data:" + mimeOf(audio) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("读取音频文件失败: " + audio, e);
        }
        ObjectNode payload = Json.object();
        payload.put("model", ai.getAudioModel());
        var message = payload.putArray("messages").addObject();
        message.put("role", "user");
        var content = message.putArray("content").addObject();
        content.put("type", "input_audio");
        content.putObject("input_audio").put("data", dataUri);

        Http.Response response = Http.postJson(url, headers, Json.write(payload), timeout);
        if (!response.ok()) {
            throw failure(response);
        }
        JsonNode root = Json.tree(response.body());
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        return new Result(text.strip(), java.util.List.of(), "cloud-asr");
    }

    private IllegalStateException failure(Http.Response response) {
        // 响应体先脱敏：错误里可能回显带 key 的 URL
        return new IllegalStateException("ASR HTTP " + response.status() + ": "
                + com.videostudio.common.Texts.truncate(com.videostudio.common.Secrets.scrub(response.body()), 400));
    }

    private static String mimeOf(Path audio) {
        String name = audio.getFileName().toString().toLowerCase();
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (name.endsWith(".flac")) {
            return "audio/flac";
        }
        return "audio/wav";
    }
}
