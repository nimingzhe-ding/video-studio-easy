package com.videostudio.service;

import com.videostudio.agent.ContextRenderer;
import com.videostudio.agent.PromptLibrary;
import com.videostudio.common.Texts;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图路由：把用户的自然语言分析目标归类到具体的 {@link AnalysisMode}。
 *
 * <p>两条设计约束：
 * <ol>
 *   <li><b>只产出具体模式。</b>路由在任务落库之前完成，任务上的 mode 一定是四个业务模式之一，
 *       不存在「待路由」中间态——否则读写两端会用不同的键，前端永远查不到结果。</li>
 *   <li><b>绝不阻断主流程。</b>模型不可用、超时、返回非法值，统统回退到确定性关键词规则，
 *       再不行才落到 GENERAL。本类对外不抛异常。</li>
 * </ol>
 */
@Service
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);

    /** 关键词兜底规则：模型不可用时也能给出合理归类，顺序即优先级 */
    private static final Map<AnalysisMode, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(AnalysisMode.LEARNING, List.of("学习", "笔记", "复习", "概念", "考点", "知识", "教程",
                "原理", "入门", "讲解", "课程", "自测", "理解"));
        KEYWORDS.put(AnalysisMode.REVIEW, List.of("评审", "审查", "复盘", "风险", "问题", "决策", "待办",
                "分歧", "质疑", "漏洞", "会议", "访谈", "结论是否"));
        KEYWORDS.put(AnalysisMode.CREATION, List.of("创作", "素材", "标题", "脚本", "爆款", "剪辑", "二创",
                "口播", "文案", "选题", "切片", "钩子"));
    }

    private final LlmGateway llm;
    private final PromptLibrary prompts;

    public ModeRouter(LlmGateway llm, PromptLibrary prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    /**
     * @param reason 面向用户的判定理由（已做长度裁剪），永远非空
     */
    public record Decision(AnalysisMode mode, String reason, boolean byModel) {
    }

    public Decision route(String goal) {
        String text = goal == null ? "" : goal.strip();
        if (text.isEmpty()) {
            return new Decision(AnalysisMode.GENERAL, "未提供分析目标，按通用模式分析", false);
        }
        if (llm.enabled()) {
            try {
                String user = prompts.get("mode-router") + "\n\n用户目标：" + Texts.truncate(text, 500);
                LlmGateway.ChatResult result = llm.chat(List.of(
                        LlmGateway.Message.system(prompts.get("system")),
                        LlmGateway.Message.user(user)), 0.0, 200, "mode-route");
                String raw = result == null ? null : result.content();
                AnalysisMode mode = strictMode(raw);
                if (mode != null) {
                    String reason = Texts.oneLine(ContextRenderer.parseModeReason(raw), 60);
                    return new Decision(mode, reason.isBlank() ? fallbackReason(mode) : reason, true);
                }
            } catch (RuntimeException e) {
                // 路由只是增强：失败一次不影响主流程，直接走关键词规则
                log.warn("自动模式路由失败，回退关键词规则：{}", Texts.oneLine(e.getMessage(), 120));
            }
        }
        AnalysisMode mode = guess(text);
        return new Decision(mode, fallbackReason(mode), false);
    }

    /** 严格解析：模型没给出可识别的模式名时返回 null，交由调用方决定回退方式（而不是静默变成 GENERAL） */
    private static AnalysisMode strictMode(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        String upper = text.toUpperCase();
        for (AnalysisMode mode : AnalysisMode.values()) {
            if (upper.contains("\"" + mode.name() + "\"")) {
                return mode;
            }
        }
        return null;
    }

    /** 关键词兜底：统计各模式命中词数量，取最高分；全不命中则 GENERAL */
    AnalysisMode guess(String goal) {
        AnalysisMode best = AnalysisMode.GENERAL;
        int bestHits = 0;
        for (Map.Entry<AnalysisMode, List<String>> entry : KEYWORDS.entrySet()) {
            int hits = 0;
            for (String keyword : entry.getValue()) {
                if (goal.contains(keyword)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static String fallbackReason(AnalysisMode mode) {
        return switch (mode) {
            case LEARNING -> "目标偏向知识梳理与复习，选学习笔记";
            case REVIEW -> "目标偏向查错与结论核验，选评审纪要";
            case CREATION -> "目标偏向内容再创作，选创作素材";
            case GENERAL -> "目标较为通用，选通用理解";
        };
    }
}
