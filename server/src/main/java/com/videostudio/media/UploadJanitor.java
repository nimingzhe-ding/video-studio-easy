package com.videostudio.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分片会话的定时清理。
 *
 * <p>用户上传到一半关掉页面是常态，残留的分片会一直占着磁盘。
 * 每小时扫一次，把超过保留时长仍未合并的会话连同分片一起删掉。
 * 会话在数据库里也留痕，所以清理不会影响已入库的媒体。
 */
@Component
public class UploadJanitor {

    private static final Logger log = LoggerFactory.getLogger(UploadJanitor.class);

    private final ChunkedUploadService uploadService;

    public UploadJanitor(ChunkedUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** 每小时第 17 分钟执行，避开启动时刻与其他维护任务的碰撞 */
    @Scheduled(cron = "${app.upload.cleanup-cron:0 17 * * * *}")
    public void purge() {
        try {
            int removed = uploadService.purgeStale();
            if (removed > 0) {
                log.info("清理过期分片会话 {} 个", removed);
            }
        } catch (RuntimeException e) {
            // 清理失败不能影响主流程，下个周期再试
            log.warn("清理过期分片会话失败: {}", e.getMessage());
        }
    }
}
