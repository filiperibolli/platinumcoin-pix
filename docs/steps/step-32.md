# Step 32 — Receive Pix: inbound flow

## Objective
mock-bacen gains `POST /simulate/inbound-pix` (generates an inbound payment and delivers it to settlement-service's `POST /v1/inbound/pix` webhook, retrying like BACEN would). settlement-service dedupes by endToEndId (conditional write), resolves the key, posts **debit SPI_CLEARING / credit user**, records an INBOUND transaction with `PixReceived` outbox event, acks 200.

## Why / what you'll learn
Being on the *receiving* end of at-least-once delivery: the sender (BACEN) retries until acked, so your webhook must be idempotent **before side effects** — the conditional-put dedup you've used three times now, applied at a system boundary. Also the inbound mirror of double-entry (clearing is the debit leg — the same account that absorbs outbound, so its balance nets in-flight both ways), and a webhook-vs-queue design note: real SPI pushes; we accept the push then do our own work transactionally.

## Prerequisites
Steps 12, 14, 21/22 machinery, 26.

## Tasks
1. mock-bacen: `/simulate/inbound-pix {pixKey, amount, payerName}` mints endToEndId, POSTs to settlement webhook with small retry loop until 200 (configurable duplicate-send for testing dedup).
2. settlement-service webhook: dedup claim `INBOUND#<endToEndId>` (conditional put) — duplicate ⇒ 200 immediately (idempotent ack); resolve key via account-service — unknown key ⇒ 404 to BACEN (mock logs it; real world: rejection message);
3. Ledger posting (txId = `in-<endToEndId>`), INBOUND tx item status RECEIVED_SETTLED + `PixReceived` outbox in one transaction (payment-service internal API or direct tx-table API owned by payment-service — keep ownership: call payment-service `POST /internal/payments/inbound`).
4. Ack 200 only after everything committed.

## Tests (TDD)
- Happy: bob's balance +X, INBOUND tx exists, PixReceived in outbox, exactly once even when mock sends the webhook 3× (dedup assert).
- Unknown key ⇒ 404, zero writes.
- Crash-before-ack simulation ⇒ redelivery completes exactly-once effect.

## Verify locally
```bash
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
 -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"External Payer"}' | jq
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance | jq   # +300.00
```

## Definition of Done
- [ ] Inbound idempotent by endToEndId under duplicate delivery
- [ ] Double-entry symmetry: clearing debited, user credited atomically
- [ ] Ack-after-commit; unknown keys rejected cleanly

## CHANGELOG entry
`### Added` → `Inbound Pix: BACEN-simulated webhook with endToEndId dedup and atomic credit posting (step 32)`
