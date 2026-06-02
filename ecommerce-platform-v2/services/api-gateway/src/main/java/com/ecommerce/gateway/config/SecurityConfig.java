package com.ecommerce.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers(HttpMethod.POST, "/auth/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/products/**").permitAll()
                .pathMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .pathMatchers("/s/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(jwt -> {}))
            .build();
    }
}
