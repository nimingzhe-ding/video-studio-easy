package com.videostudio.storage;

import com.videostudio.retrieval.VectorStore;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;

/** Metadata removal and cleanup intent commit together. External deletion is retried after commit. */
@Component
public class CleanupOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStore objects;
    private final VectorStore vectors;
    private final RedissonClient locks;

    public CleanupOutbox(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectStore objects,
                         VectorStore vectors, RedissonClient locks) {
        this.jdbc=jdbc; this.transactions=transactions; this.objects=objects; this.vectors=vectors; this.locks=locks;
    }
    public void enqueue(String kind, String key, long referenceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("清理任务必须与元数据在同一个事务内提交");
        jdbc.update("""
            INSERT INTO storage_cleanup(kind,object_key,reference_id,status,next_attempt_at,created_at)
            VALUES (?,?,?,'PENDING',NOW(3),NOW(3))
            """,kind,key,referenceId);
    }
    public void job(long assetId,long jobId) {
        enqueue("PREFIX","assets/"+assetId+"/frames/job-"+jobId+"/",0);
        enqueue("OBJECT","assets/"+assetId+"/report/report-"+jobId+".docx",0);
        enqueue("VECTOR_JOB","",jobId);
    }
    public void asset(long id) {
        enqueue("PREFIX","assets/"+id+"/",0);
        enqueue("VECTOR_ASSET","",id);
    }
    public void upload(String id,long assetId) { enqueue("UPLOAD_PARTS",id,assetId); }

    @Scheduled(fixedDelayString="${cleanup.interval-ms:5000}")
    public void dispatch() {
        for(int count=0;count<10;count++) {
            Map<String,Object> row=transactions.execute(tx -> {
                var rows=jdbc.queryForList("""
                    SELECT id,kind,object_key,reference_id,attempts FROM storage_cleanup
                    WHERE status='PENDING' AND next_attempt_at<=NOW(3)
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """);
                if(rows.isEmpty()) return null;
                var claimed=new java.util.HashMap<>(rows.get(0));
                String token=UUID.randomUUID().toString();
                jdbc.update("UPDATE storage_cleanup SET lease_token=?,next_attempt_at=TIMESTAMPADD(SECOND,300,NOW(3)) WHERE id=?",
                    token,claimed.get("id"));
                claimed.put("lease_token",token);
                return claimed;
            });
            if(row==null) return;
            long id=((Number)row.get("id")).longValue();
            String token=(String)row.get("lease_token");
            try {
                execute((String)row.get("kind"),(String)row.get("object_key"),((Number)row.get("reference_id")).longValue());
                jdbc.update("UPDATE storage_cleanup SET status='DONE',finished_at=NOW(3),last_error=NULL,lease_token=NULL WHERE id=? AND lease_token=?",id,token);
            } catch(RuntimeException ex) {
                int attempts=((Number)row.get("attempts")).intValue()+1;
                jdbc.update("""
                    UPDATE storage_cleanup SET attempts=?,next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3)),
                        last_error=?,lease_token=NULL WHERE id=? AND lease_token=?
                    """,attempts,Math.min(300,1<<Math.min(attempts,8)),"外部清理失败，将自动重试",id,token);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("清理任务失败 id={} kind={}",id,row.get("kind"),ex);
            }
        }
    }
    void execute(String kind,String key,long ref) {
        switch(kind) {
            case "PREFIX" -> objects.deletePrefix(key);
            case "OBJECT" -> objects.delete(key);
            case "VECTOR_JOB" -> vectors.deleteJob(ref);
            case "VECTOR_ASSET" -> vectors.deleteAsset(ref);
            case "UPLOAD_PARTS" -> {
                var lock=locks.getLock("upload:"+key);
                if(!lock.tryLock()) throw new IllegalStateException("上传正在处理，稍后清理");
                try {
                    var sessions=jdbc.queryForList("SELECT asset_id FROM upload_session WHERE upload_id=?",key);
                    // An upload id may be reused after cancellation/deletion. Never delete the new session's parts.
                    if(!sessions.isEmpty()) {
                        Object current=sessions.get(0).get("asset_id");
                        if(current==null || ((Number)current).longValue()!=ref) return;
                    }
                    objects.deletePrefix("uploads/"+key+"/");
                } finally { if(lock.isHeldByCurrentThread()) lock.unlock(); }
            }
            default -> throw new IllegalArgumentException("未知清理任务类型");
        }
    }
}
