package com.videostudio.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;
import java.util.Comparator;

/** Reclaims disposable work files; durable copies are always in object storage. */
@Component
public class WorkFileJanitor {
    private final Storage storage; private final JdbcTemplate jdbc;
    public WorkFileJanitor(Storage storage,JdbcTemplate jdbc) { this.storage=storage; this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${app.storage.cleanup-interval-ms:3600000}")
    public void clean() {
        Path media=storage.root().resolve("media").toAbsolutePath().normalize();
        if(!Files.isDirectory(media)) return;
        try(var children=Files.list(media)) {
            for(Path dir:children.toList()) {
                String name=dir.getFileName().toString();
                if(!name.matches("[0-9]+") || !Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)) continue;
                long id=Long.parseLong(name);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job WHERE asset_id=? AND status IN ('PENDING','RUNNING')",Long.class,id)>0) continue;
                try(var tree=Files.walk(dir)) {
                    var files=tree.toList();
                    Instant newest=Instant.EPOCH;
                    for(Path file:files) {
                        Instant modified=Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant();
                        if(modified.isAfter(newest)) newest=modified;
                    }
                    if(newest.isAfter(Instant.now().minus(Duration.ofHours(24)))) continue;
                    for(Path file:files.stream().sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                }
            }
        } catch(Exception ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("临时工作文件清理失败，将在下次重试",ex); }
    }
}
