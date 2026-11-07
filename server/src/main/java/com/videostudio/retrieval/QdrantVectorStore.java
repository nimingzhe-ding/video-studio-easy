package com.videostudio.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.videostudio.common.*;
import com.videostudio.domain.ContextChunk;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class QdrantVectorStore implements VectorStore {
    private final String url; private final String collection; private final Map<String,String> headers; private final int dimensions;
    public QdrantVectorStore(@Value("${vector.qdrant.url}") String url,@Value("${vector.qdrant.api-key}") String key,
        @Value("${vector.qdrant.collection:video_chunks}") String collection,@Value("${vector.qdrant.dimensions:1024}") int dimensions) {
        if(!collection.matches("[A-Za-z0-9_-]+") || dimensions<1) throw new IllegalArgumentException("Qdrant 配置无效");
        this.url=url.replaceAll("/+$",""); this.collection=collection; this.dimensions=dimensions;
        this.headers=key.isBlank()?Map.of():Map.of("api-key",key);
    }
    @PostConstruct public void initialize() {
        var response=Http.get(endpoint(""),headers,Duration.ofSeconds(15));
        if(response.status()==404) {
            call("PUT","",Map.of("vectors",Map.of("size",dimensions,"distance","Cosine")));
            response=Http.get(endpoint(""),headers,Duration.ofSeconds(15));
        }
        if(!response.ok()) throw new IllegalStateException("Qdrant 集合不可用，服务无法启动");
        int size=Json.tree(response.body()).path("result").path("config").path("params").path("vectors").path("size").asInt();
        if(size!=dimensions) throw new IllegalStateException("Qdrant 向量维度不匹配，请迁移索引或更换集合；禁止自动删除现有集合");
        for(String name:List.of("job_id","asset_id")) call("PUT","/index?wait=true",Map.of("field_name",name,"field_schema","integer"));
    }
    public String provider() { return "qdrant"; }
    public boolean enabled() { return true; }
    public void health() {
        if(!Http.get(url+"/readyz",headers,Duration.ofSeconds(3)).ok()) throw new IllegalStateException("Qdrant 不可用");
    }
    public void index(long assetId,long jobId,List<ContextChunk> chunks) {
        List<Map<String,Object>> points=new ArrayList<>();
        for(var chunk:chunks) {
            validate(chunk.embedding());
            String id=UUID.nameUUIDFromBytes(VectorStore.key(jobId,chunk.chunkIndex()).getBytes(StandardCharsets.UTF_8)).toString();
            points.add(Map.of("id",id,"vector",chunk.embedding(),"payload",
                Map.of("job_id",jobId,"asset_id",assetId,"chunk_index",chunk.chunkIndex())));
        }
        if(points.isEmpty()) throw new IllegalStateException("没有可索引的证据分块");
        deleteJob(jobId);
        for(int i=0;i<points.size();i+=64) call("PUT","/points?wait=true",Map.of("points",points.subList(i,Math.min(i+64,points.size()))));
    }
    public Map<Integer,Double> semanticScores(long jobId,List<ContextChunk> chunks,List<Double> vector) {
        validate(vector);
        var result=call("POST","/points/query",Map.of("query",vector,"filter",filter("job_id",jobId),
            "limit",Math.max(1,chunks.size()),"with_payload",true));
        Map<Integer,Double> scores=new HashMap<>();
        for(var point:result.path("result").path("points"))
            scores.put(point.path("payload").path("chunk_index").asInt(),point.path("score").asDouble());
        if(scores.isEmpty() && !chunks.isEmpty()) throw new IllegalStateException("Qdrant 未找到该任务的索引，请重试以重建索引");
        return scores;
    }
    public void deleteJob(long id) { call("POST","/points/delete?wait=true",Map.of("filter",filter("job_id",id))); }
    public void deleteAsset(long id) { call("POST","/points/delete?wait=true",Map.of("filter",filter("asset_id",id))); }
    private Map<String,Object> filter(String field,long value) { return Map.of("must",List.of(Map.of("key",field,"match",Map.of("value",value)))); }
    private void validate(List<Double> vector) {
        if(vector==null || vector.size()!=dimensions || vector.stream().anyMatch(v -> v==null || !Double.isFinite(v)))
            throw new IllegalArgumentException("向量维度或数值不合法，期望 "+dimensions+" 维");
    }
    private String endpoint(String suffix) { return url+"/collections/"+collection+suffix; }
    private JsonNode call(String method,String suffix,Object body) {
        var response=Http.json(method,endpoint(suffix),headers,Json.write(body),Duration.ofSeconds(30));
        if(!response.ok()) throw new IllegalStateException("Qdrant 操作失败（HTTP "+response.status()+"）");
        return Json.tree(response.body());
    }
}
