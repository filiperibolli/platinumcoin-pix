# Step 12 — LocalStack init: pix_ledger table + seed postings

> **Sprint 3 — Ledger** · **Flow:** atomic double-entry posting · **Infra que sobe:** DynamoDB table `pix_ledger` · **Diagram:** ARCHITECTURE §6.3

## Objective
Extend the init scripts to create `pix_ledger` (PK `ACCOUNT#<accountId>`, SK `BALANCE` or `ENTRY#<ts>#<txId>`, GSI1 `TX#<txId>`) per `docs/data-model.md`, seed the demo balances (R$ 10,000.00 for alice/bob funded from `ACCOUNT#SEED`) and the system accounts `SPI_CLEARING` and `SEED`.

## Why / what you'll learn
The single-table shape of the ledger: **one partition per account** holding a mutable `BALANCE` item and immutable `ENTRY` items sorted by timestamped sort keys. Timestamp-prefixed sort keys give chronological ordering for free (used by the statement). You'll also encode the **system accounts** here: `SPI_CLEARING` (money in flight, exempt from the non-negative rule) and `SEED` (the funding source), so the money supply is explicit and the conservation invariant (Σ balances constant) is checkable from step 15 on.

## Prerequisites
Step 07 (init framework), Step 08 (harness runs the scripts).

## Tasks
1. `02-dynamodb-ledger.sh` — create `pix_ledger` with GSI1 `TX#<txId>`; on-demand; idempotent.
2. `05-seed-ledger.sh` — `BALANCE` items: alice=1_000_000, bob=1_000_000 (cents), `SPI_CLEARING`=0, `SEED`=large negative offset (so total nets to the funded amount); `version=0`; seed `ENTRY` items recording the initial funding.
3. Mirror commands in `docs/local-dev.md`; document the system-account convention in `docs/data-model.md` if any wording needs it.

## Tests (TDD)
Verified in step 13 (`getBalance` reads the seed) and step 15 (conservation invariant uses these seeds). Runbook check below.

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 dynamodb get-item --table-name pix_ledger \
  --key '{"pk":{"S":"ACCOUNT#acc-001"},"sk":{"S":"BALANCE"}}' | jq   # balanceCents 1000000, version 0
```

## Definition of Done
- [ ] `pix_ledger` + GSI1 created exactly per docs/data-model.md; script idempotent
- [ ] Seed balances + system accounts present; money supply explicit
- [ ] `down -v && up` reseeds deterministically

## CHANGELOG entry
`### Added` → `LocalStack init: pix_ledger table (GSI1) + seed balances and system accounts SPI_CLEARING/SEED (step 12)`
