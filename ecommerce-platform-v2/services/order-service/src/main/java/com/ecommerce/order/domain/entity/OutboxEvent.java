package com.ecommerce.order.domain.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox: event row written in same DB tx as Order.
 * OutboxPoller reads and publishes these to Kafka.
 */
@Entity @Table(name = "outbox_events")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String topic;
    @Column(name = "partition_key", length = 200) private String partitionKey;
    @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Builder.Default private boolean published = false;
    @Builder.Default @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "published_at") private Instant publishedAt;
    @Builder.Default private int retryCount = 0;
}
