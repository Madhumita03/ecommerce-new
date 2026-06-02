package com.ecommerce.product.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Product aggregate.
 * Sharding key: category_id (ShardingSphere MOD algorithm).
 * Optimistic locking via @Version.
 */
@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_product_category", columnList = "category_id"),
    @Index(name = "idx_product_sku",      columnList = "sku", unique = true)
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Product {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255) private String name;
    @Column(columnDefinition = "TEXT")      private String description;
    @Column(nullable = false, unique = true, length = 100) private String sku;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal price;
    @Column(name = "sale_price", precision = 12, scale = 2) private BigDecimal salePrice;
    @Column(nullable = false)               private Integer stockQuantity;

    /** Sharding key */
    @Column(name = "category_id", nullable = false) private Long categoryId;

    @Column(length = 500) private String imageUrl;
    @Column(length = 200) private String vendorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) @Builder.Default
    private ProductStatus status = ProductStatus.ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version private Long version;

    public enum ProductStatus { ACTIVE, INACTIVE, OUT_OF_STOCK, DISCONTINUED }
}
