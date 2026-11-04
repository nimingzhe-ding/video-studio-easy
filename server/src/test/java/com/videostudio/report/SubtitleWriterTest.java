package com.videostudio.report;

import com.videostudio.domain.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字幕导出的格式测试。
 * <p>播放器对字幕时间轴的容错很低：SRT 必须是逗号毫秒、VTT 必须是句点并且首行 {@code WEBVTT}，
 * 少一个字段整条字幕就不显示。这类「格式死了但不报错」的问题只能在单测里锁住。
 */
class SubtitleWriterTest {

    private static TranscriptSegment seg(long start, long end, String text) {
        return new TranscriptSegment(start, end, text, null, null, "whisper");
    }

    @Test
    void srtUsesCommaMillisecondsAndSequenceFromOne() {
        String srt = SubtitleWriter.srt(List.of(seg(0, 3_400, "大家好"), seg(30_160, 53_360, "第二段")));
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:03,400\n大家好\n\n"), srt);
        assertTrue(srt.contains("2\n00:00:30,160 --> 00:00:53,360\n第二段"), srt);
    }

    @Test
    void vttStartsWithHeaderAndUsesDotMilliseconds() {
        String vtt = SubtitleWriter.vtt(List.of(seg(1_200, 3_400, "开场")));
        assertTrue(vtt.startsWith("WEBVTT\n\n"), vtt);
        assertTrue(vtt.contains("00:00:01.200 --> 00:00:03.400"), vtt);
        assertFalse(vtt.contains(","), "VTT 不能用逗号毫秒：" + vtt);
    }

    @Test
    void emptySegmentsAreDropped() {
        String srt = SubtitleWriter.srt(List.of(seg(0, 1_000, "  "), seg(2_000, 3_000, "有内容")));
        assertFalse(srt.contains("00:00:00,000"), srt);
        assertTrue(srt.startsWith("1\n"), "序号必须重新从 1 开始：" + srt);
        assertTrue(srt.contains("有内容"), srt);
    }

    @Test
    void overlappingSegmentsGetAGap() {
        // 前一段 end=1000，后一段 start=1000（重叠）→ 后一段应前推到 1020
        String srt = SubtitleWriter.srt(List.of(seg(0, 1_000, "第一段"), seg(1_000, 2_000, "第二段")));
        assertTrue(srt.contains("00:00:01,020 --> 00:00:02,000"), srt);
    }

    @Test
    void zeroLengthSegmentGetsMinimumDuration() {
        String srt = SubtitleWriter.srt(List.of(seg(5_000, 5_000, "瞬间")));
        assertTrue(srt.contains("00:00:05,000 --> 00:00:05,200"), srt);
    }

    @Test
    void plainTextKeepsTimestampAndSpeaker() {
        TranscriptSegment withSpeaker = new TranscriptSegment(65_000, 70_000, "结论是这样", "讲者A", null, "whisper");
        String text = SubtitleWriter.plainText(List.of(withSpeaker));
        assertEquals("[01:05] 讲者A：结论是这样\n", text);
    }

    @Test
    void subtitleBodyNeverContainsLineBreaks() {
        String srt = SubtitleWriter.srt(List.of(seg(0, 1_000, "第一行\n第二行")));
        assertFalse(srt.contains("第一行\n第二行"), "字幕正文不能含换行：" + srt);
        assertTrue(srt.contains("第一行 第二行"), srt);
    }

    @Test
    void extensionAndContentTypeFallBackToSrt() {
        assertEquals("srt", SubtitleWriter.extension(null));
        assertEquals("srt", SubtitleWriter.extension("ass"));
        assertEquals("vtt", SubtitleWriter.extension("VTT"));
        assertEquals("txt", SubtitleWriter.extension("text"));
        assertEquals("text/vtt;charset=UTF-8", SubtitleWriter.contentType("vtt"));
        assertEquals("application/x-subrip;charset=UTF-8", SubtitleWriter.contentType("whatever"));
    }
}
