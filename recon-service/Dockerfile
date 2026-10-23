# syntax=docker/dockerfile:1
# Build context: the repo root (see docker-compose.yml).

# --- build --------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY config config
COPY recon-core recon-core
COPY recon-datagen recon-datagen
COPY recon-service recon-service
# The cache mount keeps downloaded dependencies between builds; tests run in CI, not here.
RUN --mount=type=cache,target=/root/.gradle ./gradlew :recon-service:bootJar --no-daemon -x test

# --- run ----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S recon && adduser -S recon -G recon
WORKDIR /app
COPY --from=build /src/recon-service/build/libs/recon-service.jar app.jar
COPY config/tolerances.properties config/tolerances.properties
USER recon
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
