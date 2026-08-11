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
| `POST` | `/internal/fraud/score` | Bearer (internal) | Rule-based score → `{decision, score, reasons[]}` under a p99 < 150ms budget |
| `GET`  | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

### `POST /internal/fraud/score`

Body `{accountId, pixKey, amountCents, timestamp?}` (integer cents; `timestamp` optional — falls back
to the server clock for the odd-hours rule). Returns `{decision: APPROVE|REVIEW|DENY, score, reasons[]}`.
Four cheap, in-path rules, all read from pre-computed Redis features — **no model, no DB, no network hop
beyond Redis** (heavy/ML scoring runs async off the event stream and feeds block-lists this check reads):

| Reason | Fires when | Signal source |
| ------ | ---------- | ------------- |
| `HIGH_AMOUNT` | single transfer > `fraud.rules.high-amount-cents` (R$5,000) | request |
| `VELOCITY_COUNT` | transfers this minute ≥ `velocity-count-threshold` (5) | Redis `INCR` + `EXPIRE 60s` |
| `VELOCITY_AMOUNT` | money this hour > `velocity-amount-threshold-cents` (R$20,000) | Redis `INCRBY` + `EXPIRE 1h` |
| `NEW_PAYEE` | this account never paid this key before | Redis `SADD` (persistent set) |
| `ODD_HOURS` | transfer time in `[00:00, 05:00)` America/Sao_Paulo | request timestamp / clock |

Each fired reason adds its configured weight to a 0–100 score; `score >= deny-band (70)` ⇒ DENY,
`>= review-band (40)` ⇒ REVIEW, else APPROVE. A single huge amount (weight 70) denies on its own.
`/internal/**` is **not** on the JWT allow-list, so the endpoint requires a Bearer token; payment-service
forwards the caller's token with the 200ms timeout + fail-open flag in **step 25**.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `REDIS_HOST` / `spring.data.redis.host` | `localhost` (compose: `redis`) | Redis host — the velocity-counter store |
| `REDIS_PORT` / `spring.data.redis.port` | `6379` | Redis port |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must match auth-service. This service only **validates** tokens. |
| `web.cors.allowed-origin-patterns` | `*` | Local-dev CORS for the from-disk API explorer; a deployed posture pins origins (step 45) |

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/            FraudScoreController (1 use case + Micrometer timer), ScoreRequest (wire + validation)
domain/model/   Decision, FraudReason, ScoreResult, FraudRules
domain/port/    FraudSignalStore  (the outbound Redis-feature interface)
domain/usecase/ ScoreFraudUseCase, ScoreCommand
infra/persistence/ RedisFraudSignalStore  (@Repository — INCR/EXPIRE/SADD)
infra/config/   FraudProperties (@ConfigurationProperties → FraudRules), FraudBeansConfig, CorsConfig
```

`FraudArchitectureTest` enforces **both** ArchUnit rules: `domain/` imports nothing outward (no Spring /
Redis / servlet / JWT / Jackson — the Redis client stays behind the `FraudSignalStore` port in `infra/`),
and `api/` never depends on an interface in `domain/` — so the controller cannot reach the Redis port
itself, only the `ScoreFraudUseCase`. No exception handler of its own: bean-validation failures on the
body become `400 VALIDATION_ERROR` via common-lib's shared `GlobalExceptionHandler`.

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
mvn -pl services/fraud-service verify          # unit/architecture (*Test) + ITs (*IT, Testcontainers Redis)

# health (after `docker compose up`)
curl -s localhost:8083/actuator/health | jq
# Redis reachable from the stack
docker compose -f infra/docker-compose.yml exec redis redis-cli ping   # PONG

# score a transfer (the endpoint is authenticated — forge/borrow a Bearer token)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -s -X POST localhost:8083/internal/fraud/score \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-001","pixKey":"bob@platinum.com","amountCents":12550,"timestamp":"2026-07-07T12:00:00Z"}' | jq
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
