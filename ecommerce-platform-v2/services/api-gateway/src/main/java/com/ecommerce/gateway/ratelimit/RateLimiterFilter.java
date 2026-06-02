package com.ecommerce.gateway.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Bucket4j Token Bucket rate limiter for Spring Cloud Gateway.
 *
 * SLF4J via @Slf4j – no logback imports anywhere in this class.
 * Spring Boot 3.5: uses @ConditionalOnBooleanProperty in companion config.
 */
@Slf4j
@Component
public class RateLimiterFilter extends AbstractGatewayFilterFactory<RateLimiterFilter.Config> {

    private static final long CAPACITY = 100;
    private static final long BURST    = 20;
    private static final String BODY   = "{\"error\":\"Too Many Requests\",\"retryAfter\":60}";

    private final ProxyManager<String> proxyManager;

    public RateLimiterFilter(ProxyManager<String> proxyManager) {
        super(Config.class);
        this.proxyManager = proxyManager;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String key = resolveKey(exchange);
            Supplier<BucketConfiguration> cfg = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(config.getCapacity(),
                    Refill.greedy(config.getCapacity(), Duration.ofMinutes(1))))
                .addLimit(Bandwidth.classic(config.getBurstCapacity(),
                    Refill.intervally(config.getBurstCapacity(), Duration.ofSeconds(5))))
                .build();

            return Mono.fromCallable(() -> proxyManager.builder().build(key, cfg).tryConsume(1))
                .flatMap(ok -> Boolean.TRUE.equals(ok) ? chain.filter(exchange) : deny(exchange));
        };
    }

    private String resolveKey(ServerWebExchange ex) {
        String apiKey = ex.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) return "api:" + apiKey;
        var addr = ex.getRequest().getRemoteAddress();
        return "ip:" + (addr != null ? addr.getAddress().getHostAddress() : "unknown");
    }

    private Mono<Void> deny(ServerWebExchange ex) {
        ServerHttpResponse resp = ex.getResponse();
        resp.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        resp.getHeaders().add("Retry-After", "60");
        resp.getHeaders().add("Content-Type", "application/json");
        log.warn("Rate limit exceeded for {}", resolveKey(ex));
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(BODY.getBytes())));
    }

    public static class Config {
        private long capacity      = CAPACITY;
        private long burstCapacity = BURST;
        public long getCapacity()            { return capacity; }
        public void setCapacity(long v)      { capacity = v; }
        public long getBurstCapacity()       { return burstCapacity; }
        public void setBurstCapacity(long v) { burstCapacity = v; }
    }
}
