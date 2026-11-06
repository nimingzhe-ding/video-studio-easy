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
    private final ReportRepository reports;
    private final MediaObjects objects; private final AppProperties props;
    public EvidenceService(JobRepository jobs,TranscriptRepository transcripts,VisualRepository visuals,
        ChunkRepository chunks,ReportRepository reports,MediaObjects objects,AppProperties props) {
        this.jobs=jobs; this.transcripts=transcripts; this.visuals=visuals; this.chunks=chunks;
        this.reports=reports; this.objects=objects; this.props=props;
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
            if(candidate.id()==self ? !allowSelf : candidate.status()!=JobStatus.SUCCEEDED) continue;
            var speech=transcripts.listByJob(candidate.id());
            // 开关打开、而这份转写是在「说话人分离」上线之前生成的，就必须重跑一次语音识别：
            // 否则单人素材（本来就不该有标签）会被反复误判，或者反过来永远拿不到讲者标签。
            // 判据是「报告的 trace 里有没有 speakerSeparation 标记」，不是「有没有标签」。
            if(needsSpeakers() && !speakerSeparationMarked(candidate.id()) && !speech.isEmpty()) {
                log.info("任务 {} 的转写由未启用说话人分离的版本生成，跳过复用（重新识别一次）",candidate.id());
                continue;
            }
            var images=visuals.listByJob(candidate.id()).stream()
                .map(v -> objects.materializeFrame(asset,candidate.id(),v)).toList();
            if(!speech.isEmpty() || !images.isEmpty())
                return new Reused(candidate.id(),speech,images,chunks.listByJob(candidate.id()),
                        speakerSeparationMarked(candidate.id()));
        }
        return Reused.none();
    }

    private boolean needsSpeakers() { return props.getMedia().isSpeakerSeparationEnabled(); }

    /** 该任务的报告里记录过「启用过说话人分离」。旧任务没有这个标记，需要重跑一次。 */
    private boolean speakerSeparationMarked(long jobId) {
        try {
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
