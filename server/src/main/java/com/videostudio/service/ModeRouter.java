package com.videostudio.service;

import com.videostudio.agent.ModeRegistry;
import com.videostudio.agent.PromptLibrary;
import com.videostudio.common.Json;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.llm.LlmGateway;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 模式路由：用户选「自动判断」时，让模型在<b>当前注册的模式</b>里选一个。
 *
 * <p>可选模式清单来自 {@link ModeRegistry} 而不是写死在提示词里：提示词里那份清单
 * 一旦和代码不同步，模型就会返回一个不存在的模式名，任务直接失败。
 * <p>判定失败不猜、不降级——猜错的模式会产出一份「看起来正常、其实用错提纲」的报告，
 * 比直接报错更难发现。
 */
@Service
public class ModeRouter {

    private final LlmGateway llm;
    private final PromptLibrary prompts;
    private final ModeRegistry modes;

    public ModeRouter(LlmGateway llm, PromptLibrary prompts, ModeRegistry modes) {
        this.llm = llm;
        this.prompts = prompts;
        this.modes = modes;
    }

    public record Decision(AnalysisMode mode, String reason, boolean byModel) {
    }

    public Decision route(String goal) {
        if (goal == null || goal.isBlank()) {
            return new Decision(modes.defaultMode(), "未指定目标，使用通用模式", false);
        }
        String catalog = modes.ordered().stream()
                .map(mode -> "- " + mode.code() + "：" + mode.guidance())
                .collect(Collectors.joining("\n"));
        String user = prompts.get("mode-router") + "\n可选模式：\n" + catalog + "\n\n用户目标：" + goal;
        String raw = llm.chat(List.of(LlmGateway.Message.system(prompts.get("system")),
                LlmGateway.Message.user(user)), 0, 200, "mode-route").content();
        raw = stripFence(raw);
        var node = Json.tree(raw);
        String code = node.path("mode").asText("").toUpperCase(java.util.Locale.ROOT);
        AnalysisMode mode = modes.find(code).orElseThrow(() -> new IllegalStateException(
                "自动模式判断返回了未注册的模式 " + code + "，请重试或手动选择模式"));
        String reason = node.path("reason").asText("根据分析目标选择");
        return new Decision(mode, reason.substring(0, Math.min(200, reason.length())), true);
    }

    /** 模型常把 JSON 包在 ``` 围栏里；剥掉后再解析，避免围栏让判定白白失败 */
    private static String stripFence(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (firstLine > 0 && end > firstLine) {
                text = text.substring(firstLine + 1, end).strip();
            }
        }
        return text;
    }
}
