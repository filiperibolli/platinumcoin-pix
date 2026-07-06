# Step 26 — mock-bacen-spi: settlement API + chaos knobs

## Objective
mock-bacen gains the SPI surface: `POST /spi/settlements` (idempotent by endToEndId; waits `BACEN_LATENCY_MS`; fails/hangs per configured rates), `GET /spi/settlements/{endToEndId}` (status lookup), `POST /admin/config` (runtime latency/failureRate/timeoutRate), in-memory settlement store.

## Why / what you'll learn
Building a good **test double of an external dependency** is a core engineering skill: it must honor the contract's semantics (idempotency by endToEndId — re-POST of a settled id returns the same result, *exactly what real SPI does and what step 28's query-before-retry relies on*), and expose chaos knobs so failure paths are testable on demand instead of by luck. The `GET` endpoint exists because reconciliation needs it — you're designing the dependency's API from your consumer's needs.

## Prerequisites
Step 02 (skeleton); step 12 added DICT here.

## Tasks
1. `SettlementStore` (concurrent map endToEndId → record{status SETTLED|FAILED, settledAt}).
2. POST: known id ⇒ return stored outcome (idempotent); else roll dice: timeoutRate ⇒ sleep > caller timeout; failureRate ⇒ 500; else sleep latency ⇒ store SETTLED ⇒ 200.
3. GET by endToEndId ⇒ 200 record | 404.
4. `/admin/config` GET/POST (allowlisted, no JWT); config is live (no restart).
5. Inbound-Pix generator arrives in step 32 — leave a TODO marker.

## Tests (TDD)
- Idempotency: two POSTs same id ⇒ one settlement, same response.
- Chaos: failureRate=1 ⇒ 500 but **no stored settlement**; timeoutRate=1 ⇒ request hangs past client deadline (test with short-timeout client).
- Config hot-swap reflected immediately.

## Verify locally
```bash
curl -s -X POST localhost:9090/spi/settlements -H 'Content-Type: application/json' \
 -d '{"endToEndId":"E-test-1","amountCents":100,"creditorKey":"x@otherbank.com"}' | jq   # after latency: SETTLED
curl -s localhost:9090/spi/settlements/E-test-1 | jq
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' -d '{"latencyMs":8000}' | jq
```

## Definition of Done
- [ ] SPI contract idempotent by endToEndId, query-able by id
- [ ] Latency/failure/timeout injectable at runtime
- [ ] Latency configurable across the full 0–10s SLA range

## CHANGELOG entry
`### Added` → `mock-bacen-spi settlement API: idempotent by endToEndId with runtime latency/failure/timeout injection (step 26)`
