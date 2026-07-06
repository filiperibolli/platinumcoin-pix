# Step 02 — Spring Boot skeletons for all 8 services

## Objective
Eight runnable Spring Boot modules — auth, account, fraud, payment, ledger, settlement, notification services + mock-bacen-spi — each exposing `/actuator/health` on its assigned port (8081–8087, 9090).

## Why / what you'll learn
Walking skeletons first: getting every deployable to boot, own a port and report health *before any business logic* means all later steps change one service at a time against a stable topology. Actuator health is also the contract docker-compose healthchecks (step 07) and the runbook rely on.

## Prerequisites
Step 01.

## Tasks
1. For each service: module dir `services/<name>`, POM (parent ref; `spring-boot-starter-web`, `spring-boot-starter-actuator`, `common-lib`), main class `com.platinumcoin.pix.<name>.Application`, `application.yml` with `server.port` per docs/local-dev.md §2 and `spring.application.name`.
2. Add all modules to the parent `<modules>`.
3. Expose only `health,info,metrics` actuator endpoints.
4. Set `management.endpoint.health.probes.enabled=true` (readiness/liveness groups — the same shape k8s would use; compose healthchecks use `/actuator/health`).

## Tests (TDD)
- Per service: `ApplicationContextIT` (`@SpringBootTest`) — the context loads. Catches broken wiring at build time forever after.

## Verify locally
```bash
mvn -q clean package
java -jar services/ledger-service/target/*.jar &   # spot-check one
curl -s localhost:8085/actuator/health | jq        # {"status":"UP"}
kill %1
```

## Definition of Done
- [ ] All 8 modules build; each context-load test green
- [ ] Ports match docs/local-dev.md §2 exactly
- [ ] Each service starts standalone with `java -jar`

## CHANGELOG entry
`### Added` → `Spring Boot skeletons for all 8 services with Actuator health on assigned ports (step 02)`
