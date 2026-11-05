package com.videostudio.service;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.*;
import com.videostudio.repository.*;
import com.videostudio.storage.MediaObjects;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

@Service
public class EvidenceService {
    private final JobRepository jobs; private final TranscriptRepository transcripts;
    private final VisualRepository visuals; private final ChunkRepository chunks;
    private final MediaObjects objects;
    public EvidenceService(JobRepository jobs,TranscriptRepository transcripts,VisualRepository visuals,
        ChunkRepository chunks,MediaObjects objects) {
        this.jobs=jobs; this.transcripts=transcripts; this.visuals=visuals; this.chunks=chunks; this.objects=objects;
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
            var images=visuals.listByJob(candidate.id()).stream()
                .map(v -> objects.materializeFrame(asset,candidate.id(),v)).toList();
            if(!speech.isEmpty() || !images.isEmpty())
                return new Reused(candidate.id(),speech,images,chunks.listByJob(candidate.id()),true);
        }
        return Reused.none();
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
