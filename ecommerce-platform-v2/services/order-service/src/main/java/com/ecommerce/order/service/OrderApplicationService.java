package com.ecommerce.order.service;

import com.ecommerce.order.client.ProductCatalogClient;
import com.ecommerce.order.domain.dto.OrderDtos;
import com.ecommerce.order.domain.entity.Order;
import com.ecommerce.order.domain.entity.OrderItem;
import com.ecommerce.order.domain.entity.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ProductCatalogClient productCatalogClient;

    @Transactional
    public OrderDtos.OrderResponse create(OrderDtos.CreateOrderRequest request) {
        UUID sagaId = UUID.randomUUID();
        List<ResolvedItem> resolvedItems = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (OrderDtos.OrderItemRequest requestedItem : request.items()) {
            ProductCatalogClient.ProductSnapshot product =
                productCatalogClient.getProduct(requestedItem.productId());
            if (!"ACTIVE".equals(product.status())) {
                throw new IllegalArgumentException("Product is not available: " + product.name());
            }
            if (product.stockQuantity() < requestedItem.quantity()) {
                throw new IllegalArgumentException("Insufficient stock for " + product.name());
            }
            BigDecimal lineTotal = product.effectivePrice()
                .multiply(BigDecimal.valueOf(requestedItem.quantity()));
            resolvedItems.add(new ResolvedItem(product, requestedItem.quantity(), lineTotal));
            total = total.add(lineTotal);
        }

        Order order = Order.builder()
            .userId(request.userId())
            .shippingAddress(request.shippingAddress())
            .sagaId(sagaId)
            .totalAmount(total)
            .build();

        for (ResolvedItem resolved : resolvedItems) {
            order.addItem(OrderItem.builder()
                .productId(resolved.product().id())
                .productName(resolved.product().name())
                .sku(resolved.product().sku())
                .quantity(resolved.quantity())
                .unitPrice(resolved.product().effectivePrice())
                .lineTotal(resolved.lineTotal())
                .build());
        }
        Order saved = orderRepository.save(order);

        Map<String, Object> paymentRequest = new LinkedHashMap<>();
        paymentRequest.put("eventType", "PAYMENT_REQUESTED");
        paymentRequest.put("sagaId", sagaId);
        paymentRequest.put("orderId", saved.getId());
        paymentRequest.put("userId", saved.getUserId());
        paymentRequest.put("userEmail", request.userEmail());
        paymentRequest.put("amount", saved.getTotalAmount());
        enqueue("payment-events", sagaId.toString(), "PAYMENT_REQUESTED", paymentRequest);

        log.info("Order created orderId={} sagaId={} total={}", saved.getId(), sagaId, total);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or returnObject.userId().toString() == authentication.name")
    public OrderDtos.OrderResponse getById(UUID id) {
        return orderRepository.findByIdWithItems(id)
            .map(this::toResponse)
            .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<OrderDtos.OrderResponse> listByUser(UUID userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable).map(this::toResponse);
    }

    @Transactional
    public void handlePaymentResult(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            UUID sagaId = UUID.fromString(event.path("sagaId").asText());
            Order order = orderRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment saga: " + sagaId));

            if (order.getStatus() != Order.OrderStatus.PENDING) {
                log.info("Ignoring duplicate payment result sagaId={} status={}", sagaId, order.getStatus());
                return;
            }

            boolean success = event.path("success").asBoolean(false);
            String eventType;
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("userId", event.path("userId").asText(order.getUserId().toString()));
            notification.put("userEmail", event.path("userEmail").asText("noemail@example.com"));
            notification.put("orderId", order.getId());

            if (success) {
                order.confirm();
                eventType = "ORDER_CONFIRMED";
                notification.put("totalAmount", order.getTotalAmount());
                notification.put("transactionId", event.path("transactionId").asText());
            } else {
                String reason = event.path("failureReason").asText("Payment was declined");
                order.cancel(reason);
                eventType = "ORDER_CANCELLED";
                notification.put("reason", reason);
            }

            notification.put("eventType", eventType);
            enqueue("notification-events", order.getUserId().toString(), eventType, notification);
            log.info("Payment result applied orderId={} status={}", order.getId(), order.getStatus());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid payment result event", e);
        }
    }

    private void enqueue(String topic, String key, String eventType, Map<String, Object> payload) {
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                .topic(topic)
                .partitionKey(key)
                .eventType(eventType)
                .payload(objectMapper.writeValueAsString(payload))
                .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize outbox event", e);
        }
    }

    private OrderDtos.OrderResponse toResponse(Order order) {
        return new OrderDtos.OrderResponse(
            order.getId(),
            order.getUserId(),
            order.getItems().stream()
                .map(item -> new OrderDtos.OrderItemResponse(
                    item.getProductId(),
                    item.getProductName(),
                    item.getSku(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getLineTotal()))
                .toList(),
            order.getStatus(),
            order.getTotalAmount(),
            order.getShippingAddress(),
            order.getSagaId(),
            order.getCreatedAt(),
            order.getUpdatedAt());
    }

    private record ResolvedItem(
        ProductCatalogClient.ProductSnapshot product,
        int quantity,
        BigDecimal lineTotal
    ) {
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) {
            super("Order not found: " + id);
        }
    }
}
