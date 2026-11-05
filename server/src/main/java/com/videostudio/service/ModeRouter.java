package com.videostudio.service;

import com.videostudio.agent.PromptLibrary;
import com.videostudio.agent.ContextRenderer;
import com.videostudio.common.Json;
import com.videostudio.domain.AnalysisMode;
import com.videostudio.llm.LlmGateway;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Locale;

@Service
public class ModeRouter {
    private final LlmGateway llm; private final PromptLibrary prompts;
    public ModeRouter(LlmGateway llm,PromptLibrary prompts) { this.llm=llm; this.prompts=prompts; }
    public record Decision(AnalysisMode mode,String reason,boolean byModel) {}
    public Decision route(String goal) {
        if(goal==null || goal.isBlank()) return new Decision(AnalysisMode.GENERAL,"未指定目标，使用通用模式",false);
        String raw=llm.chat(List.of(LlmGateway.Message.system(prompts.get("system")),
            LlmGateway.Message.user(prompts.get("mode-router")+"\n用户目标："+goal)),0,200,"mode-route").content();
        raw=raw.strip().replaceAll("(?s)^" + String.valueOf((char)96).repeat(3)+"(?:json)?\\s*|"+String.valueOf((char)96).repeat(3)+"$", "").strip();
        var node=Json.tree(raw);
        try {
            AnalysisMode mode=AnalysisMode.valueOf(node.path("mode").asText().toUpperCase(Locale.ROOT));
            String reason=node.path("reason").asText("根据分析目标选择");
            return new Decision(mode,reason.substring(0,Math.min(200,reason.length())),true);
        } catch(RuntimeException ex) { throw new IllegalStateException("自动模式判断返回无效结构，请重试或手动选择模式",ex); }
    }
}
