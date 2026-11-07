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
        new AnalysisConsumer(jobs,pipeline,mock(JdbcTemplate.class),redis,mock(org.springframework.transaction.support.TransactionTemplate.class)).onMessage(message);
        verifyNoInteractions(pipeline);
    }
    @Test void messageForRunningJobIsAcknowledgedInsteadOfRetried() throws Exception {
        // 重复投递（或 Broker 因消费超时重投）撞上正在执行的任务时，不能抛错：
        // 抛错会消耗重投次数，长任务可能因此被推进死信队列。确认掉即可，真正失联由回收器接住。
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(false);
        var message=new MessageExt(); message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        var job=mock(AnalysisJob.class); when(job.status()).thenReturn(JobStatus.RUNNING); when(jobs.find(7)).thenReturn(Optional.of(job));
        var jdbc=mock(JdbcTemplate.class); when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L))).thenReturn(1);
        assertDoesNotThrow(()->new AnalysisConsumer(jobs,pipeline,jdbc,redis,mock(org.springframework.transaction.support.TransactionTemplate.class)).onMessage(message));
        verifyNoInteractions(pipeline);
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
        assertThrows(IllegalStateException.class,()->new AnalysisConsumer(jobs,pipeline,jdbc,redis,mock(org.springframework.transaction.support.TransactionTemplate.class)).onMessage(message));
        verify(jdbc).update(contains("failed_delivery"),eq(7L),eq(1),eq("delivery-1"),anyString());
    }
    @Test void busyPendingMessageMustNotBeAcknowledged() throws Exception {
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(false);
        var job=mock(AnalysisJob.class); when(job.status()).thenReturn(JobStatus.PENDING); when(jobs.find(7)).thenReturn(Optional.of(job));
        var jdbc=mock(JdbcTemplate.class); when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L))).thenReturn(1);
        var message=new MessageExt(); message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->new AnalysisConsumer(jobs,pipeline,jdbc,redis,mock(org.springframework.transaction.support.TransactionTemplate.class)).onMessage(message));
        verifyNoInteractions(pipeline);
    }
    @Test void exhaustedPendingLockConflictIsRecordedForManualRecovery() throws Exception {
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(false);
        var job=mock(AnalysisJob.class); when(job.status()).thenReturn(JobStatus.PENDING); when(jobs.find(7)).thenReturn(Optional.of(job));
        var jdbc=mock(JdbcTemplate.class); when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L))).thenReturn(1);
        when(jdbc.update(contains("UPDATE analysis_job"),anyString(),eq(7L),eq(1))).thenReturn(1);
        var tx=mock(org.springframework.transaction.support.TransactionTemplate.class);
        doAnswer(call -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action=call.getArgument(0);
            action.accept(new org.springframework.transaction.support.SimpleTransactionStatus()); return null;
        }).when(tx).executeWithoutResult(any());
        var message=new MessageExt(); message.setMsgId("busy-1"); message.setReconsumeTimes(3);
        message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->new AnalysisConsumer(jobs,pipeline,jdbc,redis,tx).onMessage(message));
        verify(jdbc).update(contains("failed_delivery"),eq(7L),eq(1),eq("busy-1"),anyString());
        verifyNoInteractions(pipeline);
    }
    @Test void rejectedModelRequestIsRecordedWithoutBrokerRetry() throws Exception {
        permanentErrorIsRecorded(new com.videostudio.llm.ModelRequestRejectedException(401));
    }
    @Test void permanentErrorIsRecordedWithoutBrokerRetry() throws Exception {
        permanentErrorIsRecorded(new com.videostudio.common.ApiException(429,"quota"));
    }
    private void permanentErrorIsRecorded(RuntimeException failure) throws Exception {
        var jobs=mock(JobRepository.class); var pipeline=mock(AnalysisPipeline.class);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(true);
        var job=mock(AnalysisJob.class); when(job.status()).thenReturn(JobStatus.FAILED); when(jobs.find(7)).thenReturn(Optional.of(job));
        var jdbc=mock(JdbcTemplate.class); when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(7L))).thenReturn(1);
        doThrow(failure).when(pipeline).run(7);
        var message=new MessageExt(); message.setMsgId("quota-7"); message.setBody("{\"jobId\":7,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(()->new AnalysisConsumer(jobs,pipeline,jdbc,redis,mock(org.springframework.transaction.support.TransactionTemplate.class)).onMessage(message));
        verify(jdbc).update(contains("failed_delivery"),eq(7L),eq(1),eq("quota-7"),contains("永久"));
    }
}
