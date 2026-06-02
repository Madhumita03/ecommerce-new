package com.ecommerce.crawler.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/**
 * BFS web crawler using Jsoup + Java 21 Virtual Threads.
 * SLF4J @Slf4j – no logback imports.
 *
 * Spring Boot 3.5: virtual threads enabled via spring.threads.virtual.enabled=true
 * in application.yml (no code changes required – auto-configured by Boot 3.5).
 */
@Slf4j @Service @RequiredArgsConstructor
public class WebCrawlerService {

    private static final int    MAX_PAGES        = 100;
    private static final int    MAX_DEPTH        = 3;
    private static final long   POLITENESS_MS    = 1_000;
    private static final String USER_AGENT       = "ShopEase-PriceBot/1.0";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public CrawlResult crawl(String seedUrl) {
        return crawl(new CrawlConfig(seedUrl, MAX_PAGES, MAX_DEPTH));
    }

    public CrawlResult crawl(CrawlConfig config) {
        log.info("Crawl start seed={} maxPages={} maxDepth={}",
            config.seedUrl(), config.maxPages(), config.maxDepth());

        Set<String>            visited    = ConcurrentHashMap.newKeySet();
        Queue<UrlDepth>        queue      = new ConcurrentLinkedQueue<>();
        List<ProductPrice>     discovered = Collections.synchronizedList(new ArrayList<>());
        queue.add(new UrlDepth(config.seedUrl(), 0));
        int pagesCrawled = 0;

        // Java 21 virtual threads – lightweight, no thread-pool sizing needed
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            while (!queue.isEmpty() && pagesCrawled < config.maxPages()) {
                UrlDepth current = queue.poll();
                if (current == null || visited.contains(current.url())) continue;
                visited.add(current.url());
                pagesCrawled++;
                futures.add(exec.submit(() -> {
                    try {
                        Thread.sleep(POLITENESS_MS);
                        Document doc = Jsoup.connect(current.url())
                            .userAgent(USER_AGENT).timeout(10_000).get();
                        extractProduct(doc, current.url()).ifPresent(p -> {
                            discovered.add(p);
                            publish(p);
                        });
                        if (current.depth() < config.maxDepth()) {
                            doc.select("a[href]").stream()
                                .map(e -> e.absUrl("href"))
                                .filter(u -> shouldCrawl(u, config.seedUrl()) && !visited.contains(u))
                                .forEach(u -> queue.add(new UrlDepth(u, current.depth() + 1)));
                        }
                    } catch (IOException | InterruptedException e) {
                        log.warn("Crawl fail url={} : {}", current.url(), e.getMessage());
                    }
                }));
            }
            futures.forEach(f -> { try { f.get(30, TimeUnit.SECONDS); } catch (Exception ignored) {} });
        }
        log.info("Crawl done pages={} products={}", pagesCrawled, discovered.size());
        return new CrawlResult(pagesCrawled, discovered);
    }

    protected Optional<ProductPrice> extractProduct(Document doc, String url) {
        String name  = doc.select("h1[itemprop=name],h1.product-title").text();
        String price = doc.select("[itemprop=price],.price").attr("content");
        if (price.isEmpty()) price = doc.select(".price").text().replaceAll("[^\\d.]","");
        if (name.isBlank() || price.isBlank()) return Optional.empty();
        try { return Optional.of(new ProductPrice(name.trim(), new BigDecimal(price), url)); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }

    private void publish(ProductPrice p) {
        String payload = "{\"productName\":\"%s\",\"price\":%s,\"sourceUrl\":\"%s\"}"
            .formatted(p.productName(), p.price(), p.sourceUrl());
        kafkaTemplate.send("competitor-price-events", p.productName(), payload);
    }

    private boolean shouldCrawl(String url, String seed) {
        try {
            URI s = URI.create(seed); URI t = URI.create(url);
            return s.getHost() != null && s.getHost().equals(t.getHost())
                && (url.startsWith("http://") || url.startsWith("https://"));
        } catch (Exception e) { return false; }
    }

    public record CrawlConfig(String seedUrl, int maxPages, int maxDepth) {
        public CrawlConfig(String seedUrl) { this(seedUrl, 100, 3); }
    }
    public record ProductPrice(String productName, BigDecimal price, String sourceUrl) {}
    public record CrawlResult(int pagesCrawled, List<ProductPrice> discoveredProducts) {}
    private record UrlDepth(String url, int depth) {}
}
