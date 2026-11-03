package com.videostudio.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostudio.common.Http;
import com.videostudio.common.Json;
import com.videostudio.common.Texts;
import com.videostudio.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容模型网关：Chat Completions + Embeddings + 视觉消息。
 * <p>没有配置 API key 时不会抛异常，而是返回 null 让上层走确定性回退，
 * 这样 demo 在离线环境下仍然可跑通全流程。
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final AiProperties ai;

    public LlmGateway(AiProperties ai) {
        this.ai = ai;
    }

    public boolean enabled() {
        return ai.cloudEnabled();
    }

    public record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    public record ChatResult(String content, int promptTokens, int completionTokens, String model) {
        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /**
     * @return 模型输出；未启用或调用失败时返回 null（调用方负责回退）
     */
    public ChatResult chat(List<Message> messages, double temperature, Integer maxTokens, String purpose) {
        if (!enabled()) {
            return null;
        }
        ObjectNode payload = Json.object();
        payload.put("model", ai.getModel());
        ArrayNode array = payload.putArray("messages");
        for (Message message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }
        payload.put("temperature", temperature);
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }
        String json = Json.write(payload);
        int attempts = Math.max(1, ai.getMaxRetries());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Http.Response response = Http.postJson(ai.chatUrl(), headers(ai.getApiKey()), json,
                        Duration.ofSeconds(Math.max(30, ai.getTimeoutSeconds())));
                if (!response.ok()) {
                    boolean retryable = response.status() == 429 || response.status() >= 500;
                    log.warn("LLM {}({}) HTTP {} attempt={}", purpose, ai.getModel(), response.status(), attempt);
                    if (!retryable || attempt == attempts) {
                        return null;
                    }
                } else {
                    return parse(response.body(), purpose);
                }
            } catch (RuntimeException e) {
                log.warn("LLM {}({}) 调用异常 attempt={}: {}", purpose, ai.getModel(), attempt, e.getMessage());
                if (attempt == attempts) {
                    return null;
                }
            }
            sleep(attempt);
        }
        return null;
    }

    private ChatResult parse(String body, String purpose) {
        JsonNode root = Json.tree(body);
        JsonNode choice = root.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            log.warn("LLM {} 返回空内容", purpose);
            return null;
        }
        JsonNode usage = root.path("usage");
        return new ChatResult(content.strip(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                root.path("model").asText(ai.getModel()));
    }

    /** 带图片的视觉问答（用于可选的画面描述增强） */
    public String vision(String prompt, String imageDataUri, String purpose) {
        if (!ai.visionEnabled()) {
            return null;
        }
        ObjectNode payload = Json.object();
        payload.put("model", ai.getVisionModel());
        ObjectNode message = payload.putArray("messages").addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        imagePart.putObject("image_url").put("url", imageDataUri);
        payload.put("max_tokens", 300);
        try {
            Http.Response response = Http.postJson(ai.chatUrl(), headers(ai.getApiKey()), Json.write(payload),
                    Duration.ofSeconds(Math.max(60, ai.getTimeoutSeconds())));
            if (!response.ok()) {
                log.warn("视觉模型 {} HTTP {}", purpose, response.status());
                return null;
            }
            return Json.tree(response.body()).path("choices").path(0).path("message").path("content").asText("");
        } catch (RuntimeException e) {
            log.warn("视觉模型 {} 调用失败: {}", purpose, e.getMessage());
            return null;
        }
    }

    /** 文本向量；失败返回空列表 */
    public List<Double> embed(String text) {
        return embed(List.of(text)).stream().findFirst().orElse(List.of());
    }

    public List<List<Double>> embed(List<String> texts) {
        if (!ai.embeddingEnabled() || texts.isEmpty()) {
            return List.of();
        }
        ObjectNode payload = Json.object();
        payload.put("model", ai.getEmbeddingModel());
        ArrayNode input = payload.putArray("input");
        texts.forEach(input::add);
        try {
            Http.Response response = Http.postJson(ai.embeddingUrl(),
                    headers(ai.resolvedEmbeddingApiKey()), Json.write(payload),
                    Duration.ofSeconds(Math.max(30, ai.getTimeoutSeconds())));
            if (!response.ok()) {
                log.warn("Embedding HTTP {} body={}", response.status(), Texts.truncate(response.body(), 200));
                return List.of();
            }
            JsonNode data = Json.tree(response.body()).path("data");
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : item.path("embedding")) {
                    vector.add(value.asDouble());
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (RuntimeException e) {
            log.warn("Embedding 调用失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> headers(String apiKey) {
        return Map.of("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.strip()),
                "Accept", "application/json");
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(800L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
