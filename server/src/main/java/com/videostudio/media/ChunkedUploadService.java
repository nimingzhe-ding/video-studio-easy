package com.videostudio.media;

import com.videostudio.common.ApiException;
import com.videostudio.config.AppProperties;
import com.videostudio.domain.MediaAsset;
import com.videostudio.domain.UploadSession;
import com.videostudio.repository.UploadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 分片上传：创建会话 → 逐片上传 → 合并入库 → 断点续传。
 *
 * <p>分片目录在 {@code data/uploads/{uploadId}/}，与正式媒体目录分开；
 * 合并成功后才调用 {@link MediaIngestService#ingestFile} 搬进 {@code data/media/{assetId}/}。
 * 这样即使用户中途放弃，也只是一堆可回收的临时分片，不会在媒体库里留下半截记录。
 *
 * <p>「秒传」在这里的含义是<b>同一 uploadId 已经合并过就直接返回已有资产</b>，
 * 而不是按内容哈希跨文件去重（后者需要遍历所有媒体的哈希，demo 阶段不划算）。
 */
@Service
public class ChunkedUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploadService.class);

    private final UploadSessionRepository repository;
    private final MediaIngestService ingestService;
    private final Storage storage;
    private final AppProperties props;
    private final com.videostudio.storage.ObjectStore objects;
    private final com.videostudio.security.CurrentUser current;
    private final org.redisson.api.RedissonClient locks;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public ChunkedUploadService(UploadSessionRepository repository, MediaIngestService ingestService,
                                Storage storage, AppProperties props, com.videostudio.storage.ObjectStore objects,
            com.videostudio.security.CurrentUser current, org.redisson.api.RedissonClient locks, org.springframework.transaction.support.TransactionTemplate transactions) {
        this.transactions=transactions;
        this.objects=objects; this.current=current; this.locks=locks;
        this.repository = repository;
        this.ingestService = ingestService;
        this.storage = storage;
        this.props = props;
    }

    /** 合并结果：既可能是新入库的资产，也可能是之前已经合并过的同一个 uploadId */
    public record Merged(MediaAsset asset, boolean reused) {
    }

    public int chunkSize() {
        return Math.max(256 * 1024, props.getUpload().getChunkSizeBytes());
    }

    /**
     * 创建或复用会话。
     *
     * <p>幂等：同一个 uploadId 重复 init（浏览器刷新后重试）不会重置已上传的分片，
     * 客户端拿到 {@code receivedIndexes} 后直接跳过这些片即可。
     */
    public UploadSession init(String uploadId, String name, long totalBytes) { return locked(normalise(uploadId),() -> initLocked(uploadId,name,totalBytes)); }

    private UploadSession initLocked(String uploadId, String name, long totalBytes) {
        if (totalBytes <= 0) {
            throw new ApiException("文件大小无效");
        }
        long max = props.getStorage().getMaxUploadBytes();
        if (totalBytes > max) {
            throw new ApiException(413, "文件超过大小限制（上限 %.1f GB）".formatted(max / 1024.0 / 1024 / 1024));
        }
        int chunkSize = chunkSize();
        int totalChunks = (int) Math.ceil(totalBytes / (double) chunkSize);
        if (totalChunks > props.getUpload().getMaxChunks()) {
            throw new ApiException(413, "分片数量超过上限（%d），请调大 UPLOAD_CHUNK_SIZE".formatted(props.getUpload().getMaxChunks()));
        }
        String id = normalise(uploadId);
        Optional<UploadSession> existing = repository.find(id);
        if (existing.isPresent()) {
            UploadSession session = existing.get();
            if (session.totalBytes() != totalBytes) {
                // 同一个 id 换了文件：直接重置，避免把两个文件的片拼在一起
                throw ApiException.conflict("上传信息发生变化，请使用新的上传标识");
            } else {
                return session;
            }
        }
        Instant now = Instant.now();
        UploadSession session = new UploadSession(id, safeName(name), totalBytes, totalChunks, chunkSize,
                0, null, now, now);
        repository.create(session);
        storage.uploadDir(id);
        log.info("分片上传会话创建 id={} name={} size={} chunks={} chunkSize={}",
                id, session.name(), totalBytes, totalChunks, chunkSize);
        return session;
    }

    /** 写入一片；重复上传同一片是幂等的，交给前端做重试更简单 */
    public UploadSession writeChunk(String uploadId, int index, MultipartFile chunk) { return locked(normalise(uploadId),() -> writeChunkLocked(uploadId,index,chunk)); }

    private UploadSession writeChunkLocked(String uploadId, int index, MultipartFile chunk) {
        UploadSession session = require(uploadId);
        if (session.finished()) {
            return session;
        }
        if (index < 0 || index >= session.totalChunks()) {
            throw new ApiException("分片序号越界: " + index + "（共 " + session.totalChunks() + " 片）");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new ApiException("分片内容为空: " + index);
        }
        Path target = storage.uploadChunk(session.uploadId(), index);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream in = chunk.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            // 先写临时文件再原子改名：即使进程在写入过程中被杀，也不会留下"看似存在"的半截分片
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存分片失败: " + e.getMessage(), e);
        }
        long expected=Math.min((long)session.chunkSize(),session.totalBytes()-(long)index*session.chunkSize());
        if(FilesSize(target)!=expected) throw new ApiException("分片大小不符合要求");
        objects.put(chunkKey(session.uploadId(),index),target,"application/octet-stream");
        repository.recordChunk(session.uploadId(), index, target.toFile().length());
        return repository.find(session.uploadId()).orElse(session);
    }

    /**
     * 合并分片并入库。
     *
     * <p>缺片时返回 409 并带上缺失序号，前端据此补传，而不是整段重来。
     */
    public Merged complete(String uploadId) { return locked(normalise(uploadId),() -> transactions.execute(status -> completeLocked(uploadId))); }

    private Merged completeLocked(String uploadId) {
        UploadSession session = require(uploadId);
        if (session.finished()) {
            MediaAsset asset = ingestService.findAsset(session.assetId())
                    .orElseThrow(() -> ApiException.notFound("会话已合并但媒体不存在: " + session.assetId()));
            return new Merged(asset, true);
        }
        List<Integer> missing = missingIndexes(session);
        if (!missing.isEmpty()) {
            throw new ApiException(409, "还有 %d 个分片未上传：%s".formatted(missing.size(), preview(missing)));
        }

        Path assembled = storage.uploadDir(session.uploadId()).resolve("assembled" + storage.extension(session.name()));
        Path tmp = assembled.resolveSibling(assembled.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                for (int i = 0; i < session.totalChunks(); i++) {
                    Path part=storage.uploadChunk(session.uploadId(),i);
                    objects.materialize(chunkKey(session.uploadId(),i),part).orElseThrow(() -> new IllegalStateException("上传分片不存在，请重新上传"));
                    Files.copy(part,out);
                }
            }
            long actual = Files.size(tmp);
            if (actual != session.totalBytes()) {
                Files.deleteIfExists(tmp);
                throw new ApiException(409, "合并后大小不符：期望 %d 字节，实际 %d 字节".formatted(session.totalBytes(), actual));
            }
            Files.move(tmp, assembled, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("合并分片失败: " + e.getMessage(), e);
        }

        MediaAsset asset = ingestService.ingestFile(assembled, session.name());
        repository.markFinished(session.uploadId(), asset.id());
        objects.deletePrefix("uploads/"+session.uploadId()+"/");
        // 正式文件已经在 media/{id} 下，整个分片目录都可以回收（会话行保留，用于幂等重试）
        deleteDir(storage.uploadDir(session.uploadId()));
        log.info("分片上传合并完成 uploadId={} assetId={} size={}", session.uploadId(), asset.id(), asset.sizeBytes());
        return new Merged(asset, false);
    }

    public Optional<UploadSession> status(String uploadId) {
        return repository.find(normalise(uploadId));
    }

    /** 已写入的分片字节总和 */
    public long receivedBytes(UploadSession session) {
        return repository.receivedBytes(session.uploadId());
    }

    /** 已收到的分片序号（升序），前端续传时跳过这些片 */
    public List<Integer> receivedIndexes(UploadSession session) {
        return repository.receivedIndexes(session.uploadId());
    }

    /** 断点续传需要的缺失分片序号 */
    public List<Integer> missingIndexes(UploadSession session) {
        Set<Integer> received = new LinkedHashSet<>(repository.receivedIndexes(session.uploadId()));
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < session.totalChunks(); i++) {
            if (!received.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** 取消上传：删会话与分片 */
    public void cancel(String uploadId) { locked(normalise(uploadId),() -> { cancelLocked(uploadId); return null; }); }

    private void cancelLocked(String uploadId) {
        String id = normalise(uploadId);
        objects.deletePrefix("uploads/"+id+"/");
        repository.delete(id);
        deleteDir(storage.uploadDir(id));
        log.info("分片上传已取消 id={}", id);
    }

    /**
     * 清理超时的未完成会话。
     *
     * @return 被清理的会话数
     */
    public int purgeStale() {
        Instant before = Instant.now().minus(Duration.ofHours(Math.max(1, props.getUpload().getRetentionHours())));

        // 未完成的会话：删除记录与分片
        List<String> stale = repository.stale(before);
        for (String id : stale) {
            var lock=locks.getLock("upload:"+id);
            if(!lock.tryLock()) continue;
            try {
                var session=repository.find(id);
                if(session.isPresent() && !session.get().finished() && session.get().updatedAt().isBefore(before)) {
                    objects.deletePrefix("uploads/"+id+"/");
                    repository.delete(id);
                    deleteDir(storage.uploadDir(id));
                }
            } finally { if(lock.isHeldByCurrentThread()) lock.unlock(); }
        }

        // 已完成的会话：记录保留（幂等重试时还能查到 assetId），只兜底回收残留目录
        List<String> finished = repository.finished(before);
        int reclaimed = 0;
        for (String id : finished) {
            Path dir = storage.uploadDir(id);
            if (Files.exists(dir)) {
                deleteDir(dir);
                reclaimed++;
            }
        }
        if (!stale.isEmpty() || reclaimed > 0) {
            log.info("清理分片上传残留：会话 {} 个，目录 {} 个", stale.size(), reclaimed);
        }
        return stale.size() + reclaimed;
    }

    private void clear(String uploadId) {
        objects.deletePrefix("uploads/"+uploadId+"/");
        repository.delete(uploadId);
        deleteDir(storage.uploadDir(uploadId));
    }

    private void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            });
        } catch (IOException e) {
            log.debug("删除目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private UploadSession require(String uploadId) {
        return repository.find(normalise(uploadId))
                .orElseThrow(() -> ApiException.notFound("上传会话不存在或已过期: " + uploadId));
    }

    private String normalise(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new ApiException("uploadId 不能为空");
        }
        // 与 Storage 的清洗规则保持一致，防止 ../../ 之类的路径穿越
        String id=uploadId.trim();
        if(!id.matches("[A-Za-z0-9_-]{1,110}")) throw new ApiException("uploadId 格式无效");
        String prefix="u"+current.id()+"-";
        return id.startsWith(prefix)?id:prefix+id;
    }

    private String chunkKey(String id,int index) { return "uploads/"+id+"/"+index; }
    private long FilesSize(Path file) {
        try { return Files.size(file); } catch(IOException ex) { throw new IllegalStateException("读取分片失败",ex); }
    }
    private <T> T locked(String id,java.util.function.Supplier<T> action) {
        var lock=locks.getLock("upload:"+id);
        boolean acquired=false;
        try {
            acquired=lock.tryLock(0,java.util.concurrent.TimeUnit.SECONDS);
            if(!acquired) throw ApiException.conflict("该上传正在处理，请稍后重试");
            return action.get();
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw ApiException.conflict("上传被中断"); }
        finally { if(acquired) lock.unlock(); }
    }
    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "upload.bin";
        }
        String base = Path.of(name).getFileName().toString();
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private String preview(List<Integer> indexes) {
        return indexes.size() <= 12 ? indexes.toString()
                : indexes.subList(0, 12) + " …（共 " + indexes.size() + " 片）";
    }
}
