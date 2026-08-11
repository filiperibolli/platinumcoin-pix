# fraud-service

> Synchronous fraud scoring in the send path for the PlatinumCoin Pix platform. Inserted between the
> daily-limit check and the ledger debit under a **hard 200ms client budget** (fraud targets p99 <
> 150ms), **fail-open** on timeout/error (ADR-0005). **Step 23 ships the skeleton only** — the
> rule-based scoring endpoint is step 24.

- **Port:** `8083`
- **Depends on:** `common-lib` (error model, correlation-id filter, JSON logging, JWT validation) + **Redis** (velocity counters)
- **Infra:** **Redis** (`redis:7-alpine`, its own container) — the local stand-in for **ElastiCache for Redis**, which LocalStack does not emulate (ADR-0008). No DynamoDB.

## Why it exists

Fraud scoring must not put money movement at risk: it runs on a **budget** and **fails open** — if
fraud-service is slow or down, the payment proceeds flagged rather than being blocked (ADR-0005). Redis
comes up in this sprint because fraud is the first flow that needs it (velocity counters — per-account
rolling `INCR`/`EXPIRE` windows); Sprint 9's balance cache reuses the **same** container.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `GET`  | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

> `POST /internal/fraud/score` (velocity/amount/novelty/hours → `{decision, score, reasons}`) lands in
> **step 24**; payment-service calls it with the 200ms timeout + fail-open flag in **step 25**. The
> skeleton deliberately has **no `api/` or `domain/` layer yet** — they arrive with that first endpoint.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `REDIS_HOST` / `spring.data.redis.host` | `localhost` (compose: `redis`) | Redis host — the velocity-counter store |
| `REDIS_PORT` / `spring.data.redis.port` | `6379` | Redis port |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must match auth-service. This service only **validates** tokens. |
| `web.cors.allowed-origin-patterns` | `*` | Local-dev CORS for the from-disk API explorer; a deployed posture pins origins (step 45) |

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
infra/config/   FraudBeansConfig (composition root, empty until step 24), CorsConfig   (wiring)
```

The `api/` and `domain/` layers do not exist yet — per ADR-0010 a service carries **only the roles it
has**, and the skeleton has no inbound operation. Step 24 adds `api/` (the scoring controller +
request/response records + exception handler), `domain/` (`model/`, the Redis-counter `port/`, the
`ScoreFraudUseCase` in `usecase/`) and `infra/persistence/` (the Redis adapter), and wires the use case
in `FraudBeansConfig`.

`FraudArchitectureTest` ships **both** ArchUnit rules from day one: `domain/` imports nothing outward
(no Spring / Redis / servlet / JWT / Jackson), and `api/` never depends on an interface in `domain/` —
so once step 24 lands, a controller cannot reach the Redis port itself.

## Run

```bash
# from repo root
mvn -pl services/fraud-service -am clean package
java -jar services/fraud-service/target/fraud-service-0.0.1-SNAPSHOT.jar   # needs a Redis on localhost:6379
# or via compose (brings up Redis first)
docker compose -f infra/docker-compose.yml up -d --build redis fraud-service
```

## Test

```bash
mvn -pl services/fraud-service verify          # unit/architecture (*Test) + context IT (*IT, Testcontainers Redis)

# health (after `docker compose up`)
curl -s localhost:8083/actuator/health | jq
# Redis reachable from the stack
docker compose -f infra/docker-compose.yml exec redis redis-cli ping   # PONG
```

## Related decisions

- [ADR-0005](../../docs/adr/0005-fraud-latency-budget-fail-open.md) — fraud on a latency budget, fail-open.
- [ADR-0008](../../docs/adr/0008-redis-balance-cache.md) — Redis as its own container, the local
  ElastiCache stand-in (velocity counters here, balance cache in Sprint 9).
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`,
  `com.platinumcoin.pix` at DEBUG.
