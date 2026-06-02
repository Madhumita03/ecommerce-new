package com.ecommerce.product.service;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Strategy pattern: pluggable pricing logic.
 * OCP: new strategies added without modifying ProductService.
 */
public interface PricingStrategy {
    BigDecimal calculatePrice(BigDecimal basePrice, Long categoryId);

    @Component
    class StandardPricingStrategy implements PricingStrategy {
        @Override
        public BigDecimal calculatePrice(BigDecimal price, Long categoryId) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
