package com.ecommerce.product.service;
import com.ecommerce.product.domain.dto.ProductDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

/** ISP: read-only clients depend only on this interface. */
public interface ProductReadService {
    ProductDtos.ProductResponse getById(UUID id);
    Page<ProductDtos.ProductSummary> listByCategory(Long categoryId, Pageable pageable);
    List<ProductDtos.ProductSummary> search(String query, Pageable pageable);
}
