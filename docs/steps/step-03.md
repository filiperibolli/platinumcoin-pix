# Step 03 — auth-service Spring Boot skeleton with Actuator health

> **Sprint 1 — Foundation & Identity** · **Flow:** login → JWT · **Infra que sobe:** none · **Diagram:** ARCHITECTURE §6.1

## Objective
The first runnable deployable: `auth-service` boots on port 8081, depends on `common-lib`, exposes `/actuator/health`, and (per the vertical plan) is the first service to join `infra/docker-compose.yml` with a Dockerfile — establishing the pattern every later service reuses.

## Why / what you'll learn
Walking skeleton first: get a deployable to boot, own a port and report health *before any business logic*. Actuator health is the contract that docker-compose healthchecks and the runbook rely on. Unlike the old horizontal plan (all 8 skeletons at once), here **each service is scaffolded in its own sprint**, so the topology grows with the flows — you never boot a service that has nothing to do yet.

## Prerequisites
Steps 01, 02.

## Tasks
1. Module `services/auth-service`: POM (parent ref; `spring-boot-starter-web`, `spring-boot-starter-actuator`, `common-lib`), main class `com.platinumcoin.pix.auth.Application`, `application.yml` with `server.port=8081`, `spring.application.name=auth-service`.
2. Add the module to the parent `<modules>`.
3. Expose only `health,info,metrics`; `management.endpoint.health.probes.enabled=true` (readiness/liveness groups).
4. **Dockerize + compose pattern:** `services/auth-service/Dockerfile` (layered Spring Boot jar, `JAVA_TOOL_OPTIONS=-Xmx512m`); add an `auth-service` entry to `infra/docker-compose.yml` (network `pix-net`, healthcheck on `/actuator/health`). Document the pattern in a comment — later services copy it.

## Tests (TDD)
- `ApplicationContextIT` (`@SpringBootTest`) — the context loads. Catches broken wiring at build time forever after.

## Verify locally
```bash
mvn -q -pl services/auth-service clean package
java -jar services/auth-service/target/*.jar &
curl -s localhost:8081/actuator/health | jq        # {"status":"UP"}
kill %1
```

## Definition of Done
- [ ] auth-service builds, context-load test green, starts standalone with `java -jar`
- [ ] Port 8081 matches docs/local-dev.md §2
- [ ] Dockerfile + compose entry present; the "service in compose" pattern is documented for reuse

## CHANGELOG entry
`### Added` → `auth-service Spring Boot skeleton with Actuator health, Dockerfile and compose wiring (step 03)`
