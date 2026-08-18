package com.ecommerce.order.repository;
import com.ecommerce.order.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query(value = """
        SELECT * FROM outbox_events
        WHERE published=false AND retry_count < :max
        ORDER BY created_at
        LIMIT :lim
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findUnpublished(@Param("max") int max, @Param("lim") int lim);
}
