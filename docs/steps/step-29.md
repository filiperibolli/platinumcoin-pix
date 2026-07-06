# Step 29 — Finalization: user-facing outcomes + reversal on failure

## Objective
Close the money loop on definitive outcomes: SPI **FAILED** ⇒ compensating ledger posting (debit SPI_CLEARING / credit payer, new txId suffix `-rev`), transition to FAILED→REVERSED, release daily-limit reservation, emit `PixReversed`; SPI SETTLED ⇒ clearing release entry (`CLEARING_RELEASE`, per ARCHITECTURE §6.2) and `PixSettled` already flows to notify.

## Why / what you'll learn
**Compensation, not rollback**: in a distributed flow you can't un-commit the original debit — you post an equal-and-opposite entry, keeping the ledger append-only and the audit trail complete (the user statement shows debit and reversal, which is exactly what real banks show). Also closes the loop on the limit counter (reservation released so the user's headroom returns) — the small consistency details that separate a demo from a design.

## Prerequisites
Steps 19 (release hook), 28.

## Tasks
1. Reversal use case in settlement-service: ledger posting via LedgerClient (idempotent by `txId-rev`), then FAILED→REVERSED transition + `PixReversed` outbox, then limit release (payment-service internal API), then delete message.
2. Clearing-release accounting on SETTLED (entryType `CLEARING_RELEASE`, from clearing to a `BACEN_SETTLED` system account) so the clearing balance reflects only in-flight money — with a doc note that a simpler model (leave in clearing) was considered; chosen for observability of "money in flight ≈ 0 at rest".
3. failureReason recorded on the tx; external status mapping already handles REVERSED (step 23).

## Tests (TDD)
- SPI returns FAILED ⇒ payer balance restored to the cent, REVERSED status, PixReversed emitted, limit headroom restored; reversal idempotent under redelivery (one `-rev` posting).
- SETTLED path: clearing drained by the release entry; Σ money conserved across the whole journey.

## Verify locally
```bash
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# send R$50 external; wait through retries/DLQ→(step 30 will automate; here call the internal reversal via test) or set failureRate to make SPI answer FAILED fast if implemented as explicit outcome
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance | jq   # back to original
curl -s localhost:8084/v1/payments/$TX -H "Authorization: Bearer $TOKEN" | jq '{status,failureReason}'
```

## Definition of Done
- [ ] Failed settlements reverse money exactly, append-only, idempotently
- [ ] Limits released on reversal; conservation invariant holds end-to-end
- [ ] Clearing balance ≈ 0 when nothing is in flight

## CHANGELOG entry
`### Added` → `Settlement finalization: compensating reversal on failure, clearing release on success, limit restoration (step 29)`
