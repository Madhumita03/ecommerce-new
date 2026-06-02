package com.ecommerce.product.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.*;
import java.time.Duration;
import java.util.List;

/**
 * Two-level cache: L1 Caffeine (in-process) + L2 Redis (distributed).
 * Decorator pattern: CompositeCacheManager wraps both levels.
 */
@Configuration
public class CacheConfig {

    @Bean CaffeineCache productCaffeineCache() {
        return new CaffeineCache("products",
            Caffeine.newBuilder().maximumSize(1_000)
                .expireAfterWrite(Duration.ofMinutes(5)).recordStats().build());
    }
    @Bean CaffeineCache categoryCaffeineCache() {
        return new CaffeineCache("categories",
            Caffeine.newBuilder().maximumSize(200)
                .expireAfterWrite(Duration.ofMinutes(30)).recordStats().build());
    }
    @Bean SimpleCacheManager caffeineCacheManager(CaffeineCache productCaffeineCache,
                                                   CaffeineCache categoryCaffeineCache) {
        var mgr = new SimpleCacheManager();
        mgr.setCaches(List.of(productCaffeineCache, categoryCaffeineCache));
        return mgr;
    }
    @Bean RedisCacheManager redisCacheManager(RedisConnectionFactory cf) {
        var om = new ObjectMapper().registerModule(new JavaTimeModule());
        var ser = new GenericJackson2JsonRedisSerializer(om);
        var def = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(ser))
            .disableCachingNullValues();
        return RedisCacheManager.builder(cf)
            .cacheDefaults(def)
            .withCacheConfiguration("product-list", def.entryTtl(Duration.ofMinutes(10)))
            .withCacheConfiguration("categories", def.entryTtl(Duration.ofHours(2)))
            .build();
    }
    @Primary @Bean CacheManager cacheManager(SimpleCacheManager caffeineCacheManager,
                                              RedisCacheManager redisCacheManager) {
        var comp = new CompositeCacheManager(caffeineCacheManager, redisCacheManager);
        comp.setFallbackToNoOpCache(false);
        return comp;
    }
}
