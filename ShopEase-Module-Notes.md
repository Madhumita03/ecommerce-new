# ShopEase Platform — Module-by-Module Code Notes

A class-by-class learning companion to the ShopEase e-commerce microservices project. For each service you will find: its **purpose**, the **module layout**, every **class and what it does**, and notes on the **Spring Boot / Java concepts** the code is demonstrating.

Read this top-to-bottom the first time. Later, jump to a module when you need to refresh memory on how something works.

---

## Table of Contents

1. [Project-Wide Foundations](#1-project-wide-foundations)
2. [api-gateway — Port 8080](#2-api-gateway--port-8080)
3. [product-service — Port 8081](#3-product-service--port-8081)
4. [order-service — Port 8082](#4-order-service--port-8082)
5. [user-service — Port 8083](#5-user-service--port-8083)
6. [url-shortener-service — Port 8085](#6-url-shortener-service--port-8085)
7. [search-service — Port 8086](#7-search-service--port-8086)
8. [ai-service — Port 8087](#8-ai-service--port-8087)
9. [notification-service — Port 8088](#9-notification-service--port-8088)
10. [crawler-service — Port 8089](#10-crawler-service--port-8089)
11. [payment-service — Port 8090](#11-payment-service--port-8090)
12. [Cross-Service Patterns You Should Recognise](#12-cross-service-patterns-you-should-recognise)

---

## 1. Project-Wide Foundations

### 1.1 Build layout

The repository is a **multi-module Maven project** rooted at `pom.xml`. The root POM is `packaging=pom` and lists ten children under `<modules>`:

```xml
<modules>
  <module>services/api-gateway</module>
  <module>services/product-service</module>
  <module>services/order-service</module>
  <module>services/user-service</module>
  <module>services/notification-service</module>
  <module>services/url-shortener-service</module>
  <module>services/search-service</module>
  <module>services/ai-service</module>
  <module>services/payment-service</module>
  <module>services/crawler-service</module>
</modules>
```

The root POM does three jobs:

1. **Inherits from `spring-boot-starter-parent` 3.5.4** — that gives every child module a curated, version-aligned dependency tree (Spring 6.2, Jackson, Hibernate 6.x, Tomcat 10.x, JUnit 5.12, Mockito 5.17, etc.).
2. **Imports BOMs** (`<dependencyManagement>`): Spring Cloud 2025.0.0 and Spring AI 1.0. Importing a BOM lets a child module write `<dependency>org.springframework.cloud:spring-cloud-starter-gateway</dependency>` with no version — the BOM supplies it.
3. **Declares “common” dependencies** in `<dependencies>` so every child gets them automatically: Lombok, SLF4J API, Bean Validation starter, Spring Cloud Eureka client, and `spring-boot-starter-test`.

The compiler plugin is configured with `--enable-preview` (Java 21 preview features), Lombok and MapStruct annotation processors — Lombok must come **before** MapStruct in `annotationProcessorPaths` because MapStruct's generated code reads Lombok-generated getters/setters.

### 1.2 Logging discipline

A strict project rule: **application code only uses the SLF4J API** (`org.slf4j.Logger` or Lombok's `@Slf4j`). Logback-classic is the runtime binding (pulled in transitively by `spring-boot-starter`) and is referenced only in `logback-spring.xml`. Result: you could swap logback for log4j2 without touching any service code.

### 1.3 Java 21 features used throughout

- **Records** for DTOs and events (`ProductDtos.ProductRequest`, `ChatRequest`, `CrawlConfig`, `ChargeResult`, …).
- **Pattern matching for `instanceof`** (e.g. `m instanceof UserMessage u`).
- **Text blocks** (`""" ... """`) for AI prompts.
- **Virtual threads** via `Executors.newVirtualThreadPerTaskExecutor()` in the crawler, and globally for Tomcat via `spring.threads.virtual.enabled=true`.

### 1.4 Spring Boot 3.5 + Spring Cloud 2025.0.0

Every service is `@SpringBootApplication` + `@EnableDiscoveryClient`. Eureka acts as service registry and load balancer: when the gateway routes `lb://product-service`, Spring Cloud resolves it from Eureka and round-robins across registered instances.

### 1.5 Infrastructure (Docker Compose)

| Component | Purpose |
|---|---|
| PostgreSQL × 3 | shard0, shard1 (product), and a third instance hosting users/orders/payments/urls/ai (the AI DB uses the **pgvector** image for embeddings) |
| Redis | rate-limit buckets, L2 cache, autocomplete sorted sets, URL cache, chat history |
| Kafka + Zookeeper | async event bus |
| Elasticsearch | full-text product search |
| Keycloak | OAuth2/OIDC identity provider (issues JWTs) |
| Eureka | service registry |
| Prometheus + Grafana | metrics + dashboards |
| Nginx | reverse proxy in front of the gateway |

---

## 2. api-gateway — Port 8080

**Purpose:** the single ingress for every external request. It validates JWTs, applies a distributed rate limit, tags requests with a trace ID, and routes to downstream services via Eureka. Built on **Spring Cloud Gateway**, which is *reactive* (WebFlux + Netty) — that's important because reactive Spring Security has a different API than the servlet one.

### Module structure

```
api-gateway/src/main/java/com/ecommerce/gateway/
├── ApiGatewayApplication.java
├── config/
│   ├── SecurityConfig.java
│   └── RedisConfig.java
├── filter/
│   └── LoggingFilter.java
└── ratelimit/
    └── RateLimiterFilter.java
```

### `ApiGatewayApplication`

```java
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication { ... }
```

Standard Spring Boot main class. `@SpringBootApplication` is a meta-annotation: `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`. `@EnableDiscoveryClient` registers the gateway with Eureka — required for `lb://...` URIs to resolve.

### `config.SecurityConfig`

Reactive WebFlux security (note `@EnableWebFluxSecurity`, `ServerHttpSecurity`, and `SecurityWebFilterChain` — the reactive counterparts of `HttpSecurity` and `SecurityFilterChain`).

```java
.csrf(CsrfSpec::disable)
.authorizeExchange(ex -> ex
    .pathMatchers(HttpMethod.POST, "/auth/**").permitAll()
    .pathMatchers(HttpMethod.GET,  "/products/**").permitAll()
    .pathMatchers("/actuator/health", "/actuator/prometheus").permitAll()
    .pathMatchers("/s/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
    .anyExchange().authenticated())
.oauth2ResourceServer(o -> o.jwt(jwt -> {}))
```

Concepts to absorb:

- **OAuth2 Resource Server** — the gateway doesn't issue tokens, it only *validates* them. The empty `jwt(jwt -> {})` block makes Spring read `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` from configuration (it points to Keycloak's JWKS endpoint).
- **CSRF is disabled** because the API is stateless and called from clients that don't rely on cookies.
- **Public paths** — short-URL redirects (`/s/**`) and GET on products are open; everything else needs a valid Bearer token.

### `config.RedisConfig`

Wires up a **Lettuce** Redis client (the reactive Redis driver) and a Bucket4j `ProxyManager<String>` backed by that client. The proxy manager is what makes rate-limit buckets *distributed*: every gateway instance sees the same bucket state.

```java
@Bean
public ProxyManager<String> bucketProxyManager(StatefulRedisConnection<String, byte[]> conn) {
    return LettuceBasedProxyManager.builderFor(conn)
        .withExpirationStrategy(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
        .build();
}
```

`ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(...)` automatically deletes the Redis key once a bucket has been idle long enough to be fully refilled (keeps Redis tidy).

### `filter.LoggingFilter`

A **`GlobalFilter`** (runs on every route), ordered at `-1` (very early). On entry it ensures every request has an `X-Trace-Id` header (generates a random UUID if missing) and propagates it downstream. On exit (via `.doFinally(...)`), it logs method + path + status + latency:

```java
log.info("method={} path={} status={} latencyMs={} traceId={}", ...);
```

Key Reactor idea: `doFinally(sig -> ...)` runs whether the reactive chain completes, errors, or is cancelled — perfect for emit-once instrumentation.

### `ratelimit.RateLimiterFilter`

Extends `AbstractGatewayFilterFactory<Config>` so it can be referenced by name in `application.yml`. Implements **dual-bandwidth token bucket** rate limiting:

```java
BucketConfiguration.builder()
    .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofMinutes(1))))   // sustained
    .addLimit(Bandwidth.classic(burst,    Refill.intervally(burst,  Duration.ofSeconds(5)))) // burst
    .build();
```

- **Sustained:** 100 tokens, refilled `greedy` (refill happens continuously) over 1 minute.
- **Burst:** 20 tokens, refilled `intervally` (lumpy refill — all 20 at once every 5 seconds).

`resolveKey(...)` keys the bucket by `X-API-Key` header if present, otherwise by remote IP. On rejection it sets HTTP 429 + `Retry-After: 60` and writes a tiny JSON body.

The inner `Config` class is the binding type for filter args defined in `application.yml` (e.g. `args: { capacity: 200, burstCapacity: 50 }` would override the defaults).

**Patterns you saw here:**

- *Decorator + Chain of Responsibility* — filters wrap the request pipeline and can short-circuit it.
- *Strategy* — `ExpirationAfterWriteStrategy` is a pluggable expiration policy.
- *Reactive programming* with `Mono.fromCallable(...)` to lift blocking calls into the reactive chain.

---

## 3. product-service — Port 8081

**Purpose:** the product catalog. CRUD plus search, with two-level caching, optimistic locking, and Kafka event publishing. This is the most feature-rich service — it's the canonical example for how every other service is structured.

### Module structure

```
product-service/src/main/java/com/ecommerce/product/
├── ProductServiceApplication.java
├── config/
│   ├── CacheConfig.java
│   └── SecurityConfig.java
├── controller/
│   ├── ProductController.java
│   └── GlobalExceptionHandler.java
├── domain/
│   ├── dto/ProductDtos.java
│   └── entity/Product.java
├── event/ProductEvent.java
├── repository/ProductRepository.java
└── service/
    ├── ProductReadService.java
    ├── ProductWriteService.java
    ├── ProductService.java
    ├── ProductMapper.java
    ├── PricingStrategy.java
    ├── ProductNotFoundException.java
    ├── DuplicateSkuException.java
    └── InsufficientStockException.java
```

### `ProductServiceApplication`

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableCaching
@EnableAsync
```

- `@EnableCaching` — activates Spring's caching annotations (`@Cacheable`, `@CachePut`, `@CacheEvict`). Without it, those annotations are inert.
- `@EnableAsync` — activates `@Async`. Methods marked `@Async` execute on a thread pool instead of the caller's thread.

### `domain/entity/Product`

A JPA `@Entity` modelling the canonical product row.

```java
@Id @GeneratedValue(strategy = GenerationType.UUID)
private UUID id;

@Column(name = "category_id", nullable = false)   // sharding key
private Long categoryId;

@Version private Long version;                    // optimistic locking
```

Important annotations:

- `GenerationType.UUID` (Hibernate 6) — Hibernate generates a random UUID, no DB sequence needed.
- `@Version` — Hibernate auto-increments this on every update and writes `WHERE id=? AND version=?` in the UPDATE. If the WHERE returns 0 rows, Hibernate throws `OptimisticLockException` — the loser of a concurrent update.
- Two `@Index` declarations: a regular index on `category_id` (selectivity for `findByCategoryId`) and a unique index on `sku`.
- `@CreationTimestamp` / `@UpdateTimestamp` — Hibernate populates these automatically.
- `category_id` is the **shard key** for ShardingSphere's MOD algorithm; products with the same category land on the same Postgres shard.

`ProductStatus` is an inner enum stored as a string column (`@Enumerated(EnumType.STRING)` — *always* prefer this over `ORDINAL` so adding a value doesn't renumber existing rows).

### `domain/dto/ProductDtos`

A holder class with private constructor — every nested type is a Java `record`. Records give you immutability, `equals`/`hashCode`/`toString`, and accessor methods for free.

- `ProductRequest` — input payload with Bean Validation (`@NotBlank`, `@Size`, `@DecimalMin`, `@Digits`, `@NotNull`, `@Min`). `@Valid` on controller parameters triggers these.
- `ProductResponse` — full read DTO.
- `ProductSummary` — slim DTO for list views (no description, no timestamps).
- `StockUpdateRequest` — single field `delta` for PATCH /stock.

### `repository/ProductRepository`

Spring Data JPA: extends `JpaRepository<Product, UUID>` which gives you `findById`, `save`, `delete`, `findAll`, paging, etc. for free. Custom methods:

- `findBySku(String)` — Spring Data parses the method name and generates the query.
- `findByCategoryId(Long, Pageable)` — same, with paging.
- `searchByName(String q, Pageable)` — explicit `@Query` (JPQL), case-insensitive LIKE.
- `updateStock(UUID, int delta)` — `@Modifying @Query` does an atomic SQL UPDATE that also guards against negative stock (`stockQuantity + :delta >= 0`). Returns the row count so the service can detect "no rows updated" = insufficient stock.

### `service/ProductReadService` and `ProductWriteService`

Two segregated interfaces (Interface Segregation Principle — the **I** in SOLID):

```java
public interface ProductReadService {
    ProductDtos.ProductResponse getById(UUID id);
    Page<ProductDtos.ProductSummary> listByCategory(Long, Pageable);
    List<ProductDtos.ProductSummary> search(String, Pageable);
}

public interface ProductWriteService {
    ProductDtos.ProductResponse create(...);
    ProductDtos.ProductResponse update(...);
    void delete(UUID);
    ProductDtos.ProductResponse updateStock(UUID, int);
}
```

Callers depend on whichever interface they need. The controller injects *both* — but a read-only analytics service would only depend on `ProductReadService`. This is genuine CQRS-by-interface, even though a single class implements both.

### `service/ProductService`

The implementation. Important bits:

```java
@Slf4j @Service @RequiredArgsConstructor @Transactional
public class ProductService implements ProductReadService, ProductWriteService {

    private final ProductRepository repository;
    private final KafkaTemplate<String, ProductEvent> kafkaTemplate;
    private final ProductMapper mapper;
    private final PricingStrategy pricingStrategy;
```

- `@RequiredArgsConstructor` — Lombok creates a constructor for all `final` fields. Constructor injection is the recommended Spring style (immutable, easy to test).
- `@Transactional` at class level — every public method runs inside a transaction by default. Read methods override with `@Transactional(readOnly = true)` (allows JDBC drivers to optimise and signals intent).
- The class implements both segregated interfaces — same object, different views.

**Cache annotations in action:**

| Method | Annotation | What happens |
|---|---|---|
| `getById` | `@Cacheable(value="products", key="#id")` | Return value cached by id |
| `listByCategory` | `@Cacheable(value="product-list", key="#categoryId + '_' + #pageable.pageNumber")` | Cached per page |
| `create` | `@CachePut(value="products", key="#result.id()")` | Result inserted into cache (not "invalidate") |
| `update`, `delete` | `@CacheEvict(value={"products","product-list"}, allEntries=true)` | Whole regions cleared |
| `updateStock` | `@CacheEvict(value="products", key="#id")` | One key cleared |

Note `key="#result.id()"` — SpEL with `#result` references the method's return value, which is a record so the accessor is `id()`.

**`publishEvent(...)`** sends a `ProductEvent` to Kafka topic `product-events`, keyed by `categoryId`. Keying matters in Kafka — same key always lands on the same partition, which preserves per-key ordering. Downstream consumers (e.g. search-service updating the Elasticsearch index, ai-service refreshing embeddings) listen to this topic.

### `service/ProductMapper`

A **MapStruct** interface — at compile time, MapStruct generates a `ProductMapperImpl` class with raw field-by-field copy code (zero reflection at runtime). Look in `target/generated-sources/annotations/...ProductMapperImpl.java` to see the generated class.

```java
@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProductMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    Product toEntity(ProductDtos.ProductRequest);

    @Mapping(target = "categoryName", constant = "Unknown")
    ProductDtos.ProductResponse toResponse(Product);

    ProductDtos.ProductSummary toSummary(Product);

    @Mapping(target = "id", ignore = true)
    void updateEntity(ProductDtos.ProductRequest, @MappingTarget Product);
}
```

- `componentModel = "spring"` — MapStruct registers the impl as a Spring `@Component`.
- `NullValuePropertyMappingStrategy.IGNORE` — null fields in the source leave the target untouched (great for PATCH).
- `@MappingTarget` — update in place rather than creating a new instance.

### `service/PricingStrategy`

The Strategy pattern. The interface plus a nested default implementation:

```java
public interface PricingStrategy {
    BigDecimal calculatePrice(BigDecimal basePrice, Long categoryId);

    @Component
    class StandardPricingStrategy implements PricingStrategy {
        public BigDecimal calculatePrice(BigDecimal price, Long c) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
```

Adding `DiscountedPricingStrategy` requires *no change* to `ProductService` — this is the **Open/Closed Principle**. If multiple beans existed you'd disambiguate with `@Primary` or `@Qualifier`.

### `service/*Exception` classes

Three RuntimeExceptions with `@ResponseStatus(...)` so that — if `GlobalExceptionHandler` didn't intercept — Spring would still map them to a sensible HTTP status:

- `ProductNotFoundException` → 404
- `DuplicateSkuException` → 409 CONFLICT
- `InsufficientStockException` → 422 UNPROCESSABLE_ENTITY

### `event/ProductEvent`

A Lombok `@Data @Builder` POJO. Fields: `eventType`, `productId`, `sku`, `categoryId`, `occurredAt`, `traceId`. Serialised to JSON by Spring Kafka's default serializer.

### `config/CacheConfig`

Two-level caching:

- **L1: Caffeine**, in-process, fast, no network. Two named caches: `products` (1000 entries, 5-minute TTL) and `categories` (200 entries, 30-minute TTL).
- **L2: Redis**, distributed, slower but shared across instances. `cacheDefaults` gives 30-minute TTL with Jackson JSON serialisation; `product-list` and `categories` override the TTL.
- **Composite**: a `CompositeCacheManager` wraps both. Spring checks L1 first; on miss it asks L2; on miss it actually invokes the method. `@CacheEvict` clears both.

`@Primary` is on the composite so it wins when multiple `CacheManager` beans exist.

### `config/SecurityConfig`

Servlet-style security (this is a regular MVC service, not WebFlux):

```java
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)   // turns on @PreAuthorize
```

- Stateless sessions (no `JSESSIONID`).
- Public: actuator health/prometheus/info, OpenAPI docs, **GET /products/**.
- All other requests authenticated.
- Custom `KeycloakRolesConverter` extracts `realm_access.roles` from the JWT and prefixes each role with `ROLE_` (Spring Security convention so `hasRole('ADMIN')` matches).

### `controller/ProductController`

Annotation-driven REST controller. Highlights:

- `@RequestMapping("/products")` at class level — all endpoints share the prefix.
- `@PageableDefault(size = 20)` — sensible default if the client omits `page`/`size` query params.
- `@PreAuthorize("hasRole('ADMIN')")` for write endpoints. The expression is SpEL evaluated against the Spring Security context.
- `@PreAuthorize("hasAnyRole('ADMIN','INVENTORY')")` on PATCH /{id}/stock — multiple allowed roles.
- `@SecurityRequirement(name="bearerAuth")` and `@Operation(...)` — SpringDoc OpenAPI annotations for Swagger UI documentation.

### `controller/GlobalExceptionHandler`

`@RestControllerAdvice` — Spring runs this advice across every controller. Each handler returns a `ProblemDetail` (the RFC 7807 standard error format introduced in Spring 6):

```json
{
  "type": "https://api.shopease.com/errors/validation",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "fieldErrors": { "sku": "must not be blank" },
  "timestamp": "2026-05-24T..."
}
```

Handlers cover:

- `MethodArgumentNotValidException` — Bean Validation failures, collected into a `fieldErrors` map.
- `ProductNotFoundException` — domain "not found" → 404.
- `NoResourceFoundException` — unmatched URL → 404 (otherwise Boot returns 500).
- `MethodArgumentTypeMismatchException` — bad path-variable type (e.g. non-UUID) → 400.
- Catch-all `Exception` — logs at `ERROR` level and returns 500.

---

## 4. order-service — Port 8082

**Purpose:** owns order state. An order goes PENDING → CONFIRMED → SHIPPED → DELIVERED (or CANCELLED). This service demonstrates the **Aggregate Root** DDD pattern and the **Transactional Outbox** pattern.

### Module structure

```
order-service/src/main/java/com/ecommerce/order/
├── OrderServiceApplication.java
├── config/SecurityConfig.java
├── domain/entity/
│   ├── Order.java
│   ├── OrderItem.java
│   └── OutboxEvent.java
└── repository/
    ├── OrderRepository.java
    └── OutboxEventRepository.java
```

### `OrderServiceApplication`

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
```

`@EnableScheduling` activates `@Scheduled` methods — used by the (planned) outbox poller that runs at a fixed delay to publish unpublished events.

### `domain/entity/Order` — the Aggregate Root

```java
@Getter @Setter(AccessLevel.PACKAGE)   // setters package-private
public class Order {
    public void confirm() { requireStatus(OrderStatus.PENDING);   status = OrderStatus.CONFIRMED; }
    public void ship()    { requireStatus(OrderStatus.CONFIRMED); status = OrderStatus.SHIPPED; }
    public void deliver() { requireStatus(OrderStatus.SHIPPED);   status = OrderStatus.DELIVERED; }
    public void cancel(String reason) {
        if (status == OrderStatus.DELIVERED) throw new IllegalStateException(...);
        status = OrderStatus.CANCELLED;
    }
}
```

What to study here:

- `@Setter(AccessLevel.PACKAGE)` — Lombok generates setters but only with package-private visibility. Other packages **cannot** do `order.setStatus(DELIVERED)`. They must call `deliver()`, which guards the transition. This converts the entity from an anaemic record into a real **aggregate root** with invariants.
- The `OrderStatus` enum + `requireStatus(...)` guard is a hand-rolled **State Machine** embedded in the aggregate.
- `addItem(OrderItem)` keeps the bidirectional relationship consistent (`item.setOrder(this)`); callers never set this themselves.
- `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval=true)` — items live and die with the order; removing one from the list deletes it from the DB.
- `@Version` — optimistic locking guards concurrent updates (e.g. two services racing to ship vs cancel the same order).
- `saga_id` — generated once per order, used by downstream services as a deduplication / correlation key.

### `domain/entity/OrderItem`

A child entity. `@ManyToOne` back to `Order` plus copy-on-write fields (`productName`, `sku`, `unitPrice`, `lineTotal`). Storing the price and name at order time means the row stays correct even if the product is later renamed or repriced (snapshot pattern).

### `domain/entity/OutboxEvent` — the Transactional Outbox

```java
@Entity @Table(name = "outbox_events")
public class OutboxEvent {
    private UUID id;
    private String topic;
    private String partitionKey;
    private String payload;       // JSON
    private String eventType;
    private boolean published;
    private Instant createdAt, publishedAt;
    private int retryCount;
}
```

Why this exists: the classic *dual write problem*. You want to (a) save the order and (b) publish a Kafka message. If you do them as two independent operations, one can succeed while the other fails — inconsistent state.

The fix is to write the event row in the **same DB transaction** as the order:

```
BEGIN
  INSERT INTO orders ...
  INSERT INTO outbox_events (topic='order-events', published=false, payload=...)
COMMIT
```

Now both either commit or roll back together. A separate **polling component** reads `published=false` rows, publishes them to Kafka, and marks them `published=true`. If the poller dies mid-publish, the next run picks them up. Consumers must be **idempotent** because at-least-once delivery is guaranteed.

`retryCount` lets the poller cap retries and dead-letter persistent failures.

### `repository/OrderRepository`

```java
Page<Order> findByUserId(UUID, Pageable);
Optional<Order> findBySagaId(UUID);

@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findByIdWithItems(@Param("id") UUID);
```

The `JOIN FETCH` solves the N+1 query problem: instead of fetching the order then issuing a second SELECT for items (lazy load), Hibernate joins them in one query.

### `repository/OutboxEventRepository`

Custom native query that finds unpublished rows under the retry cap, oldest first, capped by `:lim`:

```java
@Query(value = "SELECT * FROM outbox_events WHERE published=false AND retry_count < :max ORDER BY created_at LIMIT :lim",
       nativeQuery = true)
List<OutboxEvent> findUnpublished(int max, int lim);
```

Native SQL because `LIMIT` isn't standard JPQL; native is the cleanest way to express "give me the next batch to publish."

### `config/SecurityConfig`

Identical pattern to product-service: stateless JWT resource server, `KeycloakRolesConverter` extracts roles from `realm_access.roles`, only actuator + OpenAPI are public, everything else requires authentication.

---

## 5. user-service — Port 8083

**Purpose:** stores the application-side user profile and maps Keycloak JWT roles into Spring Security authorities. Authentication itself is delegated to Keycloak (OAuth2/OIDC) — this service never sees passwords.

### Module structure

```
user-service/src/main/java/com/ecommerce/user/
├── UserServiceApplication.java
├── domain/entity/User.java
└── security/UserSecurityConfig.java
```

### `domain/entity/User`

```java
@Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "keycloak_id", unique = true) private String keycloakId;
    @Column(unique = true) private String email;
    private String firstName, lastName;
    @Enumerated(EnumType.STRING) private UserStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<String> roles = new HashSet<>();

    @Version private Long version;
}
```

The interesting part is `@ElementCollection` — it maps a `Set<String>` to a join table (`user_roles`) without needing a `Role` entity class. Use this when the collection elements are **values** rather than entities with their own identity. Two ways Hibernate stores collections:

- **Embedded values** (this case) → `@ElementCollection` + `@CollectionTable`.
- **Entities** → `@OneToMany` with a separate entity.

`keycloakId` is the bridge between Keycloak's identity (`sub` claim in JWT) and the local user row.

### `security/UserSecurityConfig`

The same Keycloak JWT resource server setup as the other services, but with finer-grained path rules:

```java
.requestMatchers(HttpMethod.POST, "/auth/register").permitAll()    // signup is public
.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
.requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
.requestMatchers(HttpMethod.GET, "/users/me").authenticated()      // any logged-in user
.requestMatchers("/users/**").hasRole("ADMIN")                     // admin-only
.anyRequest().authenticated()
```

The `KeycloakRolesConverter` inner class is the auth-model bridge. A Keycloak JWT carries roles like:

```json
"realm_access": { "roles": ["admin", "user"] }
```

Spring Security expects authorities like `ROLE_ADMIN`. The converter:

1. Reads `realm_access.roles` from the JWT claims (`jwt.getClaimAsMap("realm_access")`).
2. For each role, builds `new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())`.

Now `@PreAuthorize("hasRole('ADMIN')")` works. The same converter is duplicated in product, order, and payment configs — a candidate for a shared module.

---

## 6. url-shortener-service — Port 8085

**Purpose:** turn a long URL into a short code (Base62), and redirect when someone visits `/s/{code}`. Demonstrates a **cache-aside** pattern with Redis.

### Module structure

```
urlshortener/
├── UrlShortenerApplication.java
├── controller/UrlShortenerController.java
├── domain/ShortUrl.java
├── repository/ShortUrlRepository.java
└── service/
    ├── UrlShortenerService.java
    └── UrlNotFoundException.java
```

### `domain/ShortUrl`

```java
@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
@Column(unique = true, length = 12) private String code;
private String originalUrl;
private long clickCount;
private LocalDateTime createdAt, expiresAt;
private String createdBy;
```

Notice `GenerationType.IDENTITY` (auto-increment) rather than UUID. The integer id is what gets Base62-encoded into the short code, so it has to be a monotonically increasing number.

### `service/UrlShortenerService`

The core algorithm:

```java
@Transactional
public String shorten(String originalUrl, LocalDateTime expiresAt, String createdBy) {
    ShortUrl e = ShortUrl.builder().originalUrl(originalUrl).code("TEMP").build();
    e = repository.save(e);              // 1. INSERT → DB assigns the auto-increment id
    String code = toBase62(e.getId());   // 2. encode id to 0-9A-Za-z
    e.setCode(code);
    repository.save(e);                  // 3. UPDATE row with the code
    redis.opsForValue().set(REDIS_PREFIX + code, originalUrl, CACHE_TTL);
    return code;
}
```

`toBase62` is a textbook base conversion:

```java
static String toBase62(long id) {
    if (id == 0) return "0";
    StringBuilder sb = new StringBuilder();
    while (id > 0) { sb.append(BASE62.charAt((int)(id % 62))); id /= 62; }
    return sb.reverse().toString();
}
```

`resolve(code)` is the **cache-aside** pattern:

1. Look in Redis → on hit, return immediately (and bump click count async, ignoring failures).
2. On miss, query DB. If expired, throw 404.
3. Back-fill Redis with the value and TTL so the next call is a hit.

### `repository/ShortUrlRepository`

Two methods: `findByCode(String)` derived from method name, and an atomic `incrementClickCount` via `@Modifying @Query` (avoids the read-modify-write race you'd get with a Java-side `entity.setClickCount(entity.getClickCount() + 1)`).

### `controller/UrlShortenerController`

Two endpoints:

- `POST /urls/shorten` — accepts a `ShortenRequest` record, returns 201 with the code and full short URL.
- `GET /s/{code}` — returns HTTP 301 (`MOVED_PERMANENTLY`) with a `Location` header pointing at the original URL. The browser follows the redirect.

Note `/s/**` is in the gateway's public path list — short URLs work without authentication.

---

## 7. search-service — Port 8086

**Purpose:** fast prefix autocomplete on product names using Redis Sorted Sets. (Full-text search through Elasticsearch is set up at the infra level but isn't a class in the codebase.)

### Module structure

```
search/
├── SearchServiceApplication.java
├── controller/SearchController.java
└── service/AutocompleteService.java
```

### `service/AutocompleteService`

A clever use of Redis's `ZRANGEBYLEX`. The trick: store every prefix of a product name plus a "terminal" marker that contains the product id.

**Insert "samsung":**

```
ZADD autocomplete:products 0 "s"
ZADD autocomplete:products 0 "sa"
ZADD autocomplete:products 0 "sam"
...
ZADD autocomplete:products 0 "samsung"
ZADD autocomplete:products 0 "samsung*<productId>"   ← terminal marker
```

Every element has score 0, so the set is ordered **lexicographically**. That's what makes `ZRANGEBYLEX` work.

**Query "sam":**

```java
Range.of(Range.Bound.inclusive("[sam"),
         Range.Bound.inclusive("[sam" + (char)0xFFFF))
```

The `\uFFFF` character is the highest code point — `"[sam\uFFFF"` is the upper bound that includes any string starting with "sam". Redis returns every member in that lex range. The service filters to terminals (`s.contains("*")`), strips the `*<id>` suffix, deduplicates, and limits to 10.

Time complexity: **O(log N + M)** where N is total entries and M is results returned. Very fast even with millions of products.

### `controller/SearchController`

Single endpoint `GET /search/autocomplete?q=...`. Returns empty list for queries shorter than 2 chars (prevents firehose results from "s" alone).

---

## 8. ai-service — Port 8087

**Purpose:** a shopping-assistant chatbot powered by Spring AI 1.0. Combines OpenAI GPT-4o, **RAG (Retrieval-Augmented Generation)** via `pgvector`, conversation memory in Redis, sentiment analysis, and product recommendations. Demonstrates SSE streaming.

### Module structure

```
ai/
├── AiServiceApplication.java
├── controller/AiController.java
└── service/AiChatService.java
```

### `service/AiChatService`

Dependencies:

```java
private final ChatModel chatModel;            // Spring AI abstraction over OpenAI
private final VectorStore vectorStore;        // pgvector backend for similarity search
private final StringRedisTemplate redis;      // chat history
```

#### Conversation flow (`chat`)

```java
List<Message> history  = loadHistory(sessionId);
String        context  = ragContext(userMessage);   // top-3 similar products
List<Message> messages = buildMessages(context, history, userMessage);
String response = chatModel.call(new Prompt(messages))
    .getResult().getOutput().getText();
history.add(new UserMessage(userMessage));
history.add(new AssistantMessage(response));
saveHistory(sessionId, history);
return response;
```

1. **Load history** from Redis (key `chat:history:<sessionId>`). Newline-delimited, prefixed with `USER:` or `AI:`.
2. **RAG retrieval** — `vectorStore.similaritySearch(...)` queries pgvector with `topK=3, similarityThreshold=0.7`. Embedding is computed by Spring AI under the hood.
3. **Build messages** — system prompt (with retrieved product context appended) + last 20 history messages + the new user message. Trimming with `skip(history.size() - MAX_HISTORY)` keeps the context window bounded.
4. **Call the model** — `chatModel.call(new Prompt(messages))` does the HTTP call to OpenAI.
5. **Append and persist** — extend history with both messages, save back to Redis with 2-hour TTL.

Note the Spring AI 1.0 naming: `Message.getText()` (renamed from `getContent()`).

#### Streaming (`chatStream`)

```java
return ChatClient.create(chatModel).prompt()
    .messages(messages).stream().content()
    .doOnComplete(() -> log.debug("Stream complete"));
```

Returns `Flux<String>` — Reactor's "zero or many values over time." The controller produces `text/event-stream`, so the browser receives Server-Sent Events token-by-token, exactly like ChatGPT's typing animation.

#### Sentiment analysis

A "structured output" pattern: tell the model to return ONLY JSON with specific fields, parse the result. The implementation here is intentionally naive (manual string splitting); production code would use Spring AI's `BeanOutputConverter<SentimentResult>` to enforce the schema.

#### Recommendations

Similar prompting trick: ask for a JSON array, strip code fences, split commas, return up to 5 strings.

#### Helpers

- `buildMessages` — assembles `SystemMessage` + history slice + `UserMessage`.
- `ragContext` — wraps `similaritySearch`, joins document text with `---` separators, returns "Context unavailable." on error so a vector-store outage doesn't break chat.
- `loadHistory` / `saveHistory` — serialise list of `Message` to/from a `\n|||\n`-delimited string in Redis. Pattern matching `instanceof UserMessage u` is a Java 21 feature.
- `parseSentiment` — defensive parser that returns NEUTRAL on any failure.

### `controller/AiController`

Four endpoints: `POST /ai/chat`, `POST /ai/chat/stream` (SSE), `POST /ai/sentiment`, `POST /ai/recommend`. All request/response shapes are inner `record`s — clean and immutable.

---

## 9. notification-service — Port 8088

**Purpose:** receive events from Kafka and dispatch them via email (SMTP/SendGrid + Thymeleaf templates) and WebSocket (STOMP push). Demonstrates multi-channel fan-out.

### Module structure

```
notification/
├── NotificationServiceApplication.java
├── config/WebSocketConfig.java
├── consumer/NotificationEventConsumer.java
└── service/NotificationService.java
```

### `config/WebSocketConfig`

STOMP-over-WebSocket setup:

```java
reg.enableSimpleBroker("/queue", "/topic");
reg.setApplicationDestinationPrefixes("/app");
reg.setUserDestinationPrefix("/user");
reg.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
```

- `enableSimpleBroker` — in-memory broker (no external broker like RabbitMQ).
- `setUserDestinationPrefix("/user")` — combined with `convertAndSendToUser(userId, "/queue/notifications", ...)`, this routes a message to exactly the user with that id (assuming the client subscribed to `/user/queue/notifications`).
- `withSockJS()` — JavaScript fallback for browsers that can't speak raw WebSockets.

### `service/NotificationService`

Two channels:

- **Email** — `JavaMailSender` + `MimeMessageHelper` (multipart with HTML). The HTML body comes from a Thymeleaf template (`templateEngine.process(templateName, ctx)`). Variables passed as a `Map<String, Object>` are bound to the template context.
- **WebSocket** — `wsTemplate.convertAndSendToUser(userId, "/queue/notifications", payload)`.

Both methods swallow exceptions and log at ERROR — notification is "best effort," failure shouldn't bubble back to the upstream Kafka consumer (otherwise the offset wouldn't commit and the message would redeliver forever).

Convenience composites — `notifyOrderConfirmed`, `notifyOrderShipped` — send email + WebSocket in one call.

The inner `NotificationPayload` record (`type`, `message`, `data`) is the wire format pushed over WebSocket. The frontend listens for it and renders a toast.

### `consumer/NotificationEventConsumer`

A `@KafkaListener` for `notification-events`:

```java
JsonNode node = objectMapper.readTree(payload);
String eventType = node.path("eventType").asText();
switch (eventType) {
    case "ORDER_CONFIRMED" -> notificationService.notifyOrderConfirmed(...);
    case "ORDER_SHIPPED"   -> notificationService.notifyOrderShipped(...);
    case "ORDER_CANCELLED" -> notificationService.sendEmail(...);
    default -> log.debug("No handler for eventType={}", eventType);
}
```

Notes:

- `node.path("foo").asText()` — `path` returns a `MissingNode` if absent (no NPE); `asText()` then returns an empty string. Safer than `get(...)`.
- `asText("N/A")` — fallback default if the field is missing.
- On error, the method re-throws — Kafka will retry per the consumer's retry policy.

---

## 10. crawler-service — Port 8089

**Purpose:** a polite web crawler that fetches competitor pages with Jsoup, extracts product names/prices, and publishes to Kafka. Showcases Java 21 **virtual threads**.

### Module structure

```
crawler/
├── CrawlerServiceApplication.java
└── service/WebCrawlerService.java
```

### `service/WebCrawlerService`

A classic **BFS** crawler. Visited set is a `ConcurrentHashMap.newKeySet()`, queue is a `ConcurrentLinkedQueue`, results in a synchronized `ArrayList`.

```java
try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
    while (!queue.isEmpty() && pagesCrawled < config.maxPages()) {
        UrlDepth current = queue.poll();
        ...
        futures.add(exec.submit(() -> {
            Thread.sleep(POLITENESS_MS);
            Document doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(10_000).get();
            extractProduct(doc, url).ifPresent(p -> { discovered.add(p); publish(p); });
            if (current.depth() < config.maxDepth()) {
                doc.select("a[href]").stream()
                    .map(e -> e.absUrl("href"))
                    .filter(u -> shouldCrawl(u, seed) && !visited.contains(u))
                    .forEach(u -> queue.add(new UrlDepth(u, current.depth() + 1)));
            }
        }));
    }
    futures.forEach(f -> f.get(30, TimeUnit.SECONDS));
}
```

Concepts:

- **Virtual threads** — `newVirtualThreadPerTaskExecutor()` is the Java 21 lightweight-thread executor. Unlike platform threads (one OS thread each), you can have *hundreds of thousands* without breaking a sweat. The `Thread.sleep(POLITENESS_MS)` cost — which would normally tie up a precious platform thread — is essentially free.
- **`try-with-resources` on the executor** — when the block exits, `close()` waits for all submitted tasks to complete. Cleaner than manual `shutdown` + `awaitTermination`.
- **Politeness delay** — 1 second between fetches. Don't be a bad neighbour on the web.
- **`shouldCrawl(url, seed)`** — same-host check (only crawl inside the seed domain) and scheme check (http/https only).
- **Extraction** uses CSS selectors via Jsoup: `h1[itemprop=name],h1.product-title`, `[itemprop=price],.price`. Falls back to text scraping if the structured attributes aren't present.

Records at the bottom — `CrawlConfig`, `ProductPrice`, `CrawlResult`, `UrlDepth` — are clean data carriers. The compact constructor `public CrawlConfig(String seedUrl) { this(seedUrl, 100, 3); }` is a Java record convenience constructor.

`publish(ProductPrice)` formats a JSON string by hand (using Java 15 text blocks would be cleaner, but `formatted` works fine) and sends to `competitor-price-events` topic, keyed by product name.

---

## 11. payment-service — Port 8090

**Purpose:** receive payment requests via Kafka, call the payment gateway, persist the result, and publish a payment-result event. Demonstrates idempotent consumers in saga choreography.

### Module structure

```
payment/
├── PaymentServiceApplication.java
├── config/SecurityConfig.java
├── domain/entity/PaymentRecord.java
├── repository/PaymentRepository.java
└── service/
    ├── PaymentService.java
    └── PaymentGateway.java
```

### `domain/entity/PaymentRecord`

Audit trail of every charge attempt:

```java
private UUID sagaId;        // unique per saga — idempotency key
private UUID orderId, userId;
private BigDecimal amount;
private boolean success;
private String transactionId;   // gateway-side id (e.g. Stripe "pi_...")
private String failureReason;
```

`saga_id` is **`unique = true`** — that's the database-level guarantee that prevents double charges, even if the Kafka message is delivered more than once.

### `repository/PaymentRepository`

```java
boolean existsBySagaId(UUID sagaId);
```

Spring Data parses this into `SELECT COUNT(*) > 0 FROM payment_records WHERE saga_id = ?`. Used as the idempotency check.

### `service/PaymentGateway`

The Strategy interface and its default Stripe-flavoured implementation:

```java
public interface PaymentGateway {
    ChargeResult charge(UUID userId, BigDecimal amount, UUID orderId);
    record ChargeResult(boolean success, String transactionId, String failureReason) {}

    @Component
    class StripePaymentGateway implements PaymentGateway {
        @Value("${payment.stripe.secret-key:sk_test_placeholder}")
        private String stripeSecretKey;
        public ChargeResult charge(UUID userId, BigDecimal amount, UUID orderId) {
            boolean success = !amount.toPlainString().endsWith("13");   // simulate
            String  txId    = success ? "pi_" + UUID.randomUUID()... : null;
            return new ChargeResult(success, txId, success ? null : "Simulated failure");
        }
    }
}
```

Right now it's a stub — the real Stripe SDK call would replace the `success`/`txId` lines. Swapping to PayPal would mean adding another `@Component class PayPalPaymentGateway implements PaymentGateway` and `@Primary`/`@Qualifier` annotations.

### `service/PaymentService`

The saga participant:

```java
@KafkaListener(topics = "payment-events", groupId = "payment-service")
@Transactional
public void onPaymentRequested(String payload) {
    // 1. Parse the event
    UUID sagaId = UUID.fromString(node.get("sagaId").asText());
    ...

    // 2. Idempotency guard
    if (paymentRepository.existsBySagaId(sagaId)) {
        log.warn("Duplicate payment request sagaId={} – skipping", sagaId);
        return;
    }

    // 3. Charge via gateway
    ChargeResult result = paymentGateway.charge(userId, amount, orderId);

    // 4. Persist the record
    paymentRepository.save(PaymentRecord.builder().sagaId(sagaId)... .build());

    // 5. Publish the result event
    kafkaTemplate.send("payment-result-events", sagaId.toString(),
        "{\"sagaId\":\"%s\",\"success\":%b,\"transactionId\":\"%s\"}".formatted(...));
}
```

Things to notice:

- **Idempotent consumer** — the `existsBySagaId` check + DB unique constraint together guarantee at-most-once charge per saga.
- **`@Transactional`** — DB write and `kafkaTemplate.send` are in the same Spring transaction. (Note: Kafka's `KafkaTemplate.send` is not automatically transactional unless you configure a transactional producer — in this codebase it isn't, so think of this as "save first, then send.")
- **Re-throwing on error** — the listener re-throws, so Kafka redelivers. Combined with idempotency, that's "effectively-once" semantics.

### `config/SecurityConfig`

Same Keycloak JWT pattern as the other services.

---

## 12. Cross-Service Patterns You Should Recognise

A condensed cheat-sheet of the patterns and concepts that show up repeatedly.

### Architectural patterns

- **API Gateway** — single ingress, JWT validation, rate limiting (api-gateway).
- **Service Discovery** — Eureka registry, `lb://service-name` routing.
- **CQRS by interface segregation** — `ProductReadService` / `ProductWriteService`.
- **Aggregate Root with State Machine** — `Order.confirm()/ship()/deliver()/cancel()`.
- **Transactional Outbox** — `OutboxEvent` row written in the same DB tx as the business write.
- **Saga choreography** — `saga_id` correlates events across order-service and payment-service.
- **Idempotent consumer** — `existsBySagaId` + DB unique constraint in payment-service.
- **Cache-aside** — url-shortener-service.
- **Two-level cache (L1 Caffeine + L2 Redis)** — product-service.
- **Strategy pattern** — `PricingStrategy`, `PaymentGateway`.
- **Observer (via Kafka)** — `product-events`, `order-events`, `notification-events`, `payment-events`, `competitor-price-events`.

### Spring annotations roadmap

- `@SpringBootApplication` — the meta-annotation that boots everything.
- `@EnableDiscoveryClient` — register with Eureka.
- `@EnableCaching` / `@Cacheable` / `@CachePut` / `@CacheEvict` — declarative caching.
- `@EnableAsync` / `@Async` — fire-and-forget on a thread pool.
- `@EnableScheduling` / `@Scheduled` — periodic methods.
- `@EnableWebSecurity` (servlet) vs `@EnableWebFluxSecurity` (reactive).
- `@EnableMethodSecurity(prePostEnabled=true)` — turns on `@PreAuthorize`.
- `@Transactional` — declarative transactions; `readOnly=true` for queries.
- `@RestControllerAdvice` + `@ExceptionHandler` — global error handling, `ProblemDetail` for RFC 7807.
- `@RequiredArgsConstructor` (Lombok) — constructor injection for `final` fields.
- `@Slf4j` (Lombok) — generates a static `log` field.
- `@KafkaListener` — consume from a Kafka topic.

### JPA / Hibernate idioms

- `GenerationType.UUID` for surrogate keys, `IDENTITY` when you need a monotonically increasing number (url-shortener).
- `@Version` for optimistic locking.
- `@CreationTimestamp` / `@UpdateTimestamp` for audit fields.
- `@Enumerated(EnumType.STRING)` — never use `ORDINAL`.
- `@ElementCollection` + `@CollectionTable` for value collections without a child entity (`User.roles`).
- `@OneToMany(mappedBy="parent", cascade=ALL, orphanRemoval=true)` — child lifecycle owned by parent.
- `@Query("...JOIN FETCH...")` to avoid N+1 selects.
- `@Modifying @Query("UPDATE ...")` for atomic UPDATEs that bypass the persistence context.

### Bean Validation

- `@NotBlank`, `@NotNull`, `@Size`, `@DecimalMin`, `@Digits`, `@Min` etc. on record fields.
- `@Valid` on controller params triggers validation; failures throw `MethodArgumentNotValidException`, caught by the global handler.

### Reactive (api-gateway and ai-service streaming)

- `Mono<T>` — zero or one value.
- `Flux<T>` — zero or many values.
- `doFinally`, `doOnComplete` — side-effect callbacks.
- `Mono.fromCallable(...)` — lift a blocking call into the reactive chain.
- `MediaType.TEXT_EVENT_STREAM_VALUE` — produces Server-Sent Events.

### Java 21 features

- **Records** for DTOs and events.
- **Pattern matching** in `instanceof`.
- **Text blocks** `""" ... """` for prompts.
- **Virtual threads** in the crawler.
- `formatted(...)` on strings.
- `Set.of(...)`, `List.of(...)`, `Map.of(...)` for immutable collections.

### Where to dig next

When you understand this document end-to-end, try these exercises:

1. Trace a single `POST /orders` request from the API gateway all the way to a "Order Confirmed!" email, identifying every Kafka topic and DB write along the way.
2. Open `target/generated-sources/annotations/.../ProductMapperImpl.java` to see what MapStruct actually generated.
3. Add a new pricing strategy (e.g. `MembershipPricingStrategy`) and qualify it so only members get it.
4. Write a JUnit test for `Order` that proves `ship()` throws if the order is still `PENDING`.
5. Add a JPA `Outbox` poller to order-service: `@Scheduled(fixedDelay=5000)` reads unpublished rows and sends them via `KafkaTemplate`.
