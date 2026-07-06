# Step 07 — Dockerize services; full-stack compose

## Objective
Every service has a Dockerfile; `infra/docker-compose.yml` runs the entire platform (infra + 8 services) with health-gated startup ordering and memory caps. One command boots the world.

## Why / what you'll learn
Multi-stage Docker builds (JRE-only runtime image) and compose dependency management: services `depends_on` LocalStack **with `condition: service_healthy`**, so no service races the init scripts. Memory discipline: `JAVA_TOOL_OPTIONS=-Xmx512m` per JVM keeps 8 services + infra ≈ 6–8GB — the practical craft of running a "distributed system" on one 32GB PC.

## Prerequisites
Steps 02, 05.

## Tasks
1. Shared `infra/Dockerfile.service` (multi-stage: copy prebuilt jar onto `eclipse-temurin:21-jre-alpine`; `ARG SERVICE`), or one Dockerfile per module — pick one and document it.
2. Extend compose: the 8 services with `build`, port mappings per runbook, env per docs/local-dev.md §3, `depends_on` localstack+redis healthy, healthcheck `wget -qO- localhost:PORT/actuator/health`, `mem_limit: 640m`.
3. Makefile (or `scripts/`): `make build up down logs reset` wrappers.
4. Update README run instructions if anything differs.

## Tests (TDD)
Runbook-verified (compose is the E2E playground; automated tests remain on Testcontainers).

## Verify locally
```bash
mvn -q clean package -DskipTests && docker compose -f infra/docker-compose.yml up -d --build
for p in 8081 8082 8083 8084 8085 8086 8087 9090; do echo -n "$p: "; curl -s localhost:$p/actuator/health | jq -r .status; done
docker stats --no-stream | sort -k4 -h | tail   # sanity: memory within caps
```

## Definition of Done
- [ ] `up -d --build` from clean state → all 9+ containers healthy
- [ ] No service starts before LocalStack init completed
- [ ] Total stack RAM ≈ ≤8GB

## CHANGELOG entry
`### Added` → `Dockerfiles and full-stack docker-compose with health-gated ordering and memory caps (step 07)`
