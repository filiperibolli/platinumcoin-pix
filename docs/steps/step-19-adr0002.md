# Learning — ADR-0002 (Idempotency strategy) · finalized by Step 19

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0002-idempotency-strategy.md](../adr/0002-idempotency-strategy.md) · **Concept finalized by:** [Step 19](step-19.md) (the API idempotency layer on the send endpoint). *Step 19 is a ✍️ hand-written test zone — this companion only references it, it does not modify it.*
> **Why Step 19:** ADR-0002 is a *three-layer* strategy — API key, ledger `txId`, SPI `endToEndId`. The ledger layer already exists (Step 14) and the SPI layer arrives with settlement (Step 30/31), but the **headline layer — the client-facing `Idempotency-Key` with claim / replay / 409** — is the one the ADR is *about*, and it is completed in Step 19. That's where "the user tapped twice / the network retried" gets its definitive answer.

---

## 1. The decision, and the trade-off it resolves

Mobile clients retry on timeouts, and a failure can strike at any point: before persistence, after the debit but before the response, or after a `2xx` lost in transit. ADR-0002 makes a retried `POST /payments/pix` **debit at most once** via **three independent layers**, so no single bug can double-charge:

| Layer | Mechanism | What it defends |
|---|---|---|
| **API** (Step 19) | Conditional `PutItem` on `IDEM#<accountId>#<key>` (`attribute_not_exists`) storing `requestHash` (SHA-256 of canonical body) + `responseSnapshot`, TTL 24h | The client-visible contract: same key+body ⇒ replay the stored `202`; same key+different body ⇒ `409` |
| **Ledger** (Step 14) | Entry keyed by `txId` with `attribute_not_exists` inside the `TransactWriteItems` | Even a bug *above* this line cannot double-post |
| **SPI** (Step 30/31) | `endToEndId` is the idempotency key toward BACEN; query-before-retry | A settlement retry after a timeout can't double-settle |

The trade-off it resolves is **"dedupe safely" vs. "don't collapse legitimate repeats."** The naive alternative — dedupe by request-body hash alone — would wrongly merge two *intentional* identical payments (same payee, same amount, twice on purpose). ADR-0002 rejects that: the key is a **client-minted UUID per business operation**, and the hash is only used to detect *key reuse with a different payload*. The other tension it resolves is the **claim-vs-crash race**: the conditional put is a *lock-and-memo in one write* (immune to check-then-act), and an `IN_PROGRESS` claim older than a 60s staleness window is treated as an abandoned orphan and re-claimed — so a crash between claim and completion never blocks the client until the 24h TTL. A final sharp edge: DynamoDB TTL deletion is **lazy**, so reads must also treat an expired-but-still-present record as absent — the 24h window is enforced by the *application*, not by the deletion.

---

## 2. Test the behavior (curl)

Prereq: Sprint 4 up (`payment-service` :8084), a token in `$TOKEN` (see Step 04).

**(a) Retry replays — one debit, same `transactionId`:**
```bash
IDEM=$(uuidgen); BODY='{"pixKey":"bob@platinum.com","amount":"10.00","description":"idem demo"}'
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY" | jq .transactionId
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY" | jq .transactionId
# → identical transactionId; exactly ONE transaction item exists
```

**(b) Key reuse with a different payload ⇒ 409:**
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"99.00"}' | head -1   # HTTP 409 (IDEMPOTENCY_KEY_REUSED)
```

**(c) Canonicalization — key order / whitespace must NOT change the hash** (so a re-serialized retry still replays):
```bash
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' \
  -d '{ "amount":"10.00" , "pixKey":"bob@platinum.com", "description":"idem demo" }' | jq .transactionId
# → SAME transactionId as (a): reordered/whitespaced body hashes the same
```

**(d) Missing header ⇒ 400 (money-moving POSTs require the key):**
```bash
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$BODY" | head -1   # HTTP 400 (IDEMPOTENCY_KEY_REQUIRED)
```

**(e) Inspect the record and its lazy TTL directly:**
```bash
ACC=acc-001
aws --endpoint-url=http://localhost:4566 dynamodb get-item --table-name pix_idempotency \
  --key "{\"pk\":{\"S\":\"IDEM#$ACC#$IDEM\"},\"sk\":{\"S\":\"META\"}}" | jq
# → status COMPLETED, requestHash, responseSnapshot, claimedAt, expiresAt (checked on read — deletion is lazy)
```

---

## 3. Where to confirm it in the logs

| Signal | Log line | What it proves |
|---|---|---|
| First accept | `"event":"payment.accepted"` with a fresh `txId`, `correlationId` | The claim won — this is the *only* time the work runs |
| Replay | **no** new `payment.accepted`; a served-from-idempotency line, same `txId` | The conditional put lost ⇒ stored response replayed, no second debit |
| Key reuse | rejection → `409 IDEMPOTENCY_KEY_REUSED`, `correlationId` | Same key, different `requestHash` |
| Orphan reclaim | WARN "stale IN_PROGRESS reclaimed" after 60s | The crash-between-claim-and-response window handled without waiting for the 24h TTL |

```bash
docker compose -f infra/docker-compose.yml logs payment-service | grep -E 'payment.accepted|IDEMPOTENCY_KEY'
```
Two retries under the *same* `correlationId` (or two correlationIds pointing at one `txId`) that yield a single `payment.accepted` is the visual proof the layer holds.

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| `pix_idempotency` table (PK `IDEM#<accountId>#<key>`, TTL 24h) | `infra/localstack/init/*` (Step 17); schema in [docs/data-model.md](../data-model.md) |
| Claim / complete / get (lazy-TTL aware) | `services/payment-service/.../infra/IdempotencyRepository` (Step 19) |
| Canonical JSON hashing (sorted keys, trimmed) | `services/common-lib/.../` (Step 19) — shared so hashing is identical everywhere |
| Controller flow (claim → proceed → memoize → replay/409) | payment-service send controller (Step 19) |
| Ledger layer (defense in depth, `txId`) | `DynamoLedgerRepository` (Step 14) — see [ADR-0001 companion](step-16-adr0001.md) |
| SPI layer (`endToEndId`) | mock-bacen settle (Step 30), settlement consumer (Step 31) |
| Contract of record | [docs/api/openapi.yaml](../api/openapi.yaml) `/payments/pix`; narrative in [ARCHITECTURE.md §5, §7.1](../../ARCHITECTURE.md) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Why scope the key by `accountId`?** Two different users could mint the same UUID. Walk through exactly what breaks if the key were global instead of `accountId`-scoped — and what *new* problem a global scope would create even if UUIDs never collided.
2. **The 60s orphan window is a guess.** Name the two failure modes you trade off by moving it to 5s vs. 5min, and describe how you'd pick the number from data rather than intuition. What signal would tell you it's set wrong?
3. **Three layers, one job.** If the ledger layer already makes double-posting impossible by `txId`, argue why the API layer is not redundant — give a concrete sequence where the API layer is the *only* thing that saves the user from a bad experience (not a bad ledger).
4. **Hash the body, but which body?** The canonical hash covers the request body. Should it also cover the `accountId`, the endpoint, or a timestamp? For each, decide include/exclude and justify against a concrete replay/attack scenario.
5. **TTL vs. retry horizon.** The record TTL (24h) must exceed any client retry horizon. Construct the incident where a *too-short* TTL causes a double charge, and separately where a *too-long* TTL causes a wrong `409` — then state the single sentence you'd put in the runbook to set it.
