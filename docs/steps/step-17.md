# Step 17 — LocalStack init: pix_transactions + pix_idempotency tables

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** DynamoDB `pix_transactions` (+GSIs), `pix_idempotency` · **Diagram:** ARCHITECTURE §6.4

## Objective
Create `pix_transactions` (PK `TX#<txId>`, SK `META` or `OUTBOX#<eventId>`, GSI1 `E2E#<endToEndId>`, GSI2 `STATUS#<status>`+`updatedAt`, sparse GSI3 unpublished-outbox) and `pix_idempotency` (PK `IDEM#<accountId>#<key>`, TTL `expiresAt`) per `docs/data-model.md`.

## Why / what you'll learn
All three GSIs on `pix_transactions` are created **now**, even though only some are used this sprint: GSI1 (E2E lookup) and GSI2 (reconciliation scan) and GSI3 (outbox publisher) matter for the external/async flow (Sprint 6–7). Creating all three GSIs up front is a *choice*, not a constraint — unlike LSIs, **GSIs can be added to an existing table later** (`UpdateTable` + backfill); we create them now because the key schema is already fully designed and backfilling a fat table later is slow and costly. The single-table design keeps outbox items in the same table so one `TransactWriteItems` later covers tx+event. You'll also set up TTL on `pix_idempotency` (DynamoDB auto-deletes expired items) — the replay window is 24h.

## Prerequisites
Step 08 (harness), Step 12 (init framework in place).

## Tasks
1. `03-dynamodb-payment.sh` — one script for both payment-service tables (keeps the init numbering collision-free: 01 accounts, 02 ledger, 03 payment, 04–05 seeds, 06+ messaging): create `pix_transactions` with GSI1/GSI2 and the **sparse** GSI3 (`gsi3pk=OUTBOX#UNPUBLISHED`, `gsi3sk=occurredAt`), and `pix_idempotency` with TTL on `expiresAt`; on-demand; idempotent.
2. No seed rows (transactions are created by the flow); mirror commands in `docs/local-dev.md`.

## Tests (TDD)
Verified by the payment-service ITs (steps 18–21) and the runbook check below.

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 dynamodb describe-table --table-name pix_transactions \
  | jq '.Table.GlobalSecondaryIndexes[].IndexName'   # GSI1, GSI2, GSI3
aws --endpoint-url=http://localhost:4566 dynamodb describe-time-to-live --table-name pix_idempotency | jq
```

## Definition of Done
- [ ] `pix_transactions` with GSI1/GSI2/sparse-GSI3 and `pix_idempotency` with TTL created per docs/data-model.md
- [ ] Scripts idempotent; `down -v && up` recreates them
- [ ] Commands mirrored in the runbook

## CHANGELOG entry
`### Added` → `LocalStack init: pix_transactions (GSI1/GSI2/sparse GSI3) and pix_idempotency (TTL) tables (step 17)`
