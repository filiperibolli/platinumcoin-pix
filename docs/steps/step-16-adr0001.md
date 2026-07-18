# Learning — ADR-0001 (DynamoDB for the ledger) · finalized by Step 16

> **Type:** ADR learning companion (not an implementation step — it has no tasks/DoD of its own).
> **ADR:** [docs/adr/0001-dynamodb-for-the-ledger.md](../adr/0001-dynamodb-for-the-ledger.md) · **Concept finalized by:** [Step 16](step-16.md) (last step of Sprint 3 — Ledger).
> **Why Step 16:** ADR-0001 answers design **Question 3 — "database for the ledger *and* the history"**. The ledger is built across Steps 12→16 (table+seed → balance read → atomic posting → invariant storm → statement). The *decision* is only fully realized once **history** is served from the same table: that lands in Step 16, which is therefore where the ADR's claim is complete and demoable end to end.

---

## 1. The decision, and the trade-off it resolves

ADR-0001 chooses **DynamoDB with `TransactWriteItems` + condition expressions** for the money ledger — *against* the honest default of PostgreSQL.

The trade-off it resolves is **"native relational ACID + ad-hoc SQL" vs. "managed four-nines elasticity + predictable single-digit-ms latency"**. A payments ledger naturally pulls toward Postgres (declarative `CHECK balance >= 0`, serializable isolation, SQL reconciliation, decades of banking precedent). ADR-0001 accepts giving those up because the *remaining* NFRs — 99.99% availability with **zero failover engineering**, 8–10× burst absorption on-demand, and a hot-path write set that is small, fixed and key-addressable — tip the balance. The insight is that the ledger's hot path needs exactly one shape of write: *"update 2 balance items + insert 2 entry items, atomically, with conditions."* That is precisely what `TransactWriteItems` (≤100 items, ACID, serializable per item set) delivers — so we never need the relational features we're surrendering.

**What we give up, and how the design pays for it:**

| Given up (relational strength) | How this repo compensates |
|---|---|
| Declarative `CHECK` / FK constraints | Invariants become **`ConditionExpression`s** issued by exactly one service (ledger-service), defended by the Step 15 invariant storm |
| Ad-hoc SQL for reporting/reconciliation | GSIs designed up front (`GSI1 = TX#<txId>`) + S3 export (Athena in prod) |
| Cross-row transactions of arbitrary shape | The write set is fixed at 4 items — well inside the 100-item cap |
| "It's obviously correct" (engine-enforced) | First-hand proof: Step 15 storm + the **`labs/ledger-pg` counterpart** ([ADR-0009](../adr/0009-relational-ledger-counterpart-lab.md), Steps 50–51) that runs the *same* invariant suite on Postgres and benchmarks both — turning ADR-0001's "rule of thumb" into a measured claim |

---

## 2. Test the behavior (curl)

Prereq: Sprint 3 up (`ledger-service` on :8085, LocalStack seeded). Seed balances: `acc-001` (alice) = R$ 10,000.00, `acc-002` (bob) = R$ 10,000.00, `SPI_CLEARING` = 0, `SEED` = −R$ 20,000.00 (the double-entry counterpart, so **Σ all balances = 0** — the conservation baseline).

**(a) The atomic posting — debit and credit in one transaction.** No intermediate state where one leg lands without the other:
```bash
curl -s -X POST localhost:8085/internal/ledger/postings -H 'Content-Type: application/json' \
  -d '{"txId":"tx-learn-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":12550,"entryType":"PIX_INTERNAL","description":"adr0001 demo"}' | jq
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance | jq   # 10000.00 → 9874.50
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance | jq   # 10000.00 → 10125.50
```

**(b) No negative balance — the condition lives *inside* the write, never a read-then-check.** Try to move more than alice has:
```bash
curl -si -X POST localhost:8085/internal/ledger/postings -H 'Content-Type: application/json' \
  -d '{"txId":"tx-learn-overdraft","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":999999999,"entryType":"PIX_INTERNAL","description":"should fail"}' | head -1
# → HTTP 422  (code INSUFFICIENT_FUNDS) and ZERO writes — balances unchanged
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance | jq   # still 9874.50
```

**(c) Idempotent by `txId` — an internal replay cannot double-post.** Re-send the exact same command from (a):
```bash
curl -s -X POST localhost:8085/internal/ledger/postings -H 'Content-Type: application/json' \
  -d '{"txId":"tx-learn-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":12550,"entryType":"PIX_INTERNAL","description":"adr0001 demo"}' | jq
# → 200, returns the SAME posting result; balances DID NOT move again (attribute_not_exists on the txId-keyed entry)
```
Same `txId` with a **different amount** ⇒ `409` (conflicting replay), still no writes.

**(d) History from the same table (the Step 16 half of the ADR).** Reverse-chronological, cursor-paginated — no second store:
```bash
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=5" | jq
# → {entries:[... newest first ...], nextCursor: "<base64 LastEvaluatedKey>|null"}
```

**(e) See the raw single-table shape and the invariants' substrate directly in DynamoDB:**
```bash
# the mutable BALANCE item + its version counter
aws --endpoint-url=http://localhost:4566 dynamodb get-item --table-name pix_ledger \
  --key '{"pk":{"S":"ACCOUNT#acc-001"},"sk":{"S":"BALANCE"}}' | jq
# both legs of one posting via GSI1 (TX#<txId>) — the "SQL join" replaced by a designed index
aws --endpoint-url=http://localhost:4566 dynamodb query --table-name pix_ledger \
  --index-name GSI1 --key-condition-expression 'gsi1pk = :t' \
  --expression-attribute-values '{":t":{"S":"TX#tx-learn-1"}}' | jq
```

---

## 3. Where to confirm it in the logs

All ledger stages log at **INFO** as structured JSON with `correlationId` (+ `txId`) in the MDC — the contract from `CLAUDE.md` and the path audit in [Step 44](step-44.md). What to grep for:

| Signal | Log line (event name) | What it proves for ADR-0001 |
|---|---|---|
| A posting committed | `"event":"ledger.posted"` with `txId`, `debitAccount`, `creditAccount`, `amountCents` | The 4-write `TransactWriteItems` succeeded atomically |
| Insufficient funds | rejection at INFO/WARN → HTTP `422` `code:INSUFFICIENT_FUNDS`, `correlationId` | The `balanceCents >= :amount` **condition fired inside the transaction** (no partial write) |
| Idempotent replay | `ledger.posted` **not** re-emitted; replay returns the stored result | `attribute_not_exists` on the `txId`-keyed entry blocked the double-post |
| Contention | `WARN` retry-with-jitter on `TransactionConflict` (then `503` after max attempts) | Serialization is provided by **DynamoDB itself**, not the `version` field (which is an audit counter, not a lock) |

Reconstruct one transaction's whole path (ledger stage included) by its correlation id:
```bash
docker compose -f infra/docker-compose.yml logs ledger-service | grep '"ledger.posted"'
bash scripts/trace.sh <correlationId>     # step 44 — full cross-service path
```

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Table, keys, GSI1, seed & system accounts | `infra/localstack/init/02-dynamodb-ledger.sh`, `05-seed-ledger.sh` (Step 12); schema of record in [docs/data-model.md](../data-model.md) |
| Atomic posting (the `TransactWriteItems`) | `services/ledger-service/.../infra/DynamoLedgerRepository` (Step 14) — builds update+condition, update, put+condition, put+condition |
| `balanceCents >= :amount` + `attribute_not_exists(txId)` conditions | same repository; system accounts (`SPI_CLEARING`, `SEED`) exempted from the non-negative rule via an explicit **`AccountPolicy`** (Step 14) |
| Strongly consistent balance read + `version` semantics | `DynamoLedgerRepository.getBalance` (`ConsistentRead=true`, Step 13) |
| Statement / history pagination | `LedgerRepository.getEntries` (Step 16) — `begins_with(sk,"ENTRY#")`, `ScanIndexForward=false`, base64 `LastEvaluatedKey` cursor |
| Proof the guarantees hold | Step 15 invariant storm (✍️ hand-written); the relational counterpart & benchmark in `labs/ledger-pg` ([ADR-0009](../adr/0009-relational-ledger-counterpart-lab.md), Steps 50–51) |
| Narrative / trade-off in the design doc | [ARCHITECTURE.md §8](../../ARCHITECTURE.md) (Question 3) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Constraint location.** In Postgres the "no negative balance" rule is a one-line `CHECK`. Here it is an application-issued `ConditionExpression` inside `TransactWriteItems`. What class of bug does moving the invariant *out of the schema* expose you to, and which two mechanisms in this design (name them) are the compensating controls? What would make you distrust those controls?
2. **The `version` counter is not a lock.** Given DynamoDB already serializes conflicting item sets via `TransactionConflict`, argue *why the `version` field still earns its place* — and construct a concrete scenario where its absence would cost you during an incident, even though correctness never depended on it.
3. **Where the choice would flip.** Take the same 5M tx/day but change *one* requirement so that DynamoDB stops being the right call and Postgres wins. State the requirement, and explain precisely which paragraph of ADR-0001 you'd have to rewrite — not just "SQL is nicer."
4. **The 100-item cap as a design boundary.** The hot path is exactly 4 writes. Sketch a *plausible* future ledger feature (real, not contrived) that would push a single posting past the 100-item transaction cap, and decide: do you shard the write, split the transaction and accept a saga, or is the feature itself a smell? Defend the call.
5. **Reconciliation without SQL.** A regulator asks: *"prove no account went negative in the last 5 years."* With no ad-hoc SQL over the live table, describe the exact data path you'd use to answer that at 3.6 TB/yr — and what you had to design *up front* (Step 12? Step 16? elsewhere) for that answer to even be possible.
