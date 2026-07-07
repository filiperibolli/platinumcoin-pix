# Data Model — DynamoDB tables

All tables run on LocalStack DynamoDB, created by `infra/localstack/init` scripts. Naming: `pix_<table>`. Amounts are stored as **integer cents** (`amountCents: Number`) — never floats — to avoid rounding bugs; the API exposes decimal strings.

> **Learning note — how to model in DynamoDB:** unlike relational design (normalize, then query anything), DynamoDB design starts from the **access patterns** and shapes keys around them. Each table below lists its access patterns first; the key schema is the answer to those patterns.

---

## 1. `pix_accounts` (owner: account-service)

Access patterns: get accounts of a user; get account by id; read limit config.

| | Value |
|---|---|
| PK | `USER#<userId>` |
| SK | `ACCOUNT#<accountId>` |
| GSI1 | PK `ACCOUNT#<accountId>` → direct lookup by account |

```json
{
  "pk": "USER#u-alice",
  "sk": "ACCOUNT#acc-001",
  "gsi1pk": "ACCOUNT#acc-001",
  "userId": "u-alice",
  "accountId": "acc-001",
  "status": "ACTIVE",
  "dailyLimitCents": 500000,
  "createdAt": "2026-07-02T12:00:00Z"
}
```

---

## 2. `pix_keys` (owner: account-service)

Access patterns: resolve key → account (hot path of send); list keys of an account; delete a key. **Global uniqueness** of a Pix key is the critical invariant.

| | Value |
|---|---|
| PK | `KEY#<keyValue>` (e.g. `KEY#bob@platinum.com`) |
| SK | `META` |
| GSI1 | PK `ACCOUNT#<accountId>` → list keys of an account |

Uniqueness enforcement: `PutItem` with `ConditionExpression: attribute_not_exists(pk)`. Two users registering the same e-mail race → exactly one wins, the other gets `ConditionalCheckFailedException` → API `409`. No read-then-write race is possible because the check and the write are one atomic operation — **this conditional-put trick is the DynamoDB equivalent of a UNIQUE constraint** and reappears throughout the design (idempotency, ledger entries, event dedup).

```json
{
  "pk": "KEY#bob@platinum.com",
  "sk": "META",
  "gsi1pk": "ACCOUNT#acc-002",
  "keyType": "EMAIL",
  "keyValue": "bob@platinum.com",
  "accountId": "acc-002",
  "userId": "u-bob",
  "createdAt": "2026-07-02T12:00:00Z"
}
```

---

## 3. `pix_ledger` (owner: ledger-service — the only writer)

Access patterns: read balance (hottest read); post debit+credit atomically; statement = entries of an account, newest first, paginated; fetch both legs of a transaction (audit/reconciliation).

| | Value |
|---|---|
| PK | `ACCOUNT#<accountId>` |
| SK | `BALANCE` (one item) or `ENTRY#<isoTimestamp>#<txId>` (immutable postings) |
| GSI1 | PK `TX#<txId>` → both legs of a posting |

**Balance item:**
```json
{
  "pk": "ACCOUNT#acc-001",
  "sk": "BALANCE",
  "balanceCents": 1000000,
  "version": 42,
  "updatedAt": "2026-07-02T12:34:56Z"
}
```

**Entry items (one debit + one credit per posting, DEBIT negative / CREDIT positive):**
```json
{
  "pk": "ACCOUNT#acc-001",
  "sk": "ENTRY#2026-07-02T12:34:56.123Z#tx-9f1c",
  "gsi1pk": "TX#tx-9f1c",
  "txId": "tx-9f1c",
  "direction": "DEBIT",
  "amountCents": -12550,
  "counterpartAccountId": "SPI_CLEARING",
  "description": "PIX to bob@otherbank.com",
  "entryType": "PIX_OUT"
}
```

### The double-entry posting — one `TransactWriteItems`

A posting `debit A, credit B, amount X, txId T` is exactly four writes in **one** DynamoDB transaction:

| # | Operation | Item | Condition |
|---|---|---|---|
| 1 | Update | `A / BALANCE` — `SET balanceCents = balanceCents - :x, version = version + :one` | `balanceCents >= :x` ← **no negative balance** |
| 2 | Update | `B / BALANCE` — `SET balanceCents = balanceCents + :x, version = version + :one` | item exists |
| 3 | Put | `A / ENTRY#ts#T` (DEBIT) | `attribute_not_exists(pk)` ← **no double-post of T** |
| 4 | Put | `B / ENTRY#ts#T` (CREDIT) | `attribute_not_exists(pk)` |

DynamoDB transactions are **ACID and all-or-nothing**: if any condition fails (insufficient funds, replayed txId, concurrent conflict), all four writes are cancelled. This is the mechanical answer to *"how do you guarantee money is never debited without being credited?"* — the debit and the credit are literally the same atomic operation; there is no intermediate state where one exists without the other.

**Invariant (checkable at any time):** `Σ balanceCents over all accounts (including SPI_CLEARING) = Σ of initial seeds` — postings move money, never create or destroy it. The invariant test suite (step 15) asserts this under a concurrent debit storm.

**System accounts:** `ACCOUNT#SPI_CLEARING` (money in flight to/from BACEN — exempt from the `balance >= x` condition, since its balance represents an inter-bank position and may go negative on inbound-heavy days) and `ACCOUNT#SEED` (initial funding source for demo users — **also exempt**: its balance is negative by construction, the double-entry counterpart of the seeded user balances, so Σ over all accounts nets to **zero**). Production note: at 500 TPS all external sends hit the single clearing item → write-shard it into `SPI_CLEARING#00..#15` by hash of txId (documented, N=1 locally).

**Statement pagination:** `Query pk = ACCOUNT#id AND begins_with(sk, "ENTRY#")`, `ScanIndexForward=false` (newest first), `Limit=n`; the API cursor is the base64 of `LastEvaluatedKey`. Timestamp-prefixed sort keys give chronological ordering for free — a core DynamoDB idiom.

---

## 4. `pix_transactions` (owner: payment-service)

Access patterns: get transaction by id (status query); find by endToEndId (reconciliation, inbound dedup); scan stuck transactions by status+age; **write outbox events atomically with the transaction** (same table → same `TransactWriteItems`); reserve/release daily-limit usage per account per calendar day.

| | Value |
|---|---|
| PK | `TX#<txId>` |
| SK | `META` (the transaction) or `OUTBOX#<eventId>` (outbox items) |
| GSI1 | PK `E2E#<endToEndId>` → lookup by Pix end-to-end id |
| GSI2 | PK `STATUS#<status>`, SK `updatedAt` → reconciliation scan (`status IN (DEBITED, SENT_TO_SPI) AND updatedAt < now-2min`) |
| GSI3 (sparse) | PK `OUTBOX#UNPUBLISHED`, SK `occurredAt` → the publisher's work queue: only unpublished outbox items carry `gsi3pk`, so the index holds in-flight events only |

**Transaction item:**
```json
{
  "pk": "TX#tx-9f1c",
  "sk": "META",
  "gsi1pk": "E2E#E12345678202607021234abcdef01234",
  "gsi2pk": "STATUS#DEBITED",
  "gsi2sk": "2026-07-02T12:34:56Z",
  "txId": "tx-9f1c",
  "endToEndId": "E12345678202607021234abcdef01234",
  "direction": "OUTBOUND",
  "debtorAccountId": "acc-001",
  "creditorKey": "bob@otherbank.com",
  "creditorInternal": false,
  "amountCents": 12550,
  "status": "DEBITED",
  "fraudDecision": "APPROVE",
  "fraudSkipped": false,
  "createdAt": "...", "updatedAt": "..."
}
```

**Outbox item (same table, same partition as its transaction):**
```json
{
  "pk": "TX#tx-9f1c",
  "sk": "OUTBOX#evt-7a2b",
  "eventId": "evt-7a2b",
  "eventType": "PixDebited",
  "payload": "{...json...}",
  "occurredAt": "2026-07-02T12:34:56Z",
  "gsi3pk": "OUTBOX#UNPUBLISHED",
  "gsi3sk": "2026-07-02T12:34:56Z"
}
```

Publishing = `UpdateItem REMOVE gsi3pk` after the SNS publish (publish-then-mark ⇒ at-least-once; the item leaves the sparse index and the outbox history stays in the partition for audit). See ADR-0004.

> **Learning note — sparse GSI:** an item only appears in a GSI if it has the index's key attributes. Removing `gsi3pk` is therefore a cheap, atomic "done" flag: the pending-work index stays O(in-flight), never O(history).

**Daily-limit usage item (same table):** the counter behind step 20's limit check. The table deliberately has **no index by debtor account**, so "today's outbound total" is *not* a query-and-sum — it is a maintained counter with reserve/release semantics:

```json
{
  "pk": "LIMIT#acc-001",
  "sk": "DAY#2026-07-07",
  "usedCents": 137550,
  "expiresAt": 1751896800
}
```

- **Reserve** (before any money moves): `UpdateItem ADD usedCents :amount` with `ConditionExpression: attribute_not_exists(usedCents) OR usedCents <= :limitMinusAmount` (the account's `dailyLimitCents` is read from account-service first; the comparison value is computed client-side because condition expressions cannot do arithmetic). Condition fails ⇒ `422 LIMIT_EXCEEDED`.
- **Release** (fraud-deny, insufficient funds, reversal): `ADD usedCents -:amount` — a rejection returns exactly what it reserved.
- Window: **calendar day** (America/Sao_Paulo), matching how Pix limits are communicated to users; TTL (~48h) cleans past days.

Status transitions are guarded updates (`ConditionExpression: #status = :expectedFrom`) so out-of-order consumers cannot regress a `SETTLED` transaction back to `SENT_TO_SPI`.

> **Learning note — GSI on status:** `STATUS#<status>` as a GSI partition key concentrates all same-status items in few partitions; fine at this scale for a scan every 60s, but at very large scale you'd shard it (`STATUS#DEBITED#<0-15>`). Documented as the scale-out path; N=1 locally.

---

## 5. `pix_idempotency` (owner: payment-service)

| | Value |
|---|---|
| PK | `IDEM#<accountId>#<idempotencyKey>` |
| SK | `META` |
| TTL | attribute `expiresAt` (epoch seconds, +24h) — DynamoDB deletes expired items automatically |

```json
{
  "pk": "IDEM#acc-001#3f2a...uuid",
  "sk": "META",
  "requestHash": "sha256:ab12...",
  "status": "COMPLETED",
  "responseSnapshot": "{\"transactionId\":\"tx-9f1c\",\"status\":\"PROCESSING\"}",
  "httpStatus": 202,
  "expiresAt": 1751551200
}
```

Claimed with `attribute_not_exists(pk)`; the record also carries `claimedAt` — an `IN_PROGRESS` claim older than 60s is stale (crash mid-flight) and may be re-claimed. Note that DynamoDB TTL deletion is **lazy**: reads must check `expiresAt` themselves and treat expired-but-present records as absent. See ADR-0002 for full semantics (replay, 409 on hash mismatch, IN_PROGRESS handling).

---

## 6. `pix_processed_events` (owner: each consumer, shared table with consumer-scoped keys)

At-least-once delivery (outbox + SQS) means consumers **will** see duplicates. Each consumer dedupes with a conditional put before side effects:

| | Value |
|---|---|
| PK | `CONSUMER#<name>#EVT#<eventId>` |
| SK | `META` |
| TTL | 7 days |

If the conditional put fails → duplicate → ack the message and skip. This one small table is what makes "at-least-once + idempotent consumer = effectively-once" real across the whole platform.

---

## 7. Capacity & local settings

- All tables **on-demand** (PAY_PER_REQUEST) — no capacity planning locally, matches the auto-scaling NFR in prod.
- No DynamoDB Streams used (polling outbox — ADR-0004); GSI3 on `pix_transactions` is sparse.
- Init scripts and exact `aws dynamodb create-table` commands live in `infra/localstack/init/` and are mirrored in `docs/local-dev.md`. They are added **incrementally, per sprint** (vertical delivery — see `PLAN.md`): accounts/keys in step 07, ledger in step 12, transactions/idempotency in step 17, `pix_processed_events` in step 29 — so at any point only the tables the built flows need exist.
