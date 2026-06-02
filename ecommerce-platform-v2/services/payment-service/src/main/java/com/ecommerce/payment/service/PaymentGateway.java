package com.ecommerce.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Strategy: pluggable payment gateway.
 * SLF4J @Slf4j – no logback imports.
 */
public interface PaymentGateway {

    ChargeResult charge(UUID userId, BigDecimal amount, UUID orderId);

    record ChargeResult(boolean success, String transactionId, String failureReason) {}

    @Slf4j
    @Component
    class StripePaymentGateway implements PaymentGateway {

        @Value("${payment.stripe.secret-key:sk_test_placeholder}")
        private String stripeSecretKey;

        @Override
        public ChargeResult charge(UUID userId, BigDecimal amount, UUID orderId) {
            log.info("Stripe charge userId={} amount={} orderId={}", userId, amount, orderId);
            // Production: integrate Stripe SDK here
            // PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()...
            boolean success = !amount.toPlainString().endsWith("13");
            String  txId    = success ? "pi_" + UUID.randomUUID().toString().replace("-","") : null;
            return new ChargeResult(success, txId, success ? null : "Simulated failure");
        }
    }
}
