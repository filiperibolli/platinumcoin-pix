# ADR-0002: Idempotency strategy

**Status:** Accepted · **Date:** 2026-07-02

## Context
Mobile clients retry on timeouts. A retried `POST /payments/pix` must never debit twice (NFR: "Idempotência garantida"). Failure can happen at any point: before persistence, after debit but before response, after response lost in transit.

## Decision — three layers
1. **API layer**: required header `Idempotency-Key` (client-generated UUID, one per business operation).
   - Store: `idempotency` table, PK `IDEM#<accountId>#<key>`, attributes `requestHash` (SHA-256 of canonical body), `responseSnapshot`, `status`, TTL **24h**.
   - Conditional `PutItem` (`attribute_not_exists(pk)`) claims the key atomically — this is a lock+memo in one write, immune to check-then-act races.
   - Key exists + same hash → replay stored response (same 202, same `transactionId`).
   - Key exists + different hash → **`409 Conflict`** (client bug: key reuse).
   - Key exists but `status=IN_PROGRESS` (crash mid-flight) → `409` with `Retry-After`. **Orphan handling:** the record stores `claimedAt`; an `IN_PROGRESS` claim older than a staleness window (60s — far beyond any legitimate in-flight request) is treated as abandoned and re-claimed by the retry, so a crash between claim and completion never blocks the client until the 24h TTL.
2. **Ledger layer**: entry items keyed by `txId` with `attribute_not_exists` inside the `TransactWriteItems` — an internal replay of the same `txId` is rejected by the database itself. Defense in depth: even a bug above cannot double-post.
3. **SPI layer**: `endToEndId` (Pix standard) is the idempotency key toward BACEN; retrying a settlement after timeout is safe, and settlement-service queries status before blind retries.

## Scope & semantics
- Scope per `accountId` — two users may coincidentally use the same UUID.
- Replay window 24h (TTL). After expiry, the same key is treated as new — acceptable because clients retry within seconds/minutes.
- DynamoDB TTL deletion is **lazy** (it can lag hours behind `expiresAt`), so reads must also check `expiresAt` and treat an expired-but-still-present record as absent — the 24h window is enforced by the application, not by the deletion.

## Alternatives rejected
- Deduplicating by request-body hash alone: legitimate identical payments (same amount, same payee, twice on purpose) would be wrongly collapsed.
- Relying only on SQS dedup / consumer-side dedup: leaves the synchronous API unprotected.
