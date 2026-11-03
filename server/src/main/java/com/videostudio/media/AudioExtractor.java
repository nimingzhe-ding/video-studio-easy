package com.videostudio.media;

import com.videostudio.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
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

    /** 抽取为 16k 单声道 wav（whisper 与大多数 ASR 接口的公共格式） */
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

    public List<Slice> slice(Path wav, Path outDir, long durationMs, int segmentSeconds) {
        List<Slice> slices = new ArrayList<>();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建切片目录: " + outDir, e);
        }
        long segMs = Math.max(10, segmentSeconds) * 1000L;
        long total = Math.max(durationMs, 1);
        for (long start = 0; start < total; start += segMs) {
            long end = Math.min(start + segMs, total);
            File target = outDir.resolve("asr-%05d.wav".formatted(start / segMs)).toFile();
            List<String> cmd = new ArrayList<>(List.of(
                    props.getMedia().getFfmpeg(), "-hide_banner", "-nostdin", "-y",
                    "-ss", "%.3f".formatted(start / 1000.0),
                    "-t", "%.3f".formatted((end - start) / 1000.0),
                    "-i", wav.toString(),
                    "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    target.getAbsolutePath()));
            ProcessRunner.Result result = runner.run(cmd, Duration.ofSeconds(300));
            if (!result.ok()) {
                log.warn("音频切片失败 start={}ms: {}", start, result.failure());
                continue;
            }
            if (target.length() > 1024) {
                slices.add(new Slice(target.toPath(), start, end));
            }
        }
        log.info("音频切片完成 count={} wav={}", slices.size(), wav);
        return slices;
    }
}
