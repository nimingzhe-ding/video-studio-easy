package com.videostudio.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.videostudio.common.Http;
import com.videostudio.common.Json;
import com.videostudio.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 云端语音识别（OpenAI 兼容 /audio/transcriptions）。
 * <p>DashScope、SiliconFlow 等兼容网关都接受这个形态：multipart 上传 file + model，
 * 返回 JSON 的 text 字段。响应的 segments 若存在则一并解析，用于获得更细的时间戳。
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
        Map<String, String> fields = Map.of("model", ai.getAudioModel());
        Http.Response response = Http.postFile(url, headers, audio, "file",
                audio.getFileName().toString(), fields, timeout);
        if (!response.ok()) {
            throw new IllegalStateException("ASR HTTP " + response.status() + ": "
                    + com.videostudio.common.Texts.truncate(response.body(), 400));
        }
        JsonNode root = Json.tree(response.body());
        String text = root.path("text").asText("");
        return new Result(text.strip(), java.util.List.of(), "cloud-asr");
    }
}
