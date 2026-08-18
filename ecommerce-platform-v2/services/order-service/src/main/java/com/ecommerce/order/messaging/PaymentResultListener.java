package com.ecommerce.order.messaging;

import com.ecommerce.order.service.OrderApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentResultListener {

    private final OrderApplicationService orderService;

    @KafkaListener(topics = "payment-result-events", groupId = "order-saga")
    public void onPaymentResult(String payload) {
        orderService.handlePaymentResult(payload);
    }
}
