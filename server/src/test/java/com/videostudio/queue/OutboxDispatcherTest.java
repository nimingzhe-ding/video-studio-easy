package com.videostudio.queue;
import com.videostudio.infra.JobMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class OutboxDispatcherTest {
    @Test void unconfirmedPublishRemainsRetryable() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",1L,"job_id",7L,"generation",1,"attempts",0)));
        when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("broker unavailable"));
        new OutboxDispatcher(jdbc,mq,"analysis",metrics).dispatch();
        verify(jdbc,never()).update(contains("status='SENT'"),any(Object[].class));
        verify(jdbc).update(contains("next_attempt_at=TIMESTAMPADD"),eq(1),eq(2),anyString(),eq(1L));
    }
    @Test void brokerAcknowledgementMarksOutboxSent() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",1L,"job_id",7L,"generation",1,"attempts",0)));
        var result=new SendResult(); result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(result);
        new OutboxDispatcher(jdbc,mq,"analysis",metrics).dispatch();
        verify(jdbc).update(contains("status='SENT'"),eq(1L));
    }
    @Test void backlogGaugesAreRefreshedEveryTick() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending",4L,"oldest",93L));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(2L);
        new OutboxDispatcher(jdbc,mq,"analysis",metrics).dispatch();
        verify(metrics).outbox(4L,93L,2L);
    }
}
