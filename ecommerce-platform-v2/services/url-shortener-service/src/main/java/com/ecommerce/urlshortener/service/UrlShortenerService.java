package com.ecommerce.urlshortener.service;

import com.ecommerce.urlshortener.domain.ShortUrl;
import com.ecommerce.urlshortener.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * URL Shortener – Base62 encoding of auto-increment DB ID.
 * SLF4J via @Slf4j – no logback imports.
 *
 * Spring Boot 3.5: uses structured logging property groups.
 */
@Slf4j @Service @RequiredArgsConstructor
public class UrlShortenerService {

    static final String BASE62 =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String   REDIS_PREFIX = "url:";
    private static final Duration CACHE_TTL    = Duration.ofHours(24);

    private final ShortUrlRepository   repository;
    private final StringRedisTemplate  redis;

    @Transactional
    public String shorten(String originalUrl, LocalDateTime expiresAt, String createdBy) {
        ShortUrl entity = ShortUrl.builder()
            .originalUrl(originalUrl).expiresAt(expiresAt)
            .createdBy(createdBy).code("TEMP").build();
        entity = repository.save(entity);
        String code = toBase62(entity.getId());
        entity.setCode(code);
        repository.save(entity);
        redis.opsForValue().set(REDIS_PREFIX + code, originalUrl, CACHE_TTL);
        log.info("Shortened url code={}", code);
        return code;
    }

    @Transactional
    public String resolve(String code) {
        String cached = redis.opsForValue().get(REDIS_PREFIX + code);
        if (cached != null) {
            try { repository.incrementClickCount(code); } catch (Exception ignored) {}
            return cached;
        }
        ShortUrl entity = repository.findByCode(code)
            .orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + code));
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new UrlNotFoundException("Short URL expired: " + code);
        redis.opsForValue().set(REDIS_PREFIX + code, entity.getOriginalUrl(), CACHE_TTL);
        repository.incrementClickCount(code);
        return entity.getOriginalUrl();
    }

    /** Converts a positive long to a Base62 string. Package-private for testing. */
    static String toBase62(long id) {
        if (id == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (id > 0) { sb.append(BASE62.charAt((int)(id % 62))); id /= 62; }
        return sb.reverse().toString();
    }
}
