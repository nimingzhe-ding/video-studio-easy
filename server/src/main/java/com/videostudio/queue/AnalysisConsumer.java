package com.videostudio.queue;

import com.videostudio.domain.JobStatus;
import com.videostudio.repository.JobRepository;
import com.videostudio.service.AnalysisPipeline;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
// consumeTimeout 的单位是分钟（rocketmq-spring 约定，默认 15）：长视频分析动辄十几分钟，
// 这里放宽到 60 分钟，避免正常执行中的消息被 Broker 当成消费超时而重复投递。
@RocketMQMessageListener(topic="${rocketmq.topic.analysis}",consumerGroup="${rocketmq.consumer.group}",
    consumeThreadNumber=2,maxReconsumeTimes=3,consumeTimeout=60)
public class AnalysisConsumer implements RocketMQListener<MessageExt> {
    private final JobRepository jobs; private final AnalysisPipeline pipeline;
    private final JdbcTemplate jdbc; private final RedissonClient redis;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public AnalysisConsumer(JobRepository jobs,AnalysisPipeline pipeline,JdbcTemplate jdbc,RedissonClient redis,org.springframework.transaction.support.TransactionTemplate transactions) {
        this.transactions=transactions;
        this.jobs=jobs; this.pipeline=pipeline; this.jdbc=jdbc; this.redis=redis;
    }
    private void recordFailure(long id,int generation,MessageExt message,String reason) {
        jdbc.update("INSERT IGNORE INTO failed_delivery(job_id,generation,message_id,reason,created_at) VALUES (?,?,?,?,NOW(3))",
            id,generation,AnalysisMessage.identity(message.getMsgId(),message.getBody()),com.videostudio.common.SafeErrors.message(reason,512));
    }
    private boolean permanent(Throwable error) {
        for(int depth=0;error!=null && depth<8;depth++,error=error.getCause()) {
            if(error instanceof com.videostudio.llm.ModelRequestRejectedException) return true;
            if(error instanceof com.videostudio.common.ApiException api &&
                java.util.Set.of(400,401,403,404,413,422,429).contains(api.status())) return true;
        }
        return false;
    }
    @Override public void onMessage(MessageExt message) {
        AnalysisMessage payload;
        try { payload=AnalysisMessage.parse(message.getBody()); }
        catch(IllegalArgumentException ex) {
            jdbc.update("""
                INSERT IGNORE INTO failed_delivery(job_id,generation,message_id,reason,failure_kind,payload_sha256,created_at)
                VALUES (0,0,?,?,'INVALID_MESSAGE',?,NOW(3))
                """,AnalysisMessage.identity(message.getMsgId(),message.getBody()),
                "异常队列消息："+ex.getMessage(),AnalysisMessage.digest(message.getBody()));
            return; // Only acknowledge after the audit write succeeds. Invalid bodies are never replayed.
        }
        long id=payload.jobId();
        int generation=payload.generation();
        var lock=redis.getLock("worker:job:"+id);
        boolean acquired=false;
        try {
            acquired=lock.tryLock(0,TimeUnit.SECONDS);
            if(!acquired) {
                var current=jobs.find(id).orElse(null);
                if(current==null || current.status()==JobStatus.SUCCEEDED || current.status()==JobStatus.CANCELED) return;
                Integer active=jdbc.queryForObject("SELECT generation FROM analysis_job WHERE id=?",Integer.class,id);
                if(active==null || active!=generation) return;
                // Only a RUNNING task has an execution owner that the reaper can recover.
                if(current.status()==JobStatus.RUNNING) return;
                if(message.getReconsumeTimes()>=3) {
                    transactions.executeWithoutResult(tx -> {
                        int changed=jdbc.update("""
                            UPDATE analysis_job SET status='FAILED',stage='FAILED',error=?,finished_at=NOW(3),updated_at=NOW(3)
                            WHERE id=? AND generation=? AND status IN ('PENDING','FAILED')
                            ""","消费执行锁持续冲突，请稍后重试",id,generation);
                        if(changed==1) recordFailure(id,generation,message,"执行锁持续冲突，投递重试已用尽");
                    });
                }
                throw new IllegalStateException("任务尚未启动，等待执行锁释放后重投");
            }
            var job=jobs.find(id).orElse(null);
            if(job==null || job.status()==JobStatus.SUCCEEDED || job.status()==JobStatus.CANCELED) return;
            Integer active=jdbc.queryForObject("SELECT generation FROM analysis_job WHERE id=?",Integer.class,id);
            if(active==null || active!=generation) return; // Ignore deliveries from an earlier manual retry.
            try {
                pipeline.run(id);
            } catch(RuntimeException ex) {
                jobs.markFailed(id, com.videostudio.common.SafeErrors.message(ex.getMessage(),300));
                if(jobs.find(id).map(j -> j.status()==JobStatus.CANCELED).orElse(true)) return;
                if(permanent(ex)) {
                    recordFailure(id,generation,message,"永久错误或预算耗尽，已停止自动重试");
                    return;
                }
                if(message.getReconsumeTimes()>=3)
                    recordFailure(id,generation,message,"自动重试次数已用尽；原始消息保留在 RocketMQ 死信队列");
                throw ex; // Broker retry; after the limit RocketMQ moves the message to %DLQ%group.
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("消费者正在关闭",ex);
        } finally { if(acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
