package com.videostudio.domain;

import java.time.Instant;

/**
 * 任务断点：记录「哪个阶段已经做完了」，让失败重试不必从头再跑。
 * <p>长视频一次分析可能要十几分钟，转写和抽帧占了绝大部分时间与 CPU；
 * 如果因为模型调用超时或进程重启就全部重来，用户体验和机器成本都很糟。
 * <p>这里只存「阶段 + 关键产物路径」，不存中间数据的副本——产物本来就在磁盘/对象存储上，
 * checkpoint 只需要告诉恢复逻辑「去哪里取」。
 *
 * @param jobId       任务 id
 * @param stage       已完成的最后一个阶段
 * @param payloadJson 恢复所需的附加信息（引擎、产物路径、证据条数等）
 * @param attempts    已经尝试过的次数，用于判断是否放弃
 * @param updatedAt   更新时间
 */
public record JobCheckpoint(long jobId, JobStage stage, String payloadJson, int attempts, Instant updatedAt) {
}
