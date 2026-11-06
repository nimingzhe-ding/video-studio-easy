package com.videostudio.service;

import com.videostudio.common.ApiException;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.MediaAsset;
import com.videostudio.media.Storage;
import com.videostudio.retrieval.VectorStore;
import com.videostudio.repository.ChatRepository;
import com.videostudio.repository.ChunkRepository;
import com.videostudio.repository.JobEventRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.MediaRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.VisualRepository;
import com.videostudio.storage.MediaObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 级联清理：任务 / 媒体删除时，把证据、事件、报告、对话与磁盘目录一并收掉。
 * <p>做成独立服务而不是塞进各个仓储，是因为「删哪些表」和「删哪些目录」这件事必须在一处看全，
 * 否则很容易漏掉 job_event 或 frames 目录，留下一堆孤儿数据。
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final MediaRepository mediaRepository;
    private final JobRepository jobRepository;
    private final JobEventRepository eventRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualRepository visualRepository;
    private final ChunkRepository chunkRepository;
    private final ReportRepository reportRepository;
    private final ChatRepository chatRepository;
    private final JobService jobService;
    private final Storage storage;
    private final MediaObjects objects;
    private final VectorStore vectors;
    private final org.redisson.api.RedissonClient locks;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.videostudio.infra.JobOwners owners;

    public CleanupService(MediaRepository mediaRepository, JobRepository jobRepository,
                          JobEventRepository eventRepository, TranscriptRepository transcriptRepository,
                          VisualRepository visualRepository, ChunkRepository chunkRepository,
                          ReportRepository reportRepository, ChatRepository chatRepository,
                          JobService jobService, Storage storage,
                          MediaObjects objects, VectorStore vectors, org.redisson.api.RedissonClient locks,
                          org.springframework.transaction.support.TransactionTemplate transactions,
                          org.springframework.jdbc.core.JdbcTemplate jdbc, com.videostudio.infra.JobOwners owners) {
        this.locks=locks; this.transactions=transactions; this.jdbc=jdbc; this.owners=owners;
        this.mediaRepository = mediaRepository;
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualRepository = visualRepository;
        this.chunkRepository = chunkRepository;
        this.reportRepository = reportRepository;
        this.chatRepository = chatRepository;
        this.jobService = jobService;
        this.storage = storage;
        this.objects = objects;
        this.vectors = vectors;
    }

    /** 删除单个任务：运行中的先取消，再清证据与磁盘目录 */
    public void deleteJob(long jobId) {
        withJobLock(jobId,() -> transactions.executeWithoutResult(tx -> deleteJobLocked(jobId)));
    }
    private void deleteJobLocked(long jobId) {
        AnalysisJob job = jobRepository.find(jobId)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + jobId));
        if (job.status().running()) {
            jobService.cancel(jobId);
        }
        objects.deleteJob(job.assetId(),jobId);
        vectors.deleteJob(jobId);
        purgeJobRows(jobId);
        deleteQuietly(storage.mediaDir(job.assetId()).resolve("frames").resolve("job-" + jobId));
        deleteQuietly(storage.mediaDir(job.assetId()).resolve("asr").resolve("job-" + jobId));
        deleteQuietly(storage.mediaDir(job.assetId()).resolve("audio").resolve("job-" + jobId));
        deleteQuietly(storage.reportDocx(job.assetId(), jobId));
        // 外部存储里的副本也要收掉，否则桶里会攒下没人引用的关键帧与向量
        log.info("已删除任务 {} (asset={})", jobId, job.assetId());
    }

    /** 删除媒体：连同其全部任务、证据与 {root}/media/{id} 目录 */
    public void deleteMedia(long assetId) {
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM media_asset WHERE id=? FOR UPDATE",Long.class,assetId);
            var acquired=new java.util.ArrayList<org.redisson.api.RLock>();
            try {
                for(long id:jobRepository.idsByAsset(assetId)) {
                    var lock=locks.getLock("worker:job:"+id);
                    if(!lock.tryLock()) throw ApiException.conflict("分析任务仍在执行，请先取消并等待停止");
                    acquired.add(lock);
                }
                deleteMediaLocked(assetId);
            } finally { for(var lock:acquired) if(lock.isHeldByCurrentThread()) lock.unlock(); }
        });
    }
    private void deleteMediaLocked(long assetId) {
        MediaAsset asset = mediaRepository.find(assetId)
                .orElseThrow(() -> ApiException.notFound("媒体不存在: " + assetId));
        objects.deleteAsset(assetId);
        vectors.deleteAsset(assetId);
        List<Long> jobIds = jobRepository.idsByAsset(assetId);
        for (Long jobId : jobIds) {
            AnalysisJob job = jobRepository.find(jobId).orElse(null);
            if (job != null && job.status().running()) {
                jobService.cancel(jobId);
            }
            purgeJobRows(jobId);
        }
        jdbc.update("DELETE c FROM upload_chunk c JOIN upload_session s ON s.upload_id=c.upload_id WHERE s.asset_id=?",assetId);
        jdbc.update("DELETE FROM upload_session WHERE asset_id=?",assetId);
        mediaRepository.delete(assetId);
        deleteQuietly(storage.root().resolve("media").resolve(Long.toString(assetId)));
        log.info("已删除媒体 {} ({}), 连带任务 {}", assetId, asset.name(), jobIds);
    }

    private void withJobLock(long id,Runnable action) {
        var lock=locks.getLock("worker:job:"+id);
        if(!lock.tryLock()) throw ApiException.conflict("任务仍在执行或停止中，请稍后删除");
        try { action.run(); } finally { if(lock.isHeldByCurrentThread()) lock.unlock(); }
    }
    private void purgeJobRows(long jobId) {
        jdbc.update("DELETE FROM job_checkpoint WHERE job_id=?",jobId);
        jdbc.update("DELETE FROM job_outbox WHERE job_id=?",jobId);
        jdbc.update("DELETE FROM failed_delivery WHERE job_id=?",jobId);
        eventRepository.deleteByJob(jobId);
        transcriptRepository.deleteByJob(jobId);
        visualRepository.deleteByJob(jobId);
        chunkRepository.deleteByJob(jobId);
        reportRepository.deleteByJob(jobId);
        chatRepository.clear(jobId);
        jobRepository.delete(jobId);
        // 归属缓存跟着任务走，否则删除大量任务后 map 会一直留着旧条目
        owners.forget(jobId);
    }

    /** 目录删除失败只记日志：库里记录已清掉，残留文件不该阻塞用户操作 */
    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path item : sorted) {
                Files.deleteIfExists(item);
            }
        } catch (IOException e) {
            log.warn("清理路径失败 {}: {}", path, e.getMessage());
        }
    }
}
