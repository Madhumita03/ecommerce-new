package com.ecommerce.ai.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for {@link AiChatService}.
 * JUnit 5.12 + Mockito 5.17 | Spring AI mocked – no real OpenAI calls.
 * SLF4J @Slf4j is tested implicitly (no logback class references).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AiChatService – unit tests")
class AiChatServiceTest {

    @Mock private ChatModel            chatModel;
    @Mock private VectorStore          vectorStore;
    @Mock private StringRedisTemplate  redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ChatResponse         chatResponse;
    @Mock private Generation           generation;

    @InjectMocks private AiChatService service;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(valueOps);
        given(valueOps.get(startsWith("chat:history:"))).willReturn(null); // empty history
        given(vectorStore.similaritySearch(any())).willReturn(List.of()); // no RAG docs
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponse);
        given(chatResponse.getResult()).willReturn(generation);
        given(generation.getOutput()).willReturn(new AssistantMessage("Mock AI response"));
    }

    @Nested
    @DisplayName("chat()")
    class ChatTests {

        @Test
        @DisplayName("returns AI response and persists conversation history")
        void shouldReturnResponseAndSaveHistory() {
            String result = service.chat("session-1", "What headphones are good?");

            assertThat(result).isEqualTo("Mock AI response");
            then(valueOps).should().set(eq("chat:history:session-1"), anyString(), any());
        }

        @Test
        @DisplayName("calls chatModel with a prompt containing system + user message")
        void shouldCallChatModelWithPrompt() {
            service.chat("session-2", "Tell me about Sony WH-1000XM5");

            var captor = ArgumentCaptor.forClass(Prompt.class);
            then(chatModel).should().call(captor.capture());
            List<?> messages = captor.getValue().getInstructions();
            assertThat(messages).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("analyzeSentiment()")
    class SentimentTests {

        @ParameterizedTest(name = "raw={0} → sentiment={1}")
        @CsvSource({
            "sentiment:POSITIVE,confidence:0.9,summary:Great,       POSITIVE, 0.9",
            "sentiment:NEGATIVE,confidence:0.8,summary:Bad,         NEGATIVE, 0.8",
            "sentiment:NEUTRAL, confidence:0.5,summary:Average,     NEUTRAL,  0.5"
        })
        @DisplayName("parses JSON sentiment response correctly")
        void shouldParseSentiment(String raw, String expectedSentiment, double expectedConf) {
            given(chatModel.call(any(Prompt.class))).willReturn(chatResponse);
            given(generation.getOutput()).willReturn(new AssistantMessage(
                "{" + raw + "}"));

            var result = service.analyzeSentiment("some review");
            assertThat(result.sentiment()).isEqualTo(expectedSentiment);
            assertThat(result.confidence()).isCloseTo(expectedConf, within(0.01));
        }

        @Test
        @DisplayName("returns NEUTRAL defaults on malformed AI response")
        void shouldReturnNeutralDefaults_onMalformedResponse() {
            given(generation.getOutput()).willReturn(new AssistantMessage("not-json-at-all"));

            var result = service.analyzeSentiment("any review");
            assertThat(result.sentiment()).isEqualTo("NEUTRAL");
            assertThat(result.confidence()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("recommend()")
    class RecommendTests {

        @Test
        @DisplayName("parses AI JSON array and returns up to 5 product names")
        void shouldReturnUpToFiveRecommendations() {
            given(generation.getOutput()).willReturn(new AssistantMessage(
                "[\"Product A\",\"Product B\",\"Product C\",\"Product D\",\"Product E\",\"Product F\"]"));

            List<String> recs = service.recommend("user-1", "iPhone, MacBook");

            assertThat(recs).hasSize(5);
            assertThat(recs.get(0)).isEqualTo("Product A");
        }
    }
}
