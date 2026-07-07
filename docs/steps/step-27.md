# Step 27 — External orchestration: ledger debit to clearing

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.6

## Objective
Extend the send flow to the **external** case: when the resolved key is external (or resolves via BACEN's DICT, step 30), command the ledger posting **debit payer / credit `ACCOUNT#SPI_CLEARING`**, persist `status=DEBITED`, and answer `202 PROCESSING`. The user is not waiting on BACEN — settlement is the async half (steps 28–31).

## Why / what you'll learn
Why the credit leg goes to an **internal clearing account**: you cannot span a distributed ACID transaction across PlatinumCoin and another bank, so the money is debited from the payer and parked in `SPI_CLEARING` (money in flight). Double-entry symmetry is preserved — every external send still writes a balanced posting. This step reuses the exact ledger `post()` contract from step 14, passing `SPI_CLEARING` as the credit account: the seam that will later accept a *sharded* clearing id (step 52) without any change here.

## Prerequisites
Step 21 (internal orchestration), Step 14.

## Tasks
1. Branch on key resolution: internal ⇒ step-21 path; external ⇒ debit payer / credit `SPI_CLEARING`, `entryType=PIX_OUT`.
2. Persist `direction=OUTBOUND`, `creditorInternal=false`, the `endToEndId`, `status=DEBITED`.
3. INSUFFICIENT_FUNDS ⇒ 422 (+release limit); ledger down ⇒ 503 + Retry-After (nothing debited).
4. Do **not** publish/settle yet — that arrives with the outbox (step 28) and consumer (step 31).

## Tests (TDD)
- `ExternalSendIT` — external key: 202; payer debited, `SPI_CLEARING` credited (assert both); status DEBITED; conservation holds (Σ balances unchanged).
- Idempotent retry replays; no double-debit.

## Verify locally
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"200.00"}' | head -1   # 202
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance | jq   # credited
```

## Definition of Done
- [ ] External send debits payer / credits SPI_CLEARING atomically; status DEBITED
- [ ] Conservation of money holds; double-entry symmetry preserved
- [ ] Clearing account passed as an explicit id (shardable later without change here)

## CHANGELOG entry
`### Added` → `External Pix orchestration: atomic debit payer / credit SPI_CLEARING, status DEBITED (step 27)`
