# Step 35 — Redis balance cache (cache-aside)

## Objective
`GET /v1/accounts/me/balance` on payment-service serves from Redis (`balance:<accountId>`, TTL 5s), falls back to ledger-service on miss, and **invalidates on every posting** (ledger-service publishes/deletes affected keys post-commit). p99 < 300ms holds with big margin; correctness rule (cache never feeds money decisions) enforced by construction.

## Why / what you'll learn
Cache-aside end to end, including the part everyone gets wrong — **invalidation**: delete-after-commit (never before; never *update* the cache from the writer — deletion + lazy reload avoids stale-write races), short TTL as the backstop for missed deletes, and the failure posture: Redis down ⇒ log + serve from ledger (cache is an optimization, not a dependency). Bonus property from ADR-0008 verified by test: ledger briefly down ⇒ balance still served (≤5s stale) — reads survive.

## Prerequisites
Steps 13, 20 (+29 for reversal invalidation).

## Tasks
1. `BalanceCache` (Spring Data Redis / Lettuce): get/set(TTL 5s)/evict; metrics `cache.balance.{hit,miss}`.
2. Public balance endpoint per OpenAPI on payment-service (proxying ledger internal API on miss; `asOf` from source).
3. ledger-service: post-commit hook evicting both accounts' keys on every posting (incl. reversal, inbound, clearing release) — best effort, log on failure.
4. Resilience: Redis exceptions ⇒ bypass cache path entirely.

## Tests (TDD)
- Miss→populate→hit (assert one ledger call across two reads); posting ⇒ next read fresh (evicted).
- TTL backstop: block eviction (test hook) ⇒ stale ≤5s then fresh.
- Redis container stopped ⇒ endpoint still correct via ledger; metrics show bypass.
- Ledger stopped + warm cache ⇒ balance still answers (stale-tolerant read), documented behavior.

## Verify locally
```bash
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001   # populated, TTL:
docker compose -f infra/docker-compose.yml exec redis redis-cli TTL balance:acc-001
# send a pix, re-check: key gone/refreshed with new value
```

## Definition of Done
- [ ] Hit-path ~1ms; invalidation on all posting types; TTL backstop verified
- [ ] Redis outage degrades latency only; ledger outage degrades freshness only
- [ ] Cache provably never consulted for debit decisions (code path review + comment)

## CHANGELOG entry
`### Added` → `Redis cache-aside for balance with post-commit invalidation, 5s TTL backstop and graceful degradation (step 35)`
