package com.ecommerce.product.repository;

import com.ecommerce.product.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads route to PostgreSQL replica via read-only transaction hint. */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findBySku(String sku);
    Page<Product> findByCategoryId(Long categoryId, Pageable pageable);
    Page<Product> findByStatus(Product.ProductStatus status, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE LOWER(p.name) LIKE LOWER(CONCAT('%',:q,'%')) AND p.status='ACTIVE'")
    List<Product> searchByName(@Param("q") String q, Pageable pageable);

    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity + :delta WHERE p.id = :id AND p.stockQuantity + :delta >= 0")
    int updateStock(@Param("id") UUID id, @Param("delta") int delta);
}
