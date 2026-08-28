# Step 28 — Transactional outbox: tx + outbox item in one TransactWriteItems

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.6

## Objective
The DEBITED transition and the `PixDebited` outbox item are written in **one `TransactWriteItems`** on `pix_transactions` (tx META update + OUTBOX put on the sparse GSI3). The same mechanism serves later transitions (SETTLED/REVERSED/FraudCheckSkipped). Nothing publishes yet — that's step 29.

## Why this step exists
The **outbox pattern**, the *guarantee* half (ADR-0004): writing to the DB and publishing to SNS are two systems → the **dual-write problem** (crash between them loses the event → money stuck in clearing, or emits an event for a state that never committed). Because the outbox item lives in the **same table/partition** as the transaction (single-table design), both commit atomically in one transaction — no dual-write window. The outbox item carries `gsi3pk=OUTBOX#UNPUBLISHED` so it appears in the sparse publisher index; the event envelope (`eventId`, `eventType`, `payload`, `occurredAt`, `correlationId`) is broker-agnostic by design.

## Prerequisites
Step 27.

> **Divergence recorded on completion (2026-08-11).** Task 2 was written expecting step 27 to have left a
> *status transition* to guard. It did not: payment-service writes the `META` item **once**, already at
> its final-for-now status (`DEBITED` external / `SETTLED` internal, one `PutItem`) — the guarded
> `status = :expectedFrom` **update** is the settlement-service's write in step 31 (`ARCHITECTURE.md`
> §6.6: `SET->>DDB: tx = SETTLED (guarded) + outbox(PixSettled)`). So the atomic write built here is a
> guarded **create**: `Put META` with `ConditionExpression: attribute_not_exists(pk)` + the `OUTBOX#`
> puts, in one `TransactWriteItems`. That satisfies the DoD's "no out-of-order regress" for this step's
> only write — a create can never overwrite a transaction another step has since advanced — and no
> `transition()` method is added without a caller (step 31 owns it).
>
> **Second divergence:** the index is named `gsi3` (lower-case, `infra/localstack/init/03-dynamodb-payment.sh`),
> not `GSI3`; the corrected "Verify locally" command is below.

## Tasks
1. `OutboxEvent(eventId, eventType, payload, occurredAt, correlationId)` + envelope serialization in common-lib.
2. Replace the plain status write with a `TransactWriteItems`: update tx `META` (status DEBITED, guarded `status = :expectedFrom`) + put `OUTBOX#<eventId>` with `gsi3pk=OUTBOX#UNPUBLISHED`, `gsi3sk=occurredAt`. *(See the divergence note: implemented as a guarded create.)*
3. Emit `PixDebited` for external sends; also wire `FraudCheckSkipped` (from step 25's seam) as an outbox event.
4. Internal sends' terminal transition (→SETTLED, step 21) writes a `PixSettled` outbox event too — audit and notification consume it exactly like an external settlement.

## Tests (TDD)
- `OutboxWriteIT` — a send produces the tx in DEBITED **and** exactly one unpublished outbox item, atomically (force a failure → neither is written).
- Guarded transition test — an unexpected `expectedFrom` is rejected (no regress).

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_transactions \
  --index-name gsi3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq '.Items | length'
```

Nothing publishes yet (step 29), so every event a send writes stays in that index — the count only ever
grows here, which is exactly what the `outbox.lag` silence alert will later watch for.

## Definition of Done
- [ ] Status transition + outbox event committed in one TransactWriteItems (atomic)
- [ ] Outbox item carries the sparse-index key and a broker-agnostic envelope
- [ ] Transitions are guarded (no out-of-order regress)

## CHANGELOG entry
`### Added` → `Transactional outbox: status transition + event written atomically in one TransactWriteItems (step 28)`
