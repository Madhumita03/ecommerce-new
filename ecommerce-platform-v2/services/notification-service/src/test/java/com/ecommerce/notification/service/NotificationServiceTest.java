package com.ecommerce.notification.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for {@link NotificationService}.
 * JUnit 5.12 + Mockito 5.17 | SLF4J via @Slf4j (no logback refs in tests).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("NotificationService – unit tests")
class NotificationServiceTest {

    @Mock private JavaMailSender        mailSender;
    @Mock private SimpMessagingTemplate wsTemplate;
    @Mock private TemplateEngine        templateEngine;
    @Mock private MimeMessage           mimeMessage;

    @InjectMocks private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        given(mailSender.createMimeMessage()).willReturn(mimeMessage);
        given(templateEngine.process(anyString(), any(IContext.class)))
            .willReturn("<html>Order confirmed</html>");
    }

    @Nested
    @DisplayName("sendEmail()")
    class SendEmailTests {

        @Test
        @DisplayName("sends MimeMessage via JavaMailSender on success")
        void shouldSendEmail() {
            notificationService.sendEmail(
                "user@example.com", "Test Subject", "order-confirmed", Map.of());

            then(mailSender).should().send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("does not propagate exceptions (fire-and-forget, log-only)")
        void shouldNotThrow_onMailSenderFailure() {
            willThrow(new RuntimeException("SMTP down")).given(mailSender).send(any(MimeMessage.class));

            assertThatNoException().isThrownBy(() ->
                notificationService.sendEmail("u@x.com", "S", "t", Map.of()));
        }
    }

    @Nested
    @DisplayName("pushWebSocket()")
    class WebSocketTests {

        @Test
        @DisplayName("sends payload to correct user destination")
        void shouldPushToUserDestination() {
            var payload = new NotificationService.NotificationPayload(
                "ORDER_CONFIRMED", "Confirmed!", Map.of("orderId", "abc"));

            notificationService.pushWebSocket("user-123", payload);

            then(wsTemplate).should()
                .convertAndSendToUser(eq("user-123"), eq("/queue/notifications"), eq(payload));
        }

        @Test
        @DisplayName("does not propagate WebSocket exceptions")
        void shouldNotThrow_onWebSocketFailure() {
            willThrow(new RuntimeException("WS error"))
                .given(wsTemplate).convertAndSendToUser(anyString(), anyString(), any());

            assertThatNoException().isThrownBy(() ->
                notificationService.pushWebSocket("uid", new NotificationService.NotificationPayload(
                    "TYPE", "msg", Map.of())));
        }
    }

    @Nested
    @DisplayName("notifyOrderConfirmed()")
    class OrderConfirmedTests {

        @Test
        @DisplayName("sends both email and WebSocket push")
        void shouldSendEmailAndWebSocket() {
            notificationService.notifyOrderConfirmed(
                "u@e.com", "uid-001", "order-123", "99.99");

            then(mailSender).should().send(any(MimeMessage.class));
            then(wsTemplate).should().convertAndSendToUser(
                eq("uid-001"), eq("/queue/notifications"), any());
        }
    }
}
