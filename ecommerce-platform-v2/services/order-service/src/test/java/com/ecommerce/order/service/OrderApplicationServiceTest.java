package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductCatalogClient;
import com.ecommerce.order.domain.dto.OrderDtos;
import com.ecommerce.order.domain.entity.Order;
import com.ecommerce.order.domain.entity.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ProductCatalogClient productCatalogClient;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private OrderApplicationService orderService;

    @Test
    void createsOrderAndPaymentOutboxEventAtomically() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var request = new OrderDtos.CreateOrderRequest(
            userId,
            "shopper@example.com",
            "42 Market Street, San Francisco, CA",
            List.of(new OrderDtos.OrderItemRequest(productId, 2)));
        given(productCatalogClient.getProduct(productId)).willReturn(
            new ProductCatalogClient.ProductSnapshot(
                productId, "Headphones", "HP-1", new BigDecimal("49.50"),
                null, 5, "ACTIVE"));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderDtos.OrderResponse response = orderService.create(request);

        assertThat(response.totalAmount()).isEqualByComparingTo("99.00");
        assertThat(response.status()).isEqualTo(Order.OrderStatus.PENDING);
        assertThat(response.items()).hasSize(1);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should().save(event.capture());
        assertThat(event.getValue().getTopic()).isEqualTo("payment-events");
        assertThat(event.getValue().getPayload()).contains("shopper@example.com", "99.00");
    }

    @Test
    void confirmsOrderAndQueuesNotificationAfterSuccessfulPayment() {
        UUID sagaId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = Order.builder()
            .id(orderId)
            .userId(userId)
            .sagaId(sagaId)
            .totalAmount(new BigDecimal("75.00"))
            .build();
        given(orderRepository.findBySagaId(sagaId)).willReturn(Optional.of(order));

        orderService.handlePaymentResult("""
            {"sagaId":"%s","success":true,"userId":"%s",
             "userEmail":"shopper@example.com","transactionId":"pi_test"}
            """.formatted(sagaId, userId));

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should().save(event.capture());
        assertThat(event.getValue().getTopic()).isEqualTo("notification-events");
        assertThat(event.getValue().getPayload()).contains("ORDER_CONFIRMED", "pi_test");
    }
}
