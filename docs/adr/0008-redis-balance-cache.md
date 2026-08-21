# ADR-0008: Redis (cache-aside) for balance reads

**Status:** Accepted · **Date:** 2026-07-02

## Context
Balance reads must be <300ms p99 and are the highest-volume operation (~10 reads per transaction). LocalStack does not emulate ElastiCache.

## Decision
- **Redis as its own container** in docker-compose, explicitly documented as the local stand-in for ElastiCache for Redis.
- **Cache-aside**: read → Redis hit? return : read ledger `BALANCE` item → populate (TTL 5s) → return.
- **Invalidation**: every ledger posting deletes the affected accounts' cache keys (best-effort, after commit); the short TTL is the backstop against missed invalidations.
- **Correctness rule**: the cache serves *display* reads only. Any money-moving decision (the `balance >= amount` check) happens inside the DynamoDB conditional write — the cache can never cause an overdraft.
- Bonus availability property: if the ledger is briefly down, balance reads keep being served (≤5s stale) from Redis.

## Implementation notes added by step 40 (2026-08-21) — how "best-effort" is actually built

The decision above is unchanged; these are the operational consequences discovered while implementing
and drilling it, recorded because "best-effort" turned out to mean far more than a `try/catch`.

- **A cache that HANGS is worse than a cache that fails.** Catching every Redis exception makes a
  *failing* cache survivable and does nothing about a *hung* one. Stopping the Redis container in the
  step-40 drill produced a **114-second balance read**, and — far worse — a send that returned
  `503 LEDGER_UNAVAILABLE` **for a debit that had already committed**, because ledger-service's eviction
  blocked past payment-service's 3s read timeout. Availability was lost where the ADR promised only
  freshness would be.
- **Three mechanisms close it**, in increasing order of importance:
  1. bounded `spring.data.redis.timeout` / `connect-timeout` (200ms) in both services;
  2. a **fail-fast Lettuce client** (`RedisFailFastConfig`): `TimeoutOptions.enabled(...)` so the bound
     also applies to commands that were never sent, and `DisconnectedBehavior.REJECT_COMMANDS` so a
     disconnected client refuses immediately instead of queueing for a reconnect (Lettuce queues by
     default, and queued commands ignore the command timeout);
  3. **the eviction runs off the request thread** (a bounded, discard-on-saturation executor in
     ledger-service). This is the structural fix: an optional side effect must not share the latency of
     the transaction that triggered it, and no timeout can fully bound a first connection (name
     resolution is not covered by any Lettuce timeout — a stopped container loses its DNS name).
- **Dropping an eviction is explicitly allowed**, which is what makes (3) safe: the 5s TTL already
  bounds the staleness of a missed invalidation, so discarding under saturation costs ≤5s of display
  freshness, while an unbounded queue would turn a Redis outage into a memory leak.
- **The correctness rule is enforced by the build**, not by review: `PaymentArchitectureTest` fails if
  any domain class other than `GetBalanceUseCase` depends on the `BalanceCache` port, and the ledger's
  invalidator port can only *delete* — it cannot read a balance, so it cannot be misused to decide one.

## Alternatives rejected
- DynamoDB DAX: not in LocalStack; also read-through semantics fit worse with our invalidation-on-write.
- No cache: DynamoDB could meet 300ms alone, but at peak read volume the cache is the cost- and latency-correct answer, and invalidation-on-write is a pattern worth demonstrating.
