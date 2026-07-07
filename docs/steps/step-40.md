# Step 40 — Redis cache-aside for balance + invalidation

> **Sprint 9 — Balance & statement (cache)** · **Flow:** fast reads · **Infra que sobe:** none new (Redis cache-aside) · **Diagram:** ARCHITECTURE §6.9

## Objective
`GET /v1/accounts/me/balance` on payment-service serves from Redis (`balance:<accountId>`, TTL 5s), falls back to ledger-service on miss, and **invalidates on every posting** (ledger-service deletes affected keys post-commit). p99 < 300ms holds with big margin; the correctness rule (cache never feeds money decisions) is enforced by construction.

## Why / what you'll learn
**Cache-aside** end to end: read → hit? return : read ledger BALANCE → populate (TTL 5s) → return; write path deletes the affected keys after commit, with the short TTL as a backstop against a missed invalidation. The **correctness rule** is the whole point: the cache serves *display* reads only — any money-moving decision (`balance >= amount`) happens inside the DynamoDB conditional write (step 14), so a stale cache can never cause an overdraft. Bonus availability property: if the ledger is briefly down, balance reads keep being served (≤5s stale) from Redis (ADR-0008).

## Prerequisites
Steps 13 (balance read), 23 (Redis), 21/27 (postings exist to invalidate).

## Tasks
1. `BalanceCache` (Redis): `get`, `put(TTL 5s)`, `evict`.
2. `GET /v1/accounts/me/balance` (from JWT): cache-aside via ledger-service on miss.
3. Invalidation on postings: ledger-service evicts `balance:<debit>` and `balance:<credit>` **after** the transaction commits (best-effort); TTL is the backstop.
4. `cache.hit`/`cache.miss` metrics.

## Tests (TDD)
- `BalanceCacheIT` — miss populates; hit serves from Redis; a posting evicts and the next read reflects the new balance; TTL expiry re-populates.
- Correctness — a stale cache does not affect a concurrent debit's `balance>=amount` outcome (money decision reads the ledger).

## Verify locally
```bash
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001
```

## Definition of Done
- [ ] Cache-aside with 5s TTL + invalidation on postings; p99 < 300ms
- [ ] Cache never feeds a money decision (enforced by construction)
- [ ] Hit/miss metrics exposed

## CHANGELOG entry
`### Added` → `Redis cache-aside for balance with invalidation on postings and a 5s TTL backstop (step 40)`
