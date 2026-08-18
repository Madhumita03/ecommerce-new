package com.ecommerce.order.messaging;

import com.ecommerce.order.domain.entity.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.batch-size:50}")
    private int batchSize;

    @Value("${outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        for (OutboxEvent event : outboxEventRepository.findUnpublished(maxRetries, batchSize)) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                    .get(10, TimeUnit.SECONDS);
                event.setPublished(true);
                event.setPublishedAt(Instant.now());
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                log.error("Outbox publish failed id={} attempt={}",
                    event.getId(), event.getRetryCount(), e);
            }
        }
    }
}
