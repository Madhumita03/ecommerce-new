package com.ecommerce.notification.consumer;

import com.ecommerce.notification.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer – routes notification events to the right channel.
 * SLF4J @Slf4j only – no logback imports.
 */
@Slf4j @Component @RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper        objectMapper;

    @KafkaListener(topics = "notification-events", groupId = "notification-service")
    public void consume(@Payload String payload,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.debug("Event received topic={} offset={}", topic, offset);
        try {
            JsonNode node       = objectMapper.readTree(payload);
            String   eventType  = node.path("eventType").asText();
            String   userId     = node.path("userId").asText();
            String   userEmail  = node.path("userEmail").asText("noemail@example.com");
            String   orderId    = node.path("orderId").asText();

            switch (eventType) {
                case "ORDER_CONFIRMED" -> notificationService.notifyOrderConfirmed(
                    userEmail, userId, orderId, node.path("totalAmount").asText());
                case "ORDER_SHIPPED"   -> notificationService.notifyOrderShipped(
                    userEmail, userId, orderId, node.path("trackingNumber").asText("N/A"));
                case "ORDER_CANCELLED" -> notificationService.sendEmail(userEmail,
                    "❌ Order Cancelled", "order-cancelled",
                    java.util.Map.of("orderId", orderId, "reason", node.path("reason").asText()));
                default -> log.debug("No handler for eventType={}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process notification event", e);
            throw new RuntimeException("Notification failed", e);
        }
    }
}
