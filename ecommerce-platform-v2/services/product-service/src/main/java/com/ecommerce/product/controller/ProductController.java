package com.ecommerce.product.controller;

import com.ecommerce.product.domain.dto.ProductDtos;
import com.ecommerce.product.service.ProductReadService;
import com.ecommerce.product.service.ProductWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Product catalog REST controller.
 * Logging: SLF4J @Slf4j only – no logback imports.
 */
@Slf4j
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product catalog management")
public class ProductController {

    private final ProductReadService  productReadService;
    private final ProductWriteService productWriteService;

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductDtos.ProductResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(productReadService.getById(id));
    }

    @GetMapping
    @Operation(summary = "List products by category (paginated)")
    public ResponseEntity<Page<ProductDtos.ProductSummary>> listByCategory(
            @RequestParam Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productReadService.listByCategory(categoryId, pageable));
    }

    @GetMapping("/search")
    @Operation(summary = "Search products by name")
    public ResponseEntity<List<ProductDtos.ProductSummary>> search(
            @RequestParam String q,
            @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(productReadService.search(q, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a product (ADMIN)")
    public ResponseEntity<ProductDtos.ProductResponse> create(
            @Valid @RequestBody ProductDtos.ProductRequest request) {
        log.info("Admin creating product sku={}", request.sku());
        return ResponseEntity.status(HttpStatus.CREATED).body(productWriteService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a product (ADMIN)")
    public ResponseEntity<ProductDtos.ProductResponse> update(
            @PathVariable UUID id, @Valid @RequestBody ProductDtos.ProductRequest request) {
        return ResponseEntity.ok(productWriteService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a product (ADMIN)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productWriteService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('ADMIN','INVENTORY')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update stock quantity")
    public ResponseEntity<ProductDtos.ProductResponse> updateStock(
            @PathVariable UUID id,
            @Valid @RequestBody ProductDtos.StockUpdateRequest request) {
        return ResponseEntity.ok(productWriteService.updateStock(id, request.delta()));
    }
}
