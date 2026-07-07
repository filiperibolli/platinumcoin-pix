# Step 33 — Finalization: clearing release on SETTLED; reversal on FAILED

> **Sprint 7 — Resilience & reconciliation** · **Flow:** failure → bounded resolution · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.7

## Objective
Close the money loop on definitive outcomes: SPI **FAILED** ⇒ compensating ledger posting (debit `SPI_CLEARING` / credit payer, new `txId` suffix `-rev`), transition FAILED→REVERSED, release the daily-limit reservation, emit `PixReversed`; SPI **SETTLED** ⇒ a `CLEARING_RELEASE` entry (per ARCHITECTURE §6.3) and `PixSettled` (already flowing).

## Why / what you'll learn
**Compensation, not deletion.** When an external send definitively fails, the money parked in clearing must return to the payer — via a *new* posting (`debit clearing / credit payer`), never by updating or deleting the original entries. The ledger stays append-only and auditable; the reversal is itself atomic and idempotent (its own `txId`). On success, the clearing balance is drawn down against the real BACEN position (`CLEARING_RELEASE`). This is where "money moves, never created or destroyed" is proven for the failure branch — and where the shard-pinning rule of step 52 matters: a reversal must hit the **same** clearing shard that was credited.

## Prerequisites
Steps 27 (clearing debit), 32 (failure detection).

## Tasks
1. SPI FAILED (or DLQ-driven definitive fail): compensating posting `debit SPI_CLEARING / credit payer` with `txId = <orig>-rev`, `entryType=PIX_REVERSAL`; guarded transition →REVERSED; release limit; outbox `PixReversed`.
2. SPI SETTLED: post `CLEARING_RELEASE` entry against `SPI_CLEARING`; `PixSettled` already emitted (step 31).
3. Idempotent finalization: re-running for the same tx is a no-op (guarded transition + posting idempotency).
4. Persist the clearing account/shard used at debit time on the tx so reversal targets it exactly (forward-compat with step 52).

## Tests (TDD)
- `ReversalIT` — force SPI FAILED ⇒ payer refunded (balances back to pre-send), status REVERSED, PixReversed emitted, conservation holds; re-run ⇒ no double refund.
- `ClearingReleaseIT` — SETTLED ⇒ CLEARING_RELEASE entry present; clearing nets correctly.

## Verify locally
```bash
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# send external pix; after retries/DLQ+finalization, payer is refunded and status REVERSED
```

## Definition of Done
- [ ] FAILED ⇒ compensating credit (append-only), REVERSED, limit released, PixReversed
- [ ] SETTLED ⇒ CLEARING_RELEASE; conservation of money holds both ways
- [ ] Finalization idempotent; reversal targets the exact clearing account used

## CHANGELOG entry
`### Added` → `Settlement finalization: clearing release on SETTLED, compensating reversal (append-only) on FAILED with PixReversed (step 33)`
