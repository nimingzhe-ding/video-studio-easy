package com.videostudio.agent;

import com.videostudio.config.AgentProperties;
import com.videostudio.domain.ContextChunk;
import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文渲染的预算行为测试。
 * <p>这里的核心不变量是「超预算时跳过该条、继续尝试后面更短的条目」——一旦退化成遇到超长就 break，
 * 一条 600 字的转写就能把后面所有时间点挤出上下文，模型会直接编造不存在的时间戳。
 */
class ContextRendererTest {

    private static AgentProperties props(int budget) {
        AgentProperties props = new AgentProperties();
        props.setMaxContextChars(budget);
        return props;
    }

    private static TranscriptSegment segment(long startMs, long endMs, String text) {
        return new TranscriptSegment(startMs, endMs, text, null, null, "whisper");
    }

    private static ContextRenderer.Retrieved retrieved(List<TranscriptSegment> segments, List<VisualEvidence> visuals) {
        return new ContextRenderer.Retrieved(List.<ContextChunk>of(), segments, visuals);
    }

    @Test
    void oversizedSegmentIsSkippedNotFatal() {
        List<TranscriptSegment> segments = new ArrayList<>();
        // 预算 2000 字符，每条长片段被 oneLine 截到 600 字：
        // 第三条之后剩余空间不足以再放下一条长片段，此时必须「跳过它、继续看后面的条目」，
        // 而不是像旧实现那样直接 break —— 否则一条超长片段就把后面所有关键时间点全部挤掉。
        for (int i = 0; i < 4; i++) {
            segments.add(segment(i * 60_000L, i * 60_000L + 50_000, "很长的转写内容".repeat(200)));
        }
        segments.add(segment(400_000, 410_000, "末尾关键结论"));
        String rendered = ContextRenderer.render("总结关键结论", retrieved(segments, List.of()), props(2_000));

        assertTrue(rendered.length() <= 2_100, "渲染结果应受预算约束，实际 " + rendered.length());
        assertTrue(rendered.contains("末尾关键结论"), "超长条目之后的短条目必须仍能进入上下文");
        assertTrue(rendered.contains("省略"), "被跳过的条目应有显式说明，而不是静默丢失");
    }

    @Test
    void neverCutsMidSentenceWithoutNotice() {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            segments.add(segment(i * 1_000L, i * 1_000L + 900, "片段内容编号" + i + "，用于填充上下文长度"));
        }
        String rendered = ContextRenderer.render("遍历所有内容", retrieved(segments, List.of()), props(2_000));

        assertFalse(rendered.endsWith("…（已截断）") && !rendered.contains("省略"),
                "硬截断必须伴随可见的省略提示");
        assertTrue(rendered.length() <= 2_200, "结果不应远超预算，实际 " + rendered.length());
    }

    @Test
    void outputStaysWithInBudgetWhenVisualsPresent() {
        List<VisualEvidence> visuals = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            visuals.add(new VisualEvidence(i * 5_000L, "/tmp/f" + i + ".jpg",
                    "画面文字第 " + i + " 条", null, null, "vision-ocr"));
        }
        String rendered = ContextRenderer.render("看画面文字", retrieved(List.of(), visuals), props(3_000));
        assertTrue(rendered.length() <= 3_300, "结果不应远超预算，实际 " + rendered.length());
    }

    @Test
    void recallFindsSegmentsAroundRequiredTimestamp() {
        List<TranscriptSegment> segments = List.of(
                segment(0, 20_000, "开场"),
                segment(60_000, 90_000, "中间结论"),
                segment(300_000, 330_000, "结尾"));
        ContextRenderer.Retrieved recalled = ContextRenderer.recall(List.of(75_000L), segments, List.of(), 0);

        // 容差下限是 60 秒（模型给的时刻常落在片段边界外几百毫秒甚至整段之外），
        // 所以 75s 会同时捞到相邻的「开场」与「中间结论」，但不会捞到 300s 那条
        assertTrue(recalled.segments().stream().anyMatch(s -> s.startMs() == 60_000));
        assertFalse(recalled.segments().stream().anyMatch(s -> s.startMs() == 300_000));
        assertEquals(recalled.segments().size(),
                recalled.segments().stream().map(TranscriptSegment::startMs).distinct().count(),
                "回捞结果必须去重");
    }

    @Test
    void recallWithEmptyTimestampsReturnsEmpty() {
        assertTrue(ContextRenderer.recall(List.of(), List.of(segment(0, 1_000, "x")), List.of(), 0)
                .segments().isEmpty());
    }
}
