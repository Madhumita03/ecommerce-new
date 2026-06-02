package com.ecommerce.payment.service;

import com.ecommerce.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for {@link PaymentService}.
 * JUnit 5.12 + Mockito 5.17 | SLF4J tested implicitly.
 * No real Kafka or DB connections – fully mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("PaymentService – unit tests")
class PaymentServiceTest {

    @Mock private PaymentRepository            paymentRepository;
    @Mock private KafkaTemplate<String,String> kafkaTemplate;
    @Mock private PaymentGateway               paymentGateway;

    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private PaymentService paymentService;

    private final UUID sagaId  = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID userId  = UUID.randomUUID();

    private String buildPayload(UUID saga, UUID order, UUID user, String amount) {
        return """
            {"sagaId":"%s","orderId":"%s","userId":"%s","amount":"%s"}
            """.formatted(saga, order, user, amount).strip();
    }

    @Nested
    @DisplayName("onPaymentRequested()")
    class OnPaymentRequestedTests {

        @Test
        @DisplayName("succeeds: saves record and publishes SUCCEEDED result")
        void shouldProcessPaymentAndPublishResult() {
            String payload = buildPayload(sagaId, orderId, userId, "149.99");
            given(paymentRepository.existsBySagaId(sagaId)).willReturn(false);
            given(paymentGateway.charge(userId, new BigDecimal("149.99"), orderId))
                .willReturn(new PaymentGateway.ChargeResult(true, "pi_abc123", null));

            paymentService.onPaymentRequested(payload);

            then(paymentRepository).should().save(any());
            then(kafkaTemplate).should().send(eq("payment-result-events"), anyString(), contains("true"));
        }

        @Test
        @DisplayName("skips processing on duplicate saga ID (idempotency guard)")
        void shouldSkipDuplicatePayment() {
            String payload = buildPayload(sagaId, orderId, userId, "99.99");
            given(paymentRepository.existsBySagaId(sagaId)).willReturn(true);

            paymentService.onPaymentRequested(payload);

            then(paymentGateway).should(never()).charge(any(), any(), any());
            then(paymentRepository).should(never()).save(any());
            then(kafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("publishes FAILED result when gateway declines payment")
        void shouldPublishFailedResult_onGatewayDecline() {
            String payload = buildPayload(sagaId, orderId, userId, "50.13"); // simulated fail
            given(paymentRepository.existsBySagaId(sagaId)).willReturn(false);
            given(paymentGateway.charge(userId, new BigDecimal("50.13"), orderId))
                .willReturn(new PaymentGateway.ChargeResult(false, null, "Declined"));

            paymentService.onPaymentRequested(payload);

            then(paymentRepository).should().save(any());
            then(kafkaTemplate).should().send(eq("payment-result-events"), anyString(), contains("false"));
        }

        @Test
        @DisplayName("throws RuntimeException and does NOT commit on malformed payload")
        void shouldThrow_onMalformedPayload() {
            assertThatThrownBy(() -> paymentService.onPaymentRequested("not-json"))
                .isInstanceOf(RuntimeException.class);
        }
    }
}
