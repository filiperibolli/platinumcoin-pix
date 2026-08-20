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
  `RawMessageDelivery=true`. Its final log line (`[init] notify messaging ready: …`)
  is the readiness marker the Testcontainers harness (`LocalStackTestBase`) waits
  on and the compose healthcheck probe asserts — **it is the last script, so if you
  append one that sorts after it, move both markers.** Asserted by `MessagingInitIT`
  (queue + DLQ + the filter policy).

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
- The LocalStack `SERVICES` list in `../../docker-compose.yml` is **enforced**, not a
  hint: a call to an unlisted service answers `501 Service '<x>' is not enabled`. A
  script that uses a new AWS service must land in the same change as the `SERVICES`
  entry that enables it — and as the matching `withServices(...)` in
  `LocalStackTestBase`, or every integration test in the repo hangs on the readiness
  wait while the script dies under `set -e`.
