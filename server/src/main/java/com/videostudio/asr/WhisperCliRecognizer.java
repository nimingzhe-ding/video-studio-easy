package com.videostudio.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.videostudio.common.Json;
import com.videostudio.media.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 whisper.cpp 识别器：无网络、无 API key 时的主力，也是云端失败的兜底。
 * <p>使用 -oj 输出的 JSON，拿到带偏移的分段；偏移单位是毫秒，可直接映射回原视频时间轴。
 */
@Component
public class WhisperCliRecognizer implements SpeechRecognizer {

    private static final Logger log = LoggerFactory.getLogger(WhisperCliRecognizer.class);

    private final ProcessRunner runner;
    private final String executable;
    private final String modelPath;
    private final String language;
    private final int threads;
    private final int timeoutSeconds;

    public WhisperCliRecognizer(ProcessRunner runner,
                                @Value("${app.asr.whisper.command:}") String executable,
                                @Value("${app.asr.whisper.model:}") String modelPath,
                                @Value("${app.asr.whisper.language:auto}") String language,
                                @Value("${app.asr.whisper.threads:4}") int threads,
                                @Value("${app.asr.whisper.timeout-seconds:1800}") int timeoutSeconds) {
        this.runner = runner;
        this.executable = executable;
        this.modelPath = modelPath;
        this.language = language;
        this.threads = threads;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean available() {
        if (executable == null || executable.isBlank() || modelPath == null || modelPath.isBlank()) {
            return false;
        }
        return Files.isExecutable(Path.of(executable)) && Files.isReadable(Path.of(modelPath));
    }

    @Override
    public String engine() {
        return "whisper";
    }

    @Override
    public Result transcribe(Path audio) {
        if (!available()) {
            throw new IllegalStateException("本地 whisper 未配置或不可用");
        }
        Path outBase;
        try {
            outBase = Files.createTempFile("whisper-", "");
            Files.deleteIfExists(outBase);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 whisper 临时文件", e);
        }
        List<String> cmd = List.of(
                executable,
                "-m", modelPath,
                "-f", audio.toString(),
                "-l", language,
                "-t", String.valueOf(threads),
                "-oj", "-of", outBase.toString(),
                "-np");
        ProcessRunner.Result result = runner.run(cmd, Duration.ofSeconds(timeoutSeconds));
        Path jsonPath = Path.of(outBase + ".json");
        if (!result.ok() && !Files.exists(jsonPath)) {
            throw new IllegalStateException("whisper 识别失败: " + result.failure());
        }
        try {
            String raw = Files.readString(jsonPath);
            JsonNode root = Json.tree(raw);
            StringBuilder text = new StringBuilder();
            List<Word> words = new ArrayList<>();
            for (JsonNode seg : root.path("transcription")) {
                String line = seg.path("text").asText("").strip();
                if (line.isEmpty()) {
                    continue;
                }
                text.append(line).append(' ');
                long from = seg.path("offsets").path("from").asLong(0);
                long to = seg.path("offsets").path("to").asLong(from);
                words.add(new Word(from, to, line));
            }
            return new Result(text.toString().strip(), words, "whisper");
        } catch (IOException e) {
            throw new IllegalStateException("读取 whisper 输出失败", e);
        } finally {
            try {
                Files.deleteIfExists(jsonPath);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响结果
            }
        }
    }
}
