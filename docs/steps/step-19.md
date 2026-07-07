# Step 19 — Idempotency layer  ✍️ hand-written zone (tests)

> **Sprint 4 — Send Pix (internal)** · **Flow:** internal Pix moves real money · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4

> **Hand-written zone:** the test set of this step (`IdempotencyIT` + hashing unit tests) is written by the human, by hand, first (AI may review afterwards and may still generate the production code from the spec as usual). See CLAUDE.md → "Hand-written zones".

## Objective
Implement ADR-0002 on the send endpoint: conditional claim of `IDEM#account#key`, request-hash comparison, stored-response replay, `409` on hash mismatch, `IN_PROGRESS` handling, TTL 24h.

## Why / what you'll learn
The full lifecycle of an idempotency record — claim (conditional put, atomically wins or loses), execute, memoize (store status+response), replay — and the sharp edges: canonicalizing the body before hashing (key order, whitespace), the crash-between-claim-and-response window (`IN_PROGRESS` → 409 + Retry-After; a claim whose `claimedAt` is older than 60s is stale — the crash left an orphan — and is re-claimed by the retry, so no client is blocked until the TTL), why DynamoDB's lazy TTL means `expiresAt` must also be checked on read, and why the record's TTL (24h) must exceed any client retry horizon. This is the API-layer answer to "the user tapped twice / the network retried".

## Prerequisites
Step 18.

## Tasks
1. `IdempotencyRepository`: `claim(accountId, key, requestHash)` conditional put status=IN_PROGRESS with `claimedAt`; `complete(…, httpStatus, responseSnapshot)`; `get(...)` (treats `expiresAt` in the past as absent — TTL deletion is lazy).
2. Canonical JSON hashing (sorted keys, trimmed) in common-lib.
3. Controller flow: claim → win: proceed, then complete; lose: load record → same hash+COMPLETED ⇒ replay stored status/body; same hash+IN_PROGRESS ⇒ 409 `REQUEST_IN_PROGRESS` + Retry-After: 2, **unless `claimedAt` is older than 60s** (stale claim from a crash) ⇒ re-claim (conditional update on `claimedAt`) and process; different hash ⇒ 409 `IDEMPOTENCY_KEY_REUSED`.
4. Missing header ⇒ 400 `IDEMPOTENCY_KEY_REQUIRED`.

## Tests (TDD)
- `IdempotencyIT` — first call 202; identical retry ⇒ identical body (same transactionId), **only one transaction item exists**; same key different amount ⇒ 409; concurrent double-fire (2 threads, same key) ⇒ one 202 + one (replay 202 or 409-in-progress), never two transactions; stale IN_PROGRESS (aged `claimedAt`) ⇒ retry re-claims and completes instead of 409ing forever.
- Hash canonicalization unit tests (key order/whitespace don't change the hash; amount change does).

## Verify locally
```bash
IDEM=$(uuidgen); BODY='{"pixKey":"bob@platinum.com","amount":"10.00"}'
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY" | jq .transactionId
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY" | jq .transactionId   # SAME id
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d '{"pixKey":"bob@platinum.com","amount":"99.00"}' | head -1  # 409
```

## Definition of Done
- [ ] Retries replay; tampered reuse 409s; double-fire race creates exactly one transaction
- [ ] Records carry TTL (checked on read — lazy deletion); IN_PROGRESS window handled, stale claims reclaimable after 60s
- [ ] Behavior matches ADR-0002 and OpenAPI verbatim

## CHANGELOG entry
`### Added` → `Idempotency layer on send: conditional claim, response replay, 409 on key reuse with different payload (step 19)`
