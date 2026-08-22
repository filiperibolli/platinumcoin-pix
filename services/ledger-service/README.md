# ledger-service

> The **ledger** of the PlatinumCoin Pix platform — the single owner and the only writer of the
> `pix_ledger` table. Step 13 delivered the read half (the data model plus a **strongly consistent**
> balance read); step 14 adds the write half: the **atomic double-entry posting**. The invariant suite
> lands in step 15 and the paginated statement in step 16.

- **Port:** `8085`
- **Depends on:** `common-lib` (error model, correlation-id log pattern, JWT validation)
- **Infra:** LocalStack (DynamoDB, table `pix_ledger`) — created by the step-12 init script and seeded
  with the platform's money supply by `infra/localstack/init/05-seed-ledger.sh`; **Redis** (step 40) as
  a *soft* dependency used only to delete the `balance:` keys a posting made stale — postings commit
  and succeed with Redis down

## Why it exists

Money correctness is the one non-negotiable of this platform, so exactly one service is allowed to
touch the ledger table (ADR-0006). Everything else — payment-service, reconciliation, the balance
cache — asks *this* service, which means the invariants (never a negative balance, never a debit
without its credit, never the same posting twice) live in one place and cannot be routed around.

This step deliberately reads before it writes: mapping the domain model onto the **already seeded**
table proves the model against real items while nothing is at stake, so step 14's first
`TransactWriteItems` is not also the first time the shape of an item is tested.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `GET` | `/internal/ledger/accounts/{accountId}/balance` | Bearer | Balance of one ledger account → `{accountId, balance, balanceCents, version}`. Strongly consistent read. |
| `POST` | `/internal/ledger/postings` | Bearer | Atomic double-entry posting → `{txId, debitAccount, creditAccount, amount, amountCents, entryType, description, postedAt, replayed}`. Idempotent by `txId`. |
| `GET` | `/internal/ledger/accounts/{accountId}/entries?cursor=&limit=` | Bearer | Statement page, newest first → `{entries:[...], nextCursor}`. Opaque base64 `LastEvaluatedKey` cursor; `limit` clamped (default 20, max 100). |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |
| `GET`  | `/actuator/prometheus` | public | Micrometer scrape surface — what Prometheus polls every 10s (step 44). Metric catalog: `docs/observability.md` |

| Outcome | Status | `code` |
| ------- | ------ | ------ |
| posted, or replayed under the same `txId` | `200` | — (`replayed` says which) |
| debtor short of funds | `422` | `INSUFFICIENT_FUNDS` |
| not a posting (amount ≤ 0, blank id, both legs on one account) | `422` | `INVALID_POSTING` |
| `txId` already used for different money | `409` | `POSTING_TXID_MISMATCH` |
| either account has no BALANCE item | `404` | `LEDGER_ACCOUNT_NOT_FOUND` |
| lost to concurrent writers past the retry budget | `503` | `LEDGER_CONFLICT` |
| body fails bean validation | `400` | `VALIDATION_ERROR` |
| statement cursor malformed or from another account | `400` | `INVALID_CURSOR` |

No token ⇒ `401` (`code: UNAUTHORIZED`) — this service has **no public surface at all**:
`/internal/**` is deliberately absent from `jwt.public-paths`, and there is no `/v1` API because no
end user talks to the ledger; payment-service does, on their behalf.

### The balance read (step 13)

- **`ConsistentRead=true`, always.** DynamoDB reads are eventually consistent by default (they cost
  half as much), but the ledger has to **read its own writes**: a balance is queried right after a
  posting, and a stale answer would show money that is already spent. The flag is asserted on the
  request itself in `DynamoLedgerRepositoryTest` — LocalStack is a single node and would return the
  right value either way, so only the request shape can prove it. It also explains why the balance
  lives at a base-table key: a GSI is *always* eventually consistent.
- **Both money representations on the wire.** `balance` is a decimal BRL string (`"10000.00"`) for
  the human running the runbook curl; `balanceCents` is the same amount as an integer for the
  services that do arithmetic on it (payment-service in step 21, the cache in step 40). Inside the
  domain it is only ever a `long` — `BalanceResponse` is the single place that formats.
- **`version` is a change counter, not a lock.** It is bumped by every posting and is there for audit
  and debugging. Nothing reads it, decides, and writes back conditioned on it: conflicting writers are
  serialized by DynamoDB transactions themselves (ARCHITECTURE §6.3). The version-as-optimistic-lock
  strategy is implemented for contrast in the relational lab (ADR-0009, step 50).
- **Not account-scoped, on purpose.** The account comes from the path, because this is an internal
  seam: payment-service reads a payee's balance, reconciliation reads `SPI_CLEARING`. The rule that
  "the debited account comes from the JWT" binds the money-moving endpoint in payment-service, which
  is where a client can name an account. Nothing here moves money.
- **404, never a zero.** An account with no BALANCE item is a hard miss. If it answered `0`, "this
  account does not exist" would be indistinguishable from "this account has no money" — opposite
  facts in a ledger.

### The double-entry posting (step 14)

One `TransactWriteItems`, five items, all-or-nothing (`docs/data-model.md` §3, ARCHITECTURE §6.3):

| # | Write | Condition |
| - | ----- | --------- |
| 1 | debit `BALANCE` (`- :amount`, `version + 1`) | `attribute_exists(pk) AND balanceCents >= :amount` |
| 2 | credit `BALANCE` (`+ :amount`, `version + 1`) | `attribute_exists(pk)` |
| 3 | `ENTRY#<ts>#<txId>` on the debtor (amount **negative**) | `attribute_not_exists(pk)` |
| 4 | `ENTRY#<ts>#<txId>` on the payee (amount **positive**) | `attribute_not_exists(pk)` |
| 5 | `TX#<txId> / POSTING` — the idempotency guard | `attribute_not_exists(pk)` |

- **The guards are conditions of the write, never a prior read.** `balanceCents >= :amount` is
  evaluated by DynamoDB as part of the debit, so no concurrent posting can slip between the check and
  the subtraction. A read-then-check would be the same code with a race in it.
- **Idempotency is keyed on the `txId` alone** (write 5), and that is not a detail: an entry's key
  carries its timestamp, so a caller retrying after a timeout would write a *different* key, pass
  `attribute_not_exists`, and be debited twice. Same money under the same `txId` ⇒ `200` with
  `replayed: true` and the **original** `postedAt`; different money ⇒ `409`. `description` is excluded
  from that comparison — a label is not money.
- **`ReturnValuesOnConditionCheckFailure=ALL_OLD`** on writes 1, 2 and 5 is what makes the failures
  distinguishable: the cancelled debit comes back with the balance item (⇒ 422, and by how much it
  fell short) or without one (⇒ 404), and the cancelled guard comes back with the committed posting,
  so the replay verdict costs no extra read and is strongly consistent.
- **Reasons are read guard-first.** A replay that would *also* now be short of funds is still a
  replay — the money it names moved when it first committed.
- **`TransactionConflict` is contention, not a rule violation:** retried 3× with jittered backoff,
  then `503`. Nothing was written, and the caller may safely re-send the same `txId` — which is
  exactly what idempotency buys.
- **Both accounts are explicit inputs.** The ledger never infers a side. That is the seam step 52
  needs to shard `SPI_CLEARING` without touching a caller, and the reason the "debited account comes
  from the JWT" rule binds payment-service, not this service.

### The statement (step 16)

`GET /internal/ledger/accounts/{id}/entries?cursor=&limit=` → `{entries:[...], nextCursor}`, newest
first. The internal seam the public statement API (step 41) will proxy.

- **Newest-first is free.** The sort key is `ENTRY#<isoTimestamp>#<txId>`, so
  `Query begins_with(sk,"ENTRY#")` with `ScanIndexForward=false` is already reverse-chronological —
  no sort in DynamoDB or in memory. (This is exactly why the posting writes a *fixed-width*
  millisecond timestamp: lexicographic order must equal chronological order.)
- **Cursor pagination the DynamoDB way.** There is no offset in DynamoDB; the cursor is the base64 of
  the query's `LastEvaluatedKey`, **opaque** to the client, and `nextCursor` is `null` on the last
  page. The client only ever echoes it back.
- **A cursor is validated on decode.** The token embeds the partition key (`ACCOUNT#<id>`); the
  adapter refuses one that is malformed *or* names another account (`400 INVALID_CURSOR`), so a forged
  cursor can never page a partition the caller did not ask for. The cross-account guard lives in the
  adapter because only it decodes the AWS key.
- **`limit` is policy, in the use case.** Default 20, ceiling 100, floored at 1 — the controller does
  no clamping and no cursor parsing.
- **Money at the edge.** Each entry ships `amount` (signed decimal string, `"-125.50"` on a DEBIT) and
  `amountCents` (the signed integer), the same one-`long`-formatted-once discipline as the balance.

### Cache invalidation on write (step 40, ADR-0008)

After a posting commits, the ledger **deletes** the cached balances of both legs —
`DEL balance:<debit> balance:<credit>` in Redis, one variadic round-trip. That is the only thing this
service does with Redis: it holds a client to *invalidate*, never to read. A balance the ledger itself
needed would come from the strongly-consistent `GetItem` above; a cached one would be a lie waiting to
happen.

- **After the commit, not before.** Evicting first opens a window where a concurrent reader misses,
  reads the still-pre-commit balance and repopulates the cache with the old number — and nothing
  invalidates it a second time, so the stale value survives a whole TTL.
- **Best-effort, and that is a decision.** The money is already durable. Turning a Redis outage into a
  failed posting would trade ≤5s of display staleness for a caller who believes nothing happened when
  the debit in fact landed. So the failure is swallowed at WARN, and payment-service's short TTL is the
  backstop. (A replay evicts too — the original commit's `DEL` may have been the one that was lost.)
- **A refused posting evicts nothing.** No money moved, so no cached balance became stale; evicting
  anyway would let a client blow the hit rate away with malformed requests.
- **The cache has no vote on money.** `balanceCents >= :amount` is a condition expression *inside* the
  `TransactWriteItems`. `BalanceCacheInvalidationIT#aStaleCacheDoesNotAuthorizeAnOverdraft` stuffs Redis
  with a balance ten times the real one and still gets `422 INSUFFICIENT_FUNDS`.

### System accounts

`ACCOUNT#SEED` (the funding source, negative by construction) and `ACCOUNT#SPI_CLEARING` (money in
flight to/from BACEN, `0` at rest) are ordinary partitions and are read like any other. They are
exempt from the `balanceCents >= :amount` guard on **writes** (step 14), never from being read. Their
existence is what makes Σ over every account equal **zero** — the conservation invariant step 15
asserts under a concurrent debit storm, and which `DynamoLedgerRepositoryIT` pins at its baseline.

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/    InternalLedgerController (/internal/ledger/accounts/{id}/balance + /entries),
        InternalPostingController (POST /internal/ledger/postings),
        PostingRequest / PostingResponse, BalanceResponse (cents → decimal string),
        StatementResponse / StatementEntry (page + cents → signed decimal string),
        LedgerExceptionHandler (domain exception → problem+json),
        StatementArchiver (@Scheduled cold-archive run, step 43)                  (inbound adapters)
domain/model/     Balance, LedgerEntry, StatementPage, PostingCommand, PostingResult (records),
                  ArchivedEntry (one line of the cold archive, step 43),
                  Direction (enum)                                                    (plain Java)
domain/port/      LedgerRepository, BalanceCacheInvalidator (deletes only — it cannot read a
                  balance, so it cannot be misused to decide one),
                  LedgerArchiveReader (read-only bulk view) + StatementArchive
                  (write-only, step 43 — neither can delete an entry)          (outbound interfaces)
domain/exception/ LedgerAccountNotFound / InsufficientFunds / InvalidPosting /
                  PostingConflict / LedgerBusy / InvalidCursor exceptions             (plain Java)
domain/service/   AccountPolicy (system vs customer account rules)                     (plain Java)
domain/usecase/   GetBalanceUseCase, PostDoubleEntryUseCase, GetStatementUseCase,
                  ArchiveOldEntriesUseCase + ArchiveOutcome (step 43)                  (plain Java)
infra/persistence/ DynamoLedgerRepository (AWS SDK — the transaction, its conditions and the
                   reading of cancellationReasons live here and nowhere else),
                   RedisBalanceCacheInvalidator (one DEL of the two `balance:` keys, step 40),
                   DynamoLedgerArchiveReader (BALANCE scan + cold-entry key range, step 43),
                   S3StatementArchive (account=<id>/yyyy-MM.jsonl, step 43)
infra/config/      DynamoConfig, S3Config, SchedulingConfig,
                   LedgerBeansConfig (composition root, incl. the Clock),
                   AwsProperties, CorsConfig                                (outbound adapter + wiring)
```

`Clock` is injected rather than read as `Instant.now()`: the posting's instant becomes part of both
ENTRY sort keys, so it is a value the ledger decides — and a key a test can assert.

**The statement cold archive (step 43).** ledger-service owns `pix_ledger`, so it is the service that
archives it — no other service may read the table (ADR-0006), and archiving needs none of the atomicity
that justifies that ADR's two documented exceptions. An hourly job copies entries older than
`pix.archive.hot-window-days` into S3 `pix-statement-archive`, one JSONL object per account and month,
rewritten whole (the archive is *derived* data, so a rewrite is idempotent and lets the boundary month
grow as the window rolls). **Nothing is deleted from the ledger**: production would remove the archived
entries after verifying the object, and skipping that locally means no code path in this service can
delete a posting — safety rule 5 held by construction rather than by care. The two ports enforce it:
`LedgerArchiveReader` only reads, `StatementArchive` only writes.

Two ArchUnit rules in `LedgerArchitectureTest` fail the build on a violation: `domain/` imports
nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson), and `api/` never depends on an
interface in `domain/` — which is what makes "a controller may not reach the ledger table"
mechanical rather than a review habit.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must equal auth-service's. This service only **validates** tokens. |
| `jwt.public-paths` | `/actuator/**` | Paths the shared `JwtAuthFilter` skips. `/internal/**` is **not** here — every balance read requires a token. |
| `AWS_ENDPOINT_URL` / `aws.endpoint-url` | `http://localhost:4566` | LocalStack edge (**S3**, for the cold archive — DynamoDB has its own endpoint below); compose overrides to `http://localstack:4566`. |
| `AWS_REGION` / `aws.region` | `us-east-1` | SDK region. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds LocalStack ignores but the SDK demands. |
| `REDIS_HOST` / `REDIS_PORT` (`spring.data.redis.*`) | `localhost` / `6379` | Redis, used **only** to delete `balance:<accountId>` after a posting (step 40); compose overrides the host to `redis`. A Redis outage costs display freshness (bounded by payment-service's 5s TTL), never a posting. |
| `STATEMENT_ARCHIVE_BUCKET` / `pix.archive.bucket` | `pix-statement-archive` | The cold archive (step 43) — a deliberately **plain** bucket (no versioning, no Object Lock): derived, rebuildable data whose monthly object each run rewrites whole. |
| `STATEMENT_ARCHIVE_HOT_WINDOW_DAYS` / `pix.archive.hot-window-days` | `90` (**`0` in compose**) | Where the online statement ends and the archive begins. Compose uses `0` so a freshly seeded sandbox archives something instead of looking broken. |
| `STATEMENT_ARCHIVE_DELAY_MS` / `pix.archive.fixed-delay-ms` | `3600000` (**`60000` in compose**) | How often the job runs — hourly batch work over cold data; every minute in the sandbox so a demo does not wait an hour. |
| `STATEMENT_ARCHIVE_MAX_ACCOUNTS` / `pix.archive.max-accounts-per-run` | `500` | Per-run bound on the account **scan**; a larger ledger degrades into more runs, not one enormous one. |
| `PIX_SCHEDULERS_ENABLED` / `pix.schedulers.enabled` | `true` | Master switch for background jobs (today: the cold-archive job); ITs set it `false` and drive a run explicitly. |

## Run

```bash
# from repo root — build the jar first
mvn -pl services/ledger-service -am clean package

# LocalStack must be up (provides DynamoDB + the seeded pix_ledger money supply)
docker compose -f infra/docker-compose.yml up -d localstack

# then either run standalone…
java -jar services/ledger-service/target/ledger-service-0.0.1-SNAPSHOT.jar
# …or via compose (builds the image, waits on localstack healthy)
docker compose -f infra/docker-compose.yml up -d --build ledger-service
```

## Test

```bash
mvn -pl services/ledger-service -am verify     # unit (*Test) + integration (*IT, Testcontainers)
# NOTE the -am: LocalStackTestBase ships in common-lib's test-jar, and a stale one in ~/.m2 fails as
# `501 Service 's3' is not enabled` or a hang on the readiness wait (docs/local-dev.md §6).

# happy path (needs a token; mint one from auth-service)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# alice's seeded balance → {"accountId":"acc-001","balance":"10000.00","balanceCents":1000000,"version":0}
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq

# the system accounts — SEED is negative by construction, and the four balances sum to zero
curl -s localhost:8085/internal/ledger/accounts/SEED/balance -H "Authorization: Bearer $TOKEN" | jq
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance -H "Authorization: Bearer $TOKEN" | jq

# unknown account ⇒ 404 LEDGER_ACCOUNT_NOT_FOUND (not a zero balance)
curl -s localhost:8085/internal/ledger/accounts/acc-999/balance -H "Authorization: Bearer $TOKEN" | jq

# no token ⇒ 401 UNAUTHORIZED, even on /internal/**
curl -si localhost:8085/internal/ledger/accounts/acc-001/balance | head -1

# ── the posting (step 14) ────────────────────────────────────────────────────
POSTING='{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002",
          "amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}'

# alice → bob, R$ 125.50 ⇒ 200 with "replayed": false
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$POSTING" | jq

# the very same call again ⇒ 200 with "replayed": true, and the balance does NOT move twice
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$POSTING" | jq
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq
# → 9874.50, once — not 9749.00

# same txId, different amount ⇒ 409 POSTING_TXID_MISMATCH (the ledger refuses to guess)
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":99,"entryType":"PIX_INTERNAL"}' | jq

# more than the balance ⇒ 422 INSUFFICIENT_FUNDS, and nothing is written
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-2","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":99999999,"entryType":"PIX_INTERNAL"}' | jq

# ── the statement (step 16) ──────────────────────────────────────────────────
# newest-first page of acc-001's entries → {"entries":[...],"nextCursor":...}
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=5" \
  -H "Authorization: Bearer $TOKEN" | jq

# follow the pages: take .nextCursor from the response above and pass it back (opaque)
CURSOR=$(curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=5" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.nextCursor // empty')
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=5&cursor=$CURSOR" \
  -H "Authorization: Bearer $TOKEN" | jq

# tampered cursor ⇒ 400 INVALID_CURSOR (a cursor from another account is refused the same way)
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?cursor=not-a-valid-cursor" \
  -H "Authorization: Bearer $TOKEN" | jq
```

> **Local Docker note:** the Docker Engine API version Testcontainers speaks is **pinned in the
> parent POM** (`docker.api.version`, default `1.44`) — a plain `mvn verify` works, no flag. If you
> are on an engine older than API 1.44, override it: `mvn verify -Ddocker.api.version=1.41`
> (see `docs/local-dev.md` §6).

### The cold archive by hand (step 43)

```bash
# The compose stack runs the job every minute with a ZERO-day hot window, so any posting is archived.
alias awsl='aws --endpoint-url=http://localhost:4566'

awsl s3 ls s3://pix-statement-archive/ --recursive
# account=acc-001/2026-08.jsonl

awsl s3 cp s3://pix-statement-archive/account=acc-001/2026-08.jsonl - | jq -c .
# {"accountId":"acc-001","txId":"tx-…","direction":"DEBIT","amountCents":-12550,…}
#  ↑ signed integer cents: the archive is an internal artefact, not an API edge

# The point of the feature: NOTHING left the ledger — the online statement still answers in full.
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=50" \
  -H "Authorization: Bearer $TOKEN" | jq '.entries | length'
```

## Related decisions

- [ADR-0001](../../docs/adr/0001-dynamodb-for-the-ledger.md) — DynamoDB for the ledger: the posting is
  one `TransactWriteItems`, and the guards are conditions inside it.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition; the ledger
  owns `pix_ledger`, so every other service reads it through this API, never through the table.
- [ADR-0008](../../docs/adr/0008-redis-balance-cache.md) — the Redis balance cache: this service owns
  the **invalidation** half (post-commit, best-effort), and is deliberately not allowed to read it.
- [ADR-0009](../../docs/adr/0009-relational-ledger-counterpart-lab.md) — the PostgreSQL counterpart lab
  that implements the same ledger with pessimistic and optimistic locking, for contrast.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`, and **amounts
  in cents and account ids logged in the clear** — a deliberate LGPD trade-off for seeded data that
  production reverses. `com.platinumcoin.pix` runs at DEBUG, so the DynamoDB `GetItem` and the exact
  key it read are in the log too.
