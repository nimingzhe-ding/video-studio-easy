package com.videostudio.service;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.AnalysisJob;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.repository.ChunkRepository;
import com.videostudio.repository.JobRepository;
import com.videostudio.repository.ReportRepository;
import com.videostudio.repository.TranscriptRepository;
import com.videostudio.repository.VisualRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 证据复用：同一素材的第二个任务不必重新转写与识别。
 *
 * <p>转写与 OCR 是整条流水线里最贵的两步（10 分钟音频约 30 秒、长视频按帧数线性增长），
 * 但它们只取决于素材内容，与「用什么模式分析」无关。所以只要该素材上有成功任务留下过证据，
 * 新任务就直接复用，把时间花在真正因目标而异的 Agent 阶段。
 *
 * <p>复用是「尽力而为」的：任何一个仓储读失败都退回复用为空，调用方照常全量计算，不影响正确性。
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final JobRepository jobRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualRepository visualRepository;
    private final ChunkRepository chunkRepository;
    private final ReportRepository reportRepository;
    private final AppProperties props;

    public EvidenceService(JobRepository jobRepository, TranscriptRepository transcriptRepository,
                           VisualRepository visualRepository, ChunkRepository chunkRepository,
                           ReportRepository reportRepository, AppProperties props) {
        this.jobRepository = jobRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualRepository = visualRepository;
        this.chunkRepository = chunkRepository;
        this.reportRepository = reportRepository;
        this.props = props;
    }

    /**
     * @param sourceJobId 证据来源任务 id，0 表示没有可复用证据
     */
    public record Reused(long sourceJobId, List<TranscriptSegment> segments, List<VisualEvidence> visuals,
                         List<ContextChunk> chunks, boolean speakerSeparation) {
        public static Reused none() {
            return new Reused(0, List.of(), List.of(), List.of(), false);
        }

        public boolean present() {
            return !segments.isEmpty() || !visuals.isEmpty();
        }

        public boolean hasChunks() {
            return !chunks.isEmpty();
        }
    }

    /**
     * 找到该素材上证据最完整的一个成功任务并取回证据。
     * <p>用「成功且不是自己」的任务；优先返回同时有转写与画面证据的那一个。
     */
    public Reused findReusable(long assetId, long selfJobId) {
        List<AnalysisJob> candidates;
        try {
            candidates = jobRepository.listByAsset(assetId).stream()
                    .filter(job -> job.id() != selfJobId)
                    .filter(job -> job.status() == com.videostudio.domain.JobStatus.SUCCEEDED)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("查询可复用证据失败，退化为全量计算：{}", e.getMessage());
            return Reused.none();
        }
        for (AnalysisJob candidate : candidates) {
            try {
                List<TranscriptSegment> segments = transcriptRepository.listByJob(candidate.id());
                List<VisualEvidence> visuals = visualRepository.listByJob(candidate.id());
                if (segments.isEmpty() && visuals.isEmpty()) {
                    continue;
                }
                boolean marked = speakerSeparationMarked(candidate.id());
                if (needsSpeakers() && !marked && !segments.isEmpty()) {
                    // 只有「说话人分离上线之前生成的转写」才需要重跑——否则单人素材会被无限重识别。
                    // 判据是任务报告里有没有 speakerSeparation 标记，而不是「有没有标签」。
                    log.info("任务 {} 的转写由未启用说话人分离的版本生成，跳过复用（重新识别一次）", candidate.id());
                    continue;
                }
                List<ContextChunk> chunks = chunkRepository.listByJob(candidate.id());
                log.info("复用素材 {} 的证据：来源任务 {}，转写 {} 段、画面 {} 条、分块 {} 个",
                        assetId, candidate.id(), segments.size(), visuals.size(), chunks.size());
                return new Reused(candidate.id(), List.copyOf(segments), List.copyOf(visuals),
                        new ArrayList<>(chunks), marked);
            } catch (RuntimeException e) {
                log.warn("读取任务 {} 的证据失败，尝试下一个候选：{}", candidate.id(), e.getMessage());
            }
        }
        return Reused.none();
    }

    private boolean needsSpeakers() {
        return props.getMedia().isSpeakerSeparationEnabled();
    }

    /**
     * 该任务的报告里没有记录「启用过说话人分离」。
     * <p>旧任务（本能力上线前跑的）没有这个标记，需要重跑一次；跑完的新任务会带上标记，
     * 之后即使是单人素材也能正常复用——不能只看「有没有标签」，单人素材本来就不该有标签。
     */
    private boolean speakerSeparationMarked(long jobId) {
        try {
            return reportRepository.findByJob(jobId)
                    .map(report -> {
                        String trace = report.traceJson();
                        return trace != null && trace.contains("\"speakerSeparation\"");
                    })
                    .orElse(false);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * 转写存在但一条说话人标签都没有。
     * <p>没有任何转写（纯画面素材）不算「缺说话人」——那本来就不该有标签，不该因此拒绝复用。
     */
    private static boolean lacksSpeakers(List<TranscriptSegment> segments) {
        if (segments.isEmpty()) {
            return false;
        }
        for (TranscriptSegment segment : segments) {
            if (segment.speaker() != null && !segment.speaker().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 画证据的帧文件是否还在磁盘上（任务目录被清理过就只剩 DB 行，不能再复用） */
    public boolean framesAvailable(List<VisualEvidence> visuals) {
        for (VisualEvidence visual : visuals) {
            if (visual.framePath() == null || visual.framePath().isBlank()) {
                continue;
            }
            if (!java.nio.file.Files.isReadable(java.nio.file.Path.of(visual.framePath()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把源任务的帧文件复制到当前任务目录，并把证据里的路径改成新位置。
     * <p>不直接沿用源路径：任务可以被单独删除，一旦删掉源任务，复用方的关键帧就会 404。
     * 单帧约 20-40KB，即使 400 帧也只有十几 MB，换来的隔离性值得。
     */
    public List<VisualEvidence> copyFrames(List<VisualEvidence> visuals, java.nio.file.Path targetDir) {
        if (visuals.isEmpty()) {
            return visuals;
        }
        List<VisualEvidence> out = new ArrayList<>(visuals.size());
        try {
            java.nio.file.Files.createDirectories(targetDir);
        } catch (java.io.IOException e) {
            log.warn("创建帧目录失败，直接沿用源路径：{}", e.getMessage());
            return visuals;
        }
        int copied = 0;
        for (VisualEvidence visual : visuals) {
            String source = visual.framePath();
            if (source == null || source.isBlank()) {
                out.add(visual);
                continue;
            }
            java.nio.file.Path from = java.nio.file.Path.of(source);
            java.nio.file.Path to = targetDir.resolve(from.getFileName().toString());
            try {
                java.nio.file.Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied++;
                out.add(new VisualEvidence(visual.timeMs(), to.toString(), visual.ocrText(), visual.caption(),
                        visual.hash(), visual.source()));
            } catch (java.io.IOException e) {
                log.warn("复制关键帧失败，沿用源路径：{}", e.getMessage());
                out.add(visual);
            }
        }
        log.debug("复制关键帧 {} 张到 {}", copied, targetDir);
        return out;
    }

    public Optional<AnalysisJob> latestSucceeded(long assetId) {
        return jobRepository.listByAsset(assetId).stream()
                .filter(job -> job.status() == com.videostudio.domain.JobStatus.SUCCEEDED)
                .findFirst();
    }
}
