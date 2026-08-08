# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml                              ./pom.xml
COPY api-gateway/pom.xml                  ./api-gateway/pom.xml
COPY order-service/pom.xml                ./order-service/pom.xml
COPY payment-service/pom.xml              ./payment-service/pom.xml
COPY inventory-service/pom.xml            ./inventory-service/pom.xml
COPY notification-service/pom.xml         ./notification-service/pom.xml
COPY agent-orchestrator/pom.xml           ./agent-orchestrator/pom.xml

RUN mvn -pl api-gateway dependency:resolve dependency:resolve-plugins -q --no-transfer-progress
COPY api-gateway/src ./api-gateway/src
RUN mvn -pl api-gateway package -DskipTests -q --no-transfer-progress

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl is required by the Docker Compose healthcheck.
RUN apk add --no-cache curl \
    && addgroup -S poc \
    && adduser -S poc -G poc

COPY --from=build /workspace/api-gateway/target/*.jar app.jar

USER poc
EXPOSE 8080

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=docker", \
  "-jar", "app.jar"]
