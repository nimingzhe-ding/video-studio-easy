package com.videostudio.service;

import com.videostudio.domain.AnalysisMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由的关键词兜底规则测试。
 * <p>模型路由依赖网络，无法在单测里验证；但「模型不可用时也要给出合理归类」这条底线必须锁住，
 * 否则一旦规则退化，用户写的「整理成复习笔记」会被静默当成「通用理解」。
 */
class ModeRouterTest {

    /** 只测 guess()：它不依赖 Spring，也不用构造 LlmGateway */
    private final ModeRouter router = new ModeRouter(new com.videostudio.llm.LlmGateway(new com.videostudio.config.AiProperties()), null);

    @Test
    void learningGoalRoutesToLearning() {
        assertEquals(AnalysisMode.LEARNING, router.guess("把这段视频整理成可以复习的学习笔记，标出概念和考点"));
    }

    @Test
    void reviewGoalRoutesToReview() {
        assertEquals(AnalysisMode.REVIEW, router.guess("评审这份访谈，找出结论是否有漏洞与风险，列待办"));
    }

    @Test
    void creationGoalRoutesToCreation() {
        assertEquals(AnalysisMode.CREATION, router.guess("从这段视频里提炼爆款选题、标题和口播脚本素材"));
    }

    @Test
    void neutralGoalFallsBackToGeneral() {
        assertEquals(AnalysisMode.GENERAL, router.guess("总结这段视频讲了什么"));
    }

    @Test
    void emptyGoalNeverThrows() {
        ModeRouter.Decision decision = router.route("   ");
        assertEquals(AnalysisMode.GENERAL, decision.mode());
        assertFalse(decision.byModel());
        assertTrue(decision.reason().contains("未提供分析目标"));
    }
}
