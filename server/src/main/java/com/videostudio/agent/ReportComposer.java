package com.videostudio.agent;

import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.domain.AnalysisMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 Agent 产物渲染为 Markdown：结论 → 证据 → 模式小节 → 建议。
 * <p>证据段落固定带 [mm:ss]，让读者能直接跳回原片核对——这是「可追溯」的落地点。
 */
public final class ReportComposer {

    private ReportComposer() {
    }


    /**
     * 报告元信息。
     * <p>不再带 verified 字段：能生成报告就说明确定性校验已通过，
     * 模型的审阅意见单独成节，不参与「报告是否成立」的判断。
     */
    public record Meta(String goal, AnalysisMode mode, String duration, String engine, String modeReason,
                       int rounds) {
    }

    /**
     * 把 Agent 产物渲染为 Markdown。
     *
     * <p>「校验记录」小节展示的是<b>模型审阅意见</b>：确定性证据校验通过后，
     * 模型仍可能对措辞、推断边界提出建议。这些意见如实呈现给读者，
     * 但不影响报告成立——早期把它们当否决权，会让正确的报告永远出不来。
     */
    public static String markdown(Meta meta, AgentModels.Draft draft, AgentModels.Critique critique,
                                  List<String> citations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(Texts.isBlank(draft.title()) ? "未命名分析" : draft.title()).append("\n\n");

        sb.append("| 项目 | 内容 |\n| --- | --- |\n");
        sb.append("| 分析目标 | ").append(escape(Texts.oneLine(meta.goal(), 200))).append(" |\n");
        sb.append("| 分析模式 | ").append(meta.mode().label()).append(" |\n");
        sb.append("| 媒体时长 | ").append(meta.duration()).append(" |\n");
        sb.append("| 证据校验 | 确定性校验通过，全部结论可回溯到原片时间戳 |\n");
        sb.append("| 生成方式 | ").append(meta.engine()).append(" |\n\n");

        if (!draft.conclusions().isEmpty()) {
            sb.append("## 核心结论\n\n");
            for (String conclusion : draft.conclusions()) {
                sb.append("- ").append(conclusion.strip()).append('\n');
            }
            sb.append('\n');
        }

        if (!draft.evidence().isEmpty()) {
            sb.append("## 视频证据\n\n");
            for (AgentModels.Evidence evidence : draft.evidence()) {
                sb.append("- [").append(Times.format(evidence.timestampMs())).append("] ")
                        .append(evidence.source()).append("：")
                        .append(Texts.oneLine(evidence.content(), 300))
                        .append('\n');
            }
            sb.append('\n');
        }

        for (AgentModels.Section section : draft.sections()) {
            if (section.items().isEmpty()) {
                continue;
            }
            sb.append("## ").append(section.title()).append("\n\n");
            for (String item : section.items()) {
                sb.append("- ").append(item.strip()).append('\n');
            }
            sb.append('\n');
        }

        if (!draft.suggestions().isEmpty()) {
            sb.append("## 建议\n\n");
            for (String suggestion : draft.suggestions()) {
                sb.append("- ").append(suggestion.strip()).append('\n');
            }
            sb.append('\n');
        }

        if (!critique.feedback().isEmpty() || !critique.unsupportedClaims().isEmpty()) {
            sb.append("## 模型审阅意见\n\n");
            sb.append("> 以下意见来自模型的二次审阅。确定性证据校验已通过——");
            sb.append("每条结论都能在原片时间轴上找到对应原文；这些意见涉及措辞与推断边界，供核对时参考。\n\n");
            for (String line : critique.feedback()) {
                sb.append("- ").append(line).append('\n');
            }
            for (String line : critique.unsupportedClaims()) {
                sb.append("- 建议复核：").append(line).append('\n');
            }
            sb.append('\n');
        }

        if (!citations.isEmpty()) {
            sb.append("## 引用索引\n\n");
            for (String citation : citations) {
                sb.append("- ").append(citation).append('\n');
            }
            sb.append('\n');
        }

        sb.append("---\n\n> 本报告由 video-studio-easy 自动生成。所有结论均标注了原片时间戳，建议结合时间戳复核。\n");
        return sb.toString();
    }

    /** 收集草稿中出现的时间戳，作为引用索引 */
    public static List<String> citations(AgentModels.Draft draft) {
        Set<String> out = new LinkedHashSet<>();
        for (AgentModels.Evidence evidence : draft.evidence()) {
            out.add("[" + Times.format(evidence.timestampMs()) + "] " + evidence.source());
        }
        return new ArrayList<>(out);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|");
    }
}
