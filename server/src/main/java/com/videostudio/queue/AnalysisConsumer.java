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
        if(id<=0 || generation<=0) {
            // 毒消息：这条投递不指向任何任务，既不会让任务失败，也不会被任何重试路径接走。
            // 必须显式落库，否则运维只能从 Broker 的日志里知道「有消息被丢掉了」。
            // 重投没有意义——原样再投一次还是非法消息，所以这里直接确认、只留记录。
            recordInvalidMessage(message, id, generation);
            return;
        }
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
                // 脱敏在截断之前：这段文本会入库并通过接口展示给用户
                jobs.markFailed(id, com.videostudio.common.Secrets.scrubOneLine(ex.getMessage(),300));
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

    /**
     * 把无法解析的任务消息记进 failed_delivery。
     *
     * <p>job_id 用 0 占位：这条记录不属于任何任务，但表要求非空。
     * 正文只留前 200 字并脱敏——消息体可能带着上游塞进来的凭证。
     * 写库失败不抛错：记不了账也不该让消费线程反复重试同一条毒消息。
     */
    private void recordInvalidMessage(MessageExt message, long jobId, int generation) {
        try {
            String body = com.videostudio.common.Secrets.scrubOneLine(
                    new String(message.getBody(), StandardCharsets.UTF_8), 200);
            jdbc.update("""
                    INSERT IGNORE INTO failed_delivery(job_id,generation,message_id,reason,created_at)
                    VALUES (?,?,?,?,NOW(3))
                    """, Math.max(0, jobId), Math.max(0, generation), message.getMsgId(),
                    "无效的任务消息（jobId/generation 非法），内容：" + body);
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("收到无效的任务消息 msgId={} jobId={} generation={}，已记录待人工处理",
                            message.getMsgId(), jobId, generation);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("记录无效任务消息失败 msgId={}", message.getMsgId(), ex);
        }
    }
}
