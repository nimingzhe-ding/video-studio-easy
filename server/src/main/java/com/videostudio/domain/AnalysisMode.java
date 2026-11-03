package com.videostudio.domain;

import java.util.List;

/** 分析模式：决定 prompt 基调与文档结构 */
public enum AnalysisMode {
    GENERAL("通用理解", "面向任意长视频/长音频，输出内容摘要、主题脉络、关键结论与可复用的时间戳证据"),
    LEARNING("学习笔记", "把内容整理成可复习的学习笔记：概念、例子、要点、易错点，按知识点组织"),
    REVIEW("评审纪要", "面向会议/访谈/评审，输出议题、各方观点、结论与待办事项，标注发言人（可用时）"),
    CREATION("创作素材", "面向内容创作，提炼金句、钩子、结构节奏与可二次创作的素材清单");

    private final String label;
    private final String guidance;

    AnalysisMode(String label, String guidance) {
        this.label = label;
        this.guidance = guidance;
    }

    public String label() {
        return label;
    }

    public String guidance() {
        return guidance;
    }

    public static AnalysisMode of(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }

    public List<String> outlineHints() {
        return switch (this) {
            case LEARNING -> List.of("一句话概览", "核心概念清单", "知识点详解", "示例与演示片段", "复习自测问题", "延伸阅读线索");
            case REVIEW -> List.of("一句话概览", "议题与背景", "讨论要点", "结论与决策", "待办与责任人", "风险与遗留问题");
            case CREATION -> List.of("一句话概览", "内容骨架", "金句与钩子", "可二次创作素材", "节奏与结构分析", "选题建议");
            case GENERAL -> List.of("一句话概览", "内容脉络", "关键结论", "重点片段", "值得追问的问题");
        };
    }
}
