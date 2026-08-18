package com.ecommerce.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class ProductCatalogClient {

    private final RestClient restClient;

    public ProductCatalogClient(
            RestClient.Builder builder,
            @Value("${services.product.url:http://localhost:8081}") String productServiceUrl) {
        this.restClient = builder.baseUrl(productServiceUrl).build();
    }

    public ProductSnapshot getProduct(UUID productId) {
        ProductSnapshot product = restClient.get()
            .uri("/products/{id}", productId)
            .retrieve()
            .body(ProductSnapshot.class);
        if (product == null) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
        return product;
    }

    public record ProductSnapshot(
        UUID id,
        String name,
        String sku,
        BigDecimal price,
        BigDecimal salePrice,
        Integer stockQuantity,
        String status
    ) {
        public BigDecimal effectivePrice() {
            return salePrice != null ? salePrice : price;
        }
    }
}
