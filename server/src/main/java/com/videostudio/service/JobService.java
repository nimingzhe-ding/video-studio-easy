package com.videostudio.service;

import com.videostudio.common.ApiException;
import com.videostudio.domain.*;
import com.videostudio.infra.BudgetGuard;
import com.videostudio.infra.RedisSupport;
import com.videostudio.repository.JobRepository;
import com.videostudio.security.OwnerGuard;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Service
public class JobService {
    private final JobRepository jobs; private final ModeRouter router; private final BudgetGuard budget;
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    private final RedisSupport redis; private final OwnerGuard owner; private final RedissonClient locks;
    private final com.videostudio.security.CurrentUser current;
    public JobService(JobRepository jobs,ModeRouter router,BudgetGuard budget,JdbcTemplate jdbc,
            TransactionTemplate transactions,RedisSupport redis,OwnerGuard owner,RedissonClient locks,
            com.videostudio.security.CurrentUser current) {
        this.jobs=jobs; this.router=router; this.budget=budget; this.jdbc=jdbc;
        this.transactions=transactions; this.redis=redis; this.owner=owner; this.locks=locks;
        this.current=current;
    }
    public AnalysisJob submit(long assetId,AnalysisMode mode,String goal,String title) {
        owner.media(assetId);
        requireBudget();
        if(goal!=null && goal.length()>8000) throw new ApiException("分析目标不能超过 8000 字");
        if(title!=null && title.length()>512) throw new ApiException("标题不能超过 512 字");
        var decision=mode==null?router.route(goal):new ModeRouter.Decision(mode,"用户指定",false);
        String focus=goal==null||goal.isBlank()?defaultGoal(decision.mode()):goal.strip();
        String key="submit:"+assetId+":"+digest(decision.mode()+":"+focus);
        var token=redis.tryLock(key,redis.lockTtl()).orElseThrow(() -> ApiException.conflict("相同分析正在提交"));
        try {
            return transactions.execute(status -> {
                jdbc.queryForObject("SELECT id FROM media_asset WHERE id=? FOR UPDATE",Long.class,assetId);
                var active=jdbc.queryForList("SELECT id FROM analysis_job WHERE asset_id=? AND mode=? AND focus=? AND status IN ('PENDING','RUNNING') LIMIT 1",
                    Long.class,assetId,decision.mode().name(),focus);
                if(!active.isEmpty()) return jobs.find(active.get(0)).orElseThrow();
                long id=jobs.insert(assetId,decision.mode(),mode==null?"AUTO":"USER",decision.reason(),title,focus);
                enqueue(id);
                return jobs.find(id).orElseThrow();
            });
        } finally { redis.unlock(key,token); }
    }
    public AnalysisJob reanalyze(long jobId,String goal,AnalysisMode mode) {
        owner.job(jobId);
        var old=jobs.find(jobId).orElseThrow();
        return submit(old.assetId(),mode==null?old.mode():mode,goal==null?old.focus():goal,old.title());
    }
    public AnalysisJob retry(long jobId) {
        owner.job(jobId);
        return retryInternal(jobId, true);
    }

    /**
     * 管理员重投：跳过归属校验与额度检查，但保留与用户重试完全相同的状态机
     * （递增 generation + 写发件箱）。
     * <p>不查额度是有意的：管理员清理的是已经花过钱的死信，如果卡在今日额度上，
     * 死信就永远清不掉，而这恰恰是运维最需要恢复的时候。
     */
    public AnalysisJob retryAsAdmin(long jobId) {
        return retryInternal(jobId, false);
    }

    private AnalysisJob retryInternal(long jobId, boolean checkBudget) {
        if (checkBudget) {
            requireBudget();
        }
        var lock=locks.getLock("worker:job:"+jobId);
        boolean acquired=false;
        try {
            acquired=lock.tryLock(0,TimeUnit.SECONDS);
            if(!acquired) throw ApiException.conflict("任务仍在停止或执行中，请稍后重试");
            return transactions.execute(status -> {
                int updated=jdbc.update("UPDATE analysis_job SET status='PENDING',generation=generation+1,error=NULL,finished_at=NULL,updated_at=NOW(3) WHERE id=? AND status IN ('FAILED','CANCELED')",jobId);
                if(updated!=1) throw ApiException.conflict("仅失败或已取消的任务可以重试");
                enqueue(jobId);
                return jobs.find(jobId).orElseThrow();
            });
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw ApiException.conflict("操作被中断"); }
        finally { if(acquired) lock.unlock(); }
    }
    private void enqueue(long id) {
        jdbc.update("INSERT INTO job_outbox(job_id,generation,status,next_attempt_at,created_at) SELECT id,generation,'PENDING',NOW(3),NOW(3) FROM analysis_job WHERE id=?",id);
    }
    public boolean cancel(long id) {
        owner.job(id);
        return jdbc.update("UPDATE analysis_job SET status='CANCELED',updated_at=NOW(3),finished_at=NOW(3) WHERE id=? AND status IN ('PENDING','RUNNING','FAILED')",id)>0;
    }
    /** 提交前检查额度：全站日额度 + 当前用户日额度 */
    private void requireBudget() { String reason=budget.rejectReason(current.id()); if(reason!=null) throw new ApiException(429,reason); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private String defaultGoal(AnalysisMode mode) {
        return switch(mode) {
            case LEARNING -> "整理为学习笔记，覆盖核心概念、例子与易错点";
            case REVIEW -> "整理为评审纪要，覆盖观点、结论、待办与风险";
            case CREATION -> "提炼可用于二次创作的金句、钩子和内容结构";
            case GENERAL -> "总结核心内容，给出关键结论及对应的时间戳证据";
        };
    }
}
