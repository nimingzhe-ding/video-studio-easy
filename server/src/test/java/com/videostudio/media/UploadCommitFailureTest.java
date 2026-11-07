package com.videostudio.media;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.*;
import com.videostudio.repository.UploadSessionRepository;
import com.videostudio.security.CurrentUser;
import com.videostudio.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadCommitFailureTest {
    @TempDir Path temp;
    @Test void failedMetadataCommitCannotDestroyUploadedPartsOrWorkCopy() throws Exception {
        var props=new AppProperties(); props.getStorage().setRoot(temp.toString());
        var storage=new Storage(props);
        var repository=mock(UploadSessionRepository.class);
        var ingest=mock(MediaIngestService.class);
        var objects=mock(ObjectStore.class);
        var cleanup=mock(CleanupOutbox.class);
        var user=mock(CurrentUser.class); when(user.id()).thenReturn(7L);
        var redis=mock(RedissonClient.class); var lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        var now=Instant.now();
        var session=new UploadSession("u7-up","clip.mp4",3,1,3,1,null,now,now);
        when(repository.find("u7-up")).thenReturn(Optional.of(session));
        when(repository.receivedIndexes("u7-up")).thenReturn(List.of(0));
        when(objects.materialize(anyString(),any(Path.class))).thenAnswer(call -> {
            Path target=call.getArgument(1); Files.write(target,new byte[]{1,2,3}); return Optional.of(target);
        });
        var asset=mock(MediaAsset.class); when(asset.id()).thenReturn(21L);
        when(ingest.ingestFile(any(),anyString())).thenReturn(asset);
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx,TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) { throw new TransactionSystemException("commit failed"); }
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        var service=new ChunkedUploadService(repository,ingest,storage,props,objects,user,redis,new TransactionTemplate(manager),cleanup);
        assertThrows(TransactionSystemException.class,()->service.complete("up"));
        verify(cleanup).upload("u7-up",21);
        verify(objects,never()).deletePrefix(anyString());
        assertTrue(Files.exists(storage.uploadChunk("u7-up",0)),"回滚后仍应能续传或重新合并");
    }
}
