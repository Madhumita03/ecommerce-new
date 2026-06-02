package com.ecommerce.payment.service;

import com.ecommerce.payment.domain.entity.PaymentRecord;
import com.ecommerce.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Idempotent Kafka saga participant for payment processing.
 * SLF4J @Slf4j – no logback references.
 */
@Slf4j @Service @RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository           paymentRepository;
    private final KafkaTemplate<String,String> kafkaTemplate;
    private final PaymentGateway               paymentGateway;
    private final ObjectMapper                 objectMapper;

    @KafkaListener(topics = "payment-events", groupId = "payment-service")
    @Transactional
    public void onPaymentRequested(String payload) {
        try {
            JsonNode node    = objectMapper.readTree(payload);
            UUID sagaId      = UUID.fromString(node.get("sagaId").asText());
            UUID orderId     = UUID.fromString(node.get("orderId").asText());
            UUID userId      = UUID.fromString(node.get("userId").asText());
            BigDecimal amount = new BigDecimal(node.get("amount").asText());

            // Idempotency guard – skip duplicate Kafka deliveries
            if (paymentRepository.existsBySagaId(sagaId)) {
                log.warn("Duplicate payment request sagaId={} – skipping", sagaId);
                return;
            }

            log.info("Processing payment sagaId={} amount={}", sagaId, amount);
            PaymentGateway.ChargeResult result = paymentGateway.charge(userId, amount, orderId);

            paymentRepository.save(PaymentRecord.builder()
                .sagaId(sagaId).orderId(orderId).userId(userId)
                .amount(amount).success(result.success())
                .transactionId(result.transactionId())
                .failureReason(result.failureReason()).build());

            String resultPayload = "{\"sagaId\":\"%s\",\"success\":%b,\"transactionId\":\"%s\"}"
                .formatted(sagaId, result.success(), result.transactionId());
            kafkaTemplate.send("payment-result-events", sagaId.toString(), resultPayload);
            log.info("Payment result published sagaId={} success={}", sagaId, result.success());

        } catch (Exception e) {
            log.error("Payment processing error", e);
            throw new RuntimeException("Payment failed", e);
        }
    }
}
