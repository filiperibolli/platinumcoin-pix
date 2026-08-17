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
| PK | `ACCOUNT#<accountId>` — or `TX#<txId>` for the posting guard below |
| SK | `BALANCE` (one item) or `ENTRY#<isoTimestamp>#<txId>` (immutable postings); `POSTING` under a `TX#` partition |
| GSI1 | PK `TX#<txId>` → both legs of a posting (sparse: only ENTRY items carry `gsi1pk`) |

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
  "entryType": "PIX_OUT",
  "createdAt": "2026-07-02T12:34:56.123Z"
}
```

### The double-entry posting — one `TransactWriteItems`

A posting `debit A, credit B, amount X, txId T` is exactly five writes in **one** DynamoDB transaction:

| # | Operation | Item | Condition |
|---|---|---|---|
| 1 | Update | `A / BALANCE` — `SET balanceCents = balanceCents - :x, version = version + :one, updatedAt = :now` | `attribute_exists(pk) AND balanceCents >= :x` ← **no negative balance** |
| 2 | Update | `B / BALANCE` — `SET balanceCents = balanceCents + :x, version = version + :one, updatedAt = :now` | `attribute_exists(pk)` |
| 3 | Put | `A / ENTRY#ts#T` (DEBIT) | `attribute_not_exists(pk)` ← **append-only** |
| 4 | Put | `B / ENTRY#ts#T` (CREDIT) | `attribute_not_exists(pk)` |
| 5 | Put | `TX#T / POSTING` (the posting record) | `attribute_not_exists(pk)` ← **no double-post of T** |

DynamoDB transactions are **ACID and all-or-nothing**: if any condition fails (insufficient funds, replayed txId, concurrent conflict), all five writes are cancelled. This is the mechanical answer to *"how do you guarantee money is never debited without being credited?"* — the debit and the credit are literally the same atomic operation; there is no intermediate state where one exists without the other.

**Why write 1 also checks `attribute_exists`, and why write 2 has a condition at all:** `UpdateItem` is an *upsert*. Without it, crediting a typo'd account id would silently **create** a ledger account and park the money in it, and a debit of an unknown account would fail with an opaque expression error instead of a 404. The existence check is also what makes the two failures distinguishable: the two `Update`s carry `ReturnValuesOnConditionCheckFailure=ALL_OLD`, so a cancelled debit comes back **with the balance item** when the account exists (⇒ `422 INSUFFICIENT_FUNDS`, and by how much it fell short) and **without one** when it does not (⇒ `404 LEDGER_ACCOUNT_NOT_FOUND`). Without ALL_OLD, "you have no money" and "that account does not exist" arrive as the same anonymous `ConditionalCheckFailed`.

**The `TX#T / POSTING` item (write 5) is the idempotency guard**, and the reason it exists is worth stating plainly, because the obvious design is wrong. Conditioning the *entry* puts on `attribute_not_exists` protects only against re-writing the **same key**, and an entry's key is `ENTRY#<timestamp>#<txId>`. A caller that retries after an ambiguous outcome — a timeout, a lost response — sends the same `txId` but arrives at a **new instant**, so the keys differ, the condition passes, and the payer is debited twice. Keying the guard on `txId` *alone* removes the clock from the identity of a posting; it is what makes "idempotent by txId" true rather than nearly true (step 14).

```json
{
  "pk": "TX#tx-9f1c",
  "sk": "POSTING",
  "txId": "tx-9f1c",
  "debitAccount": "acc-001",
  "creditAccount": "acc-002",
  "amountCents": 12550,
  "entryType": "PIX_INTERNAL",
  "description": "PIX to bob@platinum.com",
  "postedAt": "2026-07-02T12:34:56.123Z"
}
```

It doubles as the **stored posting record**: with `ReturnValuesOnConditionCheckFailure=ALL_OLD`, a cancelled guard hands back the committed command inside the cancellation itself, so "is this the same posting?" is answered **strongly consistently and with no extra read** — same money ⇒ idempotent replay (`200`, `replayed: true`, the *original* `postedAt`); different money under the same `txId` ⇒ `409 POSTING_TXID_MISMATCH`, because answering "already done" would swallow a payment and posting it would double-spend the first. `description` is excluded from that comparison: a label is not money, and refusing a retry that regenerated its text would push the caller towards a *new* txId — the one reaction that actually double-spends. The guard item deliberately carries **no `gsi1pk`**, so GSI1 keeps meaning exactly "the two legs of this transaction".

> **Learning note — why not `ClientRequestToken`?** DynamoDB offers transaction-level idempotency through that parameter, but only for ~10 minutes and only for a byte-identical request. Our request carries a fresh timestamp on every retry, so it would raise `IdempotentParameterMismatchException` in exactly the case it is meant to cover. Idempotency that must outlive a client SDK window belongs in the data.

**Reading a cancellation.** `TransactionCanceledException.cancellationReasons()` is a positional list, one reason per item above, and the order of interpretation is a business decision: **the guard is read first**. A replayed posting that would *also* now be short of funds is still a replay — the money it names moved when it first committed, and answering 422 would report as failed a payment that succeeded. Then the balance conditions (422 / 404), then a stale-entry conflict (409), and only then `TransactionConflict`, which is contention rather than a rule violation: retried up to 3 times with jittered backoff, and after that `503 LEDGER_CONFLICT` — nothing was written, and the caller may safely re-send the same `txId`.

**Invariant (checkable at any time):** `Σ balanceCents over all accounts (including SPI_CLEARING) = Σ of initial seeds` — postings move money, never create or destroy it. The invariant test suite (step 15) asserts this under a concurrent debit storm.

**`entryType` vocabulary** (grown one step at a time, as each flow lands): `SEED_FUNDING` — the initial funding postings written by `05-seed-ledger.sh` (step 12); `PIX_INTERNAL` (step 21) / `PIX_OUT` (step 27) / `PIX_IN` (step 37); and, on a **definitive external outcome** (step 33), `CLEARING_RELEASE` (a settled send draws the money out of clearing: `debit SPI_CLEARING / credit SPI_SETTLED`) and `PIX_REVERSAL` (a permanently-refused send returns the money to the payer: `debit SPI_CLEARING / credit payer`). The ledger **stores it and does not validate it** (it only refuses a blank): an entry written by a newer service must never fail to load in an older one, which is why it is an open string rather than an enum. Step 14 therefore adds no term — the `PIX_INTERNAL` in its runbook curl is an example value, not a vocabulary entry.

**System accounts:** `ACCOUNT#SPI_CLEARING` (money in flight to/from BACEN — exempt from the `balance >= x` condition, since its balance represents an inter-bank position and may go negative on inbound-heavy days); `ACCOUNT#SPI_SETTLED` (step 33 — money that has **settled out** to the SPI network, the credit counterpart of a `CLEARING_RELEASE` so that a settlement draws clearing down while Σ over all accounts stays **zero**; seeded at 0, only ever credited, so it never needs the exemption); and `ACCOUNT#SEED` (initial funding source for demo users — **also exempt**: its balance is negative by construction, the double-entry counterpart of the seeded user balances, so Σ over all accounts nets to **zero**). Production note: at 500 TPS all external sends hit the single clearing item → write-shard it into `SPI_CLEARING#00..#15` by hash of txId (documented, N=1 locally); a reversal/release must hit the **same** shard the debit credited, which is why the shard used is persisted on the transaction (§4, `clearingAccountId`).

**Statement pagination:** `Query pk = ACCOUNT#id AND begins_with(sk, "ENTRY#")`, `ScanIndexForward=false` (newest first), `Limit=n`; the API cursor is the base64 of `LastEvaluatedKey`. Timestamp-prefixed sort keys give chronological ordering for free — a core DynamoDB idiom.

> **The timestamp is written with fixed-width milliseconds** (`uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`), never `Instant.toString()`. That method omits trailing zeros, so an entry at exactly `10:15:30` renders `10:15:30Z` while one 500 ms later renders `10:15:30.500Z` — and `'Z'` (0x5A) sorts **after** `'.'` (0x2E). "Chronological ordering for free" is *lexicographic* ordering, so a variable-width timestamp silently returns the wrong page for every entry that lands on a round second.

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

The example above is an **external** send mid-flight (`DEBITED`). An **internal** send (step 21) settles
in one atomic ledger posting and is written straight to `SETTLED`, carrying two step-21 fields the
example does not show:
- `creditorAccountId` — account-service's DICT resolution of `creditorKey` to an internal account (the
  debit's counterpart leg). Present once resolved.
- `settledAt` — the instant the ledger posting committed; for an internal transfer that is the moment the
  money moved. Present once `SETTLED`.

Both are written only when set, so a not-yet-settled item carries neither — and an **external** send
(step 27) carries neither at acceptance time: its payee holds no account here, and its money sits in
`ACCOUNT#SPI_CLEARING` (in flight) rather than with the payee, so the item rests at `DEBITED` with
`settledAt` absent until settlement (step 31) writes it.

- `clearingAccountId` (step 33, external send only) — the **exact** clearing account the acceptance-time
  debit credited. Written on an external `DEBITED` item and carried on the `PixDebited` event, so a later
  reversal debits the same account it credited. Today that is the single `SPI_CLEARING`; step 52 shards it
  (`SPI_CLEARING#00..#15`), and a reversal that re-derived the shard instead of reading the one used would
  drain the wrong sub-account and break the per-shard balance. An internal send never touches clearing, so
  it carries no `clearingAccountId`.

**The settlement-confirmation fields (step 31, owner: settlement-service).** An external send is finished
by settlement-service, which is the platform's one documented exception to table ownership (ADR-0006): the
status change and the `PixSettled` it announces must commit in **one** `TransactWriteItems`, and an
internal API between the writer and this table would reintroduce the dual write the outbox exists to
remove. Its write surface is exactly these **guarded** transitions and nothing else:

| Transition | Condition (inside the write) | Attributes written |
|---|---|---|
| `DEBITED → SENT_TO_SPI` | `attribute_exists(pk) AND (status = DEBITED OR status = SENT_TO_SPI)` | `status`, `gsi2pk`, `gsi2sk`, `updatedAt` |
| `SENT_TO_SPI → SETTLED` | `attribute_exists(pk) AND status = SENT_TO_SPI` | `status`, `gsi2pk`, `gsi2sk`, `updatedAt`, **`settledAt`**, **`creditorIspb`** + the `OUTBOX#<eventId>` item |
| `(DEBITED \| SENT_TO_SPI) → REVERSED` (step 33; guard widened step 35) | `attribute_exists(pk) AND (status = SENT_TO_SPI OR status = DEBITED)` | `status`, `gsi2pk`, `gsi2sk`, `updatedAt`, **`failureReason`** + the `OUTBOX#<eventId>` (`PixReversed`) item |

- `settledAt` is **BACEN's** instant (the SPI's `recordedAt`), not ours: the money moved on the rail, and
  reconciliation (step 35) compares the two systems on exactly that fact.
- **`REVERSED` is the failure-branch twin of `SETTLED`** (step 33): a permanent BACEN refusal reverses the
  payment — settlement-service posts a compensating `debit clearing / credit payer` (`entryType=PIX_REVERSAL`,
  `txId=<orig>-rev`) through ledger-service, releases the daily-limit reservation, and writes this guarded
  transition + `PixReversed` in one `TransactWriteItems`. It stamps `failureReason` (BACEN's refusal code)
  and — like a settlement — is idempotent: a redelivery finds it already `REVERSED` and the guard refuses.
  On the **success** branch a settlement additionally posts a `CLEARING_RELEASE` (`debit clearing / credit
  SPI_SETTLED`, `txId=<orig>-rel`) so the clearing balance nets to zero; both postings are idempotent by
  their deterministic `txId`, so they run before the guarded status transition without ever double-moving
  money. **Step 35 widened the reversal guard** from strictly `SENT_TO_SPI` to *either* stuck state
  (`DEBITED OR SENT_TO_SPI`): the reconciliation resolver reverses a send whose settlement was never
  attempted and still sits at `DEBITED`, whose money has been parked in clearing since acceptance all the
  same, so reversing from `DEBITED` is as money-correct as from `SENT_TO_SPI`. The guard still refuses any
  terminal state, so a `SETTLED` transaction is never dragged to `REVERSED`.
- `creditorIspb` is the participant that received the money, written only when the rail reported one. It
  is the external counterpart of `creditorAccountId` — an external payee has no account here.
- `gsi2pk`/`gsi2sk` move with **every** transition, or a finished payment would keep showing up in the
  stuck-transaction scan forever.
- The first transition accepts an item **already** in `SENT_TO_SPI` (re-claiming a retry is not a
  regression) but never one outside those two states — dragging a `SETTLED` transaction back onto the rail
  would send the same money twice.

`creditorInternal` is written on **every** transaction (step 27), internal ones included — `true` when
the destination key resolved inside PlatinumCoin, `false` when it belongs to another PSP. A boolean has
no "absent" state, and the settlement/reconciliation reads that filter on it must not silently miss
items that merely lack the attribute; items written before step 27 were all internal sends, so a reader
treats a missing flag as "internal" (the presence of `creditorAccountId`).

The `fraud*` fields are written on **every** send that reaches the fraud stage — internal ones included,
since fraud scoring sits in the shared send path between the limit check and the debit (step 25,
ADR-0005). `fraudDecision` is `APPROVE`/`REVIEW`, or `SKIPPED` when the 200ms check timed out or errored
and the send failed open; it is written only when set. `fraudSkipped` is a boolean shorthand (`true` iff
skipped) and is **always** written on a scored send — a boolean has no "absent" state. A `DENY` never
reaches the item: it becomes `422 FRAUD_DENIED` before the transaction is written.

**Outbox item (same table, same partition as its transaction):**
```json
{
  "pk": "TX#tx-9f1c",
  "sk": "OUTBOX#evt-7a2b",
  "eventId": "evt-7a2b",
  "eventType": "PixDebited",
  "payload": "{\"amountCents\":12550,\"txId\":\"tx-9f1c\", ...}",
  "occurredAt": "2026-07-02T12:34:56.789Z",
  "correlationId": "3f9a...",
  "gsi3pk": "OUTBOX#UNPUBLISHED",
  "gsi3sk": "2026-07-02T12:34:56.789Z"
}
```

**The write (step 28).** The `META` item and its `OUTBOX#` siblings are written in **one
`TransactWriteItems`** — never two writes. Persisting the state and announcing it are two systems, so a
crash between them either loses the event (for an external send: money parked in `SPI_CLEARING` that no
settlement flow will pick up) or announces a payment that never committed. Because the outbox items sit
in the transaction's own partition, the store commits both atomically and the dual-write window does not
exist. The `META` put is guarded by `attribute_not_exists(pk)`: a create never overwrites a transaction
already on record, so no late or replayed write can regress a status a later step advanced (a `SETTLED`
payment reset to `DEBITED` would be settled twice). When the guard fires, **nothing** is written — the
outbox items roll back with it.

**Which events an accepted send writes** (`PixOutboxEvents`, payment-service): an **external** send
announces `PixDebited` (the trigger the settlement-queue's filter policy subscribes to); an **internal**
send announces `PixSettled` — the atomic posting was the settlement, and `PixDebited` would ask BACEN to
settle a transfer that never left the bank; a **fail-open fraud skip** (ADR-0005) adds a second
`FraudCheckSkipped` item to the same transaction, so "an unscored payment was let through" is as durable
as the payment. Money in a payload is always integer cents.

**And which events a settlement writes** (`SettlementOutboxEvents`, settlement-service, step 31): a
confirmed external settlement announces `PixSettled` too — the same event type, so consumers never have to
learn where the payee banks in order to know a payment completed. The payload differs only in the facts
that genuinely differ: an internal `PixSettled` carries `creditorAccountId`, an external one carries
`creditorIspb`. On a permanent refusal it instead writes **`PixReversed`** (step 33, carrying
`failureReason`) — the same publish path, announcing the failure branch of the funnel so notification and
audit act on it. The item is byte-identical in shape to payment-service's, which is what lets **one**
publisher drain the whole sparse index: `gsi3` is a property of the table, not of the writer.

`correlationId` carries the request's id into the asynchronous half, so one `grep` still reconstructs the
whole path once the flow leaves the request thread (ADR-0012); it is absent for an event minted outside a
request. `payload` is an **opaque JSON string** — DynamoDB never queries inside it, so a new event type
needs no schema change and the publisher forwards it without parsing it.

> **`occurredAt` is fixed-width milliseconds** (`uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`), never
> `Instant.toString()` — the same trap as the ledger's entry timestamps, and for the same reason: it is
> the **sort key** the publisher drains oldest-first, so an event on a round second would render
> `12:34:30Z`, sort *after* one 500 ms later (`'Z'` 0x5A > `'.'` 0x2E), and silently invert the drain
> order.

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

- **Reserve** (before any money moves): `UpdateItem ADD usedCents :amount` with `ConditionExpression: attribute_not_exists(usedCents) OR usedCents <= :limitMinusAmount` (the account's `dailyLimitCents` is read from account-service first; the comparison value is computed client-side because condition expressions cannot do arithmetic). Condition fails ⇒ `422 LIMIT_EXCEEDED`. **First-send guard:** on the day's first send the item does not exist, so `attribute_not_exists(usedCents)` is true and the condition alone would wave through *any* amount — even one larger than the whole limit. So when `dailyLimitCents - amountCents < 0` (the amount alone exceeds the limit) the send is denied **before** the counter is touched, in application code; the conditional `ADD` then only ever governs accumulation. `expiresAt` (~48h epoch seconds) is written on every reserve/release; TTL is enabled on `pix_transactions` for it (only `LIMIT#` items carry `expiresAt`, so transaction/outbox items are never reaped).
- **Release** (fraud-deny, insufficient funds, reversal): `ADD usedCents -:amount` — a rejection returns exactly what it reserved.
- Window: **calendar day** (America/Sao_Paulo), matching how Pix limits are communicated to users; TTL (~48h) cleans past days.

**Statement-export request item (same table, step 53):** the async cold-export resource. GSI1's key attributes are plain strings, so export items reuse it with an `ACCOUNT#` value — the single-table idiom of one index serving multiple item types.

```json
{
  "pk": "EXPORT#exp-4c2a",
  "sk": "META",
  "gsi1pk": "ACCOUNT#acc-001",
  "exportId": "exp-4c2a",
  "accountId": "acc-001",
  "status": "PENDING",
  "fromMonth": "2025-01", "toMonth": "2025-03",
  "downloadKey": null,
  "requestedAt": "2026-07-07T12:00:00Z"
}
```

Lifecycle `PENDING → READY | FAILED` via guarded transitions (a redelivered queue message cannot double-produce artifacts); see step 53.

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
| TTL | attribute `expiresAt` (epoch seconds, +7 days) |

```json
{
  "pk": "CONSUMER#settlement-service#EVT#evt-7a2b",
  "sk": "META",
  "consumer": "settlement-service",
  "eventId": "evt-7a2b",
  "processedAt": "2026-07-02T12:34:57.031Z",
  "expiresAt": 1752150897
}
```

If the conditional put fails → duplicate → ack the message and skip. This one small table is what makes "at-least-once + idempotent consumer = effectively-once" real across the whole platform. Created by `infra/localstack/init/07-processed-events.sh`; the shared implementation is `common-lib`'s `ProcessedEventStore.markProcessed(consumer, eventId)` (step 29).

The consumer name is part of the **key**, not an attribute: settlement, notification and audit all consume the same event and each must see it exactly once — a shared key would let whichever consumed first silently starve the others.

> **A claim is not yet a completion (step 31).** The record is written *before* the side effect — the only ordering under which two concurrent deliveries cannot both proceed — so what it really means is *"I am handling this"*. Only a completed side effect turns it into *"this is done"*; a consumer whose work failed calls `ProcessedEventStore.release(consumer, eventId)` and deletes it, so the redelivery is real work instead of being deduped away. Without the release, a failed settlement would disarm SQS's entire retry mechanism: the message returns, the gate answers "already processed", the consumer acks, and the payment never settles. The failure direction is chosen: a crash between a *failed* side effect and its release leaves a stale claim, that delivery is skipped, and the transaction is left for the reconciliation loop of ADR-0003 to close within 5 minutes — losing a retry to a safety net beats letting two workers settle one Pix.

> **TTL, and which way it is safe to be wrong.** DynamoDB deletes expired items lazily, so an expired-but-still-present record keeps answering "duplicate" — the consumer *skips* a side effect rather than repeating one. That is the opposite of `pix_idempotency` (§5), where a read must treat an expired-but-present record as absent. The asymmetry is deliberate: here a false "duplicate" costs a skipped notification, while a false "new" could pay twice. Seven days is chosen to outlive every redelivery window that can still produce a duplicate (SQS retention, the DLQ, and the reconciliation loop of step 35 all close far sooner).

---

## 7. Capacity & local settings

- All tables **on-demand** (PAY_PER_REQUEST) — no capacity planning locally, matches the auto-scaling NFR in prod.
- No DynamoDB Streams used (polling outbox — ADR-0004); GSI3 on `pix_transactions` is sparse.
- Init scripts and exact `aws dynamodb create-table` commands live in `infra/localstack/init/` and are mirrored in `docs/local-dev.md`. They are added **incrementally, per sprint** (vertical delivery — see `PLAN.md`): accounts/keys in step 07, ledger in step 12, transactions/idempotency in step 17, `pix_processed_events` in step 29 — so at any point only the tables the built flows need exist.
