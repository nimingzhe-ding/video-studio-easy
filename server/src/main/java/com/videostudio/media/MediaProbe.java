package com.videostudio.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.videostudio.common.Json;
import com.videostudio.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 用 ffprobe 读取时长/分辨率/编码信息 */
@Component
public class MediaProbe {

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);

    private final ProcessRunner runner;
    private final AppProperties props;

    public MediaProbe(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    public MediaInfo probe(String path) {
        List<String> cmd = List.of(
                props.getMedia().getFfprobe(),
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                path);
        ProcessRunner.Result result = runner.run(cmd, Duration.ofSeconds(120));
        if (!result.ok()) {
            throw new IllegalStateException("ffprobe 解析失败: " + result.failure());
        }
        JsonNode root = Json.tree(result.stdout());
        long durationMs = 0;
        JsonNode format = root.path("format");
        if (format.hasNonNull("duration")) {
            durationMs = (long) (format.get("duration").asDouble() * 1000);
        }
        Integer width = null;
        Integer height = null;
        Double fps = null;
        String videoCodec = null;
        String audioCodec = null;
        boolean hasVideo = false;
        boolean hasAudio = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asText("");
            if ("video".equals(type) && !"attached_pic".equals(stream.path("disposition").path("attached_pic").asText())) {
                hasVideo = true;
                width = stream.path("width").isNumber() ? stream.get("width").asInt() : width;
                height = stream.path("height").isNumber() ? stream.get("height").asInt() : height;
                videoCodec = stream.path("codec_name").asText(null);
                String rate = stream.path("avg_frame_rate").asText("");
                Double parsed = parseRate(rate);
                if (parsed == null) {
                    parsed = parseRate(stream.path("r_frame_rate").asText(""));
                }
                fps = parsed != null ? parsed : fps;
                if (durationMs == 0 && stream.hasNonNull("duration")) {
                    durationMs = (long) (stream.get("duration").asDouble() * 1000);
                }
            } else if ("audio".equals(type)) {
                hasAudio = true;
                audioCodec = stream.path("codec_name").asText(null);
            }
        }
        if (!hasVideo && !hasAudio) {
            throw new IllegalStateException("文件中没有可用的音视频流");
        }
        log.info("probe path={} duration={}s video={} audio={}", path, durationMs / 1000, hasVideo, hasAudio);
        return new MediaInfo(durationMs, width, height, fps, videoCodec, audioCodec, hasVideo, hasAudio);
    }

    private Double parseRate(String rate) {
        if (rate == null || rate.isBlank() || rate.contains("/")) {
            if (rate == null || !rate.contains("/")) {
                return null;
            }
            String[] parts = rate.split("/");
            try {
                double numerator = Double.parseDouble(parts[0]);
                double denominator = Double.parseDouble(parts[1]);
                if (denominator <= 0) {
                    return null;
                }
                double value = numerator / denominator;
                return value > 0 && value < 1000 ? Math.round(value * 100) / 100.0 : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(rate);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
