package com.videostudio.storage;

import com.videostudio.media.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 把「本地工作副本」同步到对象存储的一层薄封装。
 *
 * <p>只同步三类需要跨副本、跨重启仍然存在的东西：
 * <ul>
 *   <li>源文件——重新上传一次几百 MB 太贵；</li>
 *   <li>保留下来的关键帧——报告里的插图与前端证据卡都指向它们；</li>
 *   <li>Word 报告——下载链接要能命中。</li>
 * </ul>
 * 切片音频、抽帧中间产物、asr 临时文件都留在本地，它们可以从源文件重算，没必要进桶。
 */
@Component
public class MediaObjects {

    private static final Logger log = LoggerFactory.getLogger(MediaObjects.class);

    private final ObjectStore store;
    private final Storage storage;

    public MediaObjects(ObjectStore store, Storage storage) {
        this.store = store;
        this.storage = storage;
    }

    public ObjectStore store() {
        return store;
    }

    public boolean enabled() {
        return store.enabled();
    }

    public String sourceKey(long assetId, String fileName) {
        return "assets/%d/source%s".formatted(assetId, storage.extension(fileName));
    }

    public String frameKey(long assetId, long jobId, String fileName) {
        return "assets/%d/frames/job-%d/%s".formatted(assetId, jobId, fileName);
    }

    public String reportKey(long assetId, long jobId) {
        return "assets/%d/report/report-%d.docx".formatted(assetId, jobId);
    }

    public String assetPrefix(long assetId) {
        return "assets/%d/".formatted(assetId);
    }

    /** 源文件入库后上传一份；失败只记日志，本地文件仍然是可用的 */
    public void putSource(long assetId, String fileName, Path file) {
        if (!store.enabled()) {
            return;
        }
        if (store.put(sourceKey(assetId, fileName), file, ObjectStore.contentTypeOf(fileName))) {
            log.info("对象存储：源文件已上传 asset={} size={}", assetId, ObjectStore.sizeOrZero(file));
        }
    }

    /** 关键帧：只传真正进了证据集的那些，避免把抽帧中间产物全搬上去 */
    public void putFrames(long assetId, long jobId, List<Path> frames) {
        if (!store.enabled() || frames.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Path frame : frames) {
            if (store.put(frameKey(assetId, jobId, frame.getFileName().toString()), frame, "image/jpeg")) {
                ok++;
            }
        }
        log.info("对象存储：关键帧已上传 asset={} job={} {}/{}", assetId, jobId, ok, frames.size());
    }

    public void putReport(long assetId, long jobId, Path docx) {
        if (store.enabled()) {
            store.put(reportKey(assetId, jobId), docx, ObjectStore.contentTypeOf("report.docx"));
        }
    }

    /**
     * 取一份本地副本。
     * <p>本地已有就直接用（绝大多数情况），没有才回桶里拉——多个副本共享同一份数据时才会走到这里。
     */
    public Path ensureLocal(String key, Path target) {
        return store.materialize(key, target).orElse(null);
    }

    public Path ensureSource(long assetId, String fileName, Path localPath) {
        return store.materialize(sourceKey(assetId, fileName), localPath)
                .orElse(localPath.toFile().isFile() ? localPath : null);
    }

    public void deleteAsset(long assetId) {
        if (store.enabled()) {
            store.deletePrefix(assetPrefix(assetId));
        }
    }

    public void deleteJob(long assetId, long jobId) {
        if (store.enabled()) {
            store.delete(reportKey(assetId, jobId));
            store.deletePrefix("assets/%d/frames/job-%d/".formatted(assetId, jobId));
        }
    }
}
