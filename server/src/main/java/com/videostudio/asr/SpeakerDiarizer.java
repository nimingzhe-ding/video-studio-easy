package com.videostudio.asr;

import com.videostudio.config.AppProperties;
import com.videostudio.domain.TranscriptSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 本地说话人分离：用基频（F0）中位数把转写片段归到不同的说话人。
 *
 * <p>为什么是 F0 而不是声纹嵌入：声纹模型（ECAPA、Resemblyzer）需要几千万参数和一次
 * 前向推理，这个项目不想为此再塞一个模型运行时；而会议、访谈、课程这些场景里
 * 不同说话人的音高差异通常是 3-10 个半音，用中位数就足以分开，且完全确定性、
 * 可解释、零额外依赖。代价是同性别、音高接近的两个说话人分不开——这一点写进文档，
 * 不假装能做真正的声纹识别。
 *
 * <p>三个刻意的取舍：
 * <ul>
 *   <li><b>半音而不是赫兹</b>：人对音高的感知是对数的，200Hz 与 400Hz 的差异
 *       和 100Hz 与 200Hz 一样大。直接用赫兹会让高音说话人的组内方差被放大。</li>
 *   <li><b>中位数而不是均值</b>：念稿、语气词处会有一两个倍频错误（octave error），
 *       均值会被它拉偏，中位数不会。</li>
 *   <li><b>只有一个说话人时不打标签</b>：整段都是「讲者 A」只是噪声，还占界面宽度。
 *       分不出第二个人就保持 null，前端自然不显示。</li>
 * </ul>
 */
@Component
public class SpeakerDiarizer {

    private static final Logger log = LoggerFactory.getLogger(SpeakerDiarizer.class);

    /** 25ms 一帧：低于 20ms 不够覆盖一个 70Hz 的周期，高于 40ms 会跨过音素边界 */
    private static final int FRAME_MS = 25;
    /** 每段最多分析这么多帧，避免两小时的讲座把 CPU 吃满 */
    private static final int MAX_FRAMES_PER_SEGMENT = 240;
    /**
     * 超过这个半音数就认为是倍频错误而不是真实音高变化。
     * 6 个半音是三全音，真人一句话内的音高起伏很少超过它。
     */
    private static final double OCTAVE_GUARD_SEMITONES = 6;

    /** 低于这个归一化自相关峰值的帧视为清音（气声、摩擦音、静音） */
    private static final double VOICING_THRESHOLD = 0.32;
    /**
     * 认定「这是同一个基频的峰」的相对高度。0.85 是波形对称、谐波丰富时
     * 基频峰与倍周期峰之间常见的差距；再高会漏掉基频峰，再低会被倍周期峰抢走。
     */
    private static final double PEAK_RATIO = 0.85;
    /** 人的基频范围，男女声全覆盖 */
    private static final double MIN_F0 = 70;
    private static final double MAX_F0 = 400;
    /** 低于这个 RMS 的帧直接跳过，避免把底噪当成人声 */
    private static final double SILENCE_RMS = 180;

    private final AppProperties props;

    public SpeakerDiarizer(AppProperties props) {
        this.props = props;
    }

    public record Outcome(List<TranscriptSegment> segments, int speakers, int voicedSegments) {
        public boolean split() {
            return speakers > 1;
        }
    }

    /**
     * 给每个片段补上 speaker 字段。
     *
     * <p>读不到音频工作文件时直接抛错，不再返回未标注的片段：生产版本不允许把
     * 「说话人信息缺失」静默地交给下游当成功结果。调用方会把它当作任务失败处理，
     * 由用户看到明确错误后重试。
     */
    public Outcome assign(Path wav, List<TranscriptSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new Outcome(List.of(), 0, 0);
        }
        if (!props.getMedia().isSpeakerSeparationEnabled()) {
            return new Outcome(segments, 0, 0);
        }
        double splitSemitones = props.getMedia().getSpeakerSplitSemitones();

        WavAudio audio;
        try {
            if (!Files.isReadable(wav)) {
                throw new IllegalStateException("说话人分离的音频工作文件不可读");
            }
            audio = WavAudio.read(wav);
        } catch (Exception e) {
            throw new IllegalStateException("说话人分离读取音频失败",e);
        }

        List<double[]> features = new ArrayList<>(segments.size());
        int voiced = 0;
        for (TranscriptSegment segment : segments) {
            double[] f = pitchProfile(audio, segment.startMs(), segment.endMs());
            features.add(f);
            if (f != null) {
                voiced++;
            }
        }
        if (voiced < 2) {
            // 少于两段能测到音高，聚类就是瞎猜
            return new Outcome(segments, voiced == 0 ? 0 : 1, voiced);
        }

        int[] clusters = cluster(features, splitSemitones);
        clusters = smooth(clusters, segments);
        int speakers = Arrays.stream(clusters).max().orElse(-1) + 1;
        if (speakers <= 1) {
            return new Outcome(segments, 1, voiced);
        }

        List<TranscriptSegment> labeled = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment s = segments.get(i);
            String speaker = clusters[i] >= 0 ? label(clusters[i]) : null;
            labeled.add(new TranscriptSegment(s.startMs(), s.endMs(), s.text(), speaker, s.confidence(), s.source()));
        }
        log.info("说话人分离完成 segments={} voiced={} speakers={} splitSemitones={}",
                segments.size(), voiced, speakers, splitSemitones);
        return new Outcome(labeled, speakers, voiced);
    }

    /** 讲者 A、讲者 B…… 超过 26 人不再编码字母，直接给编号 */
    static String label(int index) {
        return index < 26 ? "讲者 " + (char) ('A' + index) : "讲者 " + (index + 1);
    }

    /**
     * 返回 [半音中位数, 半音标准差, 有效帧占比]，测不到音高返回 null。
     */
    static double[] pitchProfile(WavAudio audio, long startMs, long endMs) {
        int sr = audio.sampleRate();
        int frame = Math.max(1, sr * FRAME_MS / 1000);
        int from = (int) Math.max(0, startMs * sr / 1000);
        int to = (int) Math.min(audio.samples().length, endMs * sr / 1000);
        int span = to - from;
        if (span < frame) {
            return null;
        }

        int totalFrames = span / frame;
        // 片段很长时均匀抽样，而不是只分析开头——只取开头会把「同一段里换人」当成一个人
        int step = Math.max(1, totalFrames / MAX_FRAMES_PER_SEGMENT);
        double[] pitches = new double[Math.min(totalFrames, MAX_FRAMES_PER_SEGMENT)];
        int count = 0;
        int considered = 0;

        for (int k = 0; k < totalFrames && count < pitches.length; k += step) {
            int offset = from + k * frame;
            considered++;
            double f0 = estimateF0(audio.samples(), offset, frame, sr);
            if (f0 > 0) {
                pitches[count++] = 12 * (Math.log(f0 / 100.0) / Math.log(2));
            }
        }
        if (count < 3) {
            return null;
        }
        double[] voicedPitches = Arrays.copyOf(pitches, count);
        double median = medianOf(voicedPitches);

        // 去掉倍频离群点后重算。自相关偶尔会锁定到 2 倍周期上，把这类帧算进方差，
        // 组内标准差会被吹到 5 个半音以上，聚类阈值就失去了意义。
        double[] cleaned = Arrays.stream(voicedPitches)
                .filter(p -> Math.abs(p - median) <= OCTAVE_GUARD_SEMITONES)
                .toArray();
        if (cleaned.length >= 3) {
            voicedPitches = cleaned;
        }
        double refined = medianOf(voicedPitches);
        double variance = 0;
        for (double p : voicedPitches) {
            variance += (p - refined) * (p - refined);
        }
        double sd = Math.sqrt(variance / voicedPitches.length);
        return new double[]{refined, sd, (double) voicedPitches.length / Math.max(1, considered)};
    }

    /**
     * 归一化自相关求基频。用归一化而不是裸自相关，是因为裸自相关在能量大的清音上
     * 也会给出很高的值，会把「啊」的摩擦声误判成低音。
     */
    static double estimateF0(short[] samples, int offset, int frame, int sampleRate) {
        int end = Math.min(samples.length, offset + frame);
        int length = end - offset;
        if (length < frame) {
            return 0;
        }
        double sumSquares = 0;
        for (int i = 0; i < length; i++) {
            double v = samples[offset + i];
            sumSquares += v * v;
        }
        double rms = Math.sqrt(sumSquares / length);
        if (rms < SILENCE_RMS) {
            return 0;
        }

        int minLag = Math.max(2, (int) (sampleRate / MAX_F0));
        int maxLag = Math.min(length / 2, (int) (sampleRate / MIN_F0));
        if (maxLag <= minLag + 2) {
            return 0;
        }

        int span = maxLag - minLag + 1;
        double[] scores = new double[span];
        double best = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double score = normalized(samples, offset, length, lag);
            scores[lag - minLag] = score;
            if (score > best) {
                best = score;
            }
        }
        if (best < VOICING_THRESHOLD) {
            return 0;
        }

        // 取「最早的、足够高的局部峰」而不是全局最大峰。
        // 原因是自相关在周期整数倍处都会出现峰：175Hz 的信号在 1 个周期（91 采样处）
        // 和 2 个周期（183 采样处）得分几乎相等，全局最大很可能落在 2 倍周期上，
        // 于是同一句话被判成低了一个八度的 87.5Hz —— 这正是和真人录音对不上时的典型症状。
        // 从最短滞后往回找，找到第一个达到 best 的 PEAK_RATIO 的峰就停，能稳定落在基频上。
        int bestLag = 0;
        for (int i = 1; i < span - 1; i++) {
            if (scores[i] < best * PEAK_RATIO) {
                continue;
            }
            if (scores[i] >= scores[i - 1] && scores[i] >= scores[i + 1]) {
                bestLag = minLag + i;
                best = scores[i];
                break;
            }
        }
        if (bestLag == 0) {
            // 没有规整的峰（噪声、摩擦音），退回全局最大
            for (int i = 0; i < span; i++) {
                if (scores[i] == best) {
                    bestLag = minLag + i;
                    break;
                }
            }
        }
        if (bestLag == 0) {
            return 0;
        }

        // 抛物线插值：整数滞后在 200Hz 附近相差就是 10Hz，不插值会让同一个人的
        // 音高在相邻两帧之间抖动，进而抬高组内方差
        double refined = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double prev = normalized(samples, offset, length, bestLag - 1);
            double next = normalized(samples, offset, length, bestLag + 1);
            double denominator = prev - 2 * best + next;
            if (Math.abs(denominator) > 1e-9) {
                refined = bestLag + 0.5 * (prev - next) / denominator;
            }
        }
        if (refined <= 0) {
            return 0;
        }
        double f0 = sampleRate / refined;
        return f0 >= MIN_F0 && f0 <= MAX_F0 ? f0 : 0;
    }

    private static double normalized(short[] samples, int offset, int length, int lag) {
        int n = length - lag;
        if (n <= 0) {
            return 0;
        }
        double dot = 0;
        double energyA = 0;
        double energyB = 0;
        for (int i = 0; i < n; i += 2) {
            double a = samples[offset + i];
            double b = samples[offset + i + lag];
            dot += a * b;
            energyA += a * a;
            energyB += b * b;
        }
        return energyA <= 0 || energyB <= 0 ? 0 : dot / Math.sqrt(energyA * energyB);
    }

    /**
     * 一维层次聚类：每次合并「中心距离最近」的两个组，直到最近距离超过阈值。
     *
     * <p>为什么不按时间顺序贪心：那样每个新片段只跟已有的组中心比，一旦组中心被
     * 后续片段拉动，就会一路「滚雪球」把本来分开的人并成一个。实测同一段会议里
     * 228Hz 与 183Hz 相差 3.75 个半音，低于阈值 4.0，贪心版本把它们并成了一组；
     * 而全局看，组内差异只有 1.1 个半音、组间是 3.75，真正该合的其实是组内的。
     *
     * <p>测不到音高的片段记为 -1，交给 {@link #smooth} 用邻居补齐。
     */
    static int[] cluster(List<double[]> features, double splitSemitones) {
        int n = features.size();
        int[] clusters = new int[n];
        Arrays.fill(clusters, -1);

        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (features.get(i) == null) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            group.add(i);
            groups.add(group);
        }

        while (groups.size() > 1) {
            int bestA = -1;
            int bestB = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int a = 0; a < groups.size(); a++) {
                for (int b = a + 1; b < groups.size(); b++) {
                    double distance = Math.abs(center(features, groups.get(a)) - center(features, groups.get(b)));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestA < 0 || bestDistance > splitSemitones) {
                break;
            }
            // 合并到下标小的组，再把大的删掉
            groups.get(bestA).addAll(groups.get(bestB));
            groups.remove(bestB);
        }

        // 分组按「最早出现的片段」排序，保证「讲者 A」永远是先开口的那个人
        groups.sort(java.util.Comparator.comparingInt(g -> g.stream().mapToInt(Integer::intValue).min().orElse(0)));
        for (int g = 0; g < groups.size(); g++) {
            for (int index : groups.get(g)) {
                clusters[index] = g;
            }
        }
        return clusters;
    }

    private static double center(List<double[]> features, List<Integer> group) {
        double sum = 0;
        for (int index : group) {
            sum += features.get(index)[0];
        }
        return sum / group.size();
    }

    private static double medianOf(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    /**
     * 补齐与平滑：
     * <ol>
     *   <li>测不到音高的片段，继承前一个已知说话人（对话里短促的「对」「嗯」多半是这个人的）；</li>
     *   <li>只出现过一次的说话人夹在两个人之间，视为识别抖动，归到前一段的说话人。</li>
     * </ol>
     */
    static int[] smooth(int[] clusters, List<TranscriptSegment> segments) {
        int[] result = clusters.clone();
        for (int i = 0; i < result.length; i++) {
            if (result[i] >= 0) {
                continue;
            }
            for (int j = i - 1; j >= 0; j--) {
                if (result[j] >= 0) {
                    result[i] = result[j];
                    break;
                }
            }
            if (result[i] < 0) {
                for (int j = i + 1; j < result.length; j++) {
                    if (result[j] >= 0) {
                        result[i] = result[j];
                        break;
                    }
                }
            }
        }

        // 统计每个分组的总时长与片段数，只给「够长、够多次」的分组保留独立身份
        int groups = Arrays.stream(result).max().orElse(-1) + 1;
        long[] duration = new long[Math.max(groups, 0)];
        int[] occurrences = new int[Math.max(groups, 0)];
        for (int i = 0; i < result.length; i++) {
            int g = result[i];
            if (g < 0) {
                continue;
            }
            occurrences[g]++;
            duration[g] += Math.max(0, segments.get(i).endMs() - segments.get(i).startMs());
        }
        for (int g = 0; g < groups; g++) {
            // 只按「总时长」判弱，不按片段数：访谈里嘉宾只插了一句 1.5 秒的话，
            // 那也是货真价实的第二个人，按片段数判会被并掉。
            boolean weak = duration[g] < MIN_SPEAKER_MS;
            if (!weak) {
                continue;
            }
            // 找一个「强」的邻居代替它，找不到就保留——总比丢掉全部信息好
            Integer replacement = adjacentStrong(result, g, occurrences, duration);
            if (replacement != null) {
                for (int i = 0; i < result.length; i++) {
                    if (result[i] == g) {
                        result[i] = replacement;
                    }
                }
            }
        }
        return result;
    }

    /** 单次发言不足 1.2 秒不算独立说话人：多半是「嗯」「对」这类短促应答 */
    private static final long MIN_SPEAKER_MS = 1_200;

    private static Integer adjacentStrong(int[] clusters, int group, int[] occurrences, long[] duration) {
        Integer best = null;
        int bestScore = 0;
        for (int i = 0; i < clusters.length; i++) {
            if (clusters[i] != group) {
                continue;
            }
            for (int offset : new int[]{-1, 1}) {
                int j = i + offset;
                int guard = 0;
                while (j >= 0 && j < clusters.length && guard++ < 3) {
                    int candidate = clusters[j];
                    if (candidate >= 0 && candidate != group && duration[candidate] >= MIN_SPEAKER_MS) {
                        if (occurrences[candidate] > bestScore) {
                            bestScore = occurrences[candidate];
                            best = candidate;
                        }
                        break;
                    }
                    j += offset;
                }
            }
        }
        return best;
    }
}
