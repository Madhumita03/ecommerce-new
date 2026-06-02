package com.ecommerce.urlshortener.service;

import com.ecommerce.urlshortener.domain.ShortUrl;
import com.ecommerce.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for {@link UrlShortenerService} and {@code toBase62()} encoding.
 * JUnit 5.12 + Mockito 5.17 – SLF4J logging is tested implicitly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("UrlShortenerService – unit tests")
class UrlShortenerServiceTest {

    @Mock private ShortUrlRepository  repository;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(valueOps);
    }

    // ── Base62 encoding ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("toBase62() – encoding algorithm")
    class Base62Tests {

        @ParameterizedTest(name = "ID {0} → \"{1}\"")
        @CsvSource({"1,1", "62,10", "12345,dnh", "100000,q0U", "1000000000,15FTGg"})
        @DisplayName("known ID→code mappings")
        void shouldEncodeKnownValues(long id, String expected) {
            assertThat(UrlShortenerService.toBase62(id)).isEqualTo(expected);
        }

        @Test
        @DisplayName("ID=0 encodes to \"0\"")
        void shouldHandleZero() {
            assertThat(UrlShortenerService.toBase62(0)).isEqualTo("0");
        }

        @ParameterizedTest
        @ValueSource(longs = {1, 100, 10_000, 1_000_000})
        @DisplayName("all encoded characters are within Base62 alphabet")
        void shouldOnlyContainBase62Chars(long id) {
            String encoded = UrlShortenerService.toBase62(id);
            assertThat(encoded).matches("[0-9A-Za-z]+");
        }

        @Test
        @DisplayName("larger IDs produce longer or equal length codes")
        void lengthShouldBeMonotonicallyNonDecreasing() {
            assertThat(UrlShortenerService.toBase62(62).length())
                .isGreaterThanOrEqualTo(UrlShortenerService.toBase62(1).length());
            assertThat(UrlShortenerService.toBase62(3844).length())
                .isGreaterThanOrEqualTo(UrlShortenerService.toBase62(62).length());
        }
    }

    // ── shorten() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shorten()")
    class ShortenTests {

        @Test
        @DisplayName("saves entity, assigns Base62 code, warms Redis cache")
        void shouldSaveAndWarmCache() {
            ShortUrl saved = ShortUrl.builder().id(12345L).code("TEMP")
                .originalUrl("https://example.com").build();
            given(repository.save(any())).willReturn(saved);

            String code = service.shorten("https://example.com", null, "test");

            assertThat(code).isEqualTo("dnh");
            then(valueOps).should().set(eq("url:dnh"), eq("https://example.com"), any());
        }
    }

    // ── resolve() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        @Test
        @DisplayName("returns cached URL from Redis on cache hit")
        void shouldReturnCachedUrl_onCacheHit() {
            given(valueOps.get("url:abc")).willReturn("https://example.com");

            String result = service.resolve("abc");

            assertThat(result).isEqualTo("https://example.com");
            then(repository).should(never()).findByCode(any());
        }

        @Test
        @DisplayName("falls back to DB on Redis cache miss and re-warms cache")
        void shouldFallBackToDb_onCacheMiss() {
            ShortUrl entity = ShortUrl.builder().code("abc")
                .originalUrl("https://example.com").build();
            given(valueOps.get("url:abc")).willReturn(null);
            given(repository.findByCode("abc")).willReturn(Optional.of(entity));

            String result = service.resolve("abc");

            assertThat(result).isEqualTo("https://example.com");
            then(valueOps).should().set(eq("url:abc"), eq("https://example.com"), any());
        }

        @Test
        @DisplayName("throws UrlNotFoundException for unknown code")
        void shouldThrow_forUnknownCode() {
            given(valueOps.get("url:xyz")).willReturn(null);
            given(repository.findByCode("xyz")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve("xyz"))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessageContaining("xyz");
        }

        @Test
        @DisplayName("throws UrlNotFoundException for expired URL")
        void shouldThrow_forExpiredUrl() {
            ShortUrl expired = ShortUrl.builder().code("old")
                .originalUrl("https://example.com")
                .expiresAt(LocalDateTime.now().minusDays(1)).build();
            given(valueOps.get("url:old")).willReturn(null);
            given(repository.findByCode("old")).willReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.resolve("old"))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessageContaining("expired");
        }
    }
}
