# =============================================================================
# Multi-stage Dockerfile for all Spring Boot microservices.
# Build with the repository root as context and MODULE set to a service folder.
# =============================================================================

ARG MODULE

FROM maven:3.9-eclipse-temurin-21-alpine AS build
ARG MODULE
WORKDIR /workspace
COPY pom.xml .
COPY services/ services/
RUN mvn -pl "services/${MODULE}" -am package -DskipTests -B --no-transfer-progress && \
    cp "services/${MODULE}"/target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app
RUN mkdir -p /app/logs

COPY --from=build /workspace/app.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/logs/heap-dump.hprof", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
