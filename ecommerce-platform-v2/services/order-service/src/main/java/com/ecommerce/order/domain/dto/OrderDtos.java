package com.ecommerce.order.domain.dto;

import com.ecommerce.order.domain.entity.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() {
    }

    public record CreateOrderRequest(
        @NotNull UUID userId,
        @NotBlank @Email String userEmail,
        @NotBlank @Size(max = 500) String shippingAddress,
        @NotEmpty List<@Valid OrderItemRequest> items
    ) {
    }

    public record OrderItemRequest(
        @NotNull UUID productId,
        @NotNull @Positive Integer quantity
    ) {
    }

    public record OrderItemResponse(
        UUID productId,
        String productName,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
    ) {
    }

    public record OrderResponse(
        UUID id,
        UUID userId,
        List<OrderItemResponse> items,
        Order.OrderStatus status,
        BigDecimal totalAmount,
        String shippingAddress,
        UUID sagaId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }
}
