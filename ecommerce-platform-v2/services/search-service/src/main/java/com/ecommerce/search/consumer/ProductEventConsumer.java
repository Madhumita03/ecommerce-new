package com.ecommerce.search.consumer;

import com.ecommerce.search.service.AutocompleteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventConsumer {

    private final AutocompleteService autocompleteService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "product-events", groupId = "search-service-indexer")
    public void consume(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.path("eventType").asText();
            String productId = event.path("productId").asText();
            String productName = event.path("productName").asText();

            if (productId.isBlank() || productName.isBlank()) {
                log.warn("Ignoring incomplete product event: {}", payload);
                return;
            }

            if ("DELETED".equals(eventType)) {
                autocompleteService.removeProduct(productName, productId);
            } else if ("CREATED".equals(eventType) || "UPDATED".equals(eventType)) {
                autocompleteService.indexProduct(productName, productId);
            }
        } catch (Exception e) {
            log.error("Unable to index product event", e);
            throw new IllegalArgumentException("Invalid product event", e);
        }
    }
}
