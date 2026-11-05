package com.videostudio.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostudio.common.*;
import com.videostudio.config.AiProperties;
import com.videostudio.infra.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

@Component
public class LlmGateway {
    private final AiProperties ai; private final JobMetrics metrics; private final BudgetGuard budget;
    public LlmGateway(AiProperties ai) { this(ai,null,null); }
    public LlmGateway(AiProperties ai,JobMetrics metrics) { this(ai,metrics,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public LlmGateway(AiProperties ai,JobMetrics metrics,BudgetGuard budget) { this.ai=ai; this.metrics=metrics; this.budget=budget; }
    public boolean enabled() { return ai.cloudEnabled(); }
    public record Message(String role,String content) {
        public static Message system(String s) { return new Message("system",s); }
        public static Message user(String s) { return new Message("user",s); }
        public static Message assistant(String s) { return new Message("assistant",s); }
    }
    public record ChatResult(String content,int promptTokens,int completionTokens,String model) {
        public int totalTokens() { return promptTokens+completionTokens; }
    }
    public ChatResult chat(List<Message> messages,double temperature,Integer maxTokens,String purpose) {
        ObjectNode payload=Json.object(); payload.put("model",ai.getModel()); payload.put("temperature",temperature);
        var items=payload.putArray("messages");
        for(var message:messages) { var node=items.addObject(); node.put("role",message.role()); node.put("content",message.content()); }
        if(maxTokens!=null) payload.put("max_tokens",maxTokens);
        long started=System.nanoTime();
        try {
            JsonNode root=exchange(ai.chatUrl(),ai.getApiKey(),payload,purpose);
            String text=root.path("choices").path(0).path("message").path("content").asText("");
            if(text.isBlank()) throw new ApiException(502,"模型未返回有效内容，请重试");
            if(metrics!=null) metrics.llmCall(purpose,true,Duration.ofNanos(System.nanoTime()-started));
            var usage=root.path("usage");
            return new ChatResult(text.strip(),usage.path("prompt_tokens").asInt(),usage.path("completion_tokens").asInt(),root.path("model").asText(ai.getModel()));
        } catch(RuntimeException ex) {
            if(metrics!=null) metrics.llmCall(purpose,false,Duration.ofNanos(System.nanoTime()-started));
            throw ex;
        }
    }
    public String vision(String prompt,String imageDataUri,String purpose) {
        var payload=Json.object(); payload.put("model",ai.getVisionModel()); payload.put("max_tokens",300);
        var message=payload.putArray("messages").addObject(); message.put("role","user");
        var content=message.putArray("content"); var text=content.addObject();
        text.put("type","text"); text.put("text",prompt);
        var image=content.addObject(); image.put("type","image_url"); image.putObject("image_url").put("url",imageDataUri);
        String answer=exchange(ai.chatUrl(),ai.getApiKey(),payload,purpose).path("choices").path(0).path("message").path("content").asText("");
        if(answer.isBlank()) throw new ApiException(502,"视觉模型返回空内容");
        return answer;
    }
    public List<Double> embed(String text) { return embed(List.of(text)).get(0); }
    public List<List<Double>> embed(List<String> texts) {
        if(texts.isEmpty()) return List.of();
        var payload=Json.object(); payload.put("model",ai.getEmbeddingModel());
        texts.forEach(payload.putArray("input")::add);
        var root=exchange(ai.embeddingUrl(),ai.resolvedEmbeddingApiKey(),payload,"embedding");
        List<List<Double>> vectors=new ArrayList<>(Collections.nCopies(texts.size(),null));
        for(var item:root.path("data")) {
            int index=item.path("index").asInt(-1);
            if(index<0 || index>=texts.size() || vectors.get(index)!=null) throw new ApiException(502,"向量模型返回的索引无效");
            List<Double> vector=new ArrayList<>();
            for(var value:item.path("embedding")) {
                double n=value.asDouble(Double.NaN);
                if(!Double.isFinite(n)) throw new ApiException(502,"向量模型返回无效数值");
                vector.add(n);
            }
            if(vector.isEmpty()) throw new ApiException(502,"向量模型返回空向量");
            vectors.set(index,vector);
        }
        if(vectors.contains(null)) throw new ApiException(502,"向量模型返回数量不匹配");
        return vectors;
    }
    private JsonNode exchange(String url,String key,ObjectNode payload,String purpose) {
        if(!enabled() || key==null || key.isBlank()) throw new ApiException(503,"模型服务尚未正确配置");
        if(budget!=null) budget.requireAvailable();
        int attempts=Math.max(1,ai.getMaxRetries());
        for(int attempt=1;attempt<=attempts;attempt++) {
            Http.Response response;
            try { response=Http.postJson(url,Map.of("Authorization","Bearer "+key,"Accept","application/json"),Json.write(payload),Duration.ofSeconds(ai.getTimeoutSeconds())); }
            catch(RuntimeException ex) {
                if(Thread.currentThread().isInterrupted() || attempt==attempts) throw new ApiException(502,"模型服务连接失败，请稍后重试");
                pause(attempt); continue;
            }
            if(response.ok()) {
                JsonNode root=Json.tree(response.body());
                if(root==null || root.has("error")) throw new ApiException(502,"模型服务返回无效响应");
                int tokens=root.path("usage").path("total_tokens").asInt(0);
                if(tokens==0) tokens=root.path("usage").path("prompt_tokens").asInt()+root.path("usage").path("completion_tokens").asInt();
                if(tokens==0) {
                    tokens=Json.write(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        +response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
                if(budget!=null) budget.consumeCurrent(tokens);
                if(metrics!=null) metrics.tokens(payload.path("model").asText(),tokens);
                return root;
            }
            if(response.status()!=429 && response.status()<500) throw new ApiException(502,"模型服务拒绝请求（HTTP "+response.status()+"），请检查模型与服务端配置");
            if(attempt<attempts) pause(attempt);
        }
        throw new ApiException(502,"模型服务暂时不可用，重试次数已用尽");
    }
    private void pause(int attempt) {
        try { Thread.sleep(500L*attempt); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new ApiException(503,"模型调用被中断"); }
    }
}
