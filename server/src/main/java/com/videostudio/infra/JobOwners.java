package com.videostudio.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务 → 归属用户。
 *
 * <p>预算计入用户维度时必须知道「这个任务是谁的」，而消费线程里没有 SecurityContext，
 * 只能回数据库查。归属关系一经写入不再变化，所以这里做进程内缓存：
 * 一个任务从排队到结束只会查一次，不会在每次模型调用时都打一次数据库。
 */
@Component
public class JobOwners {

    private static final Logger log = LoggerFactory.getLogger(JobOwners.class);

    private final JdbcTemplate jdbc;
    private final Map<Long, Long> cache = new ConcurrentHashMap<>();

    public JobOwners(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 归属用户 id；查不到（任务不存在、旧数据没有 owner_id、数据库抖动）返回 0 */
    public long ownerOf(long jobId) {
        if (jobId <= 0) {
            return 0;
        }
        Long cached = cache.get(jobId);
        if (cached != null) {
            return cached;
        }
        try {
            Long owner = jdbc.queryForObject("""
                    SELECT m.owner_id FROM analysis_job j JOIN media_asset m ON m.id = j.asset_id
                    WHERE j.id = ?
                    """, Long.class, jobId);
            if (owner == null || owner <= 0) {
                // 未归属的历史数据不缓存：运维认领之后应该立刻生效
                return 0;
            }
            cache.put(jobId, owner);
            return owner;
        } catch (RuntimeException ex) {
            // 查不到只影响「按用户限额」这一条判定，不能反过来让任务因为预算查询失败而中断
            log.debug("查询任务 {} 的归属失败: {}", jobId, ex.getMessage());
            return 0;
        }
    }

    /** 任务被删除时清掉缓存，避免 map 随任务数无限增长 */
    public void forget(long jobId) {
        cache.remove(jobId);
    }
}
