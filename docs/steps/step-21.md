# Step 21 — Internal orchestration: key resolution + ledger debit

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4

## Objective
The send flow gains its money-moving core for the **internal** case: resolve the Pix key (step 11 API), command the ledger posting **debit payer / credit payee directly** (both accounts internal), and persist status `SETTLED` (an internal transfer has no SPI leg — it *is* settled the moment the posting commits). This is the first flow that moves a user's real money — synchronously, with no settlement, queue or BACEN.

## Why / what you'll learn
An internal transfer is the *simplest complete* money movement: both legs are inside PlatinumCoin, so one atomic ledger posting (step 14) settles it — there is nothing to settle externally. That is why the terminal status is `SETTLED`, not `DEBITED`: step 22 maps `DEBITED` to the external `PROCESSING`, and an internal Pix that stayed `DEBITED` would look "processing" to the client forever. Internal sends take the short branch of the state machine (`DEBITED → SETTLED` with no `SENT_TO_SPI`, ARCHITECTURE §4), which keeps this sprint free of messaging. You'll wire the orchestration order that the external flow (Sprint 6) will extend: resolve → (limit → fraud, already/soon) → debit → persist. Failure mapping matters: KEY_NOT_FOUND ⇒ 422, INSUFFICIENT_FUNDS ⇒ 422 (release the limit reservation), ledger down ⇒ 503 + Retry-After (nothing debited, safe to retry with the same key).

## Prerequisites
Steps 11, 14, 20.

## Tasks
1. Call `GET /internal/pix-keys/resolve?key=...`; internal ⇒ creditor accountId; not-found ⇒ 422 `KEY_NOT_FOUND` (external is out of scope until step 27/30).
2. Command ledger `POST /internal/ledger/postings` debit debtor / credit creditor, `entryType=PIX_INTERNAL`, `txId`.
3. INSUFFICIENT_FUNDS ⇒ 422 (+release limit); ledger 503/timeout ⇒ 503 + `Retry-After: 5`, circuit-breaker on repeated failures.
4. On success persist `status=SETTLED` with `settledAt` (internal → final immediately; the status endpoint shows the honest terminal state), complete the idempotency record.

## Tests (TDD)
- `InternalSendIT` — alice→bob internal: 202; balances moved atomically (assert both); status SETTLED with `settledAt`; idempotent retry replays and does not double-debit.
- Unknown key ⇒ 422 KEY_NOT_FOUND, no debit. Insufficient funds ⇒ 422, no debit, limit released.

## Verify locally
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}' | head -1   # 202
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance | jq   # bob credited
```

## Definition of Done
- [ ] Internal Pix moves real money atomically end-to-end; terminal status SETTLED (never an eternal PROCESSING)
- [ ] Failure mapping correct (KEY_NOT_FOUND / INSUFFICIENT_FUNDS 422; ledger down 503)
- [ ] Idempotent retry never double-debits

## CHANGELOG entry
`### Added` → `Internal Pix orchestration: key resolution + atomic ledger debit (credit payee directly), terminal status SETTLED (step 21)`
