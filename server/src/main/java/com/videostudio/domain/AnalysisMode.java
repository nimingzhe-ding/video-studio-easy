package com.videostudio.domain;

import java.util.List;

/**
 * 分析模式：决定提示词基调、必需小节与默认目标。
 *
 * <p>不再是 Java 枚举——模式是一个可配置的值对象，新增模式只需要在
 * {@code modes.json} 里加一条，不需要改代码、不需要重新打包。
 * 这里的静态常量只是<b>内置默认值</b>，供单测和配置缺失时兜底；同名条目在
 * 配置文件里出现时以配置为准。
 *
 * @param code         存库与 API 里使用的英文标识，必须唯一且形如大写下划线
 * @param label        界面上展示的名字
 * @param guidance     交给 Planner / Executor 的模式约束
 * @param sectionKeys  证据校验要求必须出现的小节 key；为空表示不强制
 * @param defaultGoal  用户没有填写分析目标时使用的默认目标
 */
public record AnalysisMode(String code, String label, String guidance,
                           List<String> sectionKeys, String defaultGoal) {

    public AnalysisMode {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("分析模式标识必须是大写下划线形式: " + code);
        }
        sectionKeys = sectionKeys == null ? List.of() : List.copyOf(sectionKeys);
    }

    public static final AnalysisMode GENERAL = new AnalysisMode("GENERAL", "通用理解",
            "面向任意长视频/长音频，输出内容摘要、主题脉络、关键结论与可复用的时间戳证据",
            List.of(),
            "总结核心内容，给出关键结论及对应的时间戳证据");

    public static final AnalysisMode LEARNING = new AnalysisMode("LEARNING", "学习笔记",
            "把内容整理成可复习的学习笔记：概念、例子、要点、易错点，按知识点组织",
            List.of("outline", "keypoints", "quiz", "pitfalls"),
            "整理为学习笔记，覆盖核心概念、例子与易错点");

    public static final AnalysisMode REVIEW = new AnalysisMode("REVIEW", "评审纪要",
            "面向会议/访谈/评审，输出议题、各方观点、结论与待办事项，标注发言人（可用时）",
            List.of("fallacies", "exaggerations", "omissions", "doubtful"),
            "整理为评审纪要，覆盖观点、结论、待办与风险");

    public static final AnalysisMode CREATION = new AnalysisMode("CREATION", "创作素材",
            "面向内容创作，提炼金句、钩子、结构节奏与可二次创作的素材清单",
            List.of("highlights", "titles", "intro", "script"),
            "提炼可用于二次创作的金句、钩子和内容结构");

    /** 内置模式，注册表在读取配置前以它为底 */
    public static List<AnalysisMode> builtins() {
        return List.of(GENERAL, LEARNING, REVIEW, CREATION);
    }

    /**
     * 从数据库读出的历史值还原成对象。
     *
     * <p>与「用户请求里传了非法模式」不同：那些值已经被校验过，库里出现未知 code
     * 说明模式被从配置里移除了。这时保留原始 code（label 退回 code）而不是悄悄变成
     * GENERAL——历史报告应该如实显示它当初用的是哪个模式。
     */
    public static AnalysisMode ofCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        String code = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (AnalysisMode mode : builtins()) {
            if (mode.code().equals(code)) {
                return mode;
            }
        }
        String safe = code.matches("[A-Z][A-Z0-9_]{0,31}") ? code : "GENERAL";
        return new AnalysisMode(safe, safe, "", List.of(), "");
    }
}
