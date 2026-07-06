# Step 20 — Send orchestration: resolve key + ledger debit

## Objective
The send flow gains its money-moving core: resolve the Pix key (step 12 API), then command the ledger posting **debit payer / credit SPI_CLEARING** (external) or **credit payee directly** (internal), and persist status `DEBITED`. Failures map cleanly: KEY_NOT_FOUND 422, INSUFFICIENT_FUNDS 422 (+limit release), ledger down ⇒ 503 + Retry-After.

## Why / what you'll learn
Orchestration (payment-service explicitly drives the saga) vs choreography — and the **ordering discipline**: cheap/reversible checks first (idempotency, limits), the irreversible act (ledger debit) last before persist+respond. The clearing account materializes "money in flight" so external transfers keep double-entry integrity (ARCHITECTURE §6.2). And the availability posture from question 7: ledger call gets timeout 1s + circuit breaker; when open ⇒ fail fast 503, nothing debited, idempotency record voided so the client's retry re-executes.

## Prerequisites
Steps 12, 14, 19.

## Tasks
1. `LedgerClient` (RestClient, connect 200ms/read 1s, Resilience4j circuit breaker) for `POST /internal/ledger/postings`.
2. `SendPixOrchestrator`: resolve key → choose credit account (`accountId` | `SPI_CLEARING`) → post (txId as posting id) → tx status RECEIVED→DEBITED (guarded update) with `creditorInternal` flag.
3. Failure mapping incl. limit release on INSUFFICIENT_FUNDS/KEY_NOT_FOUND; idempotency record: complete with the error for deterministic replays of business rejections; **void (delete) on 503** so retry re-executes.
4. Internal transfers: mark SETTLED immediately? **No** — keep uniform DEBITED; step 27 settles internals instantly via the same pipeline (uniformity over shortcut; note the trade-off).

## Tests (TDD)
- `SendPixOrchestratorIT` (LocalStack) — external: payer −X, clearing +X, status DEBITED; internal: payee +X; insufficient funds: 422, no entries, limit released; unknown key: 422 before any ledger call (verify no posting).
- Circuit-breaker test: ledger endpoint down ⇒ 503 + Retry-After, no idempotency memo, subsequent retry succeeds when ledger returns.

## Verify locally
```bash
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
 -H 'Content-Type: application/json' -d '{"pixKey":"zed@otherbank.com","amount":"50.00"}' | jq
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance | jq   # grew by 50.00
docker compose -f infra/docker-compose.yml stop ledger-service
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
 -H 'Content-Type: application/json' -d '{"pixKey":"bob@platinum.com","amount":"1.00"}' | head -3   # 503 Retry-After
docker compose -f infra/docker-compose.yml start ledger-service
```

## Definition of Done
- [ ] Atomic debit to clearing/internal payee; status DEBITED
- [ ] All rejection paths leave zero money moved and released limits
- [ ] Ledger outage ⇒ fast 503, safe retry with same key

## CHANGELOG entry
`### Added` → `Send orchestration: key resolution + atomic debit to clearing/internal payee with guarded DEBITED transition and 503 fail-fast (step 20)`
