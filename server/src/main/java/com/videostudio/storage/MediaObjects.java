package com.videostudio.storage;

import com.videostudio.common.ApiException;
import com.videostudio.domain.VisualEvidence;
import com.videostudio.media.Storage;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.List;

@Component
public class MediaObjects {
    private final ObjectStore store; private final Storage storage;
    public MediaObjects(ObjectStore store,Storage storage) { this.store=store; this.storage=storage; }
    public ObjectStore store() { return store; }
    public boolean enabled() { return true; }
    public String sourceKey(long id,String name) { return "assets/"+id+"/source"+storage.extension(name); }
    public String frameKey(long asset,long job,String name) { return "assets/"+asset+"/frames/job-"+job+"/"+name; }
    public String reportKey(long asset,long job) { return "assets/"+asset+"/report/report-"+job+".docx"; }
    public String assetPrefix(long id) { return "assets/"+id+"/"; }
    public void putSource(long id,String name,Path file) { store.put(sourceKey(id,name),file,ObjectStore.contentTypeOf(name)); }
    public void putFrames(long asset,long job,List<Path> frames) { for(var file:frames) store.put(frameKey(asset,job,file.getFileName().toString()),file,"image/jpeg"); }
    public void putReport(long asset,long job,Path file) { store.put(reportKey(asset,job),file,ObjectStore.contentTypeOf("report.docx")); }
    public Path ensureLocal(String key,Path target) { return store.materialize(key,target).orElseThrow(() -> ApiException.notFound("对象文件不存在")); }
    public Path ensureSource(long id,String name,Path ignored) { return ensureLocal(sourceKey(id,name),storage.sourceFile(id,name)); }
    public VisualEvidence materializeFrame(long asset,long job,VisualEvidence evidence) {
        if(evidence.framePath()==null || evidence.framePath().isBlank()) return evidence;
        String name=Path.of(evidence.framePath()).getFileName().toString();
        Path local=ensureLocal(frameKey(asset,job,name),storage.frameDir(asset,job).resolve(name));
        return new VisualEvidence(evidence.timeMs(),local.toString(),evidence.ocrText(),evidence.caption(),evidence.hash(),evidence.source());
    }
    public void deleteAsset(long id) { store.deletePrefix(assetPrefix(id)); }
    public void deleteJob(long asset,long job) { store.delete(reportKey(asset,job)); store.deletePrefix("assets/"+asset+"/frames/job-"+job+"/"); }
}
