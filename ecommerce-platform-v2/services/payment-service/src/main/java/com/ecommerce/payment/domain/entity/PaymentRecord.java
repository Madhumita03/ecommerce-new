package com.ecommerce.payment.domain.entity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "payment_records")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "saga_id", unique = true, nullable = false) private UUID sagaId;
    @Column(name = "order_id", nullable = false)               private UUID orderId;
    @Column(name = "user_id",  nullable = false)               private UUID userId;
    @Column(nullable = false, precision = 12, scale = 2)       private BigDecimal amount;
    @Column(nullable = false)                                  private boolean success;
    @Column(name = "transaction_id", length = 200)             private String transactionId;
    @Column(name = "failure_reason", length = 500)             private String failureReason;
    @Builder.Default @Column(name = "created_at")             private Instant createdAt = Instant.now();
}
