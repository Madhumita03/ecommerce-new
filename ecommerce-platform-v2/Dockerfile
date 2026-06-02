# =============================================================================
# Multi-stage Dockerfile for all Spring Boot 3.5 microservices
# Java 21 | Virtual Threads | SLF4J runtime (logback-classic)
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw dependency:go-offline -B --no-transfer-progress
COPY src/ src/
RUN ./mvnw package -DskipTests -B --no-transfer-progress && \
    java -Djarmode=layertools -jar target/*.jar extract

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app
RUN mkdir -p /app/logs

# Layered JAR (cache layers by change frequency: deps → loader → snapshot → app)
COPY --from=build /workspace/dependencies/           ./
COPY --from=build /workspace/spring-boot-loader/     ./
COPY --from=build /workspace/snapshot-dependencies/  ./
COPY --from=build /workspace/application/            ./

EXPOSE 8080

# Spring Boot 3.5 JVM flags:
#   -XX:MaxRAMPercentage=75   → use 75% of container memory
#   -XX:+UseG1GC              → balanced GC
#   virtual threads configured via spring.threads.virtual.enabled=true (no JVM flag needed)
ENTRYPOINT ["java",
  "-XX:MaxRAMPercentage=75.0",
  "-XX:+UseG1GC",
  "-XX:+HeapDumpOnOutOfMemoryError",
  "-XX:HeapDumpPath=/app/logs/heap-dump.hprof",
  "-Djava.security.egd=file:/dev/./urandom",
  "org.springframework.boot.loader.launch.JarLauncher"]

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
