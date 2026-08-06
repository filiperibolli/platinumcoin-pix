# ─────────────────────────────────────────────────────────────────────────────
# payment-service image — copies the reference pattern documented in
# services/auth-service/Dockerfile (Spring Boot layered jar, two stages).
#
# Build context is the repo root (docker-compose `build.context: ..`), so the jar
# path is qualified from there. Run `mvn clean package` before `docker compose build`.
# ─────────────────────────────────────────────────────────────────────────────
FROM amazoncorretto:21 AS builder
WORKDIR /app
ARG JAR_FILE=services/payment-service/target/*.jar
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

EXPOSE 8084
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
