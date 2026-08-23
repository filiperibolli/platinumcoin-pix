# ADR-0002: Idempotency strategy

**Status:** Accepted · **Date:** 2026-07-02 · **Validated 2026-08-10** (Redis evaluated and rejected; DynamoDB confirmed — see note) · **Amended by [ADR-0014](0014-durable-operation-identity.md)** (2026-08-22, step 65)

> **Amendment pointer.** The three layers below were never *connected*: the `txId` layer 2 guards was
> minted after layer 1's claim, so a crash between them defeated both and double-debited the payer.
> [ADR-0014](0014-durable-operation-identity.md) makes the identity **durable**: `txId`/`endToEndId`
> are minted before the claim and written by it, `status` becomes the phase
> (`CLAIMED → POSTED → RECORDED → COMPLETED`), and the TTL may only recycle a key whose operation
> reached `COMPLETED`. The rejection of a *derived* `txId` recorded below still stands — ADR-0014
> persists the identity rather than deriving it, precisely to keep the 24h key-reuse semantics.

> **Validation note (2026-08-10).** A design review asked whether idempotency should live on Redis. We
> evaluated three shapes — **Redis-only**, **Redis-fast-path over a durable store (hybrid)**, and the
> **DynamoDB-durable original** — and **confirmed the original: DynamoDB stays the source of truth;
> Redis is reserved for the balance cache (ADR-0008), not idempotency.** We also considered deriving a
> **deterministic `txId`** from the idempotency key as extra defense and **rejected it** — it collides
> with the 24h key-reuse semantics below. Reasoning in "Validation (2026-08-10)" at the end. **No code
> or schema change resulted — this ADR was already right.**

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
   - **The rail is idempotent in both directions (step 37).** `POST /v1/inbound/pix` — the webhook BACEN calls to deliver a Pix to us — carries **no `Idempotency-Key` header**, and that is layer 1 applied rather than skipped: the header exists for the one *untrusted, client-facing* money-moving POST that has **no natural id**, and this call has one. The transaction id is derived, `in-<endToEndId>`, so the item's partition key embeds the rail's own id and `attribute_not_exists(pk)` on the record **is** the dedupe — the same conditional-claim idiom as layer 1, keyed by a natural id instead of a client-supplied one, and therefore stronger (a client cannot forget it, reuse it, or send two for one payment). Deduping by querying the `E2E#` GSI instead would be a read-then-check over an *eventually consistent* index: the exact check-then-act race the conditional write exists to close.

## Ordering: claim-then-work, or work-then-claim?
Layer 1 claims **before** doing the work, and `SettlePixUseCase` claims its `eventId` before calling BACEN, for one reason: the work has a **non-idempotent external effect**, so a second attempt is a second real event.

Where the work is *already* idempotent, the ordering flips — and step 37's inbound credit is the case. Its ledger posting is idempotent by the same derived `txId`, so no ordering can double-credit; but claiming first would introduce a failure that otherwise does not exist. A crash between the claim and the posting would leave an `endToEndId` marked handled whose money never arrived, and every redelivery would be politely refused by our own guard — the payment lost silently, with no error anywhere. Posting first inverts the residual risk into a harmless one: a committed credit with no record, which the next delivery replays and completes.

**The rule:** claim first when the work is not idempotent; do the work first when it is and the claim is the durable record of it.

## Scope & semantics
- Scope per `accountId` — two users may coincidentally use the same UUID.
- Replay window 24h (TTL). After expiry, the same key is treated as new — acceptable because clients retry within seconds/minutes.
- DynamoDB TTL deletion is **lazy** (it can lag hours behind `expiresAt`), so reads must also check `expiresAt` and treat an expired-but-still-present record as absent — the 24h window is enforced by the application, not by the deletion.

## Alternatives rejected
- Deduplicating by request-body hash alone: legitimate identical payments (same amount, same payee, twice on purpose) would be wrongly collapsed.
- Relying only on SQS dedup / consumer-side dedup: leaves the synchronous API unprotected.

## Validation (2026-08-10) — why DynamoDB, not Redis

### The question
A review asked the fair question: a real fintech building Pix — wouldn't idempotency live on Redis?

### The principle the industry converges on
**The durable store is the source of truth for money idempotency; a cache is never the source of
truth.** Concretely, where the well-known stacks put it:
- **AWS Powertools Idempotency** ships **DynamoDB conditional put + TTL** as the *default backend* —
  this ADR's exact design.
- **Stripe** stores idempotency keys durably (Postgres, with request/response and state-machine
  recovery points).
- **Nubank**'s immutable ledger is itself the dedup of money.
- **Redis**, where it appears in these stacks, is a lock / fast-path / replay cache **backed by** a
  durable authority — never the sole record that a payment happened.

### Three shapes evaluated
1. **Redis-only — rejected.** Redis is volatile (failover before fsync, `maxmemory` eviction can drop
   a live claim). On loss, the `409` key-reuse detection and the exact response replay vanish; money
   could stay safe only by leaning entirely on a `txId` ledger backstop, but the **API semantics
   degrade** — not defensible for a payment rail.
2. **Hybrid (Redis fast-path + durable authority) — deferred.** Legitimate at high QPS, but it buys
   latency we do not need at this scale for the cost of two stores plus fallback logic. Revisit only if
   a real latency budget demands it.
3. **DynamoDB-durable (this ADR) — chosen.** ~10ms conditional `PutItem`, strongly consistent, survives
   crash / failover / eviction, and it is the AWS-blessed default. Simple and already production-grade.

### Deterministic `txId` considered and rejected
Deriving `txId = f(accountId, idempotencyKey)` was considered as defense-in-depth. **Rejected**, and the
reason is instructive: it permanently couples a transaction's identity to the key, which collides with
this ADR's rule that **after the 24h TTL the same key is treated as new**. A client legitimately reusing
a key *value* after expiry would regenerate a `txId` the ledger already holds, and the ledger's
`attribute_not_exists(txId)` guard would swallow the **new** payment as a duplicate. With the durable
idempotency record already guaranteeing single-execution *inside* the window, the **random `txId`**
(`tx-<UUID>`, memoized in the record and replayed on hit) is both sufficient and free of that edge. The
ledger's `txId` guard (layer 2) stays as defense-in-depth against internal replays of the *same* `txId`;
it is **not** asked to compensate for a volatile layer 1, because layer 1 is durable.

### Outcome
**No change to code or schema.** Redis stays scoped to the balance cache (ADR-0008, step 40). This note
exists so the choice is not re-litigated: the "obvious" move to Redis trades a durability guarantee for a
latency win the platform has no budget pressure for, and the deterministic-`txId` shortcut it would
require introduces a post-TTL correctness edge the durable design does not have.
