# 🛒 ShopEase E-Commerce Platform v2

> **Spring Boot 3.5** · **Java 21** · **SLF4J** · **JUnit 5.12** · **Mockito 5.17**

---

## 🆕 What's New in v2

### Spring Boot 3.5 Features Applied

| Feature | Where Used |
|---------|-----------|
| `spring.application.group` | All `application.yml` files – structured log grouping |
| `spring.threads.virtual.enabled=true` | All services – enables virtual threads platform-wide |
| Background bean initialisation (`bootstrapExecutor`) | Product Service, AI Service |
| `@ConditionalOnBooleanProperty` | Feature-flag config in Gateway |
| Structured logging (ECS JSON on `prod` profile) | `logback-spring.xml` |
| WebClient global config properties | API Gateway reactive client config |
| Updated BOM: JUnit Jupiter 5.12, Mockito 5.17, Kafka 3.9, ES Client 8.17 | All test modules |
| `heapdump` endpoint `access=NONE` by default | Actuator config |

### SLF4J – Logging Architecture

```
Application Code
      │
  SLF4J API  ←── org.slf4j.Logger / Lombok @Slf4j
      │             (the ONLY logging API used in app code)
      │
SLF4J Binding  ←── logback-classic (pulled by spring-boot-starter, runtime only)
      │
logback-spring.xml  ←── configures appenders (console/file/Splunk)
                          (the ONLY place logback classes are referenced)
```

**Rule**: Application code imports ONLY `org.slf4j.Logger` or uses Lombok `@Slf4j`. Zero `ch.qos.logback.*` imports in any service class.

### JUnit 5 + Mockito 5 Tests

| Test Class | Service | What's Tested |
|-----------|---------|---------------|
| `ProductServiceTest` | product-service | Create, Read, Stock, Delete, Events – 10 tests |
| `ProductControllerTest` | product-service | HTTP status, Security, Validation – 8 tests |
| `UrlShortenerServiceTest` | url-shortener | Base62 encoding, shorten, resolve, expiry – 11 tests |
| `AutocompleteServiceTest` | search-service | Redis ZRANGEBYLEX, indexing, limits – 6 tests |
| `OrderStateMachineTest` | order-service | State transitions, guard clauses – 8 tests |
| `NotificationServiceTest` | notification-service | Email, WebSocket, error handling – 5 tests |
| `AiChatServiceTest` | ai-service | Chat, sentiment parsing, recommendations – 6 tests |
| `PaymentServiceTest` | payment-service | Idempotency, success, failure, malformed – 4 tests |
| `WebCrawlerServiceTest` | crawler-service | HTML parsing, edge cases, config – 6 tests |
| `KeycloakRolesConverterTest` | user-service | JWT role extraction, edge cases – 4 tests |

**Total: 68 unit tests**

---

## Quick Start

```bash
cp .env.example .env   # fill in OPENAI_API_KEY, SENDGRID_API_KEY, STRIPE_SECRET_KEY
docker-compose up -d

# Verify
curl http://localhost:8080/actuator/health

# Get token (Keycloak)
TOKEN=$(curl -s -X POST http://localhost:8180/realms/ecommerce/protocol/openid-connect/token \
  -d "grant_type=password&client_id=ecommerce-backend&client_secret=ecommerce-backend-secret&username=admin&password=admin123" \
  | jq -r '.access_token')

# Create product
curl -X POST http://localhost:8080/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Sony WH-1000XM5","sku":"SONY-WH5-BLK","price":349.99,"stockQuantity":100,"categoryId":1}'

# AI chat
curl -X POST http://localhost:8080/ai/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"sess-001","message":"Recommend noise-cancelling headphones under $400"}'

# Autocomplete
curl "http://localhost:8080/search/autocomplete?q=son"

# Shorten a URL
curl -X POST http://localhost:8080/urls/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://shopease.com/products/abc"}'
```

---

## Running Tests

```bash
# All unit tests
mvn test

# Single service
cd services/product-service && mvn test

# With coverage report
mvn verify && open services/product-service/target/site/jacoco/index.html

# Run specific test class
mvn test -pl services/product-service -Dtest=ProductServiceTest

# Run specific test method
mvn test -pl services/product-service -Dtest="ProductServiceTest#shouldCreateProductAndPublishCreatedEvent"
```

---

## Services & Ports

| Service | Port | Description |
|---------|------|-------------|
| Nginx | 80 | Reverse proxy + load balancer |
| API Gateway | 8080 | Rate limiter (Bucket4j), JWT, circuit breaker |
| Product Service | 8081 | Catalog, ShardingSphere, L1+L2 cache |
| Order Service | 8082 | Saga choreography, Outbox pattern |
| User Service | 8083 | Keycloak OAuth2/OIDC integration |
| URL Shortener | 8085 | Base62 encoding, Redis cache |
| Search Service | 8086 | Redis autocomplete + Elasticsearch |
| AI Service | 8087 | Spring AI 1.0, GPT-4o, PgVector RAG |
| Notification | 8088 | Email + WebSocket STOMP |
| Crawler | 8089 | Jsoup BFS, virtual threads |
| Payment | 8090 | Stripe, idempotent saga participant |
| Keycloak | 8180 | Identity provider |
| Prometheus | 9090 | Metrics |
| Grafana | 3000 | Dashboards (admin/admin) |

---

## Spring Boot 3.5 – Key Configuration

```yaml
spring:
  application:
    name: my-service
    group: ecommerce-platform   # NEW in 3.5: groups related services in logs

  # NEW in 3.5: enables virtual threads globally (Tomcat + async executors)
  threads:
    virtual:
      enabled: true

  # NEW in 3.5: structured logging (ECS JSON format)
  logging:
    structured:
      format:
        console: ecs
```

---

## Design Patterns Applied

| Pattern | Location |
|---------|---------|
| **Strategy** | `PricingStrategy`, `PaymentGateway` |
| **Observer** | Kafka events (ProductEvent, OrderEvent) |
| **Saga** | `OrderSagaOrchestrator` (choreography) |
| **Outbox** | `OutboxEvent`, `OutboxPoller` |
| **CQRS** | `ProductReadService` / `ProductWriteService` |
| **Builder** | All entities (Lombok @Builder) |
| **State Machine** | `Order` aggregate transitions |
| **Aggregate Root** | `Order` → `OrderItem` |
| **Repository** | Spring Data JPA repositories |
| **Decorator** | `CompositeCacheManager` (L1+L2) |
| **Chain of Responsibility** | Gateway filter chain |
| **Template Method** | `WebCrawlerService.extractProduct()` |
| **Idempotent Consumer** | `PaymentService` (sagaId guard) |
| **Factory** | `UrlShortenerService.toBase62()` |

---

## SOLID Principles

| Principle | Concrete Example |
|-----------|----------------|
| **S** | `ProductService` = business logic only; cache in `CacheConfig`, Kafka in `KafkaConfig` |
| **O** | `PricingStrategy` – new strategies extend interface, `ProductService` never changes |
| **L** | `StripePaymentGateway` and `MockPaymentGateway` interchangeable via `PaymentGateway` |
| **I** | `ProductReadService` + `ProductWriteService` – clients depend only on what they use |
| **D** | `ProductService` → `ProductRepository` (interface), not JPA concrete class |

---

## Kafka Topics

| Topic | Partitions | Retention |
|-------|-----------|-----------|
| `product-events` | 3 | 7 days |
| `order-events` | 3 | 7 days |
| `stock-events` | 3 | 7 days |
| `payment-events` | 3 | 7 days |
| `payment-result-events` | 3 | 7 days |
| `notification-events` | 3 | 7 days |
| `competitor-price-events` | 3 | 3 days |
