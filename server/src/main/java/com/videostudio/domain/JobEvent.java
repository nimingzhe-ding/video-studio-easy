package com.videostudio.domain;

import java.time.Instant;

/** SSE 事件：一次任务执行过程中的结构化快照 */
public record JobEvent(
        long seq,
        JobStage stage,
        String type,
        String level,
        int progress,
        String message,
        String payloadJson,
        Instant createdAt
) {
    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_LOG = "log";
    public static final String TYPE_METRIC = "metric";
    public static final String TYPE_WARNING = "warning";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_DONE = "done";
}
