package com.videostudio.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败投递的运维面：查询、重投，以及「不能重复重投」这条约束。
 */
class FailedDeliveryServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JobService jobs = mock(JobService.class);

    @SuppressWarnings("unchecked")
    private final TransactionTemplate transactions = transactions();

    @SuppressWarnings("unchecked")
    private static TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        org.mockito.Mockito.doCallRealMethod().when(template).executeWithoutResult(any());
        return template;
    }

    private FailedDeliveryService service() {
        return new FailedDeliveryService(jdbc, transactions, jobs);
    }

    /** 模拟 SELECT 出来的一行；replayed_at 为 null 表示尚未重投 */
    private Map<String, Object> row(Object replayedAt) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("job_id", 7L);
        map.put("generation", 2);
        map.put("replayed_at", replayedAt);
        map.put("failure_kind", "TASK_FAILURE");
        return map;
    }

    @Test
    void replayRequeuesTheJobAndStampsTheRecord() {
        when(jdbc.queryForList(contains("FROM failed_delivery WHERE id = ?"), eq(99L)))
                .thenReturn(List.of(row(null)));

        long jobId = service().replay(99L, 3L);

        assertEquals(7L, jobId);
        verify(jobs).retryAsAdmin(7L);
        verify(jdbc).update(contains("replayed_at = NOW(3)"), eq(3L), eq(99L));
    }

    /** 重投会再次消耗模型额度，重复点击必须挡住，否则一条死信能被无限重放 */
    @Test
    void alreadyReplayedRecordIsRejected() {
        when(jdbc.queryForList(contains("FROM failed_delivery WHERE id = ?"), eq(99L)))
                .thenReturn(List.of(row(Timestamp.from(Instant.now()))));

        assertThrows(com.videostudio.common.ApiException.class, () -> service().replay(99L, 3L));
        verify(jobs, never()).retryAsAdmin(anyLong());
    }

    @Test
    void unknownRecordReturnsNotFound() {
        when(jdbc.queryForList(contains("FROM failed_delivery WHERE id = ?"), eq(99L)))
                .thenReturn(List.of());

        assertThrows(com.videostudio.common.ApiException.class, () -> service().replay(99L, 3L));
        verify(jobs, never()).retryAsAdmin(anyLong());
    }

    @Test void invalidMessageCannotStartAnAnalysis() {
        var invalid=row(null); invalid.put("failure_kind","INVALID_MESSAGE"); invalid.put("job_id",0L);
        when(jdbc.queryForList(contains("FROM failed_delivery WHERE id = ?"),eq(99L))).thenReturn(List.of(invalid));
        assertThrows(com.videostudio.common.ApiException.class,()->service().replay(99,3));
        verify(jobs,never()).retryAsAdmin(anyLong());
    }
    @Test void resolvingInvalidMessageRetainsAuditAndRecordsOperator() {
        when(jdbc.queryForList(contains("FROM failed_delivery WHERE id = ?"),eq(99L)))
            .thenReturn(List.of(Map.of("failure_kind","INVALID_MESSAGE")));
        service().resolve(99,3);
        verify(jdbc).update(contains("SET resolved_at=NOW(3),resolved_by=?"),eq(3L),eq(99L));
        verify(jobs,never()).retryAsAdmin(anyLong());
    }
    @Test void taskFailureCannotBeHiddenByResolvingIt() {
        when(jdbc.queryForList(contains("FROM failed_delivery WHERE id = ?"),eq(99L))).thenReturn(List.of(row(null)));
        assertThrows(com.videostudio.common.ApiException.class,()->service().resolve(99,3));
        verify(jdbc,never()).update(contains("SET resolved_at"),any(),any());
    }

    /** 默认只看未重投的：已经处理过的记录留在表里作为审计痕迹，但不该继续占据运维视线 */
    @Test
    void pendingOnlyExcludesAlreadyReplayedRows() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any())).thenReturn(List.of());

        service().list(true, 50);

        verify(jdbc).query(contains("WHERE replayed_at IS NULL AND resolved_at IS NULL"),
                any(org.springframework.jdbc.core.RowMapper.class), eq(50));
    }
}
