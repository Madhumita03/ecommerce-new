package com.ecommerce.product.event;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

/** Domain event published to Kafka product-events topic. */
@Data @Builder
public class ProductEvent {
    public enum EventType { CREATED, UPDATED, DELETED, OUT_OF_STOCK, RESTOCKED }
    private EventType eventType;
    private UUID productId;
    private String productName;
    private String sku;
    private Long categoryId;
    @Builder.Default private Instant occurredAt = Instant.now();
    private String traceId;
}
