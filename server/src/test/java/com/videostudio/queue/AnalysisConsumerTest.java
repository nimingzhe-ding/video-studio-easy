package com.videostudio.queue;
import com.videostudio.domain.*;
import com.videostudio.repository.JobRepository;
import com.videostudio.service.AnalysisPipeline;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisConsumerTest {
    @Test void duplicateCompletedMessageDoesNotRunAnalysisAgain() throws Exception {
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(true);
        var job=mock(AnalysisJob.class); when(job.status()).thenReturn(JobStatus.SUCCEEDED); when(jobs.find(7)).thenReturn(Optional.of(job));
        var message=new MessageExt(); message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        new AnalysisConsumer(jobs,pipeline,mock(JdbcTemplate.class),redis).onMessage(message);
        verifyNoInteractions(pipeline);
    }
    @Test void messageForRunningJobIsAcknowledgedInsteadOfRetried() throws Exception {
        // 重复投递（或 Broker 因消费超时重投）撞上正在执行的任务时，不能抛错：
        // 抛错会消耗重投次数，长任务可能因此被推进死信队列。确认掉即可，真正失联由回收器接住。
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(false);
        var message=new MessageExt(); message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(()->new AnalysisConsumer(jobs,pipeline,mock(JdbcTemplate.class),redis).onMessage(message));
        verifyNoInteractions(pipeline);
    }
    /**
     * 毒消息：jobId 非法时既不指向任何任务，也不会被任何重试路径接走。
     * 必须落库，否则运维只能从 Broker 日志里知道有消息被丢掉了。
     */
    @Test void invalidMessageIsRecordedInsteadOfSilentlyThrown() throws Exception {
        var jobs = mock(JobRepository.class); var pipeline = mock(AnalysisPipeline.class);
        var jdbc = mock(JdbcTemplate.class); var redis = mock(RedissonClient.class);
        var message = new MessageExt(); message.setMsgId("poison-1");
        message.setBody("{\"jobId\":0,\"generation\":0}".getBytes(StandardCharsets.UTF_8));

        // 直接确认，不抛错：原样重投还是非法消息，重试只会把这条毒消息再走一遍
        assertDoesNotThrow(() -> new AnalysisConsumer(jobs, pipeline, jdbc, redis).onMessage(message));

        verifyNoInteractions(pipeline);
        verify(jdbc).update(contains("failed_delivery"), eq(0L), eq(0), eq("poison-1"),
                contains("无效的任务消息"));
    }

    /** 消息体可能带上游塞进来的凭证，落库前必须抹掉 */
    @Test void invalidMessageBodyIsScrubbedBeforeRecording() throws Exception {
        var jobs = mock(JobRepository.class); var pipeline = mock(AnalysisPipeline.class);
        var jdbc = mock(JdbcTemplate.class); var redis = mock(RedissonClient.class);
        var message = new MessageExt(); message.setMsgId("poison-2");
        message.setBody(("{\"jobId\":-1,\"note\":\"Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz\"}")
                .getBytes(StandardCharsets.UTF_8));

        new AnalysisConsumer(jobs, pipeline, jdbc, redis).onMessage(message);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("failed_delivery"), any(), any(), eq("poison-2"), captor.capture());
        assertFalse(captor.getValue().contains("sk-abcdefghijklmnopqrstuvwxyz"),
                "消息体里的凭证不能原样进 failed_delivery");
    }

    @Test void exhaustedFailureIsRecordedAndStillThrownForBrokerDeadLetter() throws Exception {
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class); var jdbc=mock(JdbcTemplate.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(true);
        var job=mock(AnalysisJob.class); when(job.status()).thenReturn(JobStatus.FAILED); when(jobs.find(7)).thenReturn(Optional.of(job));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L))).thenReturn(1);
        doThrow(new IllegalStateException("provider unavailable")).when(pipeline).run(7);
        var message=new MessageExt(); message.setMsgId("delivery-1"); message.setReconsumeTimes(3);
        message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->new AnalysisConsumer(jobs,pipeline,jdbc,redis).onMessage(message));
        verify(jdbc).update(contains("failed_delivery"),eq(7L),eq(1),eq("delivery-1"),anyString());
    }
}
