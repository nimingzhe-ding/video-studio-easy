package com.videostudio.queue;

import com.videostudio.common.Json;
import com.videostudio.domain.JobStatus;
import com.videostudio.repository.JobRepository;
import com.videostudio.service.AnalysisPipeline;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
// consumeTimeout 的单位是分钟（rocketmq-spring 约定，默认 15）：长视频分析动辄十几分钟，
// 这里放宽到 60 分钟，避免正常执行中的消息被 Broker 当成消费超时而重复投递。
@RocketMQMessageListener(topic="${rocketmq.topic.analysis}",consumerGroup="${rocketmq.consumer.group}",
    consumeThreadNumber=2,maxReconsumeTimes=3,consumeTimeout=60)
public class AnalysisConsumer implements RocketMQListener<MessageExt> {
    private final JobRepository jobs; private final AnalysisPipeline pipeline;
    private final JdbcTemplate jdbc; private final RedissonClient redis;
    public AnalysisConsumer(JobRepository jobs,AnalysisPipeline pipeline,JdbcTemplate jdbc,RedissonClient redis) {
        this.jobs=jobs; this.pipeline=pipeline; this.jdbc=jdbc; this.redis=redis;
    }
    @Override public void onMessage(MessageExt message) {
        var payload=Json.tree(new String(message.getBody(),StandardCharsets.UTF_8));
        long id=payload.path("jobId").asLong();
        int generation=payload.path("generation").asInt();
        if(id<=0 || generation<=0) throw new IllegalArgumentException("无效的任务消息");
        var lock=redis.getLock("worker:job:"+id);
        boolean acquired=false;
        try {
            acquired=lock.tryLock(0,TimeUnit.SECONDS);
            if(!acquired) {
                // 同一任务的另一条投递正在跑（重复投递、或 Broker 因消费超时重投）。
                // 这里必须确认而不是抛错：抛错会消耗重投次数，长任务的重复投递能把消息推进死信；
                // 而万一持有者正好挂掉，任务停在 RUNNING 时会由 StaleJobReaper 重新排队。
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("任务 {} 正由其他消费者执行，本条投递直接确认",id);
                return;
            }
            var job=jobs.find(id).orElse(null);
            if(job==null || job.status()==JobStatus.SUCCEEDED || job.status()==JobStatus.CANCELED) return;
            Integer active=jdbc.queryForObject("SELECT generation FROM analysis_job WHERE id=?",Integer.class,id);
            if(active==null || active!=generation) return; // Ignore deliveries from an earlier manual retry.
            try {
                pipeline.run(id);
            } catch(RuntimeException ex) {
                jobs.markFailed(id, com.videostudio.common.Texts.oneLine(ex.getMessage(),300));
                if(jobs.find(id).map(j -> j.status()==JobStatus.CANCELED).orElse(true)) return;
                if(message.getReconsumeTimes()>=3) {
                    jdbc.update("INSERT IGNORE INTO failed_delivery(job_id,generation,message_id,reason,created_at) VALUES (?,?,?,?,NOW(3))",
                        id,generation,message.getMsgId(),"自动重试次数已用尽；原始消息保留在 RocketMQ 死信队列");
                }
                throw ex; // Broker retry; after the limit RocketMQ moves the message to %DLQ%group.
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("消费者正在关闭",ex);
        } finally { if(acquired && lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
