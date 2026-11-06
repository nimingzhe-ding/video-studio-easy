package com.videostudio.service;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.*;
import com.videostudio.repository.*;
import com.videostudio.storage.MediaObjects;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/**
 * 证据复用：同一素材的第二个任务不必重新转写与识别。
 *
 * <p>转写与 OCR 只取决于素材内容，与「用什么模式分析」无关。只要该素材上有成功任务留下过证据，
 * 新任务就直接复用，把时间花在真正因目标而异的 Agent 阶段。
 */
@Service
public class EvidenceService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EvidenceService.class);

    private final JobRepository jobs; private final TranscriptRepository transcripts;
    private final VisualRepository visuals; private final ChunkRepository chunks;
    private final ReportRepository reports; private final CheckpointRepository checkpoints;
    private final MediaObjects objects; private final AppProperties props;
    public EvidenceService(JobRepository jobs,TranscriptRepository transcripts,VisualRepository visuals,
        ChunkRepository chunks,ReportRepository reports,CheckpointRepository checkpoints,
        MediaObjects objects,AppProperties props) {
        this.jobs=jobs; this.transcripts=transcripts; this.visuals=visuals; this.chunks=chunks;
        this.reports=reports; this.checkpoints=checkpoints; this.objects=objects; this.props=props;
    }
    public record Reused(long sourceJobId,List<TranscriptSegment> segments,List<VisualEvidence> visuals,
        List<ContextChunk> chunks,boolean speakerSeparation) {
        public static Reused none() { return new Reused(0,List.of(),List.of(),List.of(),false); }
        public boolean present() { return !segments.isEmpty() || !visuals.isEmpty(); }
        public boolean hasChunks() { return !chunks.isEmpty(); }
    }
    public Reused findReusable(long asset,long self) { return findReusable(asset,self,false); }
    public Reused findReusable(long asset,long self,boolean allowSelf) {
        for(var candidate:jobs.listByAsset(asset)) {
            boolean resumingSelf = candidate.id()==self && allowSelf;
            if(candidate.id()==self ? !allowSelf : candidate.status()!=JobStatus.SUCCEEDED) continue;
            var speech=transcripts.listByJob(candidate.id());
            // 开关打开、而这份转写是在「说话人分离」上线之前生成的，就必须重跑一次语音识别：
            // 否则讲者标签永久缺失。判据是「这份转写生成时有没有开分离」，不是「有没有标签」——
            // 单人素材本来就不该有标签，把「没有标签」当成「需要重跑」会让它每次都重跑。
            // 开关关掉时不存在「等价性」问题，连标记都不用查（省掉每个候选两次数据库往返）；
            // 但返回的 separated 仍然是「这份转写真的做过分离」，不能因为开关关着就谎报成 true。
            boolean separated = separatedSpeech(candidate.id());
            if(!speech.isEmpty() && needsSpeakers() && !separated) {
                // 断点续跑是唯一不能跳过自己的场景：重试沿用同一个 jobId，而上次失败时根本
                // 没有报告可读，标记只能来自断点 payload；对旧数据再留一次兜底——
                // 转写确实带着讲者标签，就认为它分离过（旧版本不写标记，标签是唯一证据）。
                if(resumingSelf && hasSpeakerLabels(speech)) {
                    separated = true;
                } else {
                    log.info("任务 {} 的转写由未启用说话人分离的版本生成，跳过复用（重新识别一次）",candidate.id());
                    continue;
                }
            }
            var images=visuals.listByJob(candidate.id()).stream()
                .map(v -> objects.materializeFrame(asset,candidate.id(),v)).toList();
            if(!speech.isEmpty() || !images.isEmpty())
                return new Reused(candidate.id(),speech,images,chunks.listByJob(candidate.id()),separated);
        }
        return Reused.none();
    }

    private boolean needsSpeakers() { return props.getMedia().isSpeakerSeparationEnabled(); }

    /** 至少一段带说话人标签，说明这份转写确实做过分离（旧任务的标记缺失时作为兜底证据） */
    private static boolean hasSpeakerLabels(List<TranscriptSegment> speech) {
        return speech.stream().anyMatch(s -> s.speaker()!=null && !s.speaker().isBlank());
    }

    /**
     * 该任务的转写生成时启用过说话人分离。
     * <p>成功任务看报告 trace；未完成的任务（断点续跑）没有报告，看断点 payload。
     * 两处都读不到就按未标记处理——宁可多识别一次，也不要拿一份没有讲者信息的转写去出报告。
     */
    private boolean separatedSpeech(long jobId) {
        try {
            if(checkpoints.find(jobId)
                    .map(checkpoint -> markedInPayload(checkpoint.payloadJson()))
                    .orElse(false)) {
                return true;
            }
            return reports.findByJob(jobId)
                    .map(report -> {
                        String trace = report.traceJson();
                        return trace != null && trace.contains("\"speakerSeparation\"");
                    })
                    .orElse(false);
        } catch (RuntimeException ex) {
            // 复用是尽力而为：读不到就当作没标记，宁可多跑一次也不要错用一份没有讲者信息的转写
            log.warn("读取任务 {} 的复核标记失败，按未标记处理: {}", jobId, ex.getMessage());
            return false;
        }
    }

    /** 断点 payload 是 pipeline 自己写的 JSON，这里只认明确为 true 的标记 */
    private static boolean markedInPayload(String payloadJson) {
        if(payloadJson==null || payloadJson.isBlank()) return false;
        try {
            return com.videostudio.common.Json.tree(payloadJson).path("speakerSeparation").asBoolean(false);
        } catch (RuntimeException ex) {
            return false;
        }
    }
    public boolean framesAvailable(List<VisualEvidence> images) {
        return images.stream().allMatch(v -> v.framePath()==null || Files.isReadable(Path.of(v.framePath())));
    }
    public List<VisualEvidence> copyFrames(List<VisualEvidence> images,Path target) {
        try {
            Files.createDirectories(target);
            List<VisualEvidence> copied=new ArrayList<>();
            for(var image:images) {
                Path source=Path.of(image.framePath()), destination=target.resolve(source.getFileName());
                if(!source.toAbsolutePath().equals(destination.toAbsolutePath()))
                    Files.copy(source,destination,StandardCopyOption.REPLACE_EXISTING);
                copied.add(new VisualEvidence(image.timeMs(),destination.toString(),image.ocrText(),image.caption(),image.hash(),image.source()));
            }
            return copied;
        } catch(java.io.IOException ex) { throw new IllegalStateException("关键帧工作副本创建失败",ex); }
    }
}
