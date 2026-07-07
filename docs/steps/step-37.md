# Step 37 — mock-bacen inbound generator → inbound Pix flow

> **Sprint 8 — Receive & notify** · **Flow:** inbound Pix → SSE push · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.8

## Objective
mock-bacen gains `POST /simulate/inbound-pix` (generates an inbound payment and delivers it to settlement-service's `POST /v1/inbound/pix` webhook — authenticated with the shared `SPI_WEBHOOK_TOKEN` — retrying like BACEN would). settlement-service validates the token, dedupes by `endToEndId` (conditional write), resolves the key, posts **debit `SPI_CLEARING` / credit user**, records an INBOUND transaction with a `PixReceived` outbox event, acks 200.

## Why / what you'll learn
Receiving is the double-entry **mirror** of sending: outbound debits the payer and credits clearing; inbound debits clearing and credits the user — same symmetry, opposite direction. Idempotency by `endToEndId` is essential because **BACEN may redeliver** (the inbound webhook is at-least-once, like everything else). You'll dedupe with the conditional-put idiom before the credit posting, so a redelivered inbound never double-credits. And because this endpoint **credits money**, it is never anonymous: it is JWT-exempt (no user is calling it) but guarded by a shared webhook token — an unauthenticated inbound webhook would let any local process mint spendable balance (threat model, boundary B4; production posture is mTLS + BACEN message signing).

## Prerequisites
Steps 30 (mock-bacen), 31/33 (settlement + ledger posting patterns), 36 (queues).

## Tasks
1. mock-bacen `POST /simulate/inbound-pix {pixKey, amount, payerName}` → build `endToEndId`, call settlement `POST /v1/inbound/pix` with header `X-Webhook-Token: $SPI_WEBHOOK_TOKEN`, retry on non-2xx.
2. Webhook auth: the route is on the JWT allow-list but settlement-service validates `X-Webhook-Token` against `SPI_WEBHOOK_TOKEN` before anything else — missing/wrong token ⇒ 401, nothing credited.
3. settlement `POST /v1/inbound/pix`: dedupe by `endToEndId` (conditional write); resolve key → accountId (account-service); ledger posting `debit SPI_CLEARING / credit user` (`entryType=PIX_IN`); persist INBOUND tx `RECEIVED_SETTLED`; outbox `PixReceived`; 200 ack.
4. Unknown key ⇒ documented handling (reject/return; BACEN would bounce).

## Tests (TDD)
- `InboundPixIT` — simulate inbound to bob ⇒ bob credited, INBOUND tx + PixReceived outbox; **redelivery of the same endToEndId ⇒ single credit** (dedupe).
- Webhook auth test — missing/wrong `X-Webhook-Token` ⇒ 401, no posting, no transaction.
- Conservation holds (clearing debited, user credited).

## Verify locally
```bash
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"External Payer"}'
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance | jq   # bob credited
```

## Definition of Done
- [ ] Inbound credits the user via debit-clearing/credit-user; INBOUND tx + PixReceived
- [ ] Idempotent by endToEndId (redelivery ⇒ single credit)
- [ ] Webhook rejects calls without the shared token (401, no side effects)
- [ ] Conservation of money holds

## CHANGELOG entry
`### Added` → `Inbound Pix flow: mock-bacen generator → settlement webhook, dedupe by endToEndId, credit posting, PixReceived (step 37)`
