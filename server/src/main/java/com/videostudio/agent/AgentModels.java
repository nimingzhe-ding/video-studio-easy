package com.videostudio.agent;

import java.util.ArrayList;
import java.util.List;

/** Agent 的中间结构：全部是不可变记录，便于落库与断言 */
public final class AgentModels {

    private AgentModels() {
    }

    /** 规划：目标理解 + 1..5 个可验证任务 */
    public record Plan(String understoodGoal, List<String> tasks) {
        public Plan {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }

        public String render() {
            StringBuilder sb = new StringBuilder("目标理解：").append(understoodGoal == null ? "" : understoodGoal).append('\n');
            sb.append("任务清单：\n");
            for (int i = 0; i < tasks.size(); i++) {
                sb.append(i + 1).append(". ").append(tasks.get(i)).append('\n');
            }
            return sb.toString().strip();
        }
    }

    /** 检索意图：语义查询 + 词项 + 画面词项 */
    public record RetrievalIntent(String semanticQuery, List<String> keywords, List<String> visualKeywords) {
        public RetrievalIntent {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            visualKeywords = visualKeywords == null ? List.of() : List.copyOf(visualKeywords);
        }

        public String query() {
            List<String> parts = new ArrayList<>();
            if (semanticQuery != null && !semanticQuery.isBlank()) {
                parts.add(semanticQuery);
            }
            parts.addAll(keywords);
            return String.join(" ", parts);
        }
    }

    /** 证据：时间戳 + 来源 + 原文 + 它支撑的结论 */
    public record Evidence(long timestampMs, String source, String content, String claim) {
    }

    /** 结构化产物中的一个可选小节 */
    public record Section(String key, String title, List<String> items) {
        public Section {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** Executor 产物 */
    public record Draft(String title, List<String> conclusions, List<Evidence> evidence,
                        List<String> suggestions, List<Section> sections) {
        public Draft {
            conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            sections = sections == null ? List.of() : List.copyOf(sections);
        }

        public boolean empty() {
            return conclusions.isEmpty() && sections.isEmpty();
        }
    }

    /** Critic 结论 */
    public record Critique(boolean passed, List<String> feedback, List<String> missingRequirements,
                           List<String> unsupportedClaims, List<Long> requiredTimestamps) {
        public Critique {
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
            missingRequirements = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
            unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
            requiredTimestamps = requiredTimestamps == null ? List.of() : List.copyOf(requiredTimestamps);
        }

        public static Critique pass() {
            return new Critique(true, List.of(), List.of(), List.of(), List.of());
        }

        public boolean needsEvidence() {
            return !requiredTimestamps.isEmpty() || !missingRequirements.isEmpty() || !unsupportedClaims.isEmpty();
        }

        public String render() {
            List<String> lines = new ArrayList<>();
            lines.add("是否通过：" + (passed ? "是" : "否"));
            add(lines, "修改建议", feedback);
            add(lines, "遗漏要求", missingRequirements);
            add(lines, "证据不足的结论", unsupportedClaims);
            if (!requiredTimestamps.isEmpty()) {
                lines.add("需要补充证据的时间戳（毫秒）：" + requiredTimestamps);
            }
            return String.join("\n", lines);
        }

        private static void add(List<String> lines, String title, List<String> values) {
            if (!values.isEmpty()) {
                lines.add(title + "：" + String.join("；", values));
            }
        }
    }
}
