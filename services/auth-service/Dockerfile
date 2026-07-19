# ─────────────────────────────────────────────────────────────────────────────
# Reference Dockerfile for a PlatinumCoin service — LATER SERVICES COPY THIS.
#
# Pattern (Spring Boot layered jar, two stages):
#   1. `builder` stage explodes the repackaged fat jar into Boot's four layers
#      (dependencies / spring-boot-loader / snapshot-dependencies / application),
#      ordered most-stable → most-volatile.
#   2. Runtime stage copies each layer as its own Docker layer, so a code-only
#      change re-ships only the tiny `application/` layer — dependencies stay cached.
#
# Build context is the repo root (see docker-compose `build.context: ..`), so the
# jar path is qualified from there. The jar must already be built by
# `mvn clean package` before `docker compose build` (see docs/local-dev.md §4).
# ─────────────────────────────────────────────────────────────────────────────
FROM amazoncorretto:21 AS builder
WORKDIR /app
ARG JAR_FILE=services/auth-service/target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=layertools -jar application.jar extract

FROM amazoncorretto:21
WORKDIR /app

# Cap the heap so the whole stack fits the reference box (docs/local-dev.md).
ENV JAVA_TOOL_OPTIONS=-Xmx512m

# Copy layers most-stable first for maximum build-cache reuse.
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

EXPOSE 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
