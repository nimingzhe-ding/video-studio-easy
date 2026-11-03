package com.videostudio.ocr;

import com.videostudio.config.AppProperties;
import com.videostudio.media.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行 OCR。
 * <p>自动适配两种常见形态：
 * <ul>
 *   <li>tesseract：<code>tesseract img stdout -l chi_sim+eng</code></li>
 *   <li>自建 vocr（macOS Vision）：<code>vocr img zh-Hans</code></li>
 * </ul>
 * 单帧失败只记录警告并返回空串——画面文字是增强信号，不该让整条流水线失败。
 */
@Component
public class CommandTextRecognizer implements TextRecognizer {

    private static final Logger log = LoggerFactory.getLogger(CommandTextRecognizer.class);

    private final ProcessRunner runner;
    private final AppProperties props;
    private final String command;

    public CommandTextRecognizer(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
        this.command = props.getMedia().getOcr();
    }

    public boolean available() {
        if (command == null || command.isBlank()) {
            return false;
        }
        Path path = Path.of(command);
        boolean looksPath = command.contains("/");
        return looksPath ? Files.isExecutable(path) : true;
    }

    @Override
    public String engine() {
        return isVision() ? "vision-ocr" : "tesseract";
    }

    private boolean isVision() {
        return command != null && command.toLowerCase().contains("vocr");
    }

    @Override
    public String recognize(Path image) {
        if (!available()) {
            return "";
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        if (isVision()) {
            cmd.add(image.toString());
            cmd.add("auto");
        } else {
            cmd.add(image.toString());
            cmd.add("stdout");
            cmd.add("-l");
            cmd.add(props.getMedia().getOcrLanguages());
        }
        ProcessRunner.Result result = runner.run(cmd,
                Duration.ofSeconds(props.getMedia().getOcrTimeoutSeconds()));
        if (result.timedOut()) {
            log.warn("OCR 超时: {}", image.getFileName());
            return "";
        }
        // tesseract 在没有文字时会以非 0 退出且 stdout 为空，属于正常情况
        return clean(result.stdout());
    }

    /** 去掉空白行与单字符噪声行，保留可读文本 */
    private String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String text = line.strip();
            if (text.length() < 2) {
                continue;
            }
            sb.append(text).append('\n');
        }
        return sb.toString().strip();
    }
}
