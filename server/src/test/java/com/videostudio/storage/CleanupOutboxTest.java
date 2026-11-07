package com.videostudio.storage;

import com.videostudio.retrieval.VectorStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class CleanupOutboxTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ObjectStore objects=mock(ObjectStore.class);
    private final VectorStore vectors=mock(VectorStore.class);
    private final RedissonClient locks=mock(RedissonClient.class);
    private CleanupOutbox outbox() { return new CleanupOutbox(jdbc,mock(TransactionTemplate.class),objects,vectors,locks); }
    @Test void cannotScheduleDeletionOutsideMetadataTransaction() {
        assertThrows(IllegalStateException.class,()->outbox().asset(7));
        verifyNoInteractions(objects,vectors,jdbc);
    }
    @Test void enqueueOnlyPersistsIntentAndDoesNotDeleteObjects() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { outbox().job(7,9); } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(jdbc,times(3)).update(contains("INSERT INTO storage_cleanup"),anyString(),anyString(),anyLong());
        verifyNoInteractions(objects,vectors);
    }
    @Test void oldUploadCleanupCannotDeleteReusedSession() {
        var lock=mock(RLock.class); when(locks.getLock("upload:u7-up")).thenReturn(lock); when(lock.tryLock()).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq("u7-up"))).thenReturn(List.of(Map.of("asset_id",22L)));
        outbox().execute("UPLOAD_PARTS","u7-up",21);
        verify(objects,never()).deletePrefix(anyString());
    }
    @Test void failedUploadPartsArePreservedWhileNewUploadIsIncomplete() {
        var lock=mock(RLock.class); when(locks.getLock(anyString())).thenReturn(lock); when(lock.tryLock()).thenReturn(true);
        var row=new java.util.HashMap<String,Object>(); row.put("asset_id",null);
        when(jdbc.queryForList(anyString(),anyString())).thenReturn(List.of(row));
        outbox().execute("UPLOAD_PARTS","u7-up",21);
        verifyNoInteractions(objects);
    }
    @Test void committedUploadCanBeCleanedIdempotently() {
        var lock=mock(RLock.class); when(locks.getLock(anyString())).thenReturn(lock); when(lock.tryLock()).thenReturn(true);
        when(jdbc.queryForList(anyString(),anyString())).thenReturn(List.of(Map.of("asset_id",21L)));
        outbox().execute("UPLOAD_PARTS","u7-up",21);
        verify(objects).deletePrefix("uploads/u7-up/");
    }
    @Test @SuppressWarnings("unchecked") void failedDeletionRemainsPendingAndCanBeRetried() {
        var transactions=mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
            ((TransactionCallback<Object>)call.getArgument(0)).doInTransaction(null));
        var row=Map.<String,Object>of("id",1L,"kind","PREFIX","object_key","assets/7/","reference_id",0L,"attempts",0);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row),List.of(),List.of(row),List.of());
        doThrow(new IllegalStateException("S3 unavailable")).doNothing().when(objects).deletePrefix("assets/7/");
        var dispatcher=new CleanupOutbox(jdbc,transactions,objects,vectors,locks);
        dispatcher.dispatch();
        verify(jdbc,never()).update(contains("status='DONE'"),anyLong(),anyString());
        verify(jdbc).update(contains("last_error=?,lease_token=NULL"),eq(1),eq(2),anyString(),eq(1L),anyString());
        dispatcher.dispatch();
        verify(jdbc).update(contains("status='DONE'"),eq(1L),anyString());
        verify(objects,times(2)).deletePrefix("assets/7/");
    }
}
