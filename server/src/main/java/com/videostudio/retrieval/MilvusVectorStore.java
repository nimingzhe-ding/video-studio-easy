package com.videostudio.retrieval;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.ContextChunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 实现。
 *
 * <p>集合结构刻意做得很小：主键（j-&lt;jobId&gt;-c-&lt;分块下标&gt;）、job_id、asset_id、向量。
 * 分块的正文、关键词、时间轴统统留在 MySQL 里——Milvus 只回答「哪些分块和这个问题最像」，
 * 拿回下标后再去关系库取完整内容。这样向量库挂掉也不会丢任何分析结果。
 *
 * <p>三条失败路径都只降级：连不上、建集合失败、检索报错。降级后语义分返回空表，
 * {@link HybridRetriever} 会退回纯关键词打分，报告照常出。
 */
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "embedding";
    private static final String FIELD_JOB = "job_id";
    private static final String FIELD_ASSET = "asset_id";

    private final String collection;
    private final int topK;
    private final MilvusServiceClient client;
    private volatile boolean ready;
    private volatile int dimension;

    public MilvusVectorStore(AppProperties.Vector config) {
        this.collection = config.getCollection();
        this.topK = Math.max(1, config.getTopK());
        this.client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(config.getHost())
                .withPort(config.getPort())
                .build());
        this.ready = bootstrap();
    }

    private boolean bootstrap() {
        try {
            if (exists()) {
                client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(collection).build());
                log.info("向量库：已连接 Milvus，集合 {}", collection);
            } else {
                log.info("向量库：集合 {} 不存在，将在首次写入时按实际维度创建", collection);
            }
            return true;
        } catch (Exception e) {
            log.warn("向量库：Milvus 不可用，语义检索降级为内存计算（{}）", e.getMessage());
            return false;
        }
    }

    @Override
    public String provider() {
        return ready ? "milvus" : "memory";
    }

    @Override
    public boolean enabled() {
        return ready;
    }

    /**
     * 维度只能从第一批真正的向量里得知（embedding 模型可配，写死 1024 会绑死供应商），
     * 所以集合在第一次写入时才建。
     */
    private boolean ensureCollection(int dim) {
        if (dim <= 0 || !ready) {
            return false;
        }
        if (dimension == dim && exists()) {
            return true;
        }
        try {
            if (exists()) {
                // 换了 embedding 模型（维度变了）只能重建；分块正文在 MySQL 里，重建不丢内容
                log.warn("向量库：集合 {} 维度由 {} 变为 {}，重建集合", collection, dimension, dim);
                client.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(collection).build());
            }
            List<FieldType> fields = List.of(
                    FieldType.newBuilder().withName(FIELD_ID).withDataType(DataType.VarChar)
                            .withMaxLength(64).withPrimaryKey(true).withAutoID(false).build(),
                    FieldType.newBuilder().withName(FIELD_JOB).withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName(FIELD_ASSET).withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName(FIELD_VECTOR).withDataType(DataType.FloatVector)
                            .withDimension(dim).build());
            client.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(collection).withFieldTypes(fields).withShardsNum(1).build());
            client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collection)
                    .withFieldName(FIELD_VECTOR)
                    .withIndexType(IndexType.HNSW)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                    .build());
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collection).build());
            dimension = dim;
            log.info("向量库：已创建集合 {} dimension={}", collection, dim);
            return true;
        } catch (Exception e) {
            log.warn("向量库：创建集合失败，降级为内存检索（{}）", e.getMessage());
            ready = false;
            return false;
        }
    }

    private boolean exists() {
        try {
            R<Boolean> result = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collection).build());
            return result.getData() != null && result.getData();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void index(long assetId, long jobId, List<ContextChunk> chunks) {
        if (!ready || chunks.isEmpty()) {
            return;
        }
        List<ContextChunk> withVector = chunks.stream().filter(c -> !c.embedding().isEmpty()).toList();
        if (withVector.isEmpty()) {
            return;
        }
        int dim = withVector.get(0).embedding().size();
        if (!ensureCollection(dim)) {
            return;
        }
        try {
            deleteJob(jobId);
            List<String> ids = new ArrayList<>(withVector.size());
            List<Long> jobs = new ArrayList<>(withVector.size());
            List<Long> assets = new ArrayList<>(withVector.size());
            List<List<Float>> vectors = new ArrayList<>(withVector.size());
            for (ContextChunk chunk : withVector) {
                if (chunk.embedding().size() != dim) {
                    continue;
                }
                ids.add(VectorStore.key(jobId, chunk.chunkIndex()));
                jobs.add(jobId);
                assets.add(assetId);
                vectors.add(chunk.embedding().stream().map(Double::floatValue).toList());
            }
            List<InsertParam.Field> fields = List.of(
                    new InsertParam.Field(FIELD_ID, ids),
                    new InsertParam.Field(FIELD_JOB, jobs),
                    new InsertParam.Field(FIELD_ASSET, assets),
                    new InsertParam.Field(FIELD_VECTOR, vectors));
            client.insert(InsertParam.newBuilder()
                    .withCollectionName(collection).withFields(fields).build());
            log.info("向量库：写入分块向量 job={} count={}", jobId, ids.size());
        } catch (Exception e) {
            log.warn("向量库：写入失败 job={}（{}）", jobId, e.getMessage());
        }
    }

    @Override
    public Map<Integer, Double> semanticScores(long jobId, List<ContextChunk> chunks, List<Double> queryVector) {
        Map<Integer, Double> scores = new HashMap<>();
        if (!ready || queryVector == null || queryVector.isEmpty() || chunks.isEmpty()) {
            return scores;
        }
        if (dimension > 0 && queryVector.size() != dimension) {
            return scores;
        }
        try {
            List<Float> vector = queryVector.stream().map(Double::floatValue).toList();
            R<SearchResults> response = client.search(SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectorFieldName(FIELD_VECTOR)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(Math.min(topK, Math.max(1, chunks.size())))
                    .withVectors(List.of(vector))
                    // 只在这个任务的分块里找：后面的关键词与画面文字打分都在这批分块内进行
                    .withExpr("%s == %d".formatted(FIELD_JOB, jobId))
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                    .withOutFields(List.of(FIELD_ID))
                    .build());
            SearchResultData data = response.getData() == null ? null : response.getData().getResults();
            if (data == null) {
                return scores;
            }
            List<String> ids = data.getIds().getStrId().getDataList();
            for (int i = 0; i < ids.size() && i < data.getScoresCount(); i++) {
                int index = VectorStore.chunkIndexOf(ids.get(i));
                if (index >= 0) {
                    scores.put(index, (double) data.getScores(i));
                }
            }
        } catch (Exception e) {
            log.warn("向量库：检索失败 job={}，本次按纯关键词打分（{}）", jobId, e.getMessage());
        }
        return scores;
    }

    @Override
    public void deleteJob(long jobId) {
        if (!ready || !exists()) {
            return;
        }
        try {
            R<MutationResult> result = client.delete(DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("%s == %d".formatted(FIELD_JOB, jobId))
                    .build());
            if (result.getData() == null) {
                log.debug("向量库：任务 {} 没有需要删除的向量", jobId);
            }
        } catch (Exception e) {
            log.warn("向量库：删除任务向量失败 job={}（{}）", jobId, e.getMessage());
        }
    }

    @Override
    public void deleteAsset(long assetId) {
        if (!ready || !exists()) {
            return;
        }
        try {
            client.delete(DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("%s == %d".formatted(FIELD_ASSET, assetId))
                    .build());
        } catch (Exception e) {
            log.warn("向量库：删除素材向量失败 asset={}（{}）", assetId, e.getMessage());
        }
    }
}
