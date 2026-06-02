package com.ecommerce.order.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Order aggregate root (DDD). Guards all state transitions (State Machine).
 * SLF4J logging via @Slf4j in service layer only – entity stays clean.
 */
@Entity @Table(name = "orders")
@Getter @Setter(AccessLevel.PACKAGE) @Builder @NoArgsConstructor @AllArgsConstructor
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false)         private UUID userId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @Builder.Default private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal totalAmount;
    @Column(name = "shipping_address", length = 500)     private String shippingAddress;
    @Column(name = "saga_id", unique = true)             private UUID sagaId;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp   @Column(name = "updated_at")                    private LocalDateTime updatedAt;
    @Version private Long version;

    public void confirm() { requireStatus(OrderStatus.PENDING);    status = OrderStatus.CONFIRMED; }
    public void ship()    { requireStatus(OrderStatus.CONFIRMED);  status = OrderStatus.SHIPPED; }
    public void deliver() { requireStatus(OrderStatus.SHIPPED);    status = OrderStatus.DELIVERED; }
    public void cancel(String reason) {
        if (status == OrderStatus.DELIVERED) throw new IllegalStateException("Cannot cancel delivered order");
        status = OrderStatus.CANCELLED;
    }

    public void addItem(OrderItem item) { items.add(item); item.setOrder(this); }

    private void requireStatus(OrderStatus required) {
        if (status != required)
            throw new IllegalStateException("Invalid transition: current=%s required=%s".formatted(status, required));
    }

    public enum OrderStatus { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, REFUNDED }
}
