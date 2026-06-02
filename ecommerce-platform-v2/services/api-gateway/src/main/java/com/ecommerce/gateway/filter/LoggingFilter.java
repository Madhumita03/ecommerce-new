package com.ecommerce.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Structured request/response logging.
 * SLF4J API via Lombok @Slf4j – zero logback type references.
 */
@Slf4j
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null) traceId = UUID.randomUUID().toString();

        var mutated = exchange.getRequest().mutate().header("X-Trace-Id", traceId).build();
        final String tid  = traceId;
        final long   start = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(mutated).build())
            .doFinally(sig -> {
                int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
                log.info("method={} path={} status={} latencyMs={} traceId={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(),
                    status, System.currentTimeMillis() - start, tid);
            });
    }

    @Override public int getOrder() { return -1; }
}
