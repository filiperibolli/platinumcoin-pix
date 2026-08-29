# PLAN — Implementation Roadmap (sprint- & flow-based)

This project is built as **vertical slices**, not horizontal layers. Each **sprint delivers one
complete, testable, documented flow** — the smallest end-to-end capability that a human can run
and demo — and brings up **only the infrastructure that flow needs**. No big-bang: infra rises
progressively, sprint by sprint (see the cumulative-infra diagram in `ARCHITECTURE.md` §6.0).

- One **step** = one small, verifiable increment with its own spec, tests and acceptance criteria.
- One **sprint** = one flow, ending in a runnable, demoable state; every flow is drawn as a
  Mermaid sequence diagram in `ARCHITECTURE.md` (Part II — §6). **One deliberate exception:** a
  *remediation* sprint (Sprint 11.5) delivers no new flow — it hardens flows that already exist, so it
  has no §6 section of its own and instead *amends* the diagrams of the flows it touches, each step
  naming which one it updates and doing so in its own commit.
- Ordering is **dependency-correct**: `ledger` before the first money-moving Pix; internal
  (synchronous) Pix before external (asynchronous) settlement.
- Work top-to-bottom; a step may only start when its prerequisites are checked.
- Rules of engagement: see [CLAUDE.md](CLAUDE.md).

> **Legend** — "Infra que sobe" = infrastructure that comes up **for the first time** in that sprint; it stays up afterwards.

---

## Sprint 1 — Foundation & Identity
**Flow delivered:** login → JWT (a client can authenticate and receive a validated token).
**Infra que sobe:** none (AWS-free; seeded users, tested with MockMvc). · **Diagram:** ARCHITECTURE §6.1

- [x] [Step 01](docs/steps/step-01.md) — Git repo + Maven multi-module parent POM + common-lib skeleton
- [x] [Step 02](docs/steps/step-02.md) — common-lib: RFC 7807 error model + correlation-id filter + JSON logging
- [x] [Step 03](docs/steps/step-03.md) — auth-service Spring Boot skeleton with Actuator health
- [x] [Step 04](docs/steps/step-04.md) — auth-service: login endpoint issuing HS256 JWT; seeded users
- [x] [Step 05](docs/steps/step-05.md) — common-lib JWT validation filter + `AuthenticatedUser` principal

## Sprint 2 — Accounts & Pix Keys
**Flow delivered:** register / list / delete a Pix key and resolve an internal key → account.
**Infra que sobe:** LocalStack (DynamoDB) + Testcontainers harness. · **Diagram:** ARCHITECTURE §6.2

- [x] [Step 06](docs/steps/step-06.md) — docker-compose: LocalStack (DynamoDB) with healthchecks (infra only)
- [x] [Step 07](docs/steps/step-07.md) — LocalStack init: `pix_accounts` + `pix_keys` tables (GSIs) + seed data
- [x] [Step 08](docs/steps/step-08.md) — Testcontainers integration-test harness (LocalStack) in common-lib
- [x] [Step 09](docs/steps/step-09.md) — account-service: accounts repository + `GET /accounts/me` + internal lookup
- [x] [Step 10](docs/steps/step-10.md) — Pix key registration with global uniqueness (conditional put) + list/delete
- [x] [Step 11](docs/steps/step-11.md) — internal key resolution endpoint (DICT role for internal keys)

## Sprint 3 — Ledger (the heart)
**Flow delivered:** atomic double-entry posting + balance read + statement, invariants proven.
**Infra que sobe:** DynamoDB `pix_ledger` table. · **Diagram:** ARCHITECTURE §6.3

- [x] [Step 12](docs/steps/step-12.md) — LocalStack init: `pix_ledger` table + seed postings
- [x] [Step 13](docs/steps/step-13.md) — ledger-service: data model + balance read (strongly consistent)
- [x] [Step 14](docs/steps/step-14.md) — atomic double-entry posting via TransactWriteItems (debit+credit+2 entries+txId guard)
- [x] [Step 15](docs/steps/step-15.md) — invariant test suite: concurrency storm, no-negative-balance, no-double-post, conservation of money
- [x] [Step 16](docs/steps/step-16.md) — statement query (paginated, newest first) + posting API polish

## Sprint 4 — Send Pix (internal, synchronous)
**Flow delivered:** alice → bob (internal key) moves real money end-to-end, idempotent, limited.
**Infra que sobe:** DynamoDB `pix_transactions` + `pix_idempotency` tables. · **Diagram:** ARCHITECTURE §6.4

- [x] [Step 17](docs/steps/step-17.md) — LocalStack init: `pix_transactions` (+GSIs) + `pix_idempotency` tables
- [x] [Step 18](docs/steps/step-18.md) — payment-service: `POST /payments/pix` walking skeleton (validation, txId/endToEndId, 202)
- [x] [Step 19](docs/steps/step-19.md) — idempotency layer: conditional claim, response replay, 409 on hash mismatch
- [x] [Step 20](docs/steps/step-20.md) — daily limit enforcement (calendar-day reservation counter, decision-object seam for future MFA)
- [x] [Step 21](docs/steps/step-21.md) — internal orchestration: key resolution + ledger debit (credit payee directly) + status SETTLED (internal settles instantly)
- [x] [Step 22](docs/steps/step-22.md) — `GET /payments/{id}` status endpoint

## Sprint 5 — Fraud in the path
**Flow delivered:** synchronous fraud score inside the send flow, under a 200ms budget, fail-open.
**Infra que sobe:** Redis (velocity counters). · **Diagram:** ARCHITECTURE §6.5

- [x] [Step 23](docs/steps/step-23.md) — docker-compose Redis + fraud-service skeleton
- [x] [Step 24](docs/steps/step-24.md) — fraud-service: rule-based `POST /score` (velocity, amount, novelty, hours), p99 < 150ms
- [x] [Step 25](docs/steps/step-25.md) — payment-service integration: 200ms hard timeout, fail-open + `FRAUD_SKIPPED` flag & event

## Sprint 6 — Send Pix (external, asynchronous settlement)
**Flow delivered:** external Pix debits to clearing, settles via BACEN SPI, reaches SETTLED.
**Infra que sobe:** SNS `pix-events` + SQS `settlement-queue`(+DLQ) + mock-bacen-spi. · **Diagram:** ARCHITECTURE §6.6

- [x] [Step 26](docs/steps/step-26.md) — LocalStack init: SNS `pix-events` + `settlement-queue` (+DLQ, redrive, filter policy)
- [x] [Step 27](docs/steps/step-27.md) — external orchestration: ledger debit → `SPI_CLEARING`; status DEBITED
- [x] [Step 28](docs/steps/step-28.md) — transactional outbox: tx + outbox item in one TransactWriteItems
- [x] [Step 29](docs/steps/step-29.md) — outbox polling publisher: sparse GSI → SNS; `ProcessedEventStore` (consumer dedup)
- [x] [Step 30](docs/steps/step-30.md) — mock-bacen-spi: settlement endpoint (latency/failure/timeout config) + external DICT resolve
- [x] [Step 31](docs/steps/step-31.md) — settlement-service: consume settlement-queue, call SPI, mark SETTLED (happy path)

## Sprint 7 — Resilience & reconciliation
**Flow delivered:** timeouts/failures never lose or double money; stuck tx resolved in < 5 min.
**Infra que sobe:** none new (schedulers + DLQ redrive). · **Diagram:** ARCHITECTURE §6.7

- [x] [Step 32](docs/steps/step-32.md) — retries with query-before-retry, visibility backoff, DLQ redrive
- [x] [Step 33](docs/steps/step-33.md) — settlement finalization: SETTLED clearing release; FAILED → REVERSED (compensating posting)
- [x] [Step 34](docs/steps/step-34.md) — stuck-transaction scanner (GSI2 status+age) on a 60s schedule
- [x] [Step 35](docs/steps/step-35.md) — reconciliation resolution: query SPI, finalize or reverse; < 5-min SLO metric + alert

## Sprint 8 — Receive Pix & real-time notification
**Flow delivered:** inbound Pix credits the user and pushes an SSE notification in real time.
**Infra que sobe:** SQS `notification-queue` (+DLQ); SSE. · **Diagram:** ARCHITECTURE §6.8

- [x] [Step 36](docs/steps/step-36.md) — LocalStack init: `notification-queue` (+DLQ, filtered subscription)
- [x] [Step 37](docs/steps/step-37.md) — mock-bacen inbound generator → inbound flow: dedupe by endToEndId, credit posting
- [x] [Step 38](docs/steps/step-38.md) — notification-service: consume notification-queue, SSE stream per user
- [x] [Step 39](docs/steps/step-39.md) — wire PixSettled/PixReceived/PixReversed to real-time pushes end to end

## Sprint 9 — Balance & statement with cache
**Flow delivered:** balance < 300ms p99 from cache; paginated statement through payment-service.
**Infra que sobe:** none new (Redis cache-aside on existing Redis). · **Diagram:** ARCHITECTURE §6.9

- [x] [Step 40](docs/steps/step-40.md) — Redis cache-aside for balance + invalidation on postings + 5s TTL backstop
- [x] [Step 41](docs/steps/step-41.md) — statement API through payment-service with opaque cursor pagination

## Sprint 10 — Immutable audit trail
**Flow delivered:** every state transition lands as an immutable S3 record; cold statement archive.
**Infra que sobe:** SQS `audit-queue` + S3 buckets. · **Diagram:** ARCHITECTURE §6.10

- [x] [Step 42](docs/steps/step-42.md) — LocalStack init: `audit-queue` (+DLQ, all-events subscription) + S3 buckets
- [x] [Step 43](docs/steps/step-43.md) — immutable audit trail: audit-queue consumer → S3 JSON lines; statement cold-archive job

## Sprint 11 — Observability
**Flow delivered:** technical + business-funnel dashboards; silence alerts; correlationId path tracing.
**Infra que sobe:** Prometheus + Grafana. · **Diagram:** ARCHITECTURE §6.11

- [x] [Step 44](docs/steps/step-44.md) — Prometheus + Grafana dashboards (technical + business funnel) + silence alerts (settlement watchdog, DLQ depth, reconciliation age)

## Sprint 11.5 — External review remediation (P0/P1)
> **Inserted, not renumbered.** An independent staff-level review by **Geison Flores** (Mercado Livre) landed as
> [`docs/solucao-e-sugestoes.html`](docs/solucao-e-sugestoes.html) via [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58),
> classifying its findings P0 (money correctness & security) / P1 (operations & scale) / P2. This sprint sits
> **between Sprints 11 and 12** so the later sprint numbers — referenced by ARCHITECTURE §6.12-6.14, the README
> and the CHANGELOG — stay valid. Its steps take the **next free numbers (65+)**, as
> step 64 already did; each step file records that it was numbered out of order and why.
> Every finding was verified against the code before a spec was written; findings already covered
> (PII in logs → ADR-0012's deliberate sandbox trade-off; PostgreSQL for the ledger → ADR-0020) produced an ADR
> or a backlog note, **not** a step.

**Flow delivered:** a payment operation that survives a crash and a race — durable identity before the money,
an ambiguous outcome resolved by that same identity, a single terminal winner, and internal ports that refuse a
user's token. **Infra que sobe:** OTLP collector + Jaeger (step 72 only). · **Diagram:** ARCHITECTURE §6.4 / §6.6 / §6.7 (amended)

**P0 — money correctness & security.** These four precede step 45; step 66 additionally requires 65.

- [x] [Step 65](docs/steps/step-65.md) — durable operation identity: `txId`/`endToEndId` minted **before** the idempotency claim and persisted by it; a resume reuses the stored identity ([ADR-0014](docs/adr/0014-durable-operation-identity.md), amends ADR-0002)
- [x] [Step 66](docs/steps/step-66.md) — a ledger timeout is an **unknown result**: resolve by re-posting the same `txId` and read the `replayed` flag the ledger already returns ([ADR-0015](docs/adr/0015-ledger-timeout-is-an-unknown-result.md)) · *requires 65*
- [x] [Step 67](docs/steps/step-67.md) — finalization fencing: CAS into `FINALIZING_SETTLEMENT`/`FINALIZING_REVERSAL` **before** any posting; settle XOR reverse ([ADR-0016](docs/adr/0016-finalization-fencing-settle-xor-reverse.md), amends ADR-0003)
- [x] [Step 68](docs/steps/step-68.md) — internal-port isolation: scoped service tokens (`typ`/`iss`/`aud`/`scope`); a user JWT gets `403` on every `/internal/**` route ([ADR-0017](docs/adr/0017-workload-identity-for-internal-ports.md), amends ADR-0007)
- [x] [Step 69](docs/steps/step-69.md) — recovery & fencing invariant suite: crash-after-commit, ambiguous timeout, concurrent settle×reverse, lateral-access matrix, conservation everywhere · *requires 65-68*

**P1 — operations & scale.**

- [x] [Step 70](docs/steps/step-70.md) — fraud failure classification: fail-open only for transient failures; auth/contract/bug failures become a visible `FRAUD_ERROR` ([ADR-0018](docs/adr/0018-fraud-failure-classification.md), amends ADR-0005) · *drilled by step 64*
- [x] [Step 71](docs/steps/step-71.md) — outbox lanes (settlement · notification · audit), parallel publishers, backpressure, per-lane queue-age SLO + parallel settlement consumer ([ADR-0019](docs/adr/0019-outbox-lanes-and-priority.md), amends ADR-0004) · *closes the reversal incident in `docs/load/RESULTS.md` Context 2*
- [x] [Step 72](docs/steps/step-72.md) — distributed tracing (OTel, across HTTP **and** the queues) + error-budget burn alerts — the **delta** over step 44, which keeps everything it delivered ([ADR-0021](docs/adr/0021-distributed-tracing-and-error-budget-alerts.md))

> **The fourth P1 — "comprovar 500+ TPS" — is discharged by [step 47](docs/steps/step-47.md), whose scope was
> widened rather than duplicated** (representative infrastructure, WCU/RCU + cost budget, p99 per dependency,
> degradation scenario). See its "What the external review added" section.

## Sprint 12 — Hardening, E2E & load
**Flow delivered:** the full journey proven under an automated E2E + failure drill + SLO load tests.
**Infra que sobe:** k6.

- [x] [Step 45](docs/steps/step-45.md) — hardening: API versioning review, guarded status transitions, error contract audit, security checklist
  > Owns the **AWS credential / IAM** posture (ADR-0013). The external review's P0 on **HTTP service identity**
  > is a different concern and lands earlier, in [step 68](docs/steps/step-68.md) — the two are neighbours and
  > are often confused. Prerequisite: all four Sprint 11.5 P0 steps.
- [x] [Step 46](docs/steps/step-46.md) — end-to-end test: full journey send→settle→receive→notify→statement, incl. failure drill
- [x] [Step 47](docs/steps/step-47.md) — k6 load tests: low, standard (~58 TPS) and Black Friday (500+ TPS) profiles with SLO thresholds
  > **Scope widened by the external review (P1 · capacidade), not duplicated by a new step:** representative
  > infrastructure (or the deviation documented against `docs/load/BOTTLENECK.md`), a WCU/RCU + cost budget,
  > p99 **per dependency** (fed by step 72's tracing), and a degradation scenario. Best run after
  > [step 71](docs/steps/step-71.md), or it measures a ~25 events/s outbox drain.

## Sprint 13 — API tooling & DX
**Flow delivered:** the two living manual-test harnesses — Postman collection + single-file HTML API explorer — **finalized** (both grown incrementally, one entry per endpoint, since their first endpoint).
**Infra que sobe:** none.

- [x] [Step 48](docs/steps/step-48.md) — **finalize** the unified Postman collection (grown incrementally since step 04): all services, auth pre-request, happy/error examples
- [x] [Step 49](docs/steps/step-49.md) — **finalize** the single-file HTML API explorer (grown incrementally since auth-service): polish the guided journey, add richer happy/error examples, audit coverage
  > Partly delivered early (2026-08-20, during step 39): the **Journeys · Services · Phone** grouping, the
  > five runnable journeys (receive · internal · external · reversal · idempotency) and the layout fix
  > landed when the step-39 review found the explorer could not answer "how do I test receiving a Pix?".
  > What step 49 still owns: coverage audit against `docs/api/openapi.yaml`, richer error examples, and
  > journeys for the flows that land after Sprint 8 (cache, statement, audit).
  > **Done 2026-08-28, and it found what step 48 found:** coverage was already complete, but the page had
  > never been *run*, and 12 of 55 cards failed when clicked top to bottom on a fresh stack (nothing
  > registered the payee; the bob-login card hijacked the session; the delete card removed the key its
  > neighbours needed; every Prometheus card failed the browser preflight). Cache and statement already had
  > a journey; **audit does not, deliberately** — the trail is S3-only (step 43 verifies it with `aws s3 ls`),
  > and LocalStack answers an unsigned `ListObjectsV2` but sends no `Access-Control-Allow-Origin`, so a
  > browser opened from `file://` cannot read it. A runnable audit journey would need an infra change
  > (`EXTRA_CORS_ALLOWED_ORIGINS` on the LocalStack container), which is not this step's scope.

## Sprint 14 — Relational counterpart & staff-grade extensions (Block Q)
> Steps 50–51 may be taken any time after Sprint 3; 52 requires 47; 53 requires 41 & 43.
**Flow delivered:** the same ledger, measured on PostgreSQL; clearing sharding proven; async cold export.
**Infra que sobe:** PostgreSQL (Testcontainers, lab only — never wired to the platform).

- [x] [Step 50](docs/steps/step-50.md) — `labs/ledger-pg`: same ledger port on PostgreSQL with pessimistic (`SELECT FOR UPDATE`) and optimistic (version column) strategies (ADR-0009)
  > **Done 2026-08-28.** Two spec corrections are recorded in the step file rather than worked around: the
  > tests are `*IT` (they need Docker, and the `docker.api.version` pin lives on failsafe only), and "the
  > same `LedgerPort` as ledger-service" is a documented **mirror** — the deployable's artifact is a Boot
  > fat jar, so depending on it would mean giving it a second artifact purely to serve a lab, which is the
  > coupling ADR-0009 forbade. Parity is asserted by one shared contract suite, not by the compiler.
  > **First result, before any benchmark:** of the two relational strategies the *optimistic* one is the
  > closer relative of the DynamoDB path (guard inside the write), and the pessimistic one — the obvious
  > relational answer — has no DynamoDB equivalent at all. Three findings handed to step 51 unfixed: a
  > replay costs a lock under `FOR UPDATE`, the retry budgets differ on purpose (3 vs 8), and the
  > `(account_id, posted_at)` index is deliberately absent so the `EXPLAIN` study can measure it both ways.
- [x] [Step 51](docs/steps/step-51.md) — invariant parity on Postgres + `EXPLAIN`/index/deadlock study + contention benchmark vs DynamoDB (findings doc + psql session)
  > **Done 2026-08-28.** Parity green on both strategies, and its worth shown by mutation: deleting
  > `FOR UPDATE` leaves the step-50 sequential contract 6/6 green and turns the new storm 3-of-4 red,
  > with the schema's `CHECK` firing in anger on an eleventh posting. The study then **found a real bug
  > in the lab**: the replay path opened a second connection while holding the first, deadlocking the
  > pool at a replay fan-in equal to its size — the same cycle as the row deadlock, one level up, now
  > fixed and pinned by a storm that replays from `POOL_SIZE + 4` threads.
  > **Two results worth carrying out of it:** the covering index is *not* worth it here (9 MB for a
  > difference inside the noise, because `Heap Fetches: 20` means the visibility map never delivered
  > the index-only scan), and the two strategies differ almost not at all in throughput but enormously
  > in *who pays* — optimistic p50 25× better, p99 4× worse, and 8 of 800 callers turned away.
  > **The DynamoDB leg could not be measured** (LocalStack saturates at ~45 write ops/s regardless of
  > concurrency — `docs/load/BOTTLENECK.md` RUNG 2, measured before this step asked), so ADR-0009 is
  > amended to say the benchmark has two of its three legs, and ADR-0001 records that nothing here
  > speaks to its availability/elasticity or retention pillars.
- [x] [Step 52](docs/steps/step-52.md) — clearing-account write sharding (N=16) proven with the Black Friday k6 profile (before/after)
  > **Done 2026-08-28.** **55,729 writes on one item at N=1; 3,770 on the busiest of sixteen at N=16**
  > (6.4% spread, bare account untouched at `version=0`) — a 14.8x cut in per-item write pressure with
  > every latency/throughput/error metric inside noise, and Σ = 0 across 224 accounts after 54,573
  > concurrent external sends. Three corrections the step file did not anticipate, all recorded in it:
  > the resolver belongs in **common-lib** because ARCHITECTURE §6.3 puts the choice in the *caller*,
  > not the ledger; **no `clearingShard` index** was added because the full `clearingAccountId` already
  > persisted survives a change of N while an index does not; and `black-friday.js` ships
  > `EXTERNAL_SHARE=0`, so run verbatim it never touches clearing and the comparison would have been
  > vacuous.
  > **The result worth carrying out of it:** the throughput win is not the point and this host cannot
  > even show it (DynamoDB Local emulates no partition throttling, and both runs sat on the same ~166
  > req/s ceiling). The point is a correctness property no benchmark would have caught — a reversal
  > that re-derives its shard is perfectly balanced, leaves Σ untouched, and still drains the wrong
  > sub-account. Pinned by `ReversalShardIT`, proven non-vacuous by mutation.
- [x] [Step 53](docs/steps/step-53.md) — cold statement retrieval: async export with `202` + polling status URL + download artifact
  > **Done 2026-08-29.** Three spec corrections are recorded rather than worked around: the download URL
  > is signed **per read** instead of at completion (a link minted when the worker finishes starts
  > expiring while the customer is still being told the file is ready, and an export whose only handle
  > expired is permanently undownloadable); the hot-window boundary is **asked of ledger-service** over a
  > new `GET /internal/ledger/statement-window` instead of being configured twice; and the step's own
  > verify block names months this sandbox has never archived, so `docs/local-dev.md` §5.8.1 computes
  > them instead.
  > **What the build caught that no isolated test could — the result worth carrying out of this step.**
  > Four defects survived a green module and a green test class and died only to the *full reactor*
  > `mvn verify`. (1) The outbox publisher rebuilt an item's key as `"TX#" + <stripped id>`, an unstated
  > assumption that every outbox item belongs to a transaction; `EXPORT#` items broke it silently, so
  > the mark-published update hit a key nothing lives under and **the event never left the sparse index
  > — republished on every tick, for ever**. The writer had been duplicated and the reader shared, and
  > the duplication even carried a javadoc defending itself. (2) Resolving the SQS queue URL in a bean
  > constructor made an unreachable *reporting* queue a startup failure for the service that runs
  > `POST /v1/payments/pix` — and destroyed a property `ApplicationContextIT`'s own javadoc had written
  > down. (3) `/money-safety-review` found the worker buffering an unbounded range in the JVM that
  > serves the money path. (4) The worker IT assumed a shared outbox lane was its own; 2024 `PixSettled`
  > items left by sibling ITs meant its event was never reached.
  > **The lesson, stated plainly:** a green module proves less than it looks like it does. Every one of
  > these needed the whole suite, sharing one LocalStack and one set of tables, to become visible.

---

## Backlog — noted, deliberately not scheduled as steps

Mostly from the external review ([PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58)), plus
anything else the project decided to name rather than schedule. Recorded here so a reader finds the answer
next to the question; none of these is an unfinished task.

- **Fraud-service runtime latency/failure injection** — an `AdminConfigController` mirroring
  mock-bacen-spi's. It was drafted as [step 64](docs/steps/step-64.md) (the next free number, out of
  top-to-bottom order) and stayed `PROPOSED` without ever being prioritized; **moved here instead of
  being carried as a permanently-unstarted step**, because an item nobody intends to take next is
  backlog whatever the file calls it. The step file stays where it is — the design work in it is done
  and is what makes this cheap to promote later.
  **The real gap it names, which is still open:** fraud-service has no runtime dial, unlike
  mock-bacen-spi, so **its fail-open path cannot be drilled against the running stack** — only inside a
  test process. `docs/load/RESULTS.md` found this: the design's most load-bearing availability claim
  (ADR-0005/ADR-0018 — a slow or broken fraud check must never block a payment) is proven by tests and
  by argument, never by a lever an operator can pull on the sandbox and watch. Promote it the day
  someone wants to *demonstrate* fail-open rather than read about it.

- **P2 · a self-transfer answers `503 LEDGER_UNAVAILABLE`, and asks the client to retry it.** Found by the
  step-49 explorer audit, not fixed there (adjacent to that step's scope). Paying your own Pix key reaches
  `PostDoubleEntryUseCase`'s guard — "both legs name the same account", a `422 InvalidPostingException` and a
  permanent refusal — but payment-service maps every ledger failure to `503 LEDGER_UNAVAILABLE` with
  `Retry-After: 5`. So a request that can *never* succeed is reported as a transient outage, and a well-behaved
  client retries it on a schedule the platform itself suggested. Reachable through the public API by any user
  with a registered key. The fix is small (distinguish the ledger's *permanent* refusals from its *unavailable*
  ones in `PaymentExceptionHandler`) but it changes a public error contract, so it wants its own step and an
  `openapi.yaml` entry rather than a drive-by.

- **P2 · versioned internal contracts (events & DTOs).** Real and open. A versioned schema, backward/forward
  compatibility and consumer-driven contract tests would stop a status enum drifting between two services — the
  live example being the two `TransactionStatus` enums that "agree by contract, not by construction" (their own
  javadocs say so), which [step 67](docs/steps/step-67.md) must update in lockstep. Not a step **yet**: with all
  consumers in one repo and one build, a schema registry buys ceremony over safety. Promote it the day a consumer
  ships on its own cadence. Public-API versioning is already [step 45](docs/steps/step-45.md) task 3.
- **P2 · PII masking in logs.** *Already decided, in the opposite direction, and that is the point.*
  [ADR-0012](docs/adr/0012-verbose-logs-with-real-values.md) deliberately logs Pix keys, CPFs and account ids in
  the clear because this is a sandbox whose logs are a teaching artifact, and it documents the LGPD trade-off and
  exactly what production reverses. Secrets are already never logged. No step; the ADR is the answer.
- **P2 · software supply chain** (Maven Wrapper, coverage gates, static analysis, SCA, SBOM, secret scanning,
  non-root images pinned by digest). Partly covered — [step 45](docs/steps/step-45.md) task 4 runs a dependency/CVE
  scan. The rest is real and would be one focused step; it moves no money and no correctness, so it queues behind
  everything in Sprint 11.5.
- **P3 · multi-AZ production plan** (RTO/RPO, PITR, KMS + key rotation, tested failover, degraded capacity).
  Out of scope by construction: this platform is 100% local, no Kubernetes, no cloud account (CLAUDE.md). It is
  already treated as target architecture in [ARCHITECTURE.md](ARCHITECTURE.md) §7.4 (availability budget and the
  ledger-outage behaviour). No step.
- **Modular monolith / service consolidation.** The review says "evolução seletiva… consolidar deve reduzir uma
  falha concreta, não apenas implantações". The concrete failure it names — the payment/settlement state machine
  split across two services — is what [ADR-0016](docs/adr/0016-finalization-fencing-settle-xor-reverse.md)
  addresses **without** moving code. Revisit only if fencing proves insufficient; see
  [ADR-0020](docs/adr/0020-keep-dynamodb-for-the-ledger.md) §5.
- **PostgreSQL for the ledger.** Decided: **no migration**, DynamoDB stays, `labs/ledger-pg` stays a lab.
  [ADR-0020](docs/adr/0020-keep-dynamodb-for-the-ledger.md) records the decision, the reasoning and the three
  conditions that would reopen it — measured by steps 50-51.
