package com.ecommerce.product.controller;

import com.ecommerce.product.domain.dto.ProductDtos;
import com.ecommerce.product.domain.entity.Product;
import com.ecommerce.product.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer tests for {@link ProductController}.
 *
 * @WebMvcTest: loads ONLY the web layer (DispatcherServlet, security, etc.).
 * @MockBean:   replaces service beans with Mockito mocks (Spring Boot 3.5).
 * @WithMockUser: simulates authenticated requests without Keycloak.
 *
 * JUnit 5 / Mockito 5 via spring-boot-parent 3.5 BOM – no version declarations.
 */
@WebMvcTest(ProductController.class)
@Import(ProductControllerTest.MethodSecurityTestConfig.class)
@DisplayName("ProductController – web layer tests")
class ProductControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired private MockMvc       mockMvc;
    @Autowired private ObjectMapper  objectMapper;

    @MockBean private ProductReadService  productReadService;
    @MockBean private ProductWriteService productWriteService;

    private static final UUID PRODUCT_ID =
        UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private ProductDtos.ProductResponse sampleResponse() {
        return new ProductDtos.ProductResponse(
            PRODUCT_ID, "Sony WH-1000XM5", "Premium headphones", "SONY-WH5-BLK",
            new BigDecimal("349.99"), new BigDecimal("299.99"), 150, 1L, "Electronics",
            null, "Sony", Product.ProductStatus.ACTIVE, null, null);
    }

    // ── GET /products/{id} ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /products/{id}")
    class GetByIdTests {

        @Test
        @WithMockUser
        @DisplayName("200 OK with product JSON when product exists")
        void shouldReturn200_withProductJson() throws Exception {
            given(productReadService.getById(PRODUCT_ID)).willReturn(sampleResponse());

            mockMvc.perform(get("/products/{id}", PRODUCT_ID)
                    .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Sony WH-1000XM5"))
                .andExpect(jsonPath("$.price").value(349.99))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @WithMockUser
        @DisplayName("404 Not Found when product does not exist")
        void shouldReturn404_whenProductNotFound() throws Exception {
            UUID missing = UUID.randomUUID();
            given(productReadService.getById(missing))
                .willThrow(new ProductNotFoundException("Product not found: " + missing));

            mockMvc.perform(get("/products/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        @DisplayName("401 Unauthorized when no authentication provided")
        void shouldReturn401_whenUnauthenticated() throws Exception {
            mockMvc.perform(get("/products/{id}", PRODUCT_ID))
                .andExpect(status().isUnauthorized());
        }
    }

    // ── POST /products ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /products")
    class CreateProductTests {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("201 Created for valid product – ADMIN role")
        void shouldReturn201_forValidProductCreation() throws Exception {
            var req = new ProductDtos.ProductRequest(
                "Sony WH-1000XM5", "Premium headphones", "SONY-WH5-BLK",
                new BigDecimal("349.99"), new BigDecimal("299.99"),
                150, 1L, null, "Sony");
            given(productWriteService.create(any())).willReturn(sampleResponse());

            mockMvc.perform(post("/products")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SONY-WH5-BLK"));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("403 Forbidden when USER role tries to create product")
        void shouldReturn403_forNonAdminCreate() throws Exception {
            var req = new ProductDtos.ProductRequest(
                "Test", null, "TST-001", BigDecimal.TEN, null, 1, 1L, null, null);

            mockMvc.perform(post("/products")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

            then(productWriteService).should(never()).create(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("400 Bad Request for invalid payload (blank name, zero price)")
        void shouldReturn400_forInvalidPayload() throws Exception {
            String invalidJson = """
                {
                  "name": "",
                  "sku": "X",
                  "price": 0,
                  "stockQuantity": -1,
                  "categoryId": 1
                }
                """;

            mockMvc.perform(post("/products")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
        }
    }

    // ── DELETE /products/{id} ────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /products/{id}")
    class DeleteTests {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("204 No Content on successful delete – ADMIN role")
        void shouldReturn204_onSuccessfulDelete() throws Exception {
            willDoNothing().given(productWriteService).delete(PRODUCT_ID);

            mockMvc.perform(delete("/products/{id}", PRODUCT_ID).with(csrf()))
                .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("403 Forbidden when USER role tries to delete")
        void shouldReturn403_whenUserTriesToDelete() throws Exception {
            mockMvc.perform(delete("/products/{id}", PRODUCT_ID).with(csrf()))
                .andExpect(status().isForbidden());
        }
    }

    // ── PATCH /products/{id}/stock ───────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /products/{id}/stock")
    class StockUpdateTests {

        @Test
        @WithMockUser(roles = "INVENTORY")
        @DisplayName("200 OK when INVENTORY role adjusts stock")
        void shouldReturn200_forStockUpdate() throws Exception {
            given(productWriteService.updateStock(eq(PRODUCT_ID), eq(-5)))
                .willReturn(sampleResponse());

            mockMvc.perform(patch("/products/{id}/stock", PRODUCT_ID)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"delta\": -5}"))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("403 Forbidden when USER role tries stock update")
        void shouldReturn403_forUserRoleStockUpdate() throws Exception {
            mockMvc.perform(patch("/products/{id}/stock", PRODUCT_ID)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"delta\": -1}"))
                .andExpect(status().isForbidden());
        }
    }

    // ── GET /products/search ─────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /products/search")
    class SearchTests {

        @Test
        @WithMockUser
        @DisplayName("200 OK with list of matching products")
        void shouldReturn200_withSearchResults() throws Exception {
            var summary = new ProductDtos.ProductSummary(
                PRODUCT_ID, "Sony WH-1000XM5", "SONY-WH5-BLK",
                new BigDecimal("349.99"), null, null, 150, Product.ProductStatus.ACTIVE);
            given(productReadService.search(eq("Sony"), any())).willReturn(List.of(summary));

            mockMvc.perform(get("/products/search").param("q", "Sony"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sony WH-1000XM5"));
        }
    }
}
