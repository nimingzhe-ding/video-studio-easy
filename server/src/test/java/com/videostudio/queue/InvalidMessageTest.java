package com.videostudio.queue;

import com.videostudio.repository.JobRepository;
import com.videostudio.service.AnalysisPipeline;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InvalidMessageTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final JobRepository jobs=mock(JobRepository.class);
    private final AnalysisPipeline pipeline=mock(AnalysisPipeline.class);
    private final RedissonClient redis=mock(RedissonClient.class);
    private AnalysisConsumer consumer() { return new AnalysisConsumer(jobs,pipeline,jdbc,redis,mock(TransactionTemplate.class)); }
    private MessageExt message(String body) {
        var message=new MessageExt(); message.setMsgId("invalid-1");
        message.setBody(body.getBytes(StandardCharsets.UTF_8)); return message;
    }
    @ParameterizedTest @ValueSource(strings={
        "", "null", "[]", "{", "{}", "{\"jobId\":7}",
        "{\"jobId\":\"7\",\"generation\":1}", "{\"jobId\":7.1,\"generation\":1}",
        "{\"jobId\":7,\"generation\":0}", "{\"jobId\":9223372036854775808,\"generation\":1}",
        "{\"jobId\":7,\"generation\":2147483648}",
        "{\"jobId\":7,\"generation\":1} {}", "{\"jobId\":7,\"jobId\":8,\"generation\":1}",
        "{api_key=secret-payload-123}"
    }) void invalidBodyIsAuditedWithoutCallingInfrastructureOrModel(String body) {
        assertDoesNotThrow(()->consumer().onMessage(message(body)));
        verify(jdbc).update(contains("'INVALID_MESSAGE'"),eq("invalid-1"),argThat((String reason)->!reason.contains("secret-payload-123")),matches("[0-9a-f]{64}"));
        verifyNoInteractions(jobs,pipeline,redis);
    }
    @Test void failedAuditMustNotAcknowledgeTheMessage() {
        when(jdbc.update(anyString(),anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("MySQL unavailable"));
        assertThrows(IllegalStateException.class,()->consumer().onMessage(message("{")));
        verifyNoInteractions(jobs,pipeline,redis);
    }
    @Test void oversizedMessagesAreAuditedWithoutParsingTheirContents() {
        consumer().onMessage(message("x".repeat(65_537)));
        verify(jdbc).update(contains("'INVALID_MESSAGE'"),eq("invalid-1"),contains("大小限制"),anyString());
    }
    @Test void missingBrokerIdUsesStableBodyDigestRatherThanRandomIds() {
        var message=message("invalid"); message.setMsgId(null);
        consumer().onMessage(message); consumer().onMessage(message);
        verify(jdbc,times(2)).update(contains("INSERT IGNORE"),eq("body:"+AnalysisMessage.digest(message.getBody())),anyString(),anyString());
    }
    @Test void contractAcceptsIntegralIdsOnlyAndPreservesLongValues() {
        var parsed=AnalysisMessage.parse("{\"jobId\":9223372036854775807,\"generation\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.MAX_VALUE,parsed.jobId()); assertEquals(1,parsed.generation());
    }
}
