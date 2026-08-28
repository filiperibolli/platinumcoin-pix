# Step 33 — Finalization: clearing release on SETTLED; reversal on FAILED

> **Sprint 7 — Resilience & reconciliation** · **Flow:** failure → bounded resolution · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.7

## Objective
Close the money loop on definitive outcomes: SPI **FAILED** ⇒ compensating ledger posting (debit `SPI_CLEARING` / credit payer, new `txId` suffix `-rev`), transition FAILED→REVERSED, release the daily-limit reservation, emit `PixReversed`; SPI **SETTLED** ⇒ a `CLEARING_RELEASE` entry (per ARCHITECTURE §6.3) and `PixSettled` (already flowing).

## Why this step exists
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

**Success branch — `CLEARING_RELEASE` (task 2), reachable end-to-end.** Send an external Pix to a key
mock-bacen's DICT knows, let it settle, and watch the clearing balance net back to zero as `SPI_SETTLED`
takes it up:
```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"aluguel"}'
# after settlement (~2s): SPI_CLEARING nets to 0, SPI_SETTLED credited by the amount
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance -H "Authorization: Bearer $TOKEN" | jq
curl -s localhost:8085/internal/ledger/accounts/SPI_SETTLED/balance  -H "Authorization: Bearer $TOKEN" | jq
```

**Failure branch — reversal on a permanent refusal (task 1).** The reversal fires on a permanent SPI
refusal (`422 SPI_REJECTED`), which mock-bacen produces for a creditor key its **settlement** DICT does
not answer for. Today the send-time resolution and the settlement both consult the *same* mock-bacen DICT
(`SpiDirectory`), so a key that would be rejected at settlement is already refused at send (`422
KEY_NOT_FOUND`) and never debits — there is no send-reachable end-to-end trigger yet. The failure branch
is therefore proven by the automated **`ReversalIT`** (it stubs the SPI refusal against real DynamoDB/SQS):
payer refunded to the pre-send balance, status `REVERSED`, `PixReversed` emitted, conservation holds,
re-run does not double-refund. A manual end-to-end drill awaits a mock-bacen settlement-rejection knob (a
natural companion to step 35's DLQ/reconciliation drills); `failureRate` is **not** it — it injects
transient `503`s (retries → DLQ), which is step 32/35 territory, not a permanent refusal.
```bash
mvn -pl services/settlement-service -am verify -Dtest=ReversalIT -Dit.test=ReversalIT
```

## Definition of Done
- [ ] FAILED ⇒ compensating credit (append-only), REVERSED, limit released, PixReversed
- [ ] SETTLED ⇒ CLEARING_RELEASE; conservation of money holds both ways
- [ ] Finalization idempotent; reversal targets the exact clearing account used

## CHANGELOG entry
`### Added` → `Settlement finalization: clearing release on SETTLED, compensating reversal (append-only) on FAILED with PixReversed (step 33)`
