package com.ecommerce.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

/**
 * Autocomplete via Redis Sorted Sets (ZRANGEBYLEX).
 * SLF4J @Slf4j – zero logback imports.
 *
 * Algorithm: Trie-like prefix storage.
 *   Insert "samsung" → store prefixes "s","sa","sam",..,"samsung","samsung*{id}"
 *   Query  "sam"     → ZRANGEBYLEX "[sam" "[sam\xff" → filter terminal entries
 *   Time: O(log N + M) where M = number of results returned.
 */
@Slf4j @Service @RequiredArgsConstructor
public class AutocompleteService {

    private static final String KEY             = "autocomplete:products";
    private static final int    MAX_SUGGESTIONS = 10;

    private final StringRedisTemplate redis;

    public void indexProduct(String productName, String productId) {
        String norm = productName.toLowerCase().trim();
        ZSetOperations<String, String> zset = redis.opsForZSet();
        for (int i = 1; i <= norm.length(); i++) {
            zset.add(KEY, norm.substring(0, i), 0);
        }
        zset.add(KEY, norm + "*" + productId, 0);
    }

    public List<String> suggest(String prefix) {
        if (prefix == null || prefix.isBlank()) return List.of();
        String norm = prefix.toLowerCase().trim();
        Set<String> range = redis.opsForZSet().rangeByLex(KEY,
            Range.of(Range.Bound.inclusive("[" + norm),
                     Range.Bound.inclusive("[" + norm + "\uffff")));
        if (range == null) return List.of();
        return range.stream()
            .filter(s -> s.contains("*"))
            .map(s -> s.substring(0, s.indexOf('*')))
            .distinct().limit(MAX_SUGGESTIONS).toList();
    }

    public void removeProduct(String productName, String productId) {
        redis.opsForZSet().remove(KEY, productName.toLowerCase() + "*" + productId);
    }
}
