package com.ecommerce.product.service;

import com.ecommerce.product.domain.dto.ProductDtos;
import com.ecommerce.product.domain.entity.Product;
import com.ecommerce.product.event.ProductEvent;
import com.ecommerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Product service – orchestrates repository, cache, and Kafka event publishing.
 *
 * <p>SLF4J logging via Lombok {@code @Slf4j} – no logback imports anywhere.
 * The runtime SLF4J implementation (logback-classic, pulled by spring-boot-starter)
 * is configured only in {@code logback-spring.xml}; application code is 100% decoupled.
 *
 * <p>Spring Boot 3.5 features used:
 * <ul>
 *   <li>Background bean initialisation via auto-configured {@code bootstrapExecutor}</li>
 *   <li>Structured logging via {@code spring.application.name} / {@code spring.application.group}</li>
 * </ul>
 *
 * <p>SOLID:
 * <ul>
 *   <li>S – single responsibility: business logic only</li>
 *   <li>O – open to new pricing strategies without touching this class</li>
 *   <li>I – implements segregated read/write interfaces</li>
 *   <li>D – depends on abstractions (interfaces, not concrete JPA/Kafka classes)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService implements ProductReadService, ProductWriteService {

    private final ProductRepository            productRepository;
    private final KafkaTemplate<String, ProductEvent> kafkaTemplate;
    private final ProductMapper                productMapper;
    private final PricingStrategy              pricingStrategy;

    // ── Read ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "#id")
    public ProductDtos.ProductResponse getById(UUID id) {
        log.debug("Fetching product id={}", id);
        return productRepository.findById(id)
            .map(productMapper::toResponse)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "product-list", key = "#categoryId + '_' + #pageable.pageNumber")
    public Page<ProductDtos.ProductSummary> listByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable)
            .map(productMapper::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDtos.ProductSummary> search(String query, Pageable pageable) {
        return productRepository.searchByName(query, pageable)
            .stream().map(productMapper::toSummary).toList();
    }

    // ── Write ────────────────────────────────────────────────────────────────

    @Override
    @CachePut(value = "products", key = "#result.id()")
    public ProductDtos.ProductResponse create(ProductDtos.ProductRequest request) {
        log.info("Creating product sku={}", request.sku());
        if (productRepository.findBySku(request.sku()).isPresent()) {
            throw new DuplicateSkuException("SKU already exists: " + request.sku());
        }
        Product product = productMapper.toEntity(request);
        product.setPrice(pricingStrategy.calculatePrice(request.price(), request.categoryId()));
        Product saved = productRepository.save(product);
        publishEvent(ProductEvent.EventType.CREATED, saved);
        return productMapper.toResponse(saved);
    }

    @Override
    @CacheEvict(value = {"products", "product-list"}, allEntries = true)
    public ProductDtos.ProductResponse update(UUID id, ProductDtos.ProductRequest request) {
        log.info("Updating product id={}", id);
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
        productMapper.updateEntity(request, product);
        product.setPrice(pricingStrategy.calculatePrice(request.price(), request.categoryId()));
        Product saved = productRepository.save(product);
        publishEvent(ProductEvent.EventType.UPDATED, saved);
        return productMapper.toResponse(saved);
    }

    @Override
    @CacheEvict(value = {"products", "product-list"}, allEntries = true)
    public void delete(UUID id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
        productRepository.delete(product);
        publishEvent(ProductEvent.EventType.DELETED, product);
        log.info("Deleted product id={}", id);
    }

    @Override
    @CacheEvict(value = "products", key = "#id")
    public ProductDtos.ProductResponse updateStock(UUID id, int delta) {
        int rows = productRepository.updateStock(id, delta);
        if (rows == 0) throw new InsufficientStockException("Insufficient stock: " + id);
        Product product = productRepository.findById(id).orElseThrow();
        if (product.getStockQuantity() == 0) {
            publishEvent(ProductEvent.EventType.OUT_OF_STOCK, product);
        }
        return productMapper.toResponse(product);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void publishEvent(ProductEvent.EventType type, Product product) {
        var event = ProductEvent.builder()
            .eventType(type).productId(product.getId())
            .sku(product.getSku()).categoryId(product.getCategoryId())
            .build();
        kafkaTemplate.send("product-events", product.getCategoryId().toString(), event);
        log.debug("Published {} event for product id={}", type, product.getId());
    }
}
