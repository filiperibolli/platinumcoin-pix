# LocalStack init scripts (`ready.d`)

Files here are mounted into the LocalStack container at
`/etc/localstack/init/ready.d` and run **once the emulator is ready** (see the
`localstack` service in `../../docker-compose.yml`). LocalStack executes any
executable `*.sh` in this directory in lexical order, so numeric prefixes
(`01-...`, `02-...`) pin the ordering.

## What lives here

- **`01-dynamodb-accounts.sh`** (step 07) — creates the account-domain tables
  `pix_accounts` and `pix_keys`, each with its `gsi1` index, on-demand billing.
  Idempotent (`describe-table || create-table`). Neither table uses TTL — TTL is
  only on `pix_idempotency` / `pix_processed_events` (arriving in later sprints);
  see `docs/data-model.md`.
- **`02-dynamodb-ledger.sh`** (step 12) — creates `pix_ledger` (PK `ACCOUNT#<id>`,
  SK `BALANCE` | `ENTRY#<ts>#<txId>`) with the sparse `gsi1` on `TX#<txId>` that
  returns both legs of a posting. Idempotent, on-demand, no TTL.
- **`03-dynamodb-payment.sh`** (step 17) — creates the payment-service tables:
  `pix_transactions` (PK `TX#<txId>`, SK `META` | `OUTBOX#<eventId>`) with three
  GSIs — `gsi1` on `E2E#<endToEndId>`, `gsi2` on `STATUS#<status>`+`updatedAt`, and
  the **sparse** `gsi3` on `OUTBOX#UNPUBLISHED`+`occurredAt` (the outbox publisher's
  work queue) — and `pix_idempotency` (PK `IDEM#<accountId>#<key>`, SK `META`) with
  **TTL on `expiresAt`**. All three GSIs are created now even though only some are
  used this sprint: GSIs (unlike LSIs) *can* be added later, but backfilling a fat
  table is slow, and the key schema is already fully designed. No seed rows —
  transactions are created by the flow (steps 18–21) — so numbering it `03-` keeps
  it before the seeds and leaves the harness's readiness marker on `05`. Idempotent,
  on-demand.
- **`04-seed-accounts.sh`** (step 07) — seeds demo accounts alice (`acc-001`) and
  bob (`acc-002`) with `dailyLimitCents=500000`, `status=ACTIVE`. No Pix keys are
  seeded — they're registered via the account-service API in step 10.
- **`05-seed-ledger.sh`** (step 12) — seeds the money supply as a double-entry
  funding operation: alice and bob at 1,000,000 cents each, `ACCOUNT#SEED` at
  −2,000,000 (the counterpart legs), `ACCOUNT#SPI_CLEARING` at 0 → **Σ = 0**, the
  baseline of the conservation invariant (step 15). Unlike the account seed,
  every put is conditional on `attribute_not_exists(pk)`: re-running it against a
  table that already holds moved money must never reset a balance while its
  `ENTRY` items survive.
- **`06-messaging-core.sh`** (step 26) — the messaging backbone, the project's first
  asynchronous infrastructure: SNS topic `pix-events`, `settlement-queue` and its
  `settlement-queue-dlq`, and the SNS→SQS subscription. The queue carries a
  **redrive policy** (`maxReceiveCount=5` → DLQ), `VisibilityTimeout=30s` (must
  exceed the 12s SPI call of step 31; it doubles as the retry backoff in step 32),
  `ReceiveMessageWaitTimeSeconds=20` (long polling) and an explicit `Policy`
  allowing only `pix-events` to `sqs:SendMessage` — the API, unlike the console,
  does not add that for you, and without it delivery fails *silently*. The
  subscription is created **guarded** (no duplicate on restart) with
  `FilterPolicy={"eventType":["PixDebited"]}` and `RawMessageDelivery=true`.
  Asserted by `MessagingInitIT` in common-lib (resources, redrive, and a real
  publish→receive proof of the filter policy + raw delivery).
- **`07-processed-events.sh`** (step 29) — the consumer-side dedup table
  `pix_processed_events` (`pk=CONSUMER#<name>#EVT#<eventId>`, `sk=META`, TTL 7d
  on `expiresAt`), the one table shared by every consumer (ADR-0006). No GSI —
  the only access pattern is a conditional `PutItem` on the primary key.
  Idempotent (`describe-table || create-table`, TTL guarded).
- **`08-messaging-notify.sh`** (step 36) — the **second** consumer off
  `pix-events`: `notification-queue` + `notification-queue-dlq`, same tuning as
  settlement (redrive after 5 receives, visibility 30s, long-poll 20s, DLQ
  retention 14d) and the same narrow `sqs:SendMessage` policy. Its subscription
  is **guarded** with `FilterPolicy={"eventType":["PixSettled","PixReceived","PixReversed"]}`
  (the user-facing outcomes only — disjoint from settlement's `PixDebited`) and
  `RawMessageDelivery=true`. Asserted by `MessagingInitIT` (queue + DLQ + the filter
  policy).
- **`09-audit.sh`** (step 42) — the **third** consumer off `pix-events` plus the
  project's first object storage. `audit-queue` + `audit-queue-dlq` are tuned like
  the other two branches, but the subscription carries **no filter policy at all**:
  audit does not *act* on events, it *records that they happened*, and any filter is
  a list somebody must remember to extend — the first unlisted event type would be
  missing from the trail silently, forever. The script therefore also *removes* a
  filter policy if one drifted in (empty `--attribute-value`), because converging to
  "none" has to be an action. Two buckets: **`pix-audit-log`** created with
  `--object-lock-enabled-for-bucket` (create-time-only in real AWS; it turns
  versioning on) + a default retention of **COMPLIANCE / 1825 days** — COMPLIANCE
  rather than GOVERNANCE because the latter is bypassable by a principal holding
  `s3:BypassGovernanceRetention`, i.e. the privileged operator an audit trail exists
  to keep honest; and **`pix-statement-archive`**, a deliberately *plain* bucket (no
  versioning, no lock) because it holds derived, rebuildable data that step 43
  rewrites monthly. It **used** to be the last script, and therefore carried the
  readiness marker; step 53 appended `10-statement-exports.sh` and moved both markers
  there. Asserted by
  `MessagingInitIT` (the unfiltered third branch) and `S3InitIT` (both buckets,
  versioning, lock configuration, and the refused delete).
  **LocalStack vs AWS:** LocalStack 3 does more than accept the Object Lock
  configuration — it *enforces* it (deleting a retained version answers
  `AccessDenied`, which `S3InitIT` pins). What stays AWS-only is everything below the
  API: WORM at the storage layer, surviving `docker compose down -v` (the emulator's
  state is ephemeral), replication of the trail, and IAM actually denying anything
  (ADR-0013).

Each later sprint that flips on a new AWS service adds its own resource script in
the same directory, matching the vertical-delivery discipline (one flow's infra
at a time). The exact `create-table` commands are mirrored in `docs/local-dev.md`.

## Convention

- Name `NN-<purpose>.sh`, executable, idempotent (safe to re-run on restart).
- Use the in-container endpoint `http://localhost:4566` (the script runs *inside*
  the LocalStack container) with the dummy AWS credentials from `../../.env.example`.
- Table names follow the `pix_*` convention (see `docs/data-model.md`).
- **Messaging names** (set in step 26): one SNS topic, `pix-events`, for the whole
  platform — fan-out happens at the *subscription*, never by adding topics. One SQS
  queue **per consuming service**, named `<purpose>-queue`, and its dead-letter
  queue is the same name plus `-dlq` (`settlement-queue` / `settlement-queue-dlq`).
  Every queue has exactly one DLQ; queues carry a filter policy on the `eventType`
  message attribute so a consumer only ever receives the event types it handles.
- **Bucket names** (set in step 42): `pix-<purpose>` — same `pix` prefix as the tables,
  but **hyphenated**, because an S3 bucket name is a DNS label: lower-case, no
  underscores (`pix_audit_log` is simply not a legal bucket name).
- The LocalStack `SERVICES` list in `../../docker-compose.yml` is **enforced**, not a
  hint: a call to an unlisted service answers `501 Service '<x>' is not enabled`. A
  script that uses a new AWS service must land in the same change as the `SERVICES`
  entry that enables it — and as the matching `withServices(...)` in
  `LocalStackTestBase`, or every integration test in the repo hangs on the readiness
  wait while the script dies under `set -e`.


- **`10-statement-exports.sh`** (step 53) — the **fourth** consumer off `pix-events`,
  and the first one that is payment-service consuming its own publication.
  `statement-export-queue` + `statement-export-queue-dlq`, filtered to the single
  event type `StatementExportRequested` so a payment event never wakes the export
  worker, plus the plain bucket **`pix-statement-exports`**. Two numbers in it are
  deliberate and related: the queue's `VisibilityTimeout` is **120s**, four times the
  other queues', because assembling an export is the one legitimately slow piece of
  work in the platform (up to 24 archive objects read, merged and uploaded) and a 30s
  timeout would hand the same message to a second worker mid-run — safe, but wasteful
  and it would burn the attempt budget on deliveries that never failed. And
  `maxReceiveCount` is **5** while the worker gives up at **3**, so an ordinary
  failing export becomes a `FAILED` export the customer can read and *never* reaches
  the DLQ; what lands there is only what the worker could not parse or resolve, which
  is what makes `pix_statement_export_dlq_depth_messages > 0` a defect signal worth
  alerting on. **This is now the last script**, so its final log line
  (`[init] statement export ready: …`) is the readiness marker `LocalStackTestBase`
  waits on and the compose healthcheck probes its bucket
  (`s3api head-bucket --bucket pix-statement-exports`) — **append one that sorts after
  it and you must move both, or every integration test in the repo hangs for two
  minutes and fails with a startup timeout that says nothing about why.**
