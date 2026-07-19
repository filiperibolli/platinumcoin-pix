# Step 30 — mock-bacen-spi: settlement endpoint + external DICT

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** mock-bacen-spi (in compose) · **Diagram:** ARCHITECTURE §6.6

## Objective
`mock-bacen-spi` (port 9090) gains the SPI surface: `POST /spi/settlements` (idempotent by `endToEndId`; waits `BACEN_LATENCY_MS`; fails/hangs per configured rates), `GET /spi/settlements/{endToEndId}` (status lookup), `POST /admin/config` (runtime latency/failureRate/timeoutRate), plus `GET /spi/dict/{key}` for external key resolution — closing the seam left open in step 11.

## Why / what you'll learn
A **controllable external dependency** is what makes the resilience work (Sprint 7) testable: configurable latency (0–10s, the SPI SLA), failure and timeout injection. Idempotency by `endToEndId` mirrors the real SPI — retrying a settlement after a timeout is safe. This step also completes **external key resolution**: account-service's step-11 seam now delegates unknown keys here (`GET /spi/dict/{key}` answers for a configured set of "external bank" keys), so `bob@otherbank.com` resolves and the external send flow is fully wired.

## Prerequisites
Steps 11 (resolution seam), 26 (messaging), and needed by 31.

## Tasks
1. Scaffold `services/mock-bacen-spi` (skeleton + Dockerfile + compose + `README.md`, port 9090); in-memory settlement store.
2. `POST /spi/settlements` (body incl. `endToEndId`): idempotent (same e2e ⇒ same result); sleep `BACEN_LATENCY_MS`; roll `BACEN_FAILURE_RATE` (⇒ 5xx) and `BACEN_TIMEOUT_RATE` (⇒ hang past client timeout).
3. `GET /spi/settlements/{endToEndId}` → SETTLED/FAILED/UNKNOWN.
4. `POST /admin/config` → mutate latency/failureRate/timeoutRate at runtime.
5. `GET /spi/dict/{key}` → external-bank resolution for a configured key set; update account-service step-11 to delegate unknown keys here (turn the step-11 red test green).

## Tests (TDD)
- `SpiSettlementIT` — settle returns SETTLED after latency; same e2e twice ⇒ one settlement; failureRate=1 ⇒ 5xx; admin config changes behavior.
- `ExternalDictIT` (account-service) — unknown internal key now resolves via mock-bacen; still-unknown ⇒ KEY_NOT_FOUND.

## Verify locally
```bash
curl -s -X POST localhost:9090/admin/config -d '{"latencyMs":2000,"failureRate":0.0}' -H 'Content-Type: application/json'
curl -s "localhost:8082/internal/pix-keys/resolve?key=bob@otherbank.com" | jq   # {internal:false, externalBank:...}
```

## Definition of Done
- [ ] `README.md` present (purpose, port, endpoints, config, run/test) — per-service README convention (CLAUDE.md)
- [ ] SPI settle/status endpoints idempotent by endToEndId with configurable latency/failure/timeout
- [ ] Runtime admin config works
- [ ] External key resolution delegates to mock-bacen; step-11 seam closed

## CHANGELOG entry
`### Added` → `mock-bacen-spi: settlement + status + admin-config endpoints and external DICT resolution (step 30)`
