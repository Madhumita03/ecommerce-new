package com.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API Gateway – single entry point for all microservices.
 *
 * Spring Boot 3.5 features used:
 *  • Background bean initialisation via bootstrapExecutor (auto-configured)
 *  • @ConditionalOnBooleanProperty for feature flags
 *  • Structured logging with spring.application.group
 *
 * Logging: SLF4J API only – never import logback classes in application code.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
