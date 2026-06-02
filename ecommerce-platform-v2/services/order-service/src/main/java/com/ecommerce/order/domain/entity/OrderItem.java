package com.ecommerce.order.domain.entity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "order_items")
@Getter @Setter(AccessLevel.PACKAGE) @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItem {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id", nullable = false) private Order order;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(nullable = false, length = 255) private String productName;
    @Column(nullable = false, length = 100) private String sku;
    @Column(nullable = false)               private Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2) private BigDecimal unitPrice;
    @Column(name = "line_total", nullable = false, precision = 12, scale = 2) private BigDecimal lineTotal;
}
