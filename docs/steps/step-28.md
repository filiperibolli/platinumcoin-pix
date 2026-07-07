# Step 28 — Transactional outbox: tx + outbox item in one TransactWriteItems

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.6

## Objective
The DEBITED transition and the `PixDebited` outbox item are written in **one `TransactWriteItems`** on `pix_transactions` (tx META update + OUTBOX put on the sparse GSI3). The same mechanism serves later transitions (SETTLED/REVERSED/FraudCheckSkipped). Nothing publishes yet — that's step 29.

## Why / what you'll learn
The **outbox pattern**, the *guarantee* half (ADR-0004): writing to the DB and publishing to SNS are two systems → the **dual-write problem** (crash between them loses the event → money stuck in clearing, or emits an event for a state that never committed). Because the outbox item lives in the **same table/partition** as the transaction (single-table design), both commit atomically in one transaction — no dual-write window. The outbox item carries `gsi3pk=OUTBOX#UNPUBLISHED` so it appears in the sparse publisher index; the event envelope (`eventId`, `eventType`, `payload`, `occurredAt`, `correlationId`) is broker-agnostic by design.

## Prerequisites
Step 27.

## Tasks
1. `OutboxEvent(eventId, eventType, payload, occurredAt, correlationId)` + envelope serialization in common-lib.
2. Replace the plain status write with a `TransactWriteItems`: update tx `META` (status DEBITED, guarded `status = :expectedFrom`) + put `OUTBOX#<eventId>` with `gsi3pk=OUTBOX#UNPUBLISHED`, `gsi3sk=occurredAt`.
3. Emit `PixDebited` for external sends; also wire `FraudCheckSkipped` (from step 25's seam) as an outbox event.
4. Keep internal sends' terminal transition writing an outbox event too (for audit/notify later).

## Tests (TDD)
- `OutboxWriteIT` — a send produces the tx in DEBITED **and** exactly one unpublished outbox item, atomically (force a failure → neither is written).
- Guarded transition test — an unexpected `expectedFrom` is rejected (no regress).

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_transactions \
  --index-name GSI3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq '.Items | length'
```

## Definition of Done
- [ ] Status transition + outbox event committed in one TransactWriteItems (atomic)
- [ ] Outbox item carries the sparse-index key and a broker-agnostic envelope
- [ ] Transitions are guarded (no out-of-order regress)

## CHANGELOG entry
`### Added` → `Transactional outbox: status transition + event written atomically in one TransactWriteItems (step 28)`
