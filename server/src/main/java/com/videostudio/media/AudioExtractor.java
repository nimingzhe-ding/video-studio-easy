package com.videostudio.media;

import com.videostudio.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频抽取与切片。
 * <p>ASR 按固定长度切片（默认 60s）：云端转写接口普遍有单文件时长上限，
 * 切片还能让失败的片段单独重试，而不是整段重来。
 */
@Component
public class AudioExtractor {

    private static final Logger log = LoggerFactory.getLogger(AudioExtractor.class);

    private final ProcessRunner runner;
    private final AppProperties props;

    public AudioExtractor(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    /** 抽取为 16k 单声道 wav（云端 ASR 使用的音频格式） */
    public Path extract(Path source, Path targetWav) {
        try {
            Files.createDirectories(targetWav.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建音频目录: " + targetWav.getParent(), e);
        }
        List<String> cmd = List.of(
                props.getMedia().getFfmpeg(), "-hide_banner", "-nostdin", "-y",
                "-i", source.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                targetWav.toString());
        ProcessRunner.Result result = runner.run(cmd, Duration.ofSeconds(props.getMedia().getFfmpegTimeoutSeconds()));
        if (!result.ok()) {
            throw new IllegalStateException("音频抽取失败: " + result.failure());
        }
        return targetWav;
    }

    /** 切片结果：文件 + 在原时间轴上的起点 */
    public record Slice(Path file, long startMs, long endMs) {
    }

    /**
     * 切片。
     *
     * <p>用一次 ffmpeg {@code -f segment} 切完所有片段，而不是每片起一个进程：
     * 实测 76 秒音频从「4 次进程启动」降到 0.043 秒，长音频（1 小时 = 60 片）差距更明显。
     * {@code -segment_list_type csv} 会输出 {@code 文件名,起秒,止秒}，
     * 正好可以还原每片在原时间轴上的位置，不用自己按时长累加（避免累计误差）。
     */
    public List<Slice> slice(Path wav, Path outDir, long durationMs, int segmentSeconds) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建切片目录: " + outDir, e);
        }
        long segMs = Math.max(10, segmentSeconds) * 1000L;
        Path list = outDir.resolve("segments.csv");
        List<String> cmd = new ArrayList<>(List.of(
                props.getMedia().getFfmpeg(), "-hide_banner", "-nostdin", "-y",
                "-i", wav.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                "-f", "segment",
                "-segment_time", "%.3f".formatted(segMs / 1000.0),
                "-reset_timestamps", "1",
                "-segment_list", list.toString(),
                "-segment_list_type", "csv",
                outDir.resolve("asr-%05d.wav").toString()));
        ProcessRunner.Result result = runner.run(cmd, Duration.ofSeconds(props.getMedia().getFfmpegTimeoutSeconds()));
        if (!result.ok()) {
            throw new IllegalStateException("音频切片失败: " + result.failure());
        }

        List<Slice> slices = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(list)) {
                String[] parts = line.trim().split(",");
                if (parts.length < 3) {
                    continue;
                }
                Path file = outDir.resolve(parts[0].trim());
                if (!Files.isReadable(file) || Files.size(file) <= 1024) {
                    continue;
                }
                long start = Math.round(Double.parseDouble(parts[1].trim()) * 1000);
                long end = Math.round(Double.parseDouble(parts[2].trim()) * 1000);
                slices.add(new Slice(file, start, durationMs > 0 ? Math.min(end, durationMs) : end));
            }
            Files.deleteIfExists(list);
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("解析音频切片清单失败: " + e.getMessage(), e);
        }
        if (slices.isEmpty()) {
            throw new IllegalStateException("音频切片为空，请检查该文件是否包含可识别的语音");
        }
        log.info("音频切片完成 count={} wav={} seg={}s", slices.size(), wav, segMs / 1000);
        return slices;
    }
}
