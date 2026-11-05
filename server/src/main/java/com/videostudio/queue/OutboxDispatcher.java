package com.videostudio.queue;

import com.videostudio.common.Json;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.LoggerFactory;

@Component
public class OutboxDispatcher {
    private final JdbcTemplate jdbc; private final RocketMQTemplate mq; private final String topic;
    private final com.videostudio.infra.JobMetrics metrics;
    public OutboxDispatcher(JdbcTemplate jdbc,RocketMQTemplate mq,@Value("${rocketmq.topic.analysis}") String topic,
                            com.videostudio.infra.JobMetrics metrics) {
        this.jdbc=jdbc; this.mq=mq; this.topic=topic; this.metrics=metrics;
    }
    @Scheduled(fixedDelayString="${queue.outbox-interval-ms:2000}")
    @Transactional
    public void dispatch() {
        var rows=jdbc.queryForList("SELECT id,job_id,generation,attempts FROM job_outbox WHERE status='PENDING' AND next_attempt_at<=NOW(3) ORDER BY id LIMIT 10 FOR UPDATE SKIP LOCKED");
        for(var row:rows) {
            long id=((Number)row.get("id")).longValue();
            long job=((Number)row.get("job_id")).longValue();
            int generation=((Number)row.get("generation")).intValue();
            try {
                var message=MessageBuilder.withPayload(Json.write(new DispatchMessage(job,generation)))
                    .setHeader("KEYS",job+":"+generation).build();
                var sent=mq.syncSend(topic,message,5000);
                if(sent.getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException("Broker 未确认持久化消息");
                jdbc.update("UPDATE job_outbox SET status='SENT',sent_at=NOW(3),last_error=NULL WHERE id=?",id);
            } catch(RuntimeException ex) {
                int attempts=((Number)row.get("attempts")).intValue()+1;
                jdbc.update("UPDATE job_outbox SET attempts=?,next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3)),last_error=? WHERE id=?",
                    attempts,Math.min(300,1<<Math.min(attempts,8)),"RocketMQ 投递未确认，将自动重试",id);
                LoggerFactory.getLogger(getClass()).warn("RocketMQ dispatch failed outbox={}",id,ex);
            }
        }
        refreshBacklog();
    }

    /**
     * 把积压量、最老记录年龄和死信数量刷进指标。
     * <p>积压是「消息发不出去」时最早能看出来的信号：Broker 不可用时 PENDING 只增不减，
     * 而最老记录年龄能区分「瞬时抖动」和「链路已经断了」。
     */
    private void refreshBacklog() {
        try {
            var row=jdbc.queryForMap("""
                SELECT COUNT(*) AS pending,
                       COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),NOW(3)),0) AS oldest
                FROM job_outbox WHERE status='PENDING'
                """);
            Long failed=jdbc.queryForObject("SELECT COUNT(*) FROM failed_delivery",Long.class);
            metrics.outbox(((Number)row.get("pending")).longValue(),((Number)row.get("oldest")).longValue(),
                failed==null?0:failed);
        } catch(RuntimeException ex) {
            // 指标刷新失败只影响监控，不能反过来打断投递
            LoggerFactory.getLogger(getClass()).debug("刷新发件箱指标失败",ex);
        }
    }
}
