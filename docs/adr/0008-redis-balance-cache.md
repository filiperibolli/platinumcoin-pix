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

## Alternatives rejected
- DynamoDB DAX: not in LocalStack; also read-through semantics fit worse with our invalidation-on-write.
- No cache: DynamoDB could meet 300ms alone, but at peak read volume the cache is the cost- and latency-correct answer, and invalidation-on-write is a pattern worth demonstrating.
