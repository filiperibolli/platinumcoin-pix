# Step 31 — settlement-service: consume queue, call SPI, mark SETTLED

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** settlement-service (in compose) · **Diagram:** ARCHITECTURE §6.6

## Objective
`settlement-service` (port 8086) long-polls `settlement-queue`, dedupes by `eventId`, calls mock-bacen (`POST /spi/settlements`, timeout 12s), and on success drives the guarded transition SENT_TO_SPI→SETTLED with a `PixSettled` outbox event. **Happy path only** — retries/DLQ/reversal come in Sprint 7.

## Why / what you'll learn
The first **queue-driven consumer**: scaling is driven by queue depth, not user traffic (ADR-0006). You'll wire SQS long-polling, dedup-before-side-effect with `ProcessedEventStore` (at-least-once ⇒ effectively-once), and the two guarded transitions (DEBITED→SENT_TO_SPI before the call, SENT_TO_SPI→SETTLED after). Settlement writes its own outbox event (`PixSettled`) via the same transactional-outbox mechanism, so the notification flow (Sprint 8) can pick it up — the walking skeleton of the external flow is now complete end-to-end for the sunny day.

## Prerequisites
Steps 29 (publisher + dedup), 30 (SPI).

## Tasks
1. Scaffold `services/settlement-service` (skeleton + Dockerfile + compose + `README.md`, port 8086).
2. SQS long-poll consumer on `settlement-queue`; `ProcessedEventStore` dedup by `eventId`.
3. Guarded DEBITED→SENT_TO_SPI; call SPI (`POST /spi/settlements`, timeout 12s); on 2xx guarded SENT_TO_SPI→SETTLED + `PixSettled` outbox event (settlement-service runs its own outbox publisher or reuses the shared component).
4. Happy path only: on failure/timeout, for now just leave the message (Sprint 7 makes this robust) — note the seam.

## Tests (TDD)
- `SettlementHappyIT` — publish PixDebited → consumer settles via SPI → tx SETTLED, PixSettled outbox written; duplicate delivery ⇒ single settlement.
- Guarded-transition test — cannot settle a tx not in SENT_TO_SPI.

## Verify locally
```bash
TX=<external transactionId>
watch -n1 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq .status"  # PROCESSING → SETTLED
```

## Definition of Done
- [ ] `README.md` present (purpose, port, endpoints, config, run/test) — per-service README convention (CLAUDE.md)
- [ ] Consumer settles the happy path: DEBITED→SENT_TO_SPI→SETTLED with PixSettled event
- [ ] Dedup by eventId (effectively-once); transitions guarded
- [ ] External send now reaches SETTLED end to end

## CHANGELOG entry
`### Added` → `settlement-service: consume settlement-queue, call SPI, guarded transition to SETTLED with PixSettled event (step 31)`
