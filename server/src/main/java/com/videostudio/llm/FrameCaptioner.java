package com.videostudio.llm;

import com.videostudio.common.Texts;
import com.videostudio.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 关键帧的可选视觉描述。
 *
 * <p>只解决 OCR 的天生短板：纯人像、实拍场景、图表画面里没有可识别的文字时，
 * 画面证据就是空的，检索与追问都够不到那一段视频。
 *
 * <p>刻意不做「全量帧都调视觉模型」：按帧计费，长视频几百帧成本不可控，
 * 而且绝大多数课件/录屏帧的文字已经由本地 OCR 拿到了，再调一次视觉模型是纯浪费。
 * 所以调用条件是「OCR 没有拿到任何文字」+「用户显式打开开关」+「不超过配额」。
 */
@Component
public class FrameCaptioner {

    private static final Logger log = LoggerFactory.getLogger(FrameCaptioner.class);

    /** 描述要短：它会被塞进上下文与报告，太长会挤掉真正的证据 */
    private static final int MAX_CHARS = 120;

    private static final String PROMPT = """
            请用一句中文描述这张视频关键帧的画面内容，重点写清场景、人物与正在发生的动作。
            画面里的文字如果清晰可读请一并转述，读不清就不要猜测。
            不要推测剧情，不要评价，不要复述这段提示词。最多 60 字。
            """;

    private final LlmGateway llm;
    private final AiProperties ai;

    public FrameCaptioner(LlmGateway llm, AiProperties ai) {
        this.llm = llm;
        this.ai = ai;
    }

    /** 是否值得尝试：需要真实模型 + 用户显式打开开关 + 还有配额 */
    public boolean enabled() {
        return ai.visionCaptionEnabled() && ai.visionCaptionMaxFrames() > 0;
    }

    public int quota() {
        return enabled() ? ai.visionCaptionMaxFrames() : 0;
    }

    /**
     * 生成一帧的描述；失败或未启用返回空串（调用方按「没有描述」处理，不影响主流程）。
     */
    public String describe(Path frame) {
        if (!enabled() || frame == null) {
            return "";
        }
        String dataUri = toDataUri(frame);
        if (dataUri.isEmpty()) {
            return "";
        }
        String raw = llm.vision(PROMPT, dataUri, "frame-caption");
        if (raw == null || raw.isBlank()) throw new IllegalStateException("视觉模型返回空内容");
        return Texts.oneLine(Texts.normalizeWhitespace(raw), MAX_CHARS);
    }

    /** 读成 data URI；图片通常 20-40KB，直接内联比再挂一个上传接口简单 */
    private String toDataUri(Path frame) {
        try {
            byte[] bytes = Files.readAllBytes(frame);
            if (bytes.length == 0) throw new IllegalStateException("关键帧为空，无法描述");
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("读取关键帧失败",e);
        }
    }
}
