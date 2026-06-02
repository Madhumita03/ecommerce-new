package com.ecommerce.product.service;
import com.ecommerce.product.domain.dto.ProductDtos;
import com.ecommerce.product.domain.entity.Product;
import org.mapstruct.*;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProductMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    Product toEntity(ProductDtos.ProductRequest request);

    @Mapping(target = "categoryName", constant = "Unknown")
    ProductDtos.ProductResponse toResponse(Product product);

    ProductDtos.ProductSummary toSummary(Product product);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    void updateEntity(ProductDtos.ProductRequest request, @MappingTarget Product product);
}
