# Step 23 — docker-compose Redis + fraud-service skeleton

> **Sprint 5 — Fraud** · **Flow:** fraud score in the path · **Infra que sobe:** Redis + fraud-service · **Diagram:** ARCHITECTURE §6.5

## Objective
Bring up **Redis** in docker-compose (its own container — LocalStack does not emulate ElastiCache) and scaffold `fraud-service` (port 8083) with health, Dockerfile and compose wiring.

## Why / what you'll learn
Redis rides alongside LocalStack **because LocalStack does not emulate ElastiCache** (documented explicitly, ADR-0008) — in production this maps 1:1 to ElastiCache for Redis. It comes up in *this* sprint, not earlier, because fraud is the first flow that needs it (velocity counters); the balance cache (Sprint 9) will reuse the same container. You'll add a `RedisTestBase` to the Testcontainers harness so fraud ITs get a disposable Redis.

## Prerequisites
Steps 05 (JWT filter), 08 (harness).

## Tasks
1. Add `redis` to `infra/docker-compose.yml` (image `redis:7-alpine`, port 6379, healthcheck `redis-cli ping`); env `REDIS_HOST`/`REDIS_PORT`.
2. Extend the common-lib test harness with `RedisTestBase` (Testcontainers Redis).
3. Scaffold `services/fraud-service` (skeleton + Dockerfile + compose entry + `README.md`, port 8083); depends on common-lib and a Redis client.

## Tests (TDD)
- `ApplicationContextIT` — fraud-service context loads with a Redis connection (against `RedisTestBase`).
- Harness smoke — `RedisTestBase` starts and `PING` returns `PONG`.

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d redis fraud-service
docker compose -f infra/docker-compose.yml exec redis redis-cli ping   # PONG
curl -s localhost:8083/actuator/health | jq
```

## Definition of Done
- [ ] `README.md` present for fraud-service (purpose, port, endpoints, config, run/test) — per-service README convention (CLAUDE.md)
- [ ] Redis up healthy; documented as the ElastiCache stand-in
- [ ] fraud-service boots with a Redis connection; Dockerfile + compose entry present
- [ ] `RedisTestBase` available to any module

## CHANGELOG entry
`### Added` → `Redis container (ElastiCache stand-in) + fraud-service skeleton + RedisTestBase harness (step 23)`
