package com.ecommerce.product.domain.dto;

import com.ecommerce.product.domain.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** All product-related DTOs as Java 21 records. */
public final class ProductDtos {
    private ProductDtos() {}

    public record ProductRequest(
        @NotBlank @Size(min = 2, max = 255) String name,
        String description,
        @NotBlank @Size(max = 100) String sku,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal salePrice,
        @NotNull @Min(0) Integer stockQuantity,
        @NotNull Long categoryId,
        String imageUrl,
        String vendorName
    ) {}

    public record ProductResponse(
        UUID id, String name, String description, String sku,
        BigDecimal price, BigDecimal salePrice, Integer stockQuantity,
        Long categoryId, String categoryName, String imageUrl, String vendorName,
        Product.ProductStatus status, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {}

    public record ProductSummary(
        UUID id, String name, String sku,
        BigDecimal price, BigDecimal salePrice,
        String imageUrl, Integer stockQuantity, Product.ProductStatus status
    ) {}

    public record StockUpdateRequest(@NotNull int delta) {}
}
