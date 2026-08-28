# Design note — ADR-0008 (Redis cache-aside for balance) · finalized by Step 40

> **Type:** ADR companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0008-redis-balance-cache.md](../adr/0008-redis-balance-cache.md) · **Concept finalized by:** [Step 40](step-40.md) (Redis cache-aside for balance + invalidation on postings + 5s TTL).
> **Why Step 40:** Redis comes up in Sprint 5 for fraud velocity counters, but ADR-0008 is specifically about the **balance read path** — cache-aside, invalidation-on-write, and the correctness rule that the cache never feeds a money decision. That whole mechanism lands in Step 40, so that's where the ADR is finalized and demoable.

---

## 1. The decision, and the trade-off it resolves

Balance reads are the **highest-volume operation** (~10 reads per transaction, every app open) and must be **< 300ms p99**. LocalStack does not emulate ElastiCache, so ADR-0008 runs **Redis as its own container** (documented as the local stand-in for ElastiCache) and uses **cache-aside**:

- **Read:** Redis hit? return : read ledger `BALANCE` (strongly consistent `GetItem`) → populate `balance:<accountId>` (TTL 5s) → return.
- **Invalidation:** every ledger posting **deletes** the affected accounts' keys *after* commit (best-effort); the short TTL is the backstop against a missed invalidation.
- **Correctness rule (the whole point):** the cache serves **display reads only**. Any money-moving decision (`balance >= amount`) happens **inside the DynamoDB conditional write** (Step 14) — a stale cache **can never cause an overdraft**.

The trade-off it resolves is **read latency/cost vs. staleness risk**, and it resolves the staleness risk by *removing it from the correctness path entirely*: staleness can only make a *displayed* number up to 5s old, never authorize a bad debit. A bonus availability property falls out: if the ledger is briefly down, balance **reads keep being served** (≤5s stale) from Redis — the degradation is latency/freshness, not correctness. Alternatives rejected: **DAX** (not in LocalStack; read-through fits worse with invalidation-on-write) and **no cache** (DynamoDB alone could hit 300ms, but at peak read volume the cache is the cost- and latency-correct answer, and invalidation-on-write is a pattern worth demonstrating).

---

## 2. Test the behavior (curl)

Prereq: Sprint 9 up (payment-service :8084, Redis container, ledger-service :8085), token in `$TOKEN`.

**(a) Miss populates, hit serves from Redis:**
```bash
docker compose -f infra/docker-compose.yml exec redis redis-cli DEL balance:acc-001
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq   # miss → ledger → populate
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001    # now cached
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq   # hit (Redis)
```

**(b) A posting invalidates — the next read reflects the new balance, not the stale one:**
```bash
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"7.00"}' >/dev/null
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001   # evicted (nil) post-commit
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq   # fresh balance
```

**(c) TTL backstop — even if an invalidation were missed, the key self-expires in 5s:**
```bash
docker compose -f infra/docker-compose.yml exec redis redis-cli TTL balance:acc-001   # ≤ 5
```

**(d) The correctness rule — a stale cache does NOT authorize an overdraft** (the money decision reads the ledger's conditional write, not Redis). Put a fat lie in the cache, then ask for more than the account really holds:

```bash
docker compose -f infra/docker-compose.yml exec redis redis-cli \
  SET balance:acc-001 '{"balanceCents":99999999,"asOf":"2026-01-01T00:00:00Z"}'
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq -r .balance  # 999999.99
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"txId":"tx-drill-1","debitAccount":"acc-001",
  "creditAccount":"acc-002","amountCents":5000000,"entryType":"PIX_INTERNAL","description":"drill"}' | jq
#   422 INSUFFICIENT_FUNDS, naming the REAL balance in cents
```

Note it is driven against **ledger-service**, not `POST /v1/payments/pix`: through payment-service the daily limit (R$ 5,000) refuses a send that large first, so it would demonstrate `LIMIT_EXCEEDED` and never reach the guard under test (see the [ADR-0001 companion](step-16-adr0001.md) §2b). Asserted in CI by `BalanceCacheInvalidationIT#aStaleCacheDoesNotAuthorizeAnOverdraft`.

---

## 3. Where to confirm it in the logs

| Signal | Log line / metric | What it proves |
|---|---|---|
| Cache outcome | `cache.hit` / `cache.miss` counters (Micrometer) | The hit rate is a KPI (README §OKRs & KPIs); it must stay high to hold the 300ms budget cheaply |
| Populate on miss | a ledger `GetItem` (ConsistentRead) followed by a Redis `SET … EX 5` | Cache-aside populate path |
| Invalidation | `evict balance:<debit>` / `balance:<credit>` **after** the posting commits | Invalidation-on-write, best-effort |
| Availability bonus | ledger down → balance still served from Redis (≤5s stale) | Losing the cache degrades latency, not correctness |

```bash
# /actuator/prometheus arrives with the Prometheus registry in step 44; until then the same counters
# are on the Actuator metrics endpoint (step 40 exposes cache.hit / cache.miss, tagged cache=balance):
curl -s localhost:8084/actuator/metrics/cache.hit  | jq '.measurements[0].value'
curl -s localhost:8084/actuator/metrics/cache.miss | jq '.measurements[0].value'
```

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Redis container (ElastiCache stand-in) | `infra/docker-compose.yml` (Step 23) |
| `BalanceCache` port (`get`/`put`, TTL 5s) + `RedisBalanceCache` | payment-service `domain/port/` + `infra/persistence/` (Step 40) |
| `GET /v1/accounts/me/balance` cache-aside (JWT-scoped) | payment-service `api/AccountBalanceController` + `domain/usecase/GetBalanceUseCase` (Step 40) |
| Invalidation on postings (post-commit evict) | ledger-service `domain/port/BalanceCacheInvalidator` + `infra/persistence/RedisBalanceCacheInvalidator` (Step 40, hooked into Step 14 posting). **Note the split:** the reader's port has no `evict` and the writer's has no `get` — invalidation belongs to whoever knows the balance changed, and neither service can misuse the other's half |
| Fail-fast Redis client (bounded timeouts, reject-while-disconnected) | `RedisFailFastConfig` in **both** services (Step 40 — see ADR-0008's implementation notes) |
| "The cache never feeds a money decision", as a build failure | `PaymentArchitectureTest#onlyTheBalanceReadDependsOnTheBalanceCache` (Step 40) |
| Strongly consistent source read | `DynamoLedgerRepository.getBalance` (Step 13) |
| `cache.hit` / `cache.miss` metrics | payment-service (Step 40 → dashboards Step 44) |
| Narrative | [ARCHITECTURE.md §6.9, §7.2](../../ARCHITECTURE.md) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Why cache-aside and not read-through/write-through?** Given invalidation-on-write is the model here, explain why cache-aside composes better with it than write-through, and the one operational failure write-through would have made worse.
2. **The 5s TTL is doing two jobs.** Name both (the one it does on the happy path and the one it does when invalidation fails), and argue whether you'd change it if invalidation were made *transactional* instead of best-effort.
3. **Best-effort eviction can be lost.** Walk the exact sequence where a posting commits but the cache DEL is dropped. Show precisely why a user might see a stale balance, why no money is ever wrong because of it, and how a reader would notice.
4. **The correctness rule, restated as an invariant.** Write the one-sentence invariant that makes it *impossible* for the cache to cause an overdraft, and point to the specific line of the ledger design (which step) that enforces it. What code review would you fail for violating it?
5. **When the cache stops paying.** At what read:write ratio or hit rate does adding Redis stop being worth its operational weight? Sketch how you'd measure that from the `cache.hit`/`cache.miss` metrics, and what you'd do if the hit rate quietly collapsed.
