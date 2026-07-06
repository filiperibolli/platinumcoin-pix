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
   - Key exists but `status=IN_PROGRESS` (crash mid-flight) → `409` with `Retry-After`, reconciliation resolves the orphan.
2. **Ledger layer**: entry items keyed by `txId` with `attribute_not_exists` inside the `TransactWriteItems` — an internal replay of the same `txId` is rejected by the database itself. Defense in depth: even a bug above cannot double-post.
3. **SPI layer**: `endToEndId` (Pix standard) is the idempotency key toward BACEN; retrying a settlement after timeout is safe, and settlement-service queries status before blind retries.

## Scope & semantics
- Scope per `accountId` — two users may coincidentally use the same UUID.
- Replay window 24h (TTL). After expiry, the same key is treated as new — acceptable because clients retry within seconds/minutes.

## Alternatives rejected
- Deduplicating by request-body hash alone: legitimate identical payments (same amount, same payee, twice on purpose) would be wrongly collapsed.
- Relying only on SQS dedup / consumer-side dedup: leaves the synchronous API unprotected.
