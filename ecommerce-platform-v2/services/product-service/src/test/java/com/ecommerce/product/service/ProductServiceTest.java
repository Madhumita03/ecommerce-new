package com.ecommerce.product.service;

import com.ecommerce.product.domain.dto.ProductDtos;
import com.ecommerce.product.domain.entity.Product;
import com.ecommerce.product.event.ProductEvent;
import com.ecommerce.product.repository.ProductRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for {@link ProductService}.
 *
 * Framework: JUnit Jupiter 5.12 + Mockito 5.17 (versions managed by
 * spring-boot-parent 3.5.0 – no need to declare versions explicitly).
 *
 * Logging: @Slf4j in production code routes through SLF4J API;
 * Mockito does not intercept or stub logging – it is tested implicitly.
 *
 * Style:
 *  • @ExtendWith(MockitoExtension.class) – strict mocking (STRICT_STUBS by default in 5.x)
 *  • BDDMockito (given/when/then) for readability
 *  • @Nested classes group related tests
 *  • @ParameterizedTest for edge-case inputs
 *  • ArgumentCaptor for Kafka event assertions
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("ProductService – unit tests")
class ProductServiceTest {

    // ── Mocks ────────────────────────────────────────────────────────────────
    @Mock private ProductRepository                    productRepository;
    @Mock private KafkaTemplate<String, ProductEvent>  kafkaTemplate;
    @Mock private ProductMapper                        productMapper;
    @Mock private PricingStrategy                      pricingStrategy;

    @InjectMocks private ProductService productService;

    // ── Fixtures ─────────────────────────────────────────────────────────────
    private UUID productId;
    private Product product;
    private ProductDtos.ProductRequest request;
    private ProductDtos.ProductResponse response;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        product = Product.builder()
            .id(productId).name("Sony WH-1000XM5").sku("SONY-WH5-BLK")
            .price(new BigDecimal("349.99")).categoryId(1L).stockQuantity(150)
            .status(Product.ProductStatus.ACTIVE).version(0L).build();

        request = new ProductDtos.ProductRequest(
            "Sony WH-1000XM5", "Premium headphones", "SONY-WH5-BLK",
            new BigDecimal("349.99"), new BigDecimal("299.99"),
            150, 1L, null, "Sony");

        response = new ProductDtos.ProductResponse(
            productId, "Sony WH-1000XM5", "Premium headphones", "SONY-WH5-BLK",
            new BigDecimal("349.99"), new BigDecimal("299.99"),
            150, 1L, "Electronics", null, "Sony",
            Product.ProductStatus.ACTIVE, null, null);
    }

    // =========================================================================
    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("happy path: persists product, applies pricing, publishes CREATED event")
        void shouldCreateProductAndPublishCreatedEvent() {
            // Given
            given(productRepository.findBySku(request.sku())).willReturn(Optional.empty());
            given(productMapper.toEntity(request)).willReturn(product);
            given(pricingStrategy.calculatePrice(request.price(), request.categoryId()))
                .willReturn(new BigDecimal("349.99"));
            given(productRepository.save(product)).willReturn(product);
            given(productMapper.toResponse(product)).willReturn(response);

            // When
            ProductDtos.ProductResponse result = productService.create(request);

            // Then
            assertThat(result).isEqualTo(response);

            // Verify Kafka event payload
            var eventCaptor = ArgumentCaptor.forClass(ProductEvent.class);
            then(kafkaTemplate).should().send(eq("product-events"), anyString(), eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventType()).isEqualTo(ProductEvent.EventType.CREATED);
            assertThat(eventCaptor.getValue().getSku()).isEqualTo("SONY-WH5-BLK");
        }

        @Test
        @DisplayName("duplicate SKU throws DuplicateSkuException and does NOT save or publish")
        void shouldThrowDuplicateSkuException_whenSkuAlreadyExists() {
            given(productRepository.findBySku(request.sku()))
                .willReturn(Optional.of(product));

            assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessageContaining("SONY-WH5-BLK");

            then(productRepository).should(never()).save(any());
            then(kafkaTemplate).should(never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("pricing strategy output is persisted, not the raw request price")
        void shouldPersistPricingStrategyResult() {
            BigDecimal strategyPrice = new BigDecimal("367.49");
            given(productRepository.findBySku(any())).willReturn(Optional.empty());
            given(productMapper.toEntity(any())).willReturn(product);
            given(pricingStrategy.calculatePrice(request.price(), 1L)).willReturn(strategyPrice);
            given(productRepository.save(any())).willReturn(product);
            given(productMapper.toResponse(any())).willReturn(response);

            productService.create(request);

            assertThat(product.getPrice()).isEqualTo(strategyPrice);
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("getById()")
    class GetByIdTests {

        @Test
        @DisplayName("returns response when product exists")
        void shouldReturnProductResponse_whenFound() {
            given(productRepository.findById(productId)).willReturn(Optional.of(product));
            given(productMapper.toResponse(product)).willReturn(response);

            assertThat(productService.getById(productId)).isEqualTo(response);
        }

        @Test
        @DisplayName("throws ProductNotFoundException for unknown ID")
        void shouldThrowProductNotFoundException_whenNotFound() {
            UUID unknown = UUID.randomUUID();
            given(productRepository.findById(unknown)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.getById(unknown))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(unknown.toString());
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("listByCategory()")
    class ListByCategoryTests {

        @Test
        @DisplayName("delegates to repository and maps results to summaries")
        void shouldReturnPageOfSummaries() {
            var pageable = PageRequest.of(0, 20);
            var page     = new PageImpl<>(List.of(product));
            var summary  = new ProductDtos.ProductSummary(
                productId, "Sony WH-1000XM5", "SONY-WH5-BLK",
                new BigDecimal("349.99"), null, null, 150, Product.ProductStatus.ACTIVE);

            given(productRepository.findByCategoryId(1L, pageable)).willReturn(page);
            given(productMapper.toSummary(product)).willReturn(summary);

            var result = productService.listByCategory(1L, pageable);

            assertThat(result).hasSize(1);
            assertThat(result.getContent().get(0).sku()).isEqualTo("SONY-WH5-BLK");
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("updateStock()")
    class UpdateStockTests {

        @Test
        @DisplayName("decrements stock successfully and returns updated product")
        void shouldDecrementStock() {
            given(productRepository.updateStock(productId, -5)).willReturn(1);
            given(productRepository.findById(productId)).willReturn(Optional.of(product));
            given(productMapper.toResponse(product)).willReturn(response);

            productService.updateStock(productId, -5);

            then(productRepository).should().updateStock(productId, -5);
        }

        @Test
        @DisplayName("throws InsufficientStockException when 0 rows updated")
        void shouldThrowInsufficientStockException() {
            given(productRepository.updateStock(productId, -999)).willReturn(0);

            assertThatThrownBy(() -> productService.updateStock(productId, -999))
                .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("publishes OUT_OF_STOCK event when stock reaches zero")
        void shouldPublishOutOfStockEvent_whenStockReachesZero() {
            product.setStockQuantity(0);
            given(productRepository.updateStock(productId, -1)).willReturn(1);
            given(productRepository.findById(productId)).willReturn(Optional.of(product));
            given(productMapper.toResponse(product)).willReturn(response);

            productService.updateStock(productId, -1);

            var captor = ArgumentCaptor.forClass(ProductEvent.class);
            then(kafkaTemplate).should().send(anyString(), anyString(), captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(ProductEvent.EventType.OUT_OF_STOCK);
        }

        @ParameterizedTest(name = "delta={0} – positive values should also succeed")
        @ValueSource(ints = {1, 10, 100})
        @DisplayName("positive deltas (restocking) succeed")
        void shouldAllowPositiveDelta(int delta) {
            product.setStockQuantity(50 + delta);
            given(productRepository.updateStock(productId, delta)).willReturn(1);
            given(productRepository.findById(productId)).willReturn(Optional.of(product));
            given(productMapper.toResponse(product)).willReturn(response);

            assertThatNoException().isThrownBy(() -> productService.updateStock(productId, delta));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("deletes product and publishes DELETED Kafka event")
        void shouldDeleteAndPublishDeletedEvent() {
            given(productRepository.findById(productId)).willReturn(Optional.of(product));

            productService.delete(productId);

            then(productRepository).should().delete(product);
            var captor = ArgumentCaptor.forClass(ProductEvent.class);
            then(kafkaTemplate).should().send(anyString(), anyString(), captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(ProductEvent.EventType.DELETED);
        }

        @Test
        @DisplayName("throws ProductNotFoundException for unknown ID")
        void shouldThrow_whenDeletingNonExistentProduct() {
            UUID missing = UUID.randomUUID();
            given(productRepository.findById(missing)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.delete(missing))
                .isInstanceOf(ProductNotFoundException.class);
            then(productRepository).should(never()).delete(any(Product.class));
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("updates product fields, applies pricing, publishes UPDATED event")
        void shouldUpdateAndPublishUpdatedEvent() {
            given(productRepository.findById(productId)).willReturn(Optional.of(product));
            given(pricingStrategy.calculatePrice(request.price(), request.categoryId()))
                .willReturn(new BigDecimal("349.99"));
            given(productRepository.save(product)).willReturn(product);
            given(productMapper.toResponse(product)).willReturn(response);
            willDoNothing().given(productMapper).updateEntity(request, product);

            productService.update(productId, request);

            then(productRepository).should().save(product);
            var captor = ArgumentCaptor.forClass(ProductEvent.class);
            then(kafkaTemplate).should().send(anyString(), anyString(), captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(ProductEvent.EventType.UPDATED);
        }
    }
}
