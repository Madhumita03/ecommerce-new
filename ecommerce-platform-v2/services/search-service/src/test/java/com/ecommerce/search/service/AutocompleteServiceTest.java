package com.ecommerce.search.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for {@link AutocompleteService}.
 * JUnit 5.12 + Mockito 5.17 | SLF4J implicitly tested.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AutocompleteService – unit tests")
class AutocompleteServiceTest {

    @Mock private StringRedisTemplate          redis;
    @Mock private ZSetOperations<String,String> zSet;

    @InjectMocks private AutocompleteService autocompleteService;

    @BeforeEach void setUp() { lenient().when(redis.opsForZSet()).thenReturn(zSet); }

    @Nested @DisplayName("suggest()")
    class SuggestTests {

        @Test
        @DisplayName("returns terminal entries matching prefix")
        void shouldReturnMatchingSuggestions() {
            Set<String> range = new LinkedHashSet<>(List.of(
                "samsung", "samsung galaxy*p1", "samsung tv 55*p2"));
            given(zSet.rangeByLex(anyString(), any(Range.class))).willReturn(range);

            List<String> result = autocompleteService.suggest("sam");

            assertThat(result).containsExactlyInAnyOrder("samsung galaxy", "samsung tv 55");
        }

        @ParameterizedTest
        @NullAndEmptySource @ValueSource(strings = {" ", "a"})
        @DisplayName("returns empty list for null/blank/single-char prefix")
        void shouldReturnEmpty_forShortOrBlankInput(String prefix) {
            List<String> result = autocompleteService.suggest(prefix);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("caps results at MAX_SUGGESTIONS (10)")
        void shouldLimitToMaxSuggestions() {
            Set<String> big = new LinkedHashSet<>();
            for (int i = 0; i < 20; i++) big.add("product" + i + "*id" + i);
            given(zSet.rangeByLex(anyString(), any(Range.class))).willReturn(big);

            assertThat(autocompleteService.suggest("prod")).hasSize(10);
        }

        @Test
        @DisplayName("returns empty list when Redis returns null")
        void shouldReturnEmpty_whenRedisReturnsNull() {
            given(zSet.rangeByLex(anyString(), any(Range.class))).willReturn(null);
            assertThat(autocompleteService.suggest("test")).isEmpty();
        }
    }

    @Nested @DisplayName("indexProduct()")
    class IndexTests {

        @Test
        @DisplayName("adds all prefixes + terminal entry (N+1 ZADD calls for N-char name)")
        void shouldAddAllPrefixesAndTerminal() {
            autocompleteService.indexProduct("iphone", "p-001");
            // "iphone" has 6 chars → 6 prefix calls + 1 terminal = 7
            then(zSet).should(times(7)).add(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("normalises product name to lower case")
        void shouldNormaliseName() {
            autocompleteService.indexProduct("APPLE WATCH", "p-002");
            var captor = ArgumentCaptor.forClass(String.class);
            then(zSet).should(atLeastOnce()).add(anyString(), captor.capture(), anyDouble());
            assertThat(captor.getAllValues()).allMatch(v -> v.equals(v.toLowerCase()));
        }
    }
}
