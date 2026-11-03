package com.videostudio.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** 轻量 JSON 工具（独立 ObjectMapper，便于在非 Spring 环境中使用） */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** 从可能包含 ```json 围栏的模型输出中提取 JSON 片段 */
    public static String extractJsonBlock(String raw) {
        if (raw == null) {
            return "{}";
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int brace = text.indexOf('{');
        int array = text.indexOf('[');
        int begin = brace < 0 ? array : (array < 0 ? brace : Math.min(brace, array));
        if (begin < 0) {
            return "{}";
        }
        char open = text.charAt(begin);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = begin; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(begin, i + 1);
                }
            }
        }
        return text.substring(begin);
    }
}
