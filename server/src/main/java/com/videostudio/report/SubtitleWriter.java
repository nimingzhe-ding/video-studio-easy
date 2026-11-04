package com.videostudio.report;

import com.videostudio.common.Texts;
import com.videostudio.common.Times;
import com.videostudio.domain.TranscriptSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把转写结果导出成字幕文件。
 *
 * <p>字幕的价值在于「能直接拖进播放器/剪辑软件对齐画面」，所以格式必须严格：
 * SRT 的序号从 1 开始、时间轴用逗号小数点、VTT 用句点并且第一行固定是 {@code WEBVTT}。
 * 很多播放器对时间格式容错很低，多打一个空格就会整段失效，因此这里不做「顺手美化」。
 *
 * <p>同时做两件对齐原始证据的事：
 * <ol>
 *   <li>空文本与零长度片段直接丢弃（静音片、纯音乐片常出现），否则播放器会显示一条空字幕；</li>
 *   <li>相邻片段之间留 20ms 空隙，避免后一条被渲染引擎判为「重叠」而闪一下消失。</li>
 * </ol>
 */
public final class SubtitleWriter {

    /** 相邻字幕之间保留的空隙，单位毫秒 */
    private static final long GAP_MS = 20;

    private SubtitleWriter() {
    }

    public static String srt(List<TranscriptSegment> segments) {
        List<TranscriptSegment> usable = usable(segments);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < usable.size(); i++) {
            TranscriptSegment segment = usable.get(i);
            sb.append(i + 1).append('\n');
            sb.append(stamp(segment.startMs(), ',')).append(" --> ").append(stamp(segment.endMs(), ',')).append('\n');
            sb.append(body(segment)).append("\n\n");
        }
        return sb.toString();
    }

    public static String vtt(List<TranscriptSegment> segments) {
        List<TranscriptSegment> usable = usable(segments);
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < usable.size(); i++) {
            TranscriptSegment segment = usable.get(i);
            // VTT 允许省略序号，但显式写出来在浏览器 DevTools 里更好定位
            sb.append(i + 1).append('\n');
            sb.append(stamp(segment.startMs(), '.')).append(" --> ").append(stamp(segment.endMs(), '.')).append('\n');
            sb.append(body(segment)).append("\n\n");
        }
        return sb.toString();
    }

    /** 纯文本稿：每段一行，行首带时间戳，方便直接粘进文档 */
    public static String plainText(List<TranscriptSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptSegment segment : usable(segments)) {
            String speaker = Texts.isBlank(segment.speaker()) ? "" : segment.speaker() + "：";
            sb.append(Times.formatBracket(segment.startMs())).append(' ')
                    .append(speaker)
                    .append(Texts.normalizeWhitespace(segment.text()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 文件名里要用的字幕扩展名 */
    public static String extension(String format) {
        return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "vtt" -> "vtt";
            case "txt", "text" -> "txt";
            default -> "srt";
        };
    }

    public static String contentType(String format) {
        return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "vtt" -> "text/vtt;charset=UTF-8";
            case "txt", "text" -> "text/plain;charset=UTF-8";
            default -> "application/x-subrip;charset=UTF-8";
        };
    }

    private static List<TranscriptSegment> usable(List<TranscriptSegment> segments) {
        List<TranscriptSegment> result = new ArrayList<>();
        if (segments == null) {
            return result;
        }
        long previousEnd = -1;
        for (TranscriptSegment segment : segments) {
            if (segment == null || Texts.isBlank(segment.text())) {
                continue;
            }
            long start = Math.max(0, segment.startMs());
            long end = Math.max(start + 200, segment.endMs());
            if (previousEnd >= 0 && start < previousEnd + GAP_MS) {
                start = previousEnd + GAP_MS;
                end = Math.max(start + 200, end);
            }
            previousEnd = end;
            result.add(new TranscriptSegment(start, end, segment.text(), segment.speaker(),
                    segment.confidence(), segment.source()));
        }
        return result;
    }

    /** 时间轴格式：SRT 用 {@code 00:00:01,200}，VTT 用 {@code 00:00:01.200} */
    private static String stamp(long ms, char separator) {
        long total = Math.max(0, ms);
        long millis = total % 1000;
        long seconds = (total / 1000) % 60;
        long minutes = (total / 60_000) % 60;
        long hours = total / 3_600_000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, separator, millis);
    }

    private static String body(TranscriptSegment segment) {
        // 字幕里不能出现换行，否则部分播放器会截断这条字幕
        String text = Texts.normalizeWhitespace(segment.text()).replace("\n", " ");
        // 有说话人时用「讲者 A：正文」的写法。SRT/VTT 没有官方的说话人字段，
        // 主流播放器与剪辑软件（YouTube、Premiere、Final Cut）都认这种行内前缀；
        // 用单独的「- 」破折号前缀会把「谁在说」和「说了什么」混在一起，导出到字幕编辑器里反而难处理。
        if (Texts.isBlank(segment.speaker())) {
            return text;
        }
        return segment.speaker() + "：" + text;
    }
}
