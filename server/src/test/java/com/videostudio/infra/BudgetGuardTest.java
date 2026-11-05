package com.videostudio.infra;

import com.videostudio.config.AppProperties;
import com.videostudio.config.BudgetProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预算护栏：单任务超限要能立刻停止，并且只上报一次超限 */
class BudgetGuardTest {

    private static BudgetGuard guard(long perJob, long perDay) {
        BudgetProperties props = new BudgetProperties();
        props.setMaxTokensPerJob(perJob);
        props.setMaxTokensPerDay(perDay);
        props.setPricePerMillionTokens(2.0);
        return new BudgetGuard(props, new JobMetrics(new SimpleMeterRegistry()),
                new RedisSupport(new AppProperties(), null));
    }

    @Test
    void consumeReturnsTrueOnlyAfterLimitIsExceeded() {
        BudgetGuard guard = guard(100, 0);
        guard.begin(1);
        assertFalse(guard.consume(1, 60));
        assertFalse(guard.consume(1, 30));
        assertTrue(guard.consume(1, 20), "累计 110 > 100 应返回超限");
        assertEquals(110, guard.used(1));
    }

    @Test
    void exceededFlagDrivesAgentLoop() {
        BudgetGuard guard = guard(50, 0);
        guard.begin(2);
        assertFalse(guard.exceeded(2), "刚开始不该判为超限");
        guard.consume(2, 80);
        assertTrue(guard.exceeded(2), "超限后 Agent 应停止继续请求模型");
    }

    @Test
    void beginResetsPreviousUsage() {
        BudgetGuard guard = guard(100, 0);
        guard.begin(3);
        guard.consume(3, 90);
        guard.begin(3);
        assertEquals(0, guard.used(3), "重试开始时应清零，否则重试一定立刻超限");
        assertFalse(guard.exceeded(3));
    }

    @Test
    void dailyCapRejectsNewJobs() {
        BudgetGuard guard = guard(0, 100);
        guard.begin(4);
        assertTrue(guard.rejectReason() == null, "未超日额度时应允许提交");
        guard.consume(4, 120);
        String reason = guard.rejectReason();
        assertTrue(reason != null && reason.contains("今日模型配额已用尽"), "应给出可读的拒绝原因：" + reason);
    }

    @Test
    void costIsProportionalToTokens() {
        BudgetGuard guard = guard(1000, 0);
        assertEquals(2.0, guard.costOf(1_000_000), 1e-9);
        assertEquals(0.5, guard.costOf(250_000), 1e-9);
    }

    @Test
    void consumeCurrentUsesThreadContext() {
        BudgetGuard guard = guard(100, 0);
        guard.begin(5);
        guard.enter(5);
        try {
            guard.consumeCurrent(40);
        } finally {
            guard.leave();
        }
        assertEquals(40, guard.used(5), "模型网关只调 consumeCurrent，必须记到当前任务上");
    }
}
