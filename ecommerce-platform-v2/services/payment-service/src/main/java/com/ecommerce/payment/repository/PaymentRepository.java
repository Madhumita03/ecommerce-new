package com.ecommerce.payment.repository;
import com.ecommerce.payment.domain.entity.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PaymentRepository extends JpaRepository<PaymentRecord, UUID> {
    boolean existsBySagaId(UUID sagaId);
}
