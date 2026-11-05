package com.videostudio.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 外部进程执行封装。ffmpeg / ffprobe / OCR 都是「命令行即接口」，
 * 统一在这里处理超时、编码与日志，避免每个工具各写一份 ProcessBuilder。
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int MAX_CAPTURE = 2 * 1024 * 1024;

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** 失败时截取尾部日志，避免把几 MB 的 ffmpeg 输出塞进异常信息 */
        public String failure() {
            String tail = stderr == null || stderr.isBlank() ? stdout : stderr;
            String text = tail == null ? "" : tail.strip();
            if (text.length() > 1200) {
                text = "…" + text.substring(text.length() - 1200);
            }
            return (timedOut ? "命令超时；" : "退出码 " + exitCode + "；") + text;
        }
    }

    public Result run(List<String> command, Duration timeout) {
        return run(command, timeout, null, null, Map.of(), null);
    }

    public Result run(List<String> command, Duration timeout, File workingDir) {
        return run(command, timeout, null, null, Map.of(), workingDir);
    }

    /**
     * @param stdoutLine 逐行回调（可为 null），用于解析命令的正常输出
     */
    public Result run(List<String> command, Duration timeout, Consumer<String> stdoutLine) {
        return run(command, timeout, stdoutLine, null, Map.of(), null);
    }

    /**
     * @param stdoutLine 逐行回调（可为 null）
     * @param stderrLine 逐行回调（可为 null）。<b>ffmpeg 的 showinfo / metadata=print 都打在 stderr 上</b>，
     *                   只挂 stdout 会导致时间戳解析静默失败、退化成按序号推算。
     * @param extraEnv   追加环境变量
     */
    public Result run(List<String> command, Duration timeout, Consumer<String> stdoutLine,
                      Consumer<String> stderrLine, Map<String, String> extraEnv, File workingDir) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir);
        }
        builder.redirectErrorStream(false);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            builder.environment().putAll(extraEnv);
        }
        log.debug("exec: {}", String.join(" ", command));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(-1, "", "启动失败: " + e.getMessage(), false);
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outPump = pump(process.getInputStream(), out, stdoutLine);
        Thread errPump = pump(process.getErrorStream(), err, stderrLine);
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outPump.join(2000);
            errPump.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(-1, out.toString(), "执行被中断", false);
        }
        int code = finished ? process.exitValue() : -1;
        if (!finished) {
            log.warn("命令超时({}s): {}", timeout.toSeconds(), String.join(" ", command));
        }
        return new Result(code, out.toString(), err.toString(), !finished);
    }

    private Thread pump(java.io.InputStream stream, StringBuilder sink, Consumer<String> each) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink.length() < MAX_CAPTURE) {
                        sink.append(line).append('\n');
                    }
                    if (each != null) {
                        try {
                            each.accept(line);
                        } catch (RuntimeException e) {
                            log.debug("stdout 回调异常: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("读取进程输出失败: {}", e.getMessage());
            }
        }, "proc-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 命令是否可执行（用于自动探测本地工具是否就绪） */
    public boolean available(List<String> command) {
        try {
            Result result = run(command, Duration.ofSeconds(10));
            return result.exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static List<String> command(String... parts) {
        return new ArrayList<>(List.of(parts));
    }
}
