package com.ecommerce.gateway.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * Redis configuration for Bucket4j distributed rate limiter.
 * SLF4J only – no logback imports.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}") private String host;
    @Value("${spring.data.redis.port:6379}")      private int    port;
    @Value("${spring.data.redis.password:}")      private String password;

    @Bean
    public RedisClient redisClient() {
        RedisURI uri = RedisURI.builder()
            .withHost(host).withPort(port)
            .withAuthentication("default", password.toCharArray())
            .build();
        return RedisClient.create(uri);
    }

    @Bean
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucketProxyManager(StatefulRedisConnection<String, byte[]> conn) {
        return LettuceBasedProxyManager.builderFor(conn)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy
                    .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
            .build();
    }
}
