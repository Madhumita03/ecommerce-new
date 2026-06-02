package com.ecommerce.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Product Service – Spring Boot 3.5 application.
 *
 * Spring Boot 3.5: background bean initialisation is auto-configured
 * when a bootstrapExecutor bean is present; no manual opt-in needed.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableCaching
@EnableAsync
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
