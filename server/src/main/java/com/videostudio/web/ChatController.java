package com.videostudio.web;

import com.videostudio.common.ApiResponse;
import com.videostudio.domain.ChatMessage;
import com.videostudio.service.FollowUpService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 报告追问：基于已有证据的连续对话 */
@RestController
@RequestMapping("/api/jobs/{id}/chat")
public class ChatController {

    private final FollowUpService followUpService;

    public ChatController(FollowUpService followUpService) {
        this.followUpService = followUpService;
    }

    public record AskRequest(String question) {
    }

    @PostMapping
    public ApiResponse<Map<String, String>> ask(@PathVariable long id, @RequestBody AskRequest body) {
        String answer = followUpService.ask(id, body == null ? null : body.question());
        return ApiResponse.success(Map.of("answer", answer));
    }

    @GetMapping
    public ApiResponse<List<ChatMessage>> history(@PathVariable long id) {
        return ApiResponse.success(followUpService.history(id));
    }

    @DeleteMapping
    public ApiResponse<Void> clear(@PathVariable long id) {
        followUpService.clear(id);
        return ApiResponse.success();
    }
}
