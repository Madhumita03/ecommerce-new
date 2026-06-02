package com.ecommerce.crawler.service;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WebCrawlerService}.
 * Jsoup Document parsing is tested with static HTML strings.
 * Kafka is mocked – no real broker needed.
 * JUnit 5.12 + Mockito 5.17.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("WebCrawlerService – unit tests")
class WebCrawlerServiceTest {

    @Mock KafkaTemplate<String,String> kafkaTemplate;
    @InjectMocks WebCrawlerService service;

    @Nested
    @DisplayName("extractProduct() – HTML parsing")
    class ExtractProductTests {

        private Document makeDoc(String html) {
            return org.jsoup.Jsoup.parse(html, "https://example.com");
        }

        @Test
        @DisplayName("extracts name and price from itemprop attributes")
        void shouldExtractFromItemprop() {
            Document doc = makeDoc("""
                <h1 itemprop="name">Sony Headphones</h1>
                <span itemprop="price" content="349.99">$349.99</span>
                """);
            Optional<WebCrawlerService.ProductPrice> result =
                service.extractProduct(doc, "https://example.com/sony");

            assertThat(result).isPresent();
            assertThat(result.get().productName()).isEqualTo("Sony Headphones");
            assertThat(result.get().price().toPlainString()).isEqualTo("349.99");
        }

        @Test
        @DisplayName("returns empty when name is blank")
        void shouldReturnEmpty_whenNameBlank() {
            Document doc = makeDoc("<span class='price'>99.99</span>");
            assertThat(service.extractProduct(doc, "https://x.com")).isEmpty();
        }

        @Test
        @DisplayName("returns empty when price is blank")
        void shouldReturnEmpty_whenPriceBlank() {
            Document doc = makeDoc("<h1 itemprop='name'>Product</h1>");
            assertThat(service.extractProduct(doc, "https://x.com")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "not-a-number", "$$$"})
        @DisplayName("returns empty for non-numeric price strings")
        void shouldReturnEmpty_forNonNumericPrice(String badPrice) {
            Document doc = makeDoc("""
                <h1 itemprop="name">Product</h1>
                <span class="price">%s</span>""".formatted(badPrice));
            assertThat(service.extractProduct(doc, "https://x.com")).isEmpty();
        }
    }

    @Nested
    @DisplayName("CrawlConfig")
    class CrawlConfigTests {

        @Test
        @DisplayName("single-arg constructor sets sane defaults")
        void shouldUseDefaults() {
            var config = new WebCrawlerService.CrawlConfig("https://example.com");
            assertThat(config.maxPages()).isEqualTo(100);
            assertThat(config.maxDepth()).isEqualTo(3);
        }
    }
}
