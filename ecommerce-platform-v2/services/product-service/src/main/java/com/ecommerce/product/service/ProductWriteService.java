package com.ecommerce.product.service;
import com.ecommerce.product.domain.dto.ProductDtos;
import java.util.UUID;

/** ISP: write clients depend only on this interface. */
public interface ProductWriteService {
    ProductDtos.ProductResponse create(ProductDtos.ProductRequest request);
    ProductDtos.ProductResponse update(UUID id, ProductDtos.ProductRequest request);
    void delete(UUID id);
    ProductDtos.ProductResponse updateStock(UUID id, int delta);
}
