package com.videostudio.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostudio.common.ApiException;
import com.videostudio.common.Http;
import com.videostudio.common.Json;
import com.videostudio.common.Secrets;
import com.videostudio.common.Texts;
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

    /**
     * 单个语音切片的上限。60 秒 16k 单声道 wav 约 2 MB，base64 后约 2.7 MB；
     * 10 MB 足够覆盖配置失误（比如切片时长被调成十分钟），又不至于把一次请求撑到网关拒绝。
     */
    private static final long MAX_AUDIO_BYTES = 10L * 1024 * 1024;

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

    /** 目标端点是不是 chat/completions；先去掉查询串再判断，避免参数里出现同名片段 */
    private boolean usesChatCompletions() {
        String path = url.split("\\?", 2)[0];
        return path.endsWith("/chat/completions") || path.endsWith("/chat/completions/");
    }

    private Result viaTranscriptions(Path audio, Map<String, String> headers, Duration timeout) {
        Map<String, String> fields = Map.of("model", ai.getAudioModel());
        Http.Response response = Http.postFile(url, headers, audio, "file",
                audio.getFileName().toString(), fields, timeout);
        if (!response.ok()) {
            throw failure(response);
        }
        JsonNode root = Json.tree(response.body());
        if (root == null || !root.path("text").isTextual()) {
            throw new ApiException(502, "语音识别服务返回结构无效");
        }
        return new Result(root.path("text").asText().strip(), java.util.List.of(), "cloud-asr");
    }

    private Result viaChatCompletions(Path audio, Map<String, String> headers, Duration timeout) {
        String dataUri = "data:" + mimeOf(audio) + ";base64," + Base64.getEncoder().encodeToString(readAudio(audio));
        ObjectNode payload = Json.object();
        payload.put("model", ai.getAudioModel());
        payload.put("stream", false);
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
        if (root != null && root.has("error")) {
            throw new ApiException(502, "语音识别服务返回错误：" + Texts.truncate(Secrets.scrub(
                    root.path("error").toString()), 200));
        }
        // 结构不对时宁可直接失败：把空字符串当成「这段没说话」会静默产出一份没有语音证据的报告
        JsonNode content0 = root == null ? null : root.path("choices").path(0).path("message").path("content");
        if (content0 == null || !content0.isTextual()) {
            throw new ApiException(502, "语音识别服务返回结构无效");
        }
        return new Result(content0.asText().strip(), java.util.List.of(), "cloud-asr");
    }

    private byte[] readAudio(Path audio) {
        try {
            long size = Files.size(audio);
            if (size == 0) {
                throw new ApiException(413, "语音片段为空，无法发起识别");
            }
            if (size > MAX_AUDIO_BYTES) {
                throw new ApiException(413, "语音片段超出 %d MB，请缩短切片时长"
                        .formatted(MAX_AUDIO_BYTES / 1024 / 1024));
            }
            return Files.readAllBytes(audio);
        } catch (IOException e) {
            throw new IllegalStateException("读取音频文件失败: " + audio, e);
        }
    }

    /**
     * 把失败翻译成调用方能处理的类型。响应体只写日志（先脱敏），不进异常消息：
     * 错误响应里可能回显带 key 的 URL。
     */
    private RuntimeException failure(Http.Response response) {
        log.warn("ASR 请求失败 status={} body={}", response.status(),
                Texts.truncate(Secrets.scrub(response.body()), 400));
        if (response.status() == 429 || response.status() >= 500) {
            return new ApiException(502, "语音识别服务暂不可用（HTTP " + response.status() + "），请稍后重试");
        }
        return new ApiException(400, "语音识别请求被拒绝（HTTP " + response.status() + "），请检查模型与端点配置");
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
        if (name.endsWith(".ogg")) {
            return "audio/ogg";
        }
        return "audio/wav";
    }
}
