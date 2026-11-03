package com.videostudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 编排参数 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private int maxRounds = 2;
    private long maxDurationMs = 180_000;
    private int maxContextChars = 24_000;
    private int chunkSeconds = 300;
    private int topKChunks = 3;
    private int maxEvidenceSegments = 8;

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(long maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    public long chunkMillis() {
        return Math.max(30, chunkSeconds) * 1000L;
    }

    public int getChunkSeconds() {
        return chunkSeconds;
    }

    public void setChunkSeconds(int chunkSeconds) {
        this.chunkSeconds = chunkSeconds;
    }

    public int getTopKChunks() {
        return topKChunks;
    }

    public void setTopKChunks(int topKChunks) {
        this.topKChunks = topKChunks;
    }

    public int getMaxEvidenceSegments() {
        return maxEvidenceSegments;
    }

    public void setMaxEvidenceSegments(int maxEvidenceSegments) {
        this.maxEvidenceSegments = maxEvidenceSegments;
    }
}
