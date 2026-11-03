package com.videostudio.domain;

import java.time.Instant;

/** 对话消息（追问） */
public record ChatMessage(
        long id,
        long jobId,
        String role,
        String content,
        String citationsJson,
        Instant createdAt
) {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
}
