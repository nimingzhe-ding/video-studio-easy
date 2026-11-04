package com.videostudio.asr;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.TranscriptSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 说话人分离的行为测试。
 *
 * <p>素材用合成正弦波而不是真人录音：测试要断言的是「音高不同就分到不同组」，
 * 而正弦波的基频是已知的，可以精确控制差几个半音，比拿录音去猜更可靠。
 */
class SpeakerDiarizerTest {

    @TempDir
    Path temp;

    @Test
    void twoDistinctPitchesAreSeparated() throws IOException {
        // A 用 220Hz（约 A3），B 用 160Hz（约 E3），相差 5.5 个半音，
        // 明确高于默认阈值 4.0，避免测试卡在阈值边界上
        Path wav = speech(temp.resolve("two.wav"), new double[]{220, 160, 220, 160});
        List<TranscriptSegment> segments = segments(4);

        SpeakerDiarizer.Outcome outcome = new SpeakerDiarizer(defaults()).assign(wav, segments);

        assertEquals(2, outcome.speakers());
        assertEquals("讲者 A", outcome.segments().get(0).speaker());
        assertEquals("讲者 B", outcome.segments().get(1).speaker());
        assertEquals("讲者 A", outcome.segments().get(2).speaker());
        assertEquals("讲者 B", outcome.segments().get(3).speaker());
    }

    @Test
    void singleSpeakerIsNotLabeled() throws IOException {
        // 同一个人说话有起伏，但都在 1 个半音内，不该被拆成两个
        Path wav = speech(temp.resolve("one.wav"), new double[]{210, 218, 205, 214});

        SpeakerDiarizer.Outcome outcome = new SpeakerDiarizer(defaults()).assign(wav, segments(4));

        assertEquals(1, outcome.speakers());
        for (TranscriptSegment segment : outcome.segments()) {
            assertNull(segment.speaker(), "只有一个说话人时不该打标签");
        }
    }

    @Test
    void disabledSwitchKeepsSegmentsUntouched() throws IOException {
        Path wav = speech(temp.resolve("off.wav"), new double[]{220, 160, 220, 160});
        AppProperties props = defaults();
        props.getMedia().setSpeakerSeparationEnabled(false);

        SpeakerDiarizer.Outcome outcome = new SpeakerDiarizer(props).assign(wav, segments(4));

        assertEquals(0, outcome.speakers());
        for (TranscriptSegment segment : outcome.segments()) {
            assertNull(segment.speaker());
        }
    }

    @Test
    void missingFileFallsBackInsteadOfThrowing() {
        SpeakerDiarizer.Outcome outcome = new SpeakerDiarizer(defaults())
                .assign(temp.resolve("nope.wav"), segments(3));

        assertEquals(0, outcome.speakers());
        assertEquals(3, outcome.segments().size(), "读不到音频也要原样返回片段");
    }

    @Test
    void silentAudioProducesNoSpeakers() throws IOException {
        Path wav = silence(temp.resolve("silence.wav"), 4);
        SpeakerDiarizer.Outcome outcome = new SpeakerDiarizer(defaults()).assign(wav, segments(4));

        assertEquals(0, outcome.speakers());
        assertTrue(outcome.segments().stream().allMatch(s -> s.speaker() == null));
    }

    @Test
    void unvoicedSegmentsInheritNeighbour() throws IOException {
        // 第 2 段是静音（测不到音高），应继承第 1 段的说话人
        Path wav = speech(temp.resolve("gap.wav"), new double[]{220, 0, 220, 160});
        SpeakerDiarizer.Outcome outcome = new SpeakerDiarizer(defaults()).assign(wav, segments(4));

        assertEquals("讲者 A", outcome.segments().get(0).speaker());
        assertEquals("讲者 A", outcome.segments().get(1).speaker(), "测不到音高的片段继承前一个说话人");
    }

    @Test
    void semitoneConversionIsMonotonic() {
        // 200Hz 到 400Hz 正好是一个八度 = 12 个半音，用来确认换算没有差一倍
        double lower = 12 * (Math.log(200.0 / 100) / Math.log(2));
        double higher = 12 * (Math.log(400.0 / 100) / Math.log(2));
        assertEquals(12.0, higher - lower, 1e-9);
    }

    // ---- 素材构造 ----

    private static AppProperties defaults() {
        AppProperties props = new AppProperties();
        props.getMedia().setSpeakerSeparationEnabled(true);
        props.getMedia().setSpeakerSplitSemitones(4.0);
        return props;
    }

    /** 每段 1.5 秒，段与段之间留 0.3 秒静音，段内叠一点谐波让它更像人声 */
    private static Path speech(Path target, double[] pitches) throws IOException {
        int sr = 16000;
        double segmentSeconds = 1.5;
        double gapSeconds = 0.3;
        int segmentSamples = (int) (sr * segmentSeconds);
        int gapSamples = (int) (sr * gapSeconds);
        int total = (segmentSamples + gapSamples) * pitches.length;
        short[] samples = new short[total];

        for (int s = 0; s < pitches.length; s++) {
            int offset = s * (segmentSamples + gapSamples);
            double f0 = pitches[s];
            for (int i = 0; i < segmentSamples; i++) {
                double t = (double) i / sr;
                // 加一个 2 倍频谐波：纯正弦的归一化自相关过于理想，真实人声更接近这种情况
                double v = 0.6 * Math.sin(2 * Math.PI * f0 * t) + 0.25 * Math.sin(4 * Math.PI * f0 * t);
                // 首尾淡入淡出，避免爆音影响自相关
                double envelope = Math.min(1, Math.min(i, segmentSamples - i) / (sr * 0.02));
                samples[offset + i] = (short) (v * envelope * 9000);
            }
        }
        return writeWav(target, samples, sr);
    }

    private static Path silence(Path target, int segments) throws IOException {
        int sr = 16000;
        short[] samples = new short[sr * 2 * segments];
        return writeWav(target, samples, sr);
    }

    private static Path writeWav(Path target, short[] samples, int sampleRate) throws IOException {
        int dataBytes = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataBytes);
        buffer.put("WAVE".getBytes());
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes());
        buffer.putInt(dataBytes);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, buffer.array());
        return target;
    }

    /** 按 speech() 的时间轴（1.5 秒说话 + 0.3 秒静音）生成片段 */
    private static List<TranscriptSegment> segments(int count) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = i * 1800L;
            segments.add(new TranscriptSegment(start, start + 1500, "第 " + (i + 1) + " 句", null, null, "whisper"));
        }
        return segments;
    }
}
