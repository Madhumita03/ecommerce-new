# ShopEase E-Commerce Platform — Learning Guide

A study-oriented walkthrough of every module in the project. Read top-to-bottom on first pass; use as a reference later.

---

## 1. Top-Level Architecture

The platform is a **multi-module Maven project** with **10 Spring Boot microservices**. They share a common parent `pom.xml` that pins Spring Boot 3.5.4, Spring Cloud 2025.0.0, Spring AI 1.0, Java 21, and managed versions of Bucket4j, MapStruct, Flyway, etc.

```
┌──────────────── Clients (Postman / curl / browser) ─────────────────┐
                              │
                              ▼
                    ┌─────────────────────┐
                    │   API Gateway 8080  │   ← rate-limit, JWT, retry, CB
                    │ Spring Cloud Gateway│
                    └──────────┬──────────┘
                               │ load-balanced via Eureka
       ┌───────────┬───────────┼───────────┬───────────┬──────────┐
       ▼           ▼           ▼           ▼           ▼          ▼
   Product     Order       User       Search      AI         URL-Shortener
   (8081)      (8082)      (8083)     (8086)      (8087)     (8085)
       │           │           │           │           │          │
       │           │           │           │           │          │
   Postgres    Postgres    Postgres    ES+Redis    Postgres   Postgres
   (sharded)   +Kafka      +Keycloak    Kafka       (pgvector) +Redis
       │           │
       └──Kafka────┴───────────┐
                               ▼
                       Notification 8088 ──► SendGrid SMTP
                       Payment      8090 ──► Stripe
                       Crawler      8089 ──► Jsoup BFS
```

**Infrastructure (run in Docker via `docker-compose.yml`)**

| Component | Purpose |
|---|---|
| Postgres × 3 | shard0 (5432), shard1 (5433), orders/users/payments/urls/ai + Keycloak (5435 — uses **pgvector** image) |
| Redis | gateway rate-limit buckets, two-level cache L2, autocomplete sorted-sets, URL cache, chat history |
| Kafka + Zookeeper | async event bus between services |
| Elasticsearch | full-text product search |
| Keycloak | OAuth2/OIDC identity provider, JWT issuer |
| Eureka | service discovery and load balancing |
| Prometheus + Grafana | metrics scrape + dashboards |
| Nginx | reverse proxy in front of gateway |

---

## 2. Cross-Cutting Concerns

### 2.1 Java 21

- **Records** are used extensively for DTOs (`ProductDtos.ProductRequest`, `ChatRequest`, `CrawlConfig`, ...).
- **Virtual threads** in `crawler-service` (`Executors.newVirtualThreadPerTaskExecutor()`) and globally for Tomcat via `spring.threads.virtual.enabled=true`.
- **Pattern matching** for `instanceof` (e.g. `AiChatService.saveHistory`).
- **Text blocks** (`""" ... """`) for AI prompts.
- `--enable-preview` is set in `pom.xml`'s compiler args.

### 2.2 Spring Boot 3.5

- `spring.application.name` + `spring.application.group` → structured logging grouping.
- `spring.threads.virtual.enabled: true` enables platform-wide virtual threads (Tomcat + async).
- `@ConditionalOnBooleanProperty` for feature flags.
- Structured logging (`logging.structured.format.console=ecs`) for ECS JSON output.
- `logback-spring.xml` configures appenders; application code never imports `ch.qos.logback.*`.

### 2.3 SLF4J discipline

The codebase has a strict rule: **application code uses only the SLF4J API** (`org.slf4j.Logger` or Lombok's `@Slf4j`). Logback is the binding (pulled by `spring-boot-starter`) and is referenced only in `logback-spring.xml`. This means you could swap logback for `log4j2-slf4j` without touching any business code.

### 2.4 Spring Cloud 2025.0.0

- **Spring Cloud Gateway** (api-gateway) — reactive routing.
- **Spring Cloud Netflix Eureka** — service registry. Every service has `@EnableDiscoveryClient` and `eureka.client.service-url.defaultZone`.
- **Spring Cloud Circuit Breaker (Resilience4j)** — wraps reactive gateway calls.

### 2.5 Spring AI 1.0

`spring-ai-bom` brings the `ChatModel`, `ChatClient`, `VectorStore`, `SearchRequest`, and OpenAI / pgvector starters. In 1.0 GA, `Message.getContent()` was renamed to `getText()` — already applied in this codebase.

### 2.6 Testing stack

- JUnit Jupiter 5.12 (managed by Boot BOM)
- Mockito 5.17
- AssertJ
- `spring-security-test` for `@WithMockUser` / JWT mocking

### 2.7 Build & quality

- MapStruct + Lombok annotation processors (order matters in `pom.xml` — Lombok must come before MapStruct).
- JaCoCo for coverage (run `mvn verify`).
- Surefire with `--enable-preview` for tests.

---

## 3. Module Walkthroughs

### 3.1 api-gateway (port 8080)

**Purpose:** single ingress for all HTTP traffic. Handles JWT validation, rate limiting, request logging, and routes requests to downstream services via Eureka.

**Key files:**

| File | Role |
|---|---|
| `ApiGatewayApplication.java` | `@SpringBootApplication` + `@EnableDiscoveryClient` |
| `config/SecurityConfig.java` | WebFlux security, `oauth2ResourceServer.jwt()` |
| `config/RedisConfig.java` | Lettuce client + Bucket4j `ProxyManager` |
| `filter/LoggingFilter.java` | `GlobalFilter` — generates X-Trace-Id, logs method/path/status/latency |
| `ratelimit/RateLimiterFilter.java` | Token-bucket per IP or API key, backed by distributed Redis |

**`SecurityConfig`** — WebFlux flavour (note `EnableWebFluxSecurity`, `SecurityWebFilterChain`, `ServerHttpSecurity`):

```java
.csrf(CsrfSpec::disable)
.authorizeExchange(ex -> ex
    .pathMatchers(HttpMethod.GET, "/products/**").permitAll()
    .pathMatchers("/actuator/health", "/actuator/prometheus").permitAll()
    .pathMatchers("/s/**").permitAll()           // short URLs are public
    .anyExchange().authenticated())
.oauth2ResourceServer(o -> o.jwt(jwt -> {}))     // validates against Keycloak JWK set
```

The JWK set URI comes from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` which points to Keycloak.

**`RateLimiterFilter`** — implements the **Token Bucket** algorithm with two limits:

1. *Sustained*: `capacity` tokens per minute (default 100/min).
2. *Burst*: `burstCapacity` tokens per 5-second interval (default 20).

Bucket state is stored in Redis via Bucket4j's `LettuceBasedProxyManager`. Identity key prefers `X-API-Key` header, falls back to IP. On rate-limit hit, returns `429 Too Many Requests` with a JSON body and `Retry-After: 60` header.

```java
Supplier<BucketConfiguration> cfg = () -> BucketConfiguration.builder()
    .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofMinutes(1))))
    .addLimit(Bandwidth.classic(burst,    Refill.intervally(burst, Duration.ofSeconds(5))))
    .build();

return Mono.fromCallable(() -> proxyManager.builder().build(key, cfg).tryConsume(1))
    .flatMap(ok -> Boolean.TRUE.equals(ok) ? chain.filter(exchange) : deny(exchange));
```

This is the **Decorator + Chain of Responsibility** pattern — the filter wraps the request chain and can short-circuit it.

**`LoggingFilter`** — `GlobalFilter` (runs on every request) with `Ordered.HIGHEST_PRECEDENCE - 1`. Generates a trace ID, propagates it as `X-Trace-Id` header to the downstream service, and logs request/response with latency.

**Routes** (in `application.yml` under `spring.cloud.gateway.server.webflux.routes`):

```yaml
- id: product-service
  uri: lb://product-service              # lb: = load-balance via Eureka
  predicates: [Path=/products/**]
  filters:
    - name: RateLimiterFilter
    - name: Retry
      args: { retries: 3, statuses: BAD_GATEWAY }
```

**Concepts to study from this module:**

- Reactive Spring Security vs servlet Spring Security (`ServerHttpSecurity` vs `HttpSecurity`).
- Global vs gateway-specific filters.
- Distributed rate limiting with Redis-backed token buckets.
- OAuth2 Resource Server pattern.

---

### 3.2 product-service (port 8081)

**Purpose:** product catalog management. CRUD + search, with caching and event publishing.

**Architecture flow for `GET /products/{id}`:**

```
ProductController  ─►  ProductReadService  ─►  ProductService.getById()
                                                       │
                              [@Cacheable hits L1/L2]  ▼
                                                ProductRepository
                                                       │
                                                       ▼
                                                  Postgres
```

**Key files:**

| File | Role |
|---|---|
| `domain/entity/Product.java` | JPA aggregate; optimistic locking with `@Version` |
| `domain/dto/ProductDtos.java` | All DTOs as Java records with Bean Validation |
| `repository/ProductRepository.java` | Spring Data JPA + custom `@Query` |
| `service/ProductReadService.java` / `ProductWriteService.java` | **CQRS interfaces** |
| `service/ProductService.java` | implements both — single class, segregated interfaces |
| `service/ProductMapper.java` | MapStruct entity ⇄ DTO conversion |
| `service/PricingStrategy.java` | **Strategy pattern** — pluggable price calculation |
| `event/ProductEvent.java` | Kafka payload record |
| `config/CacheConfig.java` | **L1 Caffeine + L2 Redis composite cache** |
| `config/SecurityConfig.java` | JWT resource server; reads public, writes require role |
| `controller/ProductController.java` | REST endpoints with `@PreAuthorize` |
| `controller/GlobalExceptionHandler.java` | RFC 7807 problem details |
| `db/migration/V1__initial_schema.sql` | Flyway migration |

**`Product` entity highlights:**

```java
@Id @GeneratedValue(strategy = GenerationType.UUID)
private UUID id;

@Column(name = "category_id", nullable = false)  // sharding key
private Long categoryId;

@Version private Long version;                   // optimistic locking
```

The `@Version` field enables Hibernate's optimistic locking — concurrent updates that race on the same row will throw `OptimisticLockException` on the loser.

**CQRS via interface segregation:**

```java
public interface ProductReadService {
    ProductResponse getById(UUID id);
    Page<ProductSummary> listByCategory(Long categoryId, Pageable pageable);
    List<ProductSummary> search(String query, Pageable pageable);
}

public interface ProductWriteService {
    ProductResponse create(ProductRequest request);
    ProductResponse update(UUID id, ProductRequest request);
    void delete(UUID id);
    ProductResponse updateStock(UUID id, int delta);
}

@Service
public class ProductService implements ProductReadService, ProductWriteService { ... }
```

Even though one class implements both, **callers depend on the role-appropriate interface**. A read-heavy module never sees the write methods. This is the **Interface Segregation Principle** in action.

**`CacheConfig` — two-level cache (Decorator pattern):**

```
@Cacheable("products")
     │
     ▼
 CompositeCacheManager (Primary CacheManager bean)
     ├──► Caffeine (in-process, 1000 entries, 5-min TTL)
     └──► Redis     (distributed, 30-min TTL, JSON serialized)
```

Reads check L1 first (fast, local), miss to L2 (distributed, slower), miss to DB. Writes invalidate both levels. `@CachePut` updates the cache without evicting; `@CacheEvict(allEntries=true)` clears entire cache regions.

**Strategy pattern in `PricingStrategy`:**

```java
public interface PricingStrategy {
    BigDecimal calculatePrice(BigDecimal basePrice, Long categoryId);

    @Component
    class StandardPricingStrategy implements PricingStrategy { ... }
}
```

Adding `DiscountedPricingStrategy` or `MembershipPricingStrategy` requires no changes to `ProductService` — that's the **Open/Closed Principle**.

**Event publishing (Observer pattern via Kafka):**

```java
private void publishEvent(EventType type, Product product) {
    var event = ProductEvent.builder().eventType(type).productId(product.getId()).build();
    kafkaTemplate.send("product-events", product.getCategoryId().toString(), event);
}
```

Other services (search-service consumes this for index updates) subscribe via `@KafkaListener` — they react without product-service ever knowing they exist.

**Method-level security:**

```java
@PostMapping
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest req) { ... }

@PatchMapping("/{id}/stock")
@PreAuthorize("hasAnyRole('ADMIN','INVENTORY')")
public ResponseEntity<ProductResponse> updateStock(@PathVariable UUID id, ...) { ... }
```

Backed by `@EnableMethodSecurity(prePostEnabled = true)` in `SecurityConfig`, evaluating SpEL against the Spring Security context.

**Concepts to study:**

- Two-level caching with composite cache manager.
- CQRS via interface segregation.
- MapStruct annotation processor (look at the generated class in `target/generated-sources/`).
- Strategy + Open/Closed Principle.
- Optimistic locking via `@Version`.
- Flyway migrations as code (V1__, V2__, ...).
- RFC 7807 problem details.
- `@KafkaTemplate.send(topic, key, payload)` for partitioned events.

---

### 3.3 order-service (port 8082)

**Purpose:** order lifecycle. Owns the canonical state of an order from PENDING → CONFIRMED → SHIPPED → DELIVERED (or CANCELLED at any non-terminal state).

**Key files:**

| File | Role |
|---|---|
| `domain/entity/Order.java` | Aggregate root; encapsulates state-machine transitions |
| `domain/entity/OrderItem.java` | Child entity, lifecycle owned by Order |
| `domain/entity/OutboxEvent.java` | Transactional Outbox row |
| `repository/OrderRepository.java` | JPA |
| `repository/OutboxEventRepository.java` | Polled for unpublished events |
| `config/SecurityConfig.java` | JWT resource server |
| `db/migration/V1__initial_schema.sql` | orders, order_items, outbox_events tables |

**Aggregate Root pattern (`Order`):**

```java
@Setter(AccessLevel.PACKAGE)    // setters are package-private — no leaking
public class Order {
    public void confirm() { requireStatus(OrderStatus.PENDING);   status = OrderStatus.CONFIRMED; }
    public void ship()    { requireStatus(OrderStatus.CONFIRMED); status = OrderStatus.SHIPPED; }
    public void deliver() { requireStatus(OrderStatus.SHIPPED);   status = OrderStatus.DELIVERED; }
    public void cancel(String reason) {
        if (status == OrderStatus.DELIVERED) throw new IllegalStateException("Cannot cancel delivered order");
        status = OrderStatus.CANCELLED;
    }
}
```

External callers can't do `order.setStatus(DELIVERED)` — they must call `deliver()`, which **guards the transition**. This is the **State Machine** pattern embedded in the aggregate. Compare with anaemic models where setters are public and business rules leak into services.

**Items are added via the aggregate, never directly:**

```java
public void addItem(OrderItem item) { items.add(item); item.setOrder(this); }
```

This keeps the bidirectional relationship consistent.

**Transactional Outbox pattern (`OutboxEvent`):**

The classic dual-write problem: "save order to DB AND publish to Kafka" — what if the DB commit succeeds but Kafka is unreachable? Inconsistency. The Outbox pattern fixes this by inserting an event row in the **same DB transaction**:

```
BEGIN TRANSACTION
  INSERT INTO orders  (...)
  INSERT INTO outbox_events (topic='order-events', payload=..., published=false)
COMMIT
```

A separate polling loop (`OutboxPoller` — not implemented in the current codebase but designed for) reads `published=false` rows and publishes them to Kafka with retry, then marks `published=true`. If the poller dies mid-publish, the next run picks them up. At-least-once delivery is guaranteed; consumers must be idempotent.

The `retryCount` field on `OutboxEvent` lets the poller cap retries before dead-lettering.

**Saga ID (`saga_id` column):**

For orchestrating cross-service workflows. When an order is placed, order-service generates a `saga_id` and emits a `payment-events` message. payment-service uses `saga_id` to **deduplicate** retries (idempotent consumer).

**Concepts to study:**

- Aggregate Root vs anaemic entity.
- State Machine inside aggregate.
- Optimistic locking on Orders for concurrent ship/cancel races.
- Transactional Outbox — solves dual-write.
- Saga choreography vs orchestration.

---

### 3.4 user-service (port 8083)

**Purpose:** user profile storage, JWT role extraction from Keycloak.

**Key files:**

| File | Role |
|---|---|
| `UserServiceApplication.java` | Standard Spring Boot main |
| `domain/entity/User.java` | JPA entity + `@ElementCollection` for roles |
| `security/UserSecurityConfig.java` | JWT resource server, `KeycloakRolesConverter` |
| `db/migration/V1__initial_schema.sql` + `V2__add_user_roles_table.sql` | users + user_roles |

**`User.roles` via `@ElementCollection`:**

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
@Column(name = "role")
@Builder.Default
private Set<String> roles = new HashSet<>();
```

This maps a collection of *values* (not entities) into a separate `user_roles` join table without needing a `Role` entity class. Hibernate auto-joins it on user load (EAGER) or lazily (LAZY).

**`KeycloakRolesConverter`** — the most important class to understand for the auth model:

```java
public Collection<GrantedAuthority> convert(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess == null) return List.of();
    List<String> roles = (List<String>) realmAccess.getOrDefault("roles", List.of());
    return roles.stream()
        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
        .collect(Collectors.toList());
}
```

Keycloak emits JWTs like:

```json
{
  "realm_access": { "roles": ["ADMIN", "USER"] },
  "preferred_username": "admin",
  ...
}
```

Spring Security by default looks for `scope` / `scp` claims. This converter wires Keycloak's `realm_access.roles` into Spring authorities prefixed with `ROLE_`, which is what `@PreAuthorize("hasRole('ADMIN')")` checks against. Without this converter, role-based checks always fail.

**Concepts to study:**

- JPA `@ElementCollection` vs `@OneToMany`.
- Custom `Converter<Jwt, AbstractAuthenticationToken>` for non-standard issuers.
- Keycloak realm/client/role model.

---

### 3.5 url-shortener-service (port 8085)

**Purpose:** convert long URLs to short codes; redirect on lookup.

**Key files:**

| File | Role |
|---|---|
| `domain/ShortUrl.java` | JPA entity with `code`, `originalUrl`, `expiresAt`, `clickCount` |
| `repository/ShortUrlRepository.java` | JPA + atomic `incrementClickCount` |
| `service/UrlShortenerService.java` | Base62 encoding + Redis cache |
| `controller/UrlShortenerController.java` | POST `/urls/shorten`, GET `/s/{code}` |

**Base62 encoding** (`toBase62`):

```java
static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

static String toBase62(long id) {
    if (id == 0) return "0";
    StringBuilder sb = new StringBuilder();
    while (id > 0) { sb.append(BASE62.charAt((int)(id % 62))); id /= 62; }
    return sb.reverse().toString();
}
```

Takes the auto-increment DB `id` (a `long`) and encodes it as a Base62 string. Why Base62? It compresses better than Base10 (long URLs → short codes) and uses URL-safe characters only. ID `100,000,000` becomes a 5-character code `"FXSi8"`.

**Algorithm:**

1. Save with placeholder `code="TEMP"` → DB assigns auto-increment ID.
2. Compute Base62 of the ID, update the row with the real code.
3. Cache `code → originalUrl` in Redis with 24h TTL.

**Resolve flow:**

1. Look up Redis first.
2. On miss, hit DB.
3. Check expiry.
4. Write-through to Redis.
5. Increment `clickCount` (fire-and-forget — try/catch suppresses errors).

**Concepts to study:**

- Base62 encoding for short identifiers.
- Cache-aside pattern (lookup, miss, fetch, populate).
- TTL-based cache expiry.
- Counter denormalization with `incrementClickCount` (UPDATE x SET count = count + 1).

---

### 3.6 search-service (port 8086)

**Purpose:** product autocomplete and full-text search.

**Key files:**

| File | Role |
|---|---|
| `service/AutocompleteService.java` | Redis sorted-set autocomplete |
| `controller/SearchController.java` | GET `/search/autocomplete?q=...` |

**Redis Sorted Set autocomplete algorithm:**

This is one of the cleverest pieces in the codebase. Instead of running ES queries for every keystroke, it precomputes all prefixes in a single sorted set:

```java
public void indexProduct(String productName, String productId) {
    String norm = productName.toLowerCase().trim();
    ZSetOperations<String, String> zset = redis.opsForZSet();
    for (int i = 1; i <= norm.length(); i++) {
        zset.add(KEY, norm.substring(0, i), 0);    // store each prefix
    }
    zset.add(KEY, norm + "*" + productId, 0);      // terminal marker
}
```

Indexing "samsung" with id `42` adds: `s`, `sa`, `sam`, `samp`, ..., `samsun`, `samsung`, `samsung*42`.

**Query (`suggest`):**

```java
Set<String> range = redis.opsForZSet().rangeByLex(KEY,
    Range.of(Range.Bound.inclusive("[" + norm),
             Range.Bound.inclusive("[" + norm + "￿")));

return range.stream()
    .filter(s -> s.contains("*"))
    .map(s -> s.substring(0, s.indexOf('*')))
    .distinct().limit(MAX_SUGGESTIONS).toList();
```

`ZRANGEBYLEX` returns all members in lexicographic range `[sam, sam\xff)` — that's every entry whose key starts with `sam`. Filter for entries containing `*` (terminal markers), strip the suffix to get the original name. Time complexity: O(log N + M) where M is the result set size. **Sub-millisecond** for millions of products.

**Concepts to study:**

- Redis sorted sets and `ZRANGEBYLEX`.
- Trie-like prefix indexing without an actual trie.
- Time/space tradeoffs (each name takes O(L²) bytes vs O(L) for naive storage, but query is O(log N)).

---

### 3.7 ai-service (port 8087)

**Purpose:** chat-based shopping assistant, sentiment analysis, recommendations.

**Key files:**

| File | Role |
|---|---|
| `controller/AiController.java` | REST: chat, chat/stream (SSE), sentiment, recommend |
| `service/AiChatService.java` | Spring AI 1.0 client + RAG + Redis conversation memory |

**Spring AI 1.0 building blocks used:**

- `ChatModel` — abstraction over chat APIs (OpenAI here, configured via `spring.ai.openai`).
- `ChatClient` — fluent API: `ChatClient.create(chatModel).prompt().user(...).call().content()`.
- `VectorStore` — `PgVectorStore` against the `ecommerce_ai` Postgres database (requires `pgvector` extension — that's why we use the `pgvector/pgvector:pg17` image).
- `Document` — chunk in the vector store. `getText()` in 1.0 GA (was `getContent()` before).
- `SearchRequest.builder().query(...).topK(3).similarityThreshold(0.7).build()`.

**RAG (Retrieval-Augmented Generation) flow:**

```java
public String chat(String sessionId, String userMessage) {
    List<Message> history = loadHistory(sessionId);          // 1. Conversation memory
    String context        = ragContext(userMessage);          // 2. Retrieve relevant docs
    List<Message> messages = buildMessages(context, history, userMessage);  // 3. Compose prompt

    String response = chatModel.call(new Prompt(messages))    // 4. Call LLM
        .getResult().getOutput().getText();

    history.add(new UserMessage(userMessage));
    history.add(new AssistantMessage(response));
    saveHistory(sessionId, history);                          // 5. Persist memory
    return response;
}
```

`ragContext` queries the vector store with the user's question, gets the top-3 most semantically similar product chunks, and concatenates them into the system prompt:

```
You are a helpful e-commerce shopping assistant.
[PRODUCT CONTEXT]
- Sony WH-1000XM5: industry-leading noise cancellation...
- Bose QuietComfort Ultra: ...
- AirPods Pro 2: ...
[USER]
Which one has the best battery life?
```

The LLM grounds its answer in the retrieved context — much less hallucination than free-form chat.

**Streaming chat (Server-Sent Events):**

```java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatStream(@RequestBody ChatRequest req) {
    return ChatClient.create(chatModel).prompt()
        .messages(messages).stream().content();    // returns Flux<String> of tokens
}
```

`stream().content()` returns a reactive `Flux<String>` of tokens as they're generated. Spring MVC adapts the Flux to SSE — each token is flushed as a `data:` line to the client. Postman shows tokens streaming live.

**Conversation memory in Redis:**

```java
String serialized = messages.stream()
    .map(m -> m instanceof UserMessage u ? "USER:" + u.getText() : "AI:" + ((AssistantMessage)m).getText())
    .collect(Collectors.joining("\n|||\n"));
redis.opsForValue().set(HISTORY_PREFIX + sessionId, serialized, HISTORY_TTL);
```

Keyed by `sessionId`. TTL of 2 hours. Pattern-match instanceof unwraps each message to its text. Total history capped at 20 messages (oldest dropped via `skip(history.size() - MAX_HISTORY)`).

**Sentiment / recommendations:**

Both use a "structured output" prompt:

```java
String prompt = """
    Analyse the sentiment of this product review.
    Respond ONLY with JSON: {"sentiment":"POSITIVE|NEGATIVE|NEUTRAL","confidence":0.0-1.0,"summary":"..."}
    Review: %s""".formatted(review);
```

The Java code then parses that JSON. In Spring AI 1.0 there's also a typed `entity(Class)` option but this codebase uses the manual approach for clarity.

**Concepts to study:**

- LLM chat abstractions in Spring AI.
- Vector stores and similarity search.
- RAG pattern.
- Server-Sent Events with Spring MVC + Reactor `Flux`.
- Conversation state management in Redis with TTL.
- Prompt engineering for structured outputs.

---

### 3.8 notification-service (port 8088)

**Purpose:** consume Kafka events, dispatch to email (SendGrid) and WebSocket (STOMP) channels.

**Key files:**

| File | Role |
|---|---|
| `consumer/NotificationEventConsumer.java` | `@KafkaListener` — entry point |
| `service/NotificationService.java` | Email + WebSocket dispatcher |
| `config/WebSocketConfig.java` | STOMP broker setup |

**Event-driven flow:**

```
order-service ──publishes──► Kafka topic "notification-events"
                                       │
                                       ▼
NotificationEventConsumer.@KafkaListener
                                       │
                            switch(eventType) on JSON
                                       │
              ┌────────────────────────┼────────────────────────┐
              ▼                        ▼                        ▼
   notifyOrderConfirmed       notifyOrderShipped       sendEmail(cancelled)
              │                        │                        │
   ┌──────────┴──────────┐  ┌──────────┴──────────┐  ┌──────────┴──────────┐
   ▼                     ▼  ▼                     ▼  ▼                     ▼
  Email             WebSocket    ...                                       
 (SendGrid          (STOMP push                                                 
  Thymeleaf HTML)    to /user/{id}/queue/notifications)                                                  
```

**`@KafkaListener` declarative consumption:**

```java
@KafkaListener(topics = "notification-events", groupId = "notification-service")
public void consume(@Payload String payload,
                    @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                    @Header(KafkaHeaders.OFFSET) long offset) { ... }
```

Spring Kafka manages consumer lifecycle, polling, and offset commits. `groupId` is the consumer group — multiple instances of notification-service share work via partition assignment.

**STOMP/WebSocket setup:**

```java
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    public void configureMessageBroker(MessageBrokerRegistry reg) {
        reg.enableSimpleBroker("/queue", "/topic");
        reg.setApplicationDestinationPrefixes("/app");
        reg.setUserDestinationPrefix("/user");
    }
    public void registerStompEndpoints(StompEndpointRegistry reg) {
        reg.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
```

- Client connects to `ws://host:8088/ws` (SockJS fallback).
- Subscribes to `/user/{userId}/queue/notifications`.
- `convertAndSendToUser(userId, "/queue/notifications", payload)` resolves to that user-specific queue.

**Thymeleaf email templates:**

```java
Context ctx = new Context();
vars.forEach(ctx::setVariable);
String html = templateEngine.process(templateName, ctx);    // resources/templates/{templateName}.html
```

`templateName="order-confirmed"` → renders `resources/templates/order-confirmed.html`. JavaMail then ships it as HTML to SendGrid's SMTP relay.

**Concepts to study:**

- Kafka consumer groups + offset management.
- `@KafkaListener` with method-level header injection.
- STOMP messaging on top of WebSocket.
- User-destination routing in Spring messaging.
- Templated email with Thymeleaf.

---

### 3.9 crawler-service (port 8089)

**Purpose:** BFS web crawler that scrapes competitor product pages and publishes prices to Kafka. Designed to showcase Java 21 virtual threads.

**Key files:**

| File | Role |
|---|---|
| `service/WebCrawlerService.java` | BFS crawl with virtual threads + Jsoup HTML parsing |
| `CrawlerServiceApplication.java` | `@EnableScheduling` |

**BFS with virtual threads:**

```java
try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
    while (!queue.isEmpty() && pagesCrawled < config.maxPages()) {
        UrlDepth current = queue.poll();
        if (visited.contains(current.url())) continue;
        visited.add(current.url());
        pagesCrawled++;
        futures.add(exec.submit(() -> {
            Thread.sleep(POLITENESS_MS);                    // virtual threads block cheaply
            Document doc = Jsoup.connect(current.url())...get();
            extractProduct(doc, current.url()).ifPresent(this::publish);
            if (current.depth() < config.maxDepth()) {
                doc.select("a[href]").stream()
                    .map(e -> e.absUrl("href"))
                    .filter(u -> shouldCrawl(u, seed))
                    .forEach(u -> queue.add(new UrlDepth(u, current.depth() + 1)));
            }
        }));
    }
}
```

Each crawled URL gets its own virtual thread. Unlike platform threads (where 10k threads = catastrophic memory), virtual threads are **stackless coroutines** scheduled onto a small carrier pool. Blocking calls (`Thread.sleep`, `Jsoup.connect`) park the virtual thread without consuming a carrier thread. 1000 concurrent crawls is fine.

**Template Method pattern in `extractProduct`:**

`extractProduct` is `protected` — subclasses can override per-site extractors. Default uses microdata (`itemprop=name`, `itemprop=price`) and falls back to CSS selectors (`.price`).

**Politeness:**

`Thread.sleep(POLITENESS_MS)` (1 sec) before each request — basic rate limiting per crawl thread.

**Same-host restriction:**

```java
URI s = URI.create(seed); URI t = URI.create(url);
return s.getHost().equals(t.getHost());
```

Prevents the crawler from wandering outside the seed domain.

**Publishing:**

```java
kafkaTemplate.send("competitor-price-events", p.productName(), payload);
```

Downstream consumers (a hypothetical pricing-engine service) react to competitor prices and adjust own product pricing.

**Concepts to study:**

- Java 21 virtual threads vs platform threads.
- `try-with-resources` on `ExecutorService` (closes pool automatically).
- BFS traversal of a graph (web pages = nodes, links = edges).
- Jsoup CSS selectors for HTML scraping.
- Template Method pattern for site-specific extractors.

---

### 3.10 payment-service (port 8090)

**Purpose:** process payments via Stripe (or mock), idempotent Kafka saga participant.

**Key files:**

| File | Role |
|---|---|
| `domain/entity/PaymentRecord.java` | Persisted payment outcome with `sagaId` unique constraint |
| `repository/PaymentRepository.java` | `existsBySagaId` for idempotency check |
| `service/PaymentGateway.java` | Strategy interface + `StripePaymentGateway` impl |
| `service/PaymentService.java` | `@KafkaListener` saga participant |
| `config/SecurityConfig.java` | JWT resource server |
| `db/migration/V1__initial_schema.sql` | payments table |

**Idempotent Consumer pattern:**

```java
@KafkaListener(topics = "payment-events", groupId = "payment-service")
@Transactional
public void onPaymentRequested(String payload) {
    UUID sagaId = UUID.fromString(node.get("sagaId").asText());

    // Idempotency guard
    if (paymentRepository.existsBySagaId(sagaId)) {
        log.warn("Duplicate payment request sagaId={} – skipping", sagaId);
        return;
    }

    ChargeResult result = paymentGateway.charge(userId, amount, orderId);
    paymentRepository.save(PaymentRecord.builder().sagaId(sagaId)... .build());
    kafkaTemplate.send("payment-result-events", sagaId.toString(), resultPayload);
}
```

Kafka guarantees **at-least-once** delivery — the same message may be redelivered if the consumer crashed before committing offset, or if a rebalance happened. Without idempotency, the customer's card would be charged twice. The `existsBySagaId` check ensures one execution per `sagaId` (which has a unique constraint at the DB level — so even concurrent duplicate deliveries can't both succeed).

**Strategy pattern in `PaymentGateway`:**

```java
public interface PaymentGateway {
    ChargeResult charge(UUID userId, BigDecimal amount, UUID orderId);

    record ChargeResult(boolean success, String transactionId, String failureReason) {}

    @Component
    class StripePaymentGateway implements PaymentGateway { ... }
}
```

Adding `MockPaymentGateway` for tests or `PayPalPaymentGateway` for an alternative requires no changes to `PaymentService`. Spring picks beans by type; for tests use `@MockBean PaymentGateway`.

**Saga choreography flow:**

```
1. user posts order        →  order-service saves order, emits payment-events {sagaId, orderId, amount}
2. payment-service consumes payment-events
                            →  charge via Stripe
                            →  persist PaymentRecord(sagaId, success=...)
                            →  emit payment-result-events {sagaId, success}
3. order-service consumes payment-result-events
                            →  on success: confirm() the order, emit notification-events
                            →  on failure: cancel() the order, emit notification-events
4. notification-service consumes notification-events
                            →  send email + WebSocket push
```

No central orchestrator — services react to events. This is **choreographed saga**. Each step is locally atomic; failure handling is via compensating events.

**Concepts to study:**

- Idempotent consumers and the role of unique business keys.
- Saga pattern (orchestration vs choreography).
- Strategy + Liskov substitution.
- Compensating transactions.

---

## 4. Design Patterns Catalog

| Pattern | Where in the codebase |
|---|---|
| **Strategy** | `PricingStrategy` (product), `PaymentGateway` (payment) |
| **Observer (event)** | Kafka events: `product-events`, `order-events`, `payment-events`, `notification-events` |
| **Saga** | order/payment/notification choreography via Kafka |
| **Transactional Outbox** | `OutboxEvent` in order-service |
| **CQRS** | `ProductReadService` / `ProductWriteService` split |
| **Builder** | Lombok `@Builder` on all entities and events |
| **State Machine** | `Order.confirm/ship/deliver/cancel` |
| **Aggregate Root** | `Order → OrderItem` cascade |
| **Repository** | Spring Data JPA interfaces |
| **Decorator** | `CompositeCacheManager` (L1 Caffeine + L2 Redis) |
| **Chain of Responsibility** | Gateway filter chain |
| **Template Method** | `WebCrawlerService.extractProduct()` override hook |
| **Idempotent Consumer** | `PaymentService.onPaymentRequested` with `sagaId` guard |
| **Factory** | `UrlShortenerService.toBase62()` (static factory method) |

---

## 5. Communication Patterns

### 5.1 Synchronous (HTTP via gateway)

| Caller | Path | Target |
|---|---|---|
| Client | GET /products/{id} | gateway → lb://product-service |
| Client | POST /ai/chat | gateway → lb://ai-service |
| Gateway-routed requests | use `lb://service-name` URIs, resolved via Eureka |

### 5.2 Asynchronous (Kafka topics)

| Topic | Producer | Consumer |
|---|---|---|
| `product-events` | product-service | search-service (index update) |
| `order-events` | order-service | notification-service, payment-service (indirectly) |
| `stock-events` | product-service | order-service (reserve/release inventory) |
| `payment-events` | order-service | payment-service |
| `payment-result-events` | payment-service | order-service |
| `notification-events` | order-service, payment-service | notification-service |
| `competitor-price-events` | crawler-service | (consumer not in repo — designed extension) |

### 5.3 WebSocket (real-time push)

| Route | Service | Use case |
|---|---|---|
| `ws://host:8088/ws` | notification-service | client subscribes to `/user/{id}/queue/notifications` |

---

## 6. Database Topology

```
postgres-primary0 (5432)
  └── ecommerce_shard0           ← product-service (was sharded with ShardingSphere)

postgres-primary1 (5433)
  └── ecommerce_shard1           ← (originally shard 1 for sharded setup)

postgres-orders (5435)  [pgvector/pgvector:pg17]
  ├── ecommerce_orders           ← order-service
  ├── ecommerce_users            ← user-service
  ├── ecommerce_payments         ← payment-service
  ├── ecommerce_urls             ← url-shortener-service
  ├── ecommerce_ai               ← ai-service (with vector extension)
  └── keycloak                   ← Keycloak realm storage
```

Flyway migrations live in each service's `src/main/resources/db/migration/Vn__name.sql`. Hibernate runs in `ddl-auto: validate` — schema is owned by Flyway, Hibernate only checks that entities match.

---

## 7. Recommended Study Path

If you're learning the codebase, follow this order:

1. **api-gateway** → understand the request entry point, JWT validation, rate limiting.
2. **user-service** → smallest data service, learn Keycloak JWT extraction.
3. **product-service** → the most feature-rich CRUD service. Spend time here on CQRS, caching, MapStruct, Flyway, optimistic locking.
4. **order-service** → aggregate roots, state machines, the Outbox pattern.
5. **payment-service** → idempotent Kafka consumers and saga participation.
6. **notification-service** → tying Kafka events to multiple output channels (email + WebSocket).
7. **search-service** → Redis sorted-set autocomplete.
8. **url-shortener-service** → Base62 + cache-aside.
9. **crawler-service** → virtual threads + BFS.
10. **ai-service** → Spring AI, vector stores, RAG, SSE streaming.

---

## 8. Hands-On Experiments

Try modifying:

1. **Add a `MockPaymentGateway`** that always succeeds, gate it behind `@Profile("test")`. Run the saga end-to-end without hitting Stripe.
2. **Add a V3 Flyway migration** to product-service that adds a `tags` column. Update the entity and DTOs. Watch Flyway apply it cleanly on next startup.
3. **Add a new Kafka topic** `order-events` consumer in search-service that maintains an order-history-by-user view.
4. **Replace `StandardPricingStrategy`** with a `MembershipPricingStrategy` that discounts 10% for category 1. No code change to ProductService — proves OCP.
5. **Implement an `OutboxPoller`** as a `@Scheduled` bean in order-service that drains the `outbox_events` table to Kafka.
6. **Add a Resilience4j circuit breaker** on `paymentGateway.charge()` to fail fast when Stripe is down.

---

## 9. Common Pitfalls & Gotchas Already Fixed

For future reference, the issues that came up while bringing this codebase up:

| Symptom | Root cause | Fix |
|---|---|---|
| `Process finished with exit code 1` immediately after banner | `logback-spring.xml` had `<springProfile>` nested inside `<root>`, plus deprecated `SizeAndTimeBasedFNATP` class | Rewrote logback config; `<springProfile>` now wraps the entire `<root>` per profile |
| `Unsupported Database: PostgreSQL 17.9` | Flyway 11.7 (Boot 3.5.0 default) doesn't recognise 17.9; missing `flyway-database-postgresql` jar | Bumped Flyway to 11.10.0, added postgres adapter to 5 services |
| `Spring Boot [3.5.4] is not compatible with this Spring Cloud release train` | Spring Cloud 2024.0.x only works with Boot 3.4.x | Bumped Spring Cloud to 2025.0.0 |
| `extension "vector" is not available` | `postgres:17-alpine` doesn't ship pgvector | Switched `postgres-orders` to `pgvector/pgvector:pg17` |
| `Failed to determine a suitable driver class` in crawler-service | `spring-boot-starter-batch` pulled JDBC autoconfig with no DataSource configured | Removed `spring-boot-starter-batch` (not actually used) |
| `Account is not fully set up` (Keycloak) | Imported user had implicit `VERIFY_EMAIL` required action | Added `emailVerified:true` and explicit `requiredActions:[]` to realm JSON |
| 401 on `/actuator/health` | Spring Security default = lock everything | Added explicit `permitAll()` for actuator endpoints in each `SecurityConfig` |
| Notification health DOWN | `MailHealthIndicator` couldn't auth against SendGrid with placeholder key | `management.health.mail.enabled: false` |
| Gateway `predicates: [Path=/users/**, /auth/**]` validation error | Two list entries — `/auth/**` parsed as standalone predicate, fails `name=value` form | Combined: `Path=/users/**,/auth/**` (single Path predicate, comma-separated patterns) |
| Spring AI `getContent()` not found | Renamed to `getText()` in Spring AI 1.0 GA | Replaced all usages |
| ShardingSphere config ignored, app falls back to H2 | Spring Cloud 2025 / Boot 3.5 doesn't auto-register ShardingSphere from `spring.shardingsphere.*` | Replaced with plain `spring.datasource.*` for dev |

---

That's the full tour. Open the files alongside this guide and trace through. The patterns repeat — once product-service clicks, the other six CRUD-shaped services will feel obvious.
