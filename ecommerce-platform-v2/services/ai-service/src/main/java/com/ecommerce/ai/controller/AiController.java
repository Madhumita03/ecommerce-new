package com.ecommerce.ai.controller;

import com.ecommerce.ai.service.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.util.List;

/**
 * Spring AI endpoints: chat, streaming, sentiment, recommendations.
 * SLF4J logging implicitly via service layer.
 */
@RestController @RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "AI", description = "ChatBot, recommendations, sentiment – Spring AI 1.0")
public class AiController {

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    @Operation(summary = "Chat with AI assistant (with RAG + conversation memory)")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        return ResponseEntity.ok(new ChatResponse(req.sessionId(),
            aiChatService.chat(req.sessionId(), req.message())));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Streaming chat via Server-Sent Events")
    public Flux<String> chatStream(@RequestBody ChatRequest req) {
        return aiChatService.chatStream(req.sessionId(), req.message());
    }

    @PostMapping("/sentiment")
    @Operation(summary = "Analyse product review sentiment")
    public ResponseEntity<AiChatService.SentimentResult> sentiment(@RequestBody SentimentRequest req) {
        return ResponseEntity.ok(aiChatService.analyzeSentiment(req.reviewText()));
    }

    @PostMapping("/recommend")
    @Operation(summary = "Personalised product recommendations")
    public ResponseEntity<RecommendResponse> recommend(@RequestBody RecommendRequest req) {
        return ResponseEntity.ok(new RecommendResponse(req.userId(),
            aiChatService.recommend(req.userId(), req.browsingHistory())));
    }

    record ChatRequest(@NotBlank String sessionId, @NotBlank String message) {}
    record ChatResponse(String sessionId, String response) {}
    record SentimentRequest(@NotBlank String reviewText) {}
    record RecommendRequest(@NotBlank String userId, @NotBlank String browsingHistory) {}
    record RecommendResponse(String userId, List<String> recommendations) {}
}
