# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Copy ALL module POMs first so Maven can resolve the full reactor graph.
# This layer is cached unless any POM changes.
COPY pom.xml                              ./pom.xml
COPY api-gateway/pom.xml                  ./api-gateway/pom.xml
COPY order-service/pom.xml                ./order-service/pom.xml
COPY payment-service/pom.xml              ./payment-service/pom.xml
COPY inventory-service/pom.xml            ./inventory-service/pom.xml
COPY notification-service/pom.xml         ./notification-service/pom.xml
COPY agent-orchestrator/pom.xml           ./agent-orchestrator/pom.xml

# Resolve dependencies for this module only (-pl), without building siblings (-am dropped).
# We skip -am to avoid needing sibling sources; shared deps come from the BOM in the parent POM.
RUN mvn -pl order-service dependency:resolve dependency:resolve-plugins     -q --no-transfer-progress

# Copy only this module's sources
COPY order-service/src ./order-service/src

# Build this module; parent POM manages version alignment
RUN mvn -pl order-service package -DskipTests -q --no-transfer-progress

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S poc && adduser -S poc -G poc
USER poc

COPY --from=build /workspace/order-service/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=docker", \
  "-jar", "app.jar"]
