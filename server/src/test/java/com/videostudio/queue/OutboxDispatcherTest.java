package com.videostudio.queue;
import com.videostudio.infra.JobMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class OutboxDispatcherTest {

    /**
     * 用「直接在调用线程里跑回调」的 TransactionTemplate 代替真事务：
     * 这里测的是「发送失败要不要标记 SENT」这类业务分支，不是数据库的隔离级别。
     * 真事务语义由 MySQL 保证，集成测试里另有用例覆盖。
     */
    @SuppressWarnings("unchecked")
    private static TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        return template;
    }

    private static List<Map<String,Object>> oneRow() {
        return List.of(Map.of("id",1L,"job_id",7L,"generation",1,"attempts",0));
    }

    @Test void unconfirmedPublishRemainsRetryable() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(oneRow());
        when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("broker unavailable"));
        new OutboxDispatcher(jdbc,mq,"analysis",metrics,transactions()).dispatch();
        verify(jdbc,never()).update(startsWith("UPDATE job_outbox SET status='SENT'"),any(Object[].class));
        verify(jdbc).update(contains("next_attempt_at=TIMESTAMPADD"),eq(1),eq(2),anyString(),eq(1L));
    }
    @Test void brokerAcknowledgementMarksOutboxSent() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(oneRow());
        var result=new SendResult(); result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(result);
        new OutboxDispatcher(jdbc,mq,"analysis",metrics,transactions()).dispatch();
        verify(jdbc).update(startsWith("UPDATE job_outbox SET status='SENT'"),eq(1L));
    }
    @Test void backlogGaugesAreRefreshedEveryTick() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending",4L,"oldest",93L));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(2L);
        new OutboxDispatcher(jdbc,mq,"analysis",metrics,transactions()).dispatch();
        verify(metrics).outbox(4L,93L,2L);
        verify(metrics).invalidMessagesPending(2L);
    }

    /**
     * 领取阶段必须把 next_attempt_at 推出租期：否则这条记录在发送过程中仍然对其它实例可见，
     * 多实例部署时会重复投递。租期（60 秒）也必须明显大于发送超时（5 秒）。
     * <p>租期从 NOW() 起算而不是在旧值上累加：积压记录的 next_attempt_at 可能已经是几分钟前，
     * 加 60 秒仍然在过去，租约等于没生效。
     */
    @Test void claimedRowsAreLeasedAwayFromOtherInstances() {
        var jdbc=mock(JdbcTemplate.class); var mq=mock(RocketMQTemplate.class); var metrics=mock(JobMetrics.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(oneRow());
        var result=new SendResult(); result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(result);
        new OutboxDispatcher(jdbc,mq,"analysis",metrics,transactions()).dispatch();
        // LEASE_SECONDS 是 int，传给 jdbc.update 时装箱成 Integer，断言要用 eq(60) 而不是 eq(60L)
        verify(jdbc).update(contains("next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3))"),eq(60));
    }
}
