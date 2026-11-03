package com.videostudio.media;

import com.videostudio.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 关键帧抽取。
 * <p>策略：首帧 + 场景切换点（scene &gt; 阈值）+ 最长间隔兜底。
 * 时间戳取自 showinfo 打印的 pts_time，而不是按序号推算，
 * 保证镜头密度不均时帧与时间轴依然对齐。
 */
@Component
public class FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractor.class);
    private static final Pattern PTS = Pattern.compile("pts_time:(\\d+(?:\\.\\d+)?)");
    private static final String PREFIX = "scene-";

    private final ProcessRunner runner;
    private final AppProperties props;

    public FrameExtractor(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    public record Frame(Path path, long timeMs, int index) {
    }

    public List<Frame> extract(Path video, Path outDir, long durationMs) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建帧目录: " + outDir, e);
        }
        clear(outDir);

        int width = props.getMedia().getFrameWidth();
        int maxCount = props.getMedia().getFrameMaxCount();
        int interval = effectiveInterval(durationMs, maxCount);
        int gap = minGapSeconds(durationMs, maxCount);
        // 三段式：首帧必取 → 场景切换点（且距上一帧至少 gap 秒）→ 超过 interval 秒强制兜底。
        // gap 让场景点不会挤成一串，同时天然把总帧数限制在 maxCount 以内。
        String scale = "scale='min(" + width + "\\,iw)':-2";
        String filter = "select='eq(n\\,0)+gte(t-prev_selected_t\\," + gap + ")*"
                + "(gt(scene\\," + num(props.getMedia().getFrameSceneThreshold())
                + ")+gte(t-prev_selected_t\\," + interval + "))'," + scale + ",showinfo";
        log.info("抽帧参数 gap={}s interval={}s scene={} max={}", gap, interval,
                num(props.getMedia().getFrameSceneThreshold()), maxCount);

        List<Double> timestamps = new ArrayList<>();
        List<String> cmd = List.of(
                props.getMedia().getFfmpeg(), "-hide_banner", "-nostdin", "-y",
                "-i", video.toString(),
                "-vf", filter,
                "-fps_mode", "vfr",
                "-frames:v", String.valueOf(maxCount),
                "-q:v", "3",
                outDir.resolve(PREFIX + "%04d.jpg").toString());
        // showinfo 打在 stderr 上，必须挂 stderr 回调，否则时间戳全部拿不到、悄悄退化成按序号推算。
        Consumer<String> parsePts = line -> {
            if (!line.contains("showinfo")) {
                return;
            }
            Matcher matcher = PTS.matcher(line);
            if (matcher.find()) {
                timestamps.add(Double.parseDouble(matcher.group(1)));
            }
        };
        ProcessRunner.Result result = runner.run(cmd,
                Duration.ofSeconds(props.getMedia().getFfmpegTimeoutSeconds()),
                null, parsePts, null, null);

        List<Path> files = listFrames(outDir);
        if (!result.ok() || files.isEmpty()) {
            if (!result.ok()) {
                log.warn("场景抽帧失败，回退固定间隔: {}", result.failure());
            }
            return extractByInterval(video, outDir, interval);
        }

        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            long ms = i < timestamps.size() ? Math.round(timestamps.get(i) * 1000) : (long) i * interval * 1000;
            if (durationMs > 0 && ms > durationMs) {
                ms = durationMs;
            }
            frames.add(new Frame(files.get(i), ms, i));
        }
        frames.sort(Comparator.comparingLong(Frame::timeMs));
        log.info("场景抽帧完成 count={} dir={}", frames.size(), outDir);
        return frames;
    }

    /**
     * 实际抽帧间隔：底噪间隔与「按帧数上限均摊」取大者。
     * <p>两小时视频若固定 20 秒抽一帧会得到 360 帧，逼近上限且大部分是静止画面；
     * 这里让它自动摊薄，把预算留给真正的场景切换点。
     */
    private int effectiveInterval(long durationMs, int maxCount) {
        int configured = Math.max(5, props.getMedia().getFrameFallbackIntervalSeconds());
        if (durationMs <= 0 || maxCount <= 0) {
            return configured;
        }
        int budget = (int) Math.ceil(durationMs / 1000.0 / maxCount);
        return Math.max(configured, budget);
    }

    /**
     * 相邻两帧的最小间隔。
     * <p>两个约束同时成立：1) 不能太小，否则一段高动态画面会在几秒内抽出几十张近乎相同的帧；
     * 2) 不能太大，实测 2.5 秒就会漏掉真实的翻页（本例 38.14s → 40.62s 只差 2.48 秒）。
     * 因此下限定为 2 秒；同时按「按时长均摊」收紧，保证总帧数不会超出预算。
     */
    private int minGapSeconds(long durationMs, int maxCount) {
        if (durationMs <= 0 || maxCount <= 0) {
            return 2;
        }
        long budget = (long) Math.ceil(durationMs / 1000.0 / maxCount);
        return (int) Math.max(2, budget);
    }

    private List<Frame> extractByInterval(Path video, Path outDir, int interval) {
        clear(outDir);
        List<String> cmd = List.of(
                props.getMedia().getFfmpeg(), "-hide_banner", "-nostdin", "-y",
                "-i", video.toString(),
                "-vf", "fps=1/" + interval + ",scale='min(" + props.getMedia().getFrameWidth() + "\\,iw)':-2",
                "-frames:v", String.valueOf(props.getMedia().getFrameMaxCount()),
                "-q:v", "3",
                outDir.resolve(PREFIX + "%04d.jpg").toString());
        ProcessRunner.Result result = runner.run(cmd,
                Duration.ofSeconds(props.getMedia().getFfmpegTimeoutSeconds()));
        if (!result.ok()) {
            log.warn("固定间隔抽帧失败: {}", result.failure());
        }
        List<Path> files = listFrames(outDir);
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            frames.add(new Frame(files.get(i), (long) i * interval * 1000, i));
        }
        log.info("固定间隔抽帧完成 count={}", frames.size());
        return frames;
    }

    private List<Path> listFrames(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(".jpg");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void clear(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 残留文件会被后续写入覆盖，不影响结果
                }
            });
        } catch (IOException ignored) {
            // 目录不存在时无需清理
        }
    }

    /**
     * 场景阈值格式化：保留有效位而不是固定两位小数。
     * <p>踩过的坑：阈值 0.006 用 "%.2f" 会变成 0.01，等于悄悄抬高了 60%，
     * 静态课件上刚好把翻页帧漏掉。
     */
    private String num(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
