package com.ecommerce.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Chatbot powered by Spring AI 1.0 + OpenAI GPT-4o.
 *
 * Spring Boot 3.5 / Spring AI 1.0 features:
 *   • ChatClient fluent API
 *   • VectorStore (PgVector) for RAG
 *   • SSE streaming via Flux<String>
 *   • Structured outputs for sentiment/recommendations
 *
 * SLF4J @Slf4j – zero logback class references.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final String SYSTEM_PROMPT = """
        You are a helpful e-commerce shopping assistant for ShopEase.
        Help customers find products, answer order questions, and make recommendations.
        Be concise, friendly, and accurate. Never fabricate product details.
        """;

    private static final String HISTORY_PREFIX = "chat:history:";
    private static final int    MAX_HISTORY    = 20;
    private static final Duration HISTORY_TTL  = Duration.ofHours(2);

    private final ChatModel           chatModel;
    private final VectorStore         vectorStore;
    private final StringRedisTemplate redis;

    // ── Standard chat ─────────────────────────────────────────────────────────

    public String chat(String sessionId, String userMessage) {
        log.debug("chat sessionId={}", sessionId);

        List<Message> history   = loadHistory(sessionId);
        String        context   = ragContext(userMessage);
        List<Message> messages  = buildMessages(context, history, userMessage);

        String response = chatModel.call(new Prompt(messages))
            .getResult().getOutput().getText();

        history.add(new UserMessage(userMessage));
        history.add(new AssistantMessage(response));
        saveHistory(sessionId, history);
        return response;
    }

    // ── Streaming chat (SSE) ──────────────────────────────────────────────────

    public Flux<String> chatStream(String sessionId, String userMessage) {
        String context  = ragContext(userMessage);
        List<Message> messages = buildMessages(context, loadHistory(sessionId), userMessage);

        return ChatClient.create(chatModel).prompt()
            .messages(messages).stream().content()
            .doOnComplete(() -> log.debug("Stream complete sessionId={}", sessionId));
    }

    // ── Sentiment analysis ────────────────────────────────────────────────────

    public SentimentResult analyzeSentiment(String review) {
        String prompt = """
            Analyse the sentiment of this product review.
            Respond ONLY with JSON: {"sentiment":"POSITIVE|NEGATIVE|NEUTRAL","confidence":0.0-1.0,"summary":"one sentence"}
            Review: %s""".formatted(review);
        String raw = ChatClient.create(chatModel).prompt().user(prompt).call().content();
        return parseSentiment(raw);
    }

    // ── Recommendations ───────────────────────────────────────────────────────

    public List<String> recommend(String userId, String browsingHistory) {
        String prompt = """
            Based on these recently viewed products, suggest 5 similar or complementary products.
            Return ONLY a JSON array: ["Product 1","Product 2",...]
            Viewed: %s""".formatted(browsingHistory);
        String raw = ChatClient.create(chatModel).prompt().user(prompt).call().content();
        String cleaned = raw.replaceAll("```json|```", "").trim();
        return Arrays.stream(cleaned.replace("[","").replace("]","").split(","))
            .map(s -> s.trim().replace("\"","")).filter(s -> !s.isBlank())
            .limit(5).collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Message> buildMessages(String context, List<Message> history, String userMsg) {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(SYSTEM_PROMPT + "\n\n[PRODUCT CONTEXT]:\n" + context));
        messages.addAll(history.stream()
            .skip(Math.max(0, history.size() - MAX_HISTORY)).toList());
        messages.add(new UserMessage(userMsg));
        return messages;
    }

    private String ragContext(String query) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(3).similarityThreshold(0.7).build());
            return docs.isEmpty() ? "No product context." :
                docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("RAG retrieval failed: {}", e.getMessage());
            return "Context unavailable.";
        }
    }

    private List<Message> loadHistory(String sessionId) {
        String raw = redis.opsForValue().get(HISTORY_PREFIX + sessionId);
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        return Arrays.stream(raw.split("\n\\|\\|\\|\n"))
            .map(line -> line.startsWith("USER:") ?
                (Message) new UserMessage(line.substring(5)) :
                new AssistantMessage(line.substring(3)))
            .collect(Collectors.toList());
    }

    private void saveHistory(String sessionId, List<Message> messages) {
        String serialized = messages.stream()
            .map(m -> m instanceof UserMessage u ? "USER:" + u.getText() : "AI:" + ((AssistantMessage)m).getText())
            .collect(Collectors.joining("\n|||\n"));
        redis.opsForValue().set(HISTORY_PREFIX + sessionId, serialized, HISTORY_TTL);
    }

    private SentimentResult parseSentiment(String raw) {
        try {
            String c = raw.replace("{","").replace("}","").replace("\"","");
            String sentiment = "NEUTRAL"; double confidence = 0.5; String summary = "N/A";
            for (String part : c.split(",")) {
                if (part.contains("sentiment:"))  sentiment  = part.split(":")[1].trim();
                if (part.contains("confidence:")) confidence = Double.parseDouble(part.split(":")[1].trim());
                if (part.contains("summary:"))    summary    = part.split(":",2)[1].trim();
            }
            return new SentimentResult(sentiment, confidence, summary);
        } catch (Exception e) {
            return new SentimentResult("NEUTRAL", 0.5, "Analysis unavailable");
        }
    }

    public record SentimentResult(String sentiment, double confidence, String summary) {}
}
