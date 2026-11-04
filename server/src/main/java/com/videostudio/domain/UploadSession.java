package com.videostudio.domain;

import java.time.Instant;

/**
 * 分片上传会话。
 *
 * <p>大文件一次性 multipart 上传有两个硬伤：一是网关/浏览器超时，二是断了就整段重来。
 * 这里把文件切成固定大小的分片，每片单独落盘，服务端只记录「哪些片已经收到」，
 * 因此刷新页面、换网络、甚至换浏览器都能从断点继续。
 *
 * @param uploadId     会话标识（客户端生成或服务端生成，建议用 SHA-256 前 32 位）
 * @param name         原始文件名，用于推断类型与最终落盘的扩展名
 * @param totalBytes   文件总字节数，合并时用来校验完整性
 * @param totalChunks  分片总数，由 totalBytes 与 chunkSize 推算，客户端也要传，用于交叉校验
 * @param chunkSize    分片大小（最后一片可能更小）
 * @param received     已收到的分片数
 * @param assetId      合并后产出的媒体 id；为空表示尚未合并
 * @param createdAt    创建时间，用于清理超时的残留会话
 * @param updatedAt    最近一次写入时间
 */
public record UploadSession(String uploadId, String name, long totalBytes, int totalChunks, int chunkSize,
                            int received, Long assetId, Instant createdAt, Instant updatedAt) {

    /** 是否已经合并完成 */
    public boolean finished() {
        return assetId != null;
    }

    /** 是否全部收到（可以合并） */
    public boolean complete() {
        return received >= totalChunks;
    }
}
