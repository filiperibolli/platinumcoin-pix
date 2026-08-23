# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

One entry is added per completed implementation step (see `PLAN.md` / `docs/steps/`).
Each step file specifies the exact entry to add under `[Unreleased]` on completion.

## [Unreleased]

### Milestone — money core complete (Sprints 1–7, steps 01–35) · 2026-08-18
The platform reached its halfway mark: **the full money path is built, tested and proven under load.**
- **What works end to end:** login → JWT; account & Pix-key management with internal key resolution; the
  atomic double-entry ledger (balance, statement, invariant suite); **send Pix internal** (synchronous,
  idempotent, daily-limit-enforced); real-time fraud scoring inside the send flow under a 200ms budget,
  fail-open; **send Pix external** (async settlement — debit → `SPI_CLEARING` → outbox → SNS/SQS →
  settlement-service → SPI → `SETTLED`); and resilience/reconciliation (retries + DLQ, compensating
  reversals, stuck-transaction scanner, `<5-min` reconciliation SLO).
- **8 service modules** (auth, account, ledger, payment, fraud, settlement, mock-bacen-spi, common-lib),
  each hexagonal-lite (ADR-0010/0011) and guarded by ArchUnit; **`mvn verify` green across all modules**
  (unit + Testcontainers integration tests).
- **Proven under load** (`docs/load/`): every money-correctness invariant held under concurrent traffic —
  atomic double-entry, conservation of money on both the synchronous and asynchronous paths, non-negative
  balance under contention, exact leak-free daily-limit reservation, and idempotency under a retry storm.
- **Next:** Sprint 8 — receive Pix & real-time SSE notification (step 36 onward).

### Changed
- **Sprint 11.5 planned — external review remediation** (2026-08-22): an independent staff-level review
  by **Geison Flores** (Mercado Livre) landed as `docs/solucao-e-sugestoes.html` (PR #58), classifying
  findings P0 (money correctness & security) / P1 (operations & scale) / P2. **Every finding was
  verified against the code before a spec was written**, and the three P0s that turned out to be real
  and open are: (1) the `txId` is minted *after* the idempotency claim
  (`SendPixUseCase:424/499`), so a crash-resume past `STALE_SECONDS` mints a **new** identity and
  double-debits; (2) a ledger timeout is asserted to mean "nothing debited"
  (`HttpLedgerClient:305-310`) while the ledger's own `replayed` flag is discarded at `:283`; (3) both
  finalization paths post to the ledger *before* their guarded transition
  (`SettlementFinalizer:88/92` and `:152/157`), so a settle racing a reverse posts `-rel` **and**
  `-rev` — money created, as `StuckTransactionResolver`'s own javadoc admits, mitigated only
  probabilistically by the safety window. The fourth: payment-service forwards the **end user's**
  bearer to every internal port, making any user's login a valid credential on
  `POST /internal/ledger/postings`. **Planned as Sprint 11.5** (inserted between Sprints 11 and 12 so
  no later sprint number moves; steps take the next free numbers 65-72, as step 64 already did):
  steps 65-68 (the P0s, all preceding step 45), step 69 (✍️ hand-written recovery & fencing invariant
  suite), steps 70-72 (the P1s). **Reconciled rather than duplicated:** step 47's scope was widened for
  the 500+ TPS finding, step 44 was left intact with step 72 delivering only its delta (OTel + error
  budgets), and step 45 keeps AWS/IAM while the HTTP-identity P0 moved to step 68.
  **ADR-0014 … ADR-0021 added**, each crediting the review and linking PR #58 — durable operation
  identity (amends ADR-0002), timeout-as-unknown-result, finalization fencing (amends ADR-0003),
  workload identity for internal ports (amends ADR-0007), fraud failure classification (amends
  ADR-0005), outbox lanes (amends ADR-0004), **keeping DynamoDB for the ledger** (the review's
  "avaliar depois", recorded as a decision *not* to migrate, with the three conditions that would
  reopen it), and OTel tracing alongside — not instead of — the ADR-0012 correlation id.
  Findings already answered produced no step: PII-in-logs is ADR-0012's deliberate sandbox trade-off,
  and internal contract versioning is a backlog note. **Planning only — no production code changed.**
- **ADR-0013 added** (2026-08-11): AWS credentials & IAM posture — local emulation vs. production.
  Raised reviewing step 26: the SQS resource policy written there is correct for real AWS but
  **unenforced by LocalStack**, and the same is true of every service's `StaticCredentialsProvider`
  (`test`/`test` is a signing formality, not authentication — the emulator validates no signature and
  only reads the access key to derive the account id). LocalStack *does* emulate the IAM/STS APIs but
  enforces nothing by default (`ENFORCE_IAM` is off and gated as a paid feature), so locally one can
  model IAM but never prove denial. The decision: no long-lived credential on the production path (the
  `DefaultCredentialsProvider` chain resolves the ambient ECS/EKS/EC2 role), the local static
  credentials isolated behind a `local` profile, and least-privilege policies committed as versioned
  `infra/iam/<service>-policy.json` artifacts — small by construction thanks to the fan-out
  (payment-service: `sns:Publish` on one topic and no SQS permission at all). Rejected: `assume-role`
  ceremony on the local path (without enforcement the credential works regardless of the policy — it
  proves nothing and adds a boot dependency), a paid tier for `ENFORCE_IAM`, and a real-AWS smoke test
  (it would validate AWS rather than this design, and contradicts the 100%-local constraint).
  **No code change in this release**: the sweep is scheduled as task 5 of step 45, deliberately done
  across all services at once, and new clients — starting with step 29's `SnsClient` — copy the
  current shape until then, since two competing shapes mid-migration is worse than one uniform shape
  awaiting a single reviewable change.
- Internal package layout standardized across all four services into role sub-packages (ADR-0010
  amendment 2026-08-10). `domain/` now groups into `model/` · `port/` · `exception/` · `service/` ·
  `usecase/`, and `infra/` into `persistence/` · `client/` · `security/` · `config/` — **one folder per
  role, always** (a lone port, a single exception, a single adapter each get their folder), with no
  `.java` loose at a layer root (only `Application` stays at the service-package root). Motivated by a
  code review: payment-service's flat `domain/` of ~26 mixed files (entities, ports and a dozen
  exceptions side by side) had become hard to navigate. **Behaviour and the HTTP contract are
  unchanged**, and each `*ArchitectureTest` stays green untouched — the ArchUnit rules match by layer
  subtree (`..domain..`/`..api..`/`..infra..`), so grouping never touches the dependency rules. Applied
  to auth (step 03), account (step 09), ledger (step 13) and payment (steps 18–22); each service's
  scaffold-step metrics line carries the `+1 issue`. `mvn verify` green on all four.
- ADR-0002 **validated** (2026-08-10): a review asked whether idempotency should move to Redis. Evaluated
  Redis-only, Redis-hybrid, and the DynamoDB-durable original, and **kept DynamoDB** as the source of
  truth (it is the AWS Powertools default; Redis stays scoped to the balance cache, ADR-0008). A
  deterministic `txId` was considered as defense-in-depth and **rejected** (it collides with the 24h
  key-reuse semantics). **No code or schema change** — the original design was already correct.

### Fixed
- Finalization fencing: settle and reverse now win a conditional FINALIZING_* transition before any ledger posting, so a race between the settlement consumer and the reconciliation resolver can no longer move money twice (step 67, ADR-0016)
  - The mechanism was already in the codebase and applied one state too late. `markSentToSpi` has always
    been a real CAS returning "did I win"; the finalization guards were real CAS operations too — they
    just ran **after** the ledger call, which made them a record of who won a race that had already cost
    money. `-rel` and `-rev` are different `txId`s, so posting idempotency never related them: both
    postings committed, `SPI_CLEARING` was drawn down twice against one credit, and **money was created**.
  - **Σ balances does not catch it**, which is why the drill asserts something sharper. Both postings are
    double-entry, so the total is conserved even when both commit; the creation shows up as the clearing
    account going negative — and `SPI_CLEARING` is deliberately exempt from the no-negative-balance guard
    (`AccountPolicy`, an inter-bank position rather than a wallet), so nothing refused it.
    `FinalizationFencingIT` therefore pins **clearing nets to zero** and **`payer + SPI_SETTLED` moved by
    exactly the amount** — the money went out to the network XOR came back, never both. Against `main` it
    failed deterministically, not flakily: both paths post before either CAS runs, so even a fully
    serialized execution double-draws.
  - **The asymmetry is the whole mechanism.** Each fence's condition is a whitelist of legal source states
    that includes itself and excludes the other; nothing enumerates what is forbidden. Re-entering your
    own fence is legal (a crash between fence and posting replays the idempotent posting), entering the
    other one is impossible by condition expression rather than by timing.
  - The two states shipped in **both** `TransactionStatus` enums in one commit. payment-service rebuilds
    that attribute with `valueOf`, so shipping one side only would have made every
    `GET /v1/payments/{id}` issued mid-finalization answer `500` — the same defect `REVERSED` caused in
    step 33, caught this time before it shipped (`StatusQueryIT#aFencedTransactionIsReadableAsProcessing`).
    Both map to `PROCESSING`: the internal state machine grew two states and the client contract grew none.
  - The stuck scan now queries **four** partitions. A fence moves `gsi2pk` onto `STATUS#FINALIZING_*`, so
    omitting them would have made a stalled finalization leave the stuck partitions and become invisible
    to every future scan — the payer's money parked in clearing with nothing left to look at it. Same
    gauge, same alert: `pix_reconciliation_oldest_seconds` covers it, and no new metric was added.
  - A stalled fence is **completed in the direction it was fenced**, never flipped. The resolver's
    "Why the safety window is a correctness mechanism" javadoc was rewritten rather than left standing:
    the window is now a latency optimisation (don't fence a reversal over a settlement legitimately in
    flight), and a comment claiming a role the fence has taken over is exactly the drift CLAUDE.md forbids.
  AI: est 4h / actual 30min / ~92% generated / 0 issues caught in human review
- A ledger timeout is now an unknown result resolved by re-posting the same txId, and the ledger's replayed flag is read instead of discarded — a committed-but-timed-out posting debits exactly once (step 66, ADR-0015)
  - `LedgerClient` returns a `LedgerOutcome` (`POSTED · REPLAYED · INSUFFICIENT_FUNDS · REFUSED ·
    UNKNOWN`) instead of `void`: a port whose only vocabulary is "returned" or "threw" cannot say
    *unknown*, and that missing third word is what let the adapter assert "nothing debited" about an
    outcome nobody knew. The resolution of an unknown is **the same call again** — the ledger's posting
    API is idempotent by `txId`, so the re-POST either commits or answers `replayed: true`, which is why
    no `GET /postings/{txId}` was added (ADR-0015 §2).
  - Binding the response body instead of `toBodilessEntity()` moved where a **read** timeout surfaces:
    it now arrives as a `RestClientException` during body extraction rather than a
    `ResourceAccessException`, so both are classified `UNKNOWN` — caught by
    `HttpLedgerClientTest#readTimeoutIsUnknownNotUnavailable`, which failed on the first run for exactly
    that reason.
  - An unresolved unknown never becomes an implicit "no": `503`, the claim stays pre-`POSTED` carrying
    the same `txId`, and **no daily-limit release** — handing back headroom for a debit that may have
    happened is the same error mirrored. Pinned by
    `SendPixUseCaseTest#unresolvedUnknownDoesNotReleaseTheDailyLimit`.
  - settlement-service classifies identically (ADR-0015 §5) and resolves differently *by mechanism*: its
    postings are keyed by a deterministic `txId`, so the SQS redelivery **is** the resolving re-POST —
    `LedgerOutcomes.requireMoneyMoved` simply refuses to let a status transition run on doubt.
  AI: est 3h / actual 1h20 / ~90% generated / 0 issues caught in human review
- Durable operation identity: txId and endToEndId are minted before the idempotency claim and persisted by it, so a crash-resume reuses the same identity instead of double-debiting (step 65, ADR-0014)
  - Making the resume reuse the identity exposed the other half of the same problem: the transaction
    write is guarded by `attribute_not_exists(pk)`, so a resume whose earlier attempt had already
    committed `TX#<txId>` could no longer finish and stranded the client on a `500`. `persistWithOutbox`
    now reads the existing item back, **verifies it describes this same operation** (debtor + amount),
    and continues to the memo — writing nothing, so the outbox events are not duplicated either.
  - ADR-0014 §4 gained the exact reach of "the TTL never recycles a money identity": it is a condition
    on an item, so it holds while the item exists. DynamoDB's TTL collector eventually deletes it, and
    that is accepted — the detector for stalled money is the reconciliation scan over `pix_transactions`
    (no TTL), and suspending the TTL here would make a *refused* send's record immortal.
  AI: est 3h / actual 1h30 / ~90% generated / 0 issues caught in human review
- **`GET /v1/payments/{transactionId}` answered `500` for every reversed payment.** A reachable defect on
  the money path, introduced in step 33 and found in step 39 by running the new *Reversal* journey in the
  API explorer end to end (then reproduced with `curl`, outside the explorer). settlement-service marks a
  permanently refused external send **`REVERSED`** in `pix_transactions`; payment-service's own
  `TransactionStatus` knew only `RECEIVED/DEBITED/SENT_TO_SPI/SETTLED`, so
  `DynamoTransactionRepository.toTransaction` threw `IllegalArgumentException: No enum constant …REVERSED`
  and the payer — whose money had just come back — could not read the payment that returned it.
  - **Why it stings more since step 39:** the push announces `REVERSED` and names `GET /payments/{id}` as
    its authoritative fallback ("best-effort push, authoritative poll"). The fallback was the thing that
    failed, and only for the outcome the push exists to soften.
  - **The guard that worked, and the one that was missing.** `PaymentResponse.externalStatusOf` is a
    `switch` with **no `default`** precisely so a new state cannot silently map to a wrong wire value — and
    it would have failed the build the moment `REVERSED` was added to the enum. But nothing forced the
    *constant* to exist: the state is written by another service and read back through `valueOf`, which
    turns an unknown name into a runtime error rather than a compile error. **An enum read across a service
    boundary is a contract, and the consumer has to know every state the owner can write.**
  - **Fixed:** `REVERSED` added to `TransactionStatus` (mapped to the external `REVERSED` — terminal and
    visible, unlike `DEBITED`/`SENT_TO_SPI`), `failureReason` added to `Transaction` and read from the
    item, so the poll now tells the payer *why* the money came back instead of being less informative than
    the push. `FAILED`/`REJECTED` deliberately **not** added: no service writes them today, and a state
    nobody can produce is a fiction the mapping would have to keep honest.
  - **Tests:** `PaymentResponseTest#reversedMapsToReversedAndCarriesTheReason` (unit) and
    `StatusQueryIT#aReversedPaymentReadsBackAsReversedInsteadOf500` — the IT marks the transaction
    `REVERSED` with a **direct item update, the way settlement-service writes it**, never through
    payment-service code, because the defect is in reading back state another service owns and a test that
    produced the state through this service could not have reproduced it. `mvn -pl services/payment-service
    verify` green: 71 unit + 40 IT.
  - **Still open, and unchanged by this fix:** the sibling gap recorded in step 37 — an inbound transaction
    (`in-<endToEndId>`, status `RECEIVED_SETTLED`) queried through this endpoint still `500`s. It stays
    deferred to step 45's error-contract audit: unlike a reversal, no flow leads a client to that id.
  AI: est 0.5h / actual ~0.7h / ~85% generated / 0 issues caught in human review

- **Twin-harness drift: the external send was invisible in the API explorer and Postman.** Caught
  reviewing the manual-test harnesses against the code: since step 27 the send flow has had two
  destinations — internal (settles in one atomic posting) and external (debit to `SPI_CLEARING`, settle
  asynchronously via steps 28–31) — but **every** send card in `tools/api-explorer/index.html` and
  `tools/postman/pix-platform.postman_collection.json` targeted `bob@platinum.com`, so the entire
  external branch (`debitToClearing`, `DEBITED`, the async settlement hand-off) was undemonstrated. The
  external resolution *was* shown, but only as an account-service DICT lookup, never as an actual money
  move. Added **Send Pix — external (`bob@otherbank.com`, 202 → async settlement)** to both harnesses:
  identical wire shape to the internal send (authority, limits and fraud are properties of the *payer*,
  not of where the payee banks), resolving `internal:false` via BACEN's DICT, debiting to the clearing
  account, resting at `DEBITED` (external status still `PROCESSING`) until settlement-service (port 8086)
  walks it to `SETTLED`. The card pairs with the status poll so a reviewer watches `PROCESSING → SETTLED`
  flip live. This is doc/code drift of exactly the kind the "every endpoint in BOTH harnesses in the same
  step" convention exists to prevent; the fix is retroactive because the harnesses are living artifacts.
- **Twin-harness drift: the idempotency contract (ADR-0002 / step 19) was undemonstrated.** The second gap
  the same audit surfaced: every send card auto-mints a fresh `Idempotency-Key` (explorer `crypto.randomUUID()`,
  Postman `{{$guid}}`), so the *whole point* of step 19 — that a double-tap or a retried request replays the
  memoized `202` instead of minting a second transaction — could not be triggered from either harness. Added
  **Send Pix — idempotent replay (press Send twice, one debit)**, which pins a **constant** `Idempotency-Key`:
  the first click accepts a fresh payment and moves money once, every later click replays the same
  `transactionId`/`endToEndId` with **no** second debit (`409 IDEMPOTENCY_KEY_REUSED` if the body changes under
  the same key; `400 IDEMPOTENCY_KEY_REQUIRED` if omitted). Layer 1 of ADR-0002 made visible in two clicks.
- **API explorer: path parameters are now editable and GET cards can auto-poll.** The explorer only
  rendered an editable *body* textarea, so every card's path was frozen at its literal/derived value —
  yet a dozen descriptions instructed the reader to *"edit the path to acc-002"*, *"edit the id in the
  path"*, *"change creditorKey…"*, promising an affordance the UI never had, and there was no way to
  inspect an arbitrary `transactionId` (only the last one auto-captured from a send). Every non-login
  card now shows an **editable Request-path input** (change ids, keys, query params in place), a send
  reads the live value, and a captured `transactionId` is pushed into the status card's field while
  leaving it fully overwritable (paste any txId, incl. one from a prior run). GET cards also gain an
  **Auto-poll** toggle that re-queries every 2s and stops on a terminal status (`SETTLED/FAILED/
  REVERSED/REJECTED`) or a 40-poll cap — the missing tool for watching an **external** Pix flip
  `PROCESSING → SETTLED` without hammering Send. Closes the "edit the path" doc/UI drift; the settlement
  and DICT status cards inherit the same controls. Explorer-only; no service or contract change.
- **Audit note — a fraud-denied send is not demonstrable through the public endpoint under default seeds,**
  so no card was added (recording the finding rather than shipping a card that cannot fire). The fraud
  `HIGH_AMOUNT` line and the daily limit are both `R$5,000.00` (`500000` cents), and the orchestration
  reserves the limit *before* it screens for fraud (`reserveDailyLimit` → `screenForFraud`), so any amount
  large enough to trip `HIGH_AMOUNT` (`> 500000`) trips `LIMIT_EXCEEDED` first. Reaching `FRAUD_DENIED` from
  `POST /v1/payments/pix` needs a velocity build-up or a raised limit — out of scope for a single deterministic
  card. The DENY branch is already covered by `ScoreFraudUseCaseTest`; the fraud-service explorer/Postman card
  exercises the `/internal/fraud/score` seam directly.
- **API explorer: full request transparency & control.** Follow-on to the editable-path work, driven by
  hands-on review — the explorer is the human's primary way to drive and *understand* the platform, and it
  hid too much of each call. Every non-login card now exposes, pre-filled and editable, the values it will
  send: an **editable headers box** (`Key: Value` per line), an **editable body**, and a live **"This is what
  will be sent"** preview (method + full target URL + headers + body, recomputed on every keystroke;
  Authorization is summarised as `Bearer … (session: <accountId>)`, never dumped — it is a long opaque token).
  Two ids are seeded as real, visible values instead of being minted invisibly at click time: the
  **`Idempotency-Key`** on money-moving POSTs (same value = replay, per ADR-0002) and **`X-Correlation-Id` on
  every call** — the client originates the trace, the service reuses the header it receives and echoes it back
  (`CorrelationIdFilter`), so the id now also renders **under the response, click-to-copy**, and one
  `grep <cid>` walks every service that handled the request (ADR-0012). Clear the line to let the server mint
  one; the **`↻ new ids`** button re-mints only the ids the explorer generated, never an authored value (the
  fixed replay-demo key survives). Each **Send** now raises a transient **toast** (top-right, auto-dismiss)
  carrying the HTTP status, so an action always leaves a visible acknowledgement even when the body is empty;
  a stopped **Auto-poll** toasts its reason (`reached SETTLED`). Explorer-only; no service or contract change.
- **Verified, no change: only payment-service takes an `Idempotency-Key` header — and it should stay that
  way.** A review question ("don't the other services need it too, like the ledger?") prompted a sweep: the
  header is read in exactly one place in the whole codebase (`PaymentController`). Every other mutating
  endpoint is idempotent by a **natural business key in the payload**, which is stronger than an opaque
  client key because it is the operation's real identity and cannot be forgotten or mis-set: the ledger
  posting by `txId` (conditional `attribute_not_exists` write — the controller documents "*No Idempotency-Key
  header here*"), the SPI settlement by `endToEndId` ("first terminal outcome wins forever"), a Pix-key
  registration by the key value itself (`409 KEY_ALREADY_EXISTS`), `DELETE` by HTTP semantics, and event
  consumers by `eventId`. The header exists for the one untrusted, client-facing money-moving POST that has no
  natural id (ADR-0002 / Domain Safety Rule #2); adding it to an internal write would be redundant and weaker.
  The explorer already reflects this — only the five `/v1/payments/pix` cards seed an `Idempotency-Key`; the
  ledger and SPI cards carry their `txId` / `endToEndId` in the editable body.
  AI: est 2h / actual ~2.5h / ~90% generated / 5 issues caught in human review
  (1: external send undemonstrated in the twin harnesses; 2: card paths not editable — no way to poll an
  arbitrary `transactionId`; 3: outgoing request values not visible/verifiable before sending; 4: no
  send-confirmation feedback on the page; 5: `X-Correlation-Id` neither shown nor editable per call.)

### Added
- Prometheus + Grafana (technical + business-funnel dashboards as code), silence alerts and correlationId
  path tracing (step 44)
  Sprint 11's flow: **see the whole system**. Three layers — logs (what happened to *this* request),
  metrics (how the *system* behaves), dashboards (who needs to see it) — plus the layer that matters most
  in an asynchronous platform: **alerts on the absence of events**.
  - **The business funnel is the point of the sprint.** One counter, `pix.payments.stage{stage,outcome}`,
    written by **two services** — payment-service owns `RECEIVED → FRAUD_CHECKED → DEBITED` (and `SETTLED`
    for an internal send, whose ledger posting *is* its settlement); settlement-service owns
    `SENT_TO_SPI → SETTLED` and the `REVERSED` branch. The same metric family from both sides of the
    asynchronous seam is what lets one Grafana panel draw one funnel across a process boundary, and it
    holds only because the tag vocabulary is a **shared enum** (`PixMetrics` in common-lib): Prometheus
    would happily store `stage="SENT_TO_SPI"` and `stage="sent_to_spi"` as two unrelated series and the
    funnel would split in half with nothing failing anywhere.
  - **Four counting rules make the funnel mean something**, and each is pinned by a test: `RECEIVED/ok`
    fires on a *won idempotency claim* (exactly once per payment, never once per HTTP request);
    **retryable failures are not rejections** (an unreachable ledger decides nothing — counting it would
    report a death the retry then resurrects); a payment is counted at the stage that *actually* refused
    it (limits at `RECEIVED`, a `DENY` at `FRAUD_CHECKED`, insufficient funds at `DEBITED`); and a payment
    is counted **once per stage, never once per attempt**.
  - Alongside it: `pix.fraud.decision{decision}` (owned by payment-service because only the *caller* can
    observe `SKIPPED`, the fail-open of ADR-0005 — that share *is* the fail-open rate),
    `pix.settled.amount` in **integer cents** (the division by 100 happens on the dashboard, never in the
    platform), `pix.idempotency.replayed` (KR1.1's live evidence: replays climb while `DEBITED` does not)
    and `pix.reconciliation.resolved{action}`.
  - **Every earlier operational metric renamed to the `pix.*` prefix** (`cache.hit`, `cache.miss`,
    `outbox.lag`, `settlement.dlq.depth`, `reconciliation.oldest.seconds`, `reconciliation.resolved`,
    `fraud.score`). One convention beats a catalog that needs a legend. Historical step files and
    CHANGELOG entries keep the names they were written with — they are a record, not a live contract.
  - **`PrometheusMetricNamesTest` pins the exact scrape output**, in payment-service and
    settlement-service, and it exists because of a trap found while writing this step: the Micrometer name
    is *not* the Prometheus name. The convention appends `_total` to every counter **and** the meter's
    `baseUnit` when one is set, so a well-meant `.baseUnit("payments")` silently turns
    `pix.payments.stage` into `pix_payments_stage_payments_total` — at which point every dashboard panel,
    every PromQL rule and the catalog are querying a series that no longer exists and **nothing fails**.
    The graph simply goes empty, which is the most expensive failure mode observability has. `baseUnit` is
    now used only where it improves the name (`pix_settled_amount_cents_total` — for money the unit is
    worth a word).
  - **Latency is exported as a percentile histogram**, configured once in common-lib
    (`CommonMetricsAutoConfiguration`, the metrics counterpart of the shared logback config), with
    **explicit buckets on the two SLO boundaries** (300ms, 2s). A quantile computed inside each JVM
    exports as a plain gauge and quantiles do not aggregate — the average of two instances' p99s is not a
    percentile of anything — so only buckets can honestly answer an SLO stated *for the platform*; and a
    bucket sitting exactly on the budget turns "what fraction met the SLO?" into a division of two
    counters instead of an interpolation.
  - **Prometheus (host 9091) + Grafana (3000) in compose, always on, not an optional profile** — a
    dashboard you have to remember to start is down exactly when something breaks. Grafana is
    **provisioned as code** from `infra/observability/`: datasource, dashboard provider and both
    dashboards are committed, `allowUiUpdates: false`, anonymous `Viewer` so a reader hits no login.
    Two dashboards, 12 panels each — **Technical** (p50/p99 vs SLO lines, % inside SLO, throughput, 5xx
    rate, DLQ depth, outbox lag, reconciliation age, cache hit rate, JVM, scrape targets) and **Business
    Funnel** (the funnel, R$ settled, duplicates absorbed, fraud mix, fail-open rate, stage throughput,
    three conversion ratios, reversals, *where payments die*, reconciliation actions).
    **4xx is deliberately excluded from the error-rate panel**: a `422 LIMIT_EXCEEDED` is the platform
    working correctly, and folding refusals into an error rate is how a dashboard learns to cry wolf —
    they live in the funnel's `REJECTED` branch, as a product signal rather than a fault.
  - **`AlertEvaluator` (settlement-service): six rules in three shapes** — threshold (DLQ depth `> 0`,
    reconciliation age `> 300s`, outbox lag `> 60s`), ratio (fail-open ceiling 5%, cache-hit floor 70%)
    and **silence** (debits flowing while `SETTLED` stands still for 120s). Silence is the shape that
    matters: a synchronous system fails as an *error*, an asynchronous one fails as **nothing at all**,
    and every error rate on the dashboard stays a healthy zero while money accumulates in the clearing
    account. Both halves of the condition are load-bearing — without the input check it fires every quiet
    night; without the duration check it fires between any two settlements. Its input is **`DEBITED`**,
    the stage the *other* service owns, because if settlement-service is what is wedged then
    `SENT_TO_SPI` stops advancing too and a rule comparing two stalled counters sees a perfectly quiet
    system.
  - Three behaviours make it a signal instead of noise: it **announces transitions, not conditions** (a
    rule firing for an hour is still firing); it **refuses to guess** — a missing sample or a ratio with
    too little traffic yields `SKIPPED` and leaves the remembered state untouched, so a monitoring outage
    can neither invent an incident nor silently close one (`0/0` has no safe convention: call it 0 and the
    cache floor fires every quiet night, call it 1 and the fail-open ceiling can never fire); and it
    **logs in ADR-0012's contract**, with `rule=`, `observed=`, `state=` and a `runbook=` on every line.
  - **The watchdog reads Prometheus, not its own registry** (`MetricSource` port → `PrometheusMetricSource`),
    because three of the six rules watch metrics *other* services own — and the failure it exists to catch
    is precisely a statement about two services at once. The dependency is **soft by construction** and
    compose deliberately gives settlement-service **no `depends_on: prometheus`**: gating a service that
    moves money on the monitoring stack being up would invert exactly the wrong priority. In production
    these become an Alertmanager rules file next to the same Prometheus; they are in code here so the
    platform can say something is wrong under plain `docker compose up`, and so each rule has a unit test
    proving it fires.
  - **`scripts/trace.sh <correlationId|txId>`** collates and time-orders every service's logs for one
    request or one payment. It is fifty lines of `grep` and not a tracing backend *precisely because* the
    id is in the log **pattern** (ADR-0012): no service has to remember to print it, framework lines carry
    it too, and the path is already written down in order. **Verified live: one external send is 48 lines
    across 7 services**, spanning the synchronous request and the whole asynchronous settlement
    (payment → account → DICT → fraud → ledger → outbox publisher → settlement → SPI → ledger →
    notification). It accepts a `txId` too, because work no request started honestly has no correlation
    id — a documented gap, since `correlationId` lives on the outbox items and not on the transaction.
  - **Path audit (task 5) found and fixed a real gap**: the reconciliation loop logged `txId=` as a pair
    but left the MDC's `tx=` slot at `n/a` for the whole resolution, so `grep tx=<id>` — and every
    framework line emitted while rescuing that payment — missed it. `ScanStuckTransactionsUseCase` now
    adopts the id onto the MDC around each resolve, the same treatment the outbox publisher already had.
  - Docs: **`docs/observability.md` (new)** — metric catalog, the funnel's counting rules, the alert table,
    the drill and the path audit. `ARCHITECTURE.md` §7.7 updated (the evaluator's Prometheus vantage
    point, the histogram reasoning, `trace.sh`); `docs/local-dev.md` §5.9 is now an observability runbook
    (the stale "`/actuator/prometheus` does not exist yet" note is gone); every service README documents
    the scrape endpoint; settlement-service's documents all nine alert knobs. Postman gains an
    **observability** folder (5 requests) and the API explorer an **observability** section (6 PromQL
    cards) plus a runnable **Observability journey** that reads the funnel, sends a Pix, reads it again
    and prints the deltas — then hands you the `trace.sh` command for that exact payment.
  - **Three defects reached a working dashboard and were caught by the drill, not by the suite** — each a
    category, all three written up in `docs/observability.md` §6:
    1. **A `@Scheduled` placeholder no test could resolve.** `fixedDelayString` accepts milliseconds or
       ISO-8601 — *not* the `30s` form that `@ConfigurationProperties` `Duration` binding accepts happily.
       Both halves looked right; the context died at startup. **No IT could have caught it**: every IT
       sets `pix.schedulers.enabled=false`, so the bean is never created and the annotation never
       processed. Now guarded by **`ScheduledPlaceholdersTest`**, which checks the *static* relationship
       between the annotation and `application.yml` with no Spring context — and which was verified to go
       red on the original bug.
    2. **PromQL is written in exactly the characters a URL treats as structure.** `{stage="DEBITED"}` is
       URI-template syntax to Spring's `RestClient`, and `+` is legal unencoded in a query string but
       decodes to a **space** in Go's form parser, so `sum(a) + sum(b)` became a syntax error — and only
       for the rules that add two series. Both surfaced as an unhelpful `400 bad_data`. Fixed by
       submitting the expression as a form body (what Grafana's own datasource does). *The design held
       under this*: every failure degraded to `SKIPPED` and not one false alert was raised.
    3. **The funnel counted attempts, not payments.** With the rail failing, the dashboard showed **31**
       payments at `SENT_TO_SPI` against **13** ever `DEBITED` — a conversion panel reading above 100%.
       Correct behaviour meeting a careless counter: the transition's guard deliberately accepts a
       transaction that is *already* `SENT_TO_SPI` so a redelivery can re-stamp `updatedAt` (step 32).
       `markSentToSpi` now returns whether it actually moved the payment (`ReturnValue.ALL_OLD`, no extra
       read), and the stage is counted only on the first claim. Proven live afterwards: **4 attempts at
       the rail, `SENT_TO_SPI = 1`**.
  - **Drill run, and the alert lifecycle observed end to end** (`failureRate=1.0`, debits kept flowing):
    `RESOLVED` (baseline, 55s) → **`FIRING`** (326s stalled) → `RESOLVED` (0s, caught up) — one line per
    transition, not one per 30s tick. Prometheus scrapes **9/9 targets up**; both dashboards render live
    data through the provisioned datasource.
  AI: est 6h / actual 7.5h / ~90% generated / 3 issues caught in live verification (see above)

- Immutable audit trail to S3 (partitioned JSONL) + statement cold-archive job (step 43)
  Sprint 10's flow closes: the buckets step 42 created now have writers. **Two jobs, in two services,
  with deliberately opposite postures** — one appends a record that may never change, the other
  maintains a projection that is rewritten on every run.
  - **`AuditWriter` (settlement-service) — the platform's long-term event store.** It consumes
    `audit-queue`, the only subscription on `pix-events` with **no filter policy**, and appends every
    event as a JSON line to `s3://pix-audit-log` under `yyyy/MM/dd/HH/<service>-<uuid>.jsonl`, batched
    at **~100 events or 30s**. This is the SNS/SQS answer to "where is the replayable log?"
    (`docs/messaging-kafka-appendix.md`).
    - **The line is the event, verbatim** — the envelope as published, merely compacted to one line
      because JSONL requires it. Nothing is re-shaped, renamed or enriched: an audit trail records what
      happened, so a field this platform does not understand today must still be in the file the day
      someone needs it. The consumer parses only enough (`eventId`) to dedupe and log.
    - **Batching is a cost decision, and it is safe here precisely because this is not the money path.**
      One `PutObject` per event would multiply the platform's event rate by a request each and fill the
      bucket with millions of tiny objects that are slow to list and expensive to keep. The event was
      already committed by its producer and is held by SQS until the batch lands, so the worst case of a
      crash mid-batch is a redelivery. The **age threshold is measured from the oldest buffered event**,
      not the newest — with a trickle of one event per second a last-event timer would never fire and the
      oldest line would sit unwritten forever.
    - **Three consequences of holding a message longer than its visibility timeout**, each of which the
      step had to answer rather than hope about. *(1) The buffer owns the lease*: a buffered message has
      its visibility extended to `AUDIT_LEASE_SECONDS` (120s) the moment it enters the batch — the queue's
      own timeout is 30s, so without it SQS would hand a still-buffered message to another receiver and
      the line would be written twice for nothing. *(2) The long poll is capped by the flush deadline*:
      a static 20s receive against a 30s promise would let a batch age up to 50s, so when something is
      buffered the wait is `min(20s, time left)`. *(3) Backpressure*: while the batch sits at its cap
      (a failing S3), the tick **stops receiving** and only retries the write — the backlog belongs in
      SQS, which is durable and has a DLQ, not in this JVM's heap, which has neither.
    - **Write, then acknowledge — never the reverse.** The use case hands back acknowledgement tokens
      only for lines that are durable, and the adapter does nothing but obey that list; a refused
      `PutObject` throws with the buffer intact, so nothing is acked and nothing is dropped. This is the
      audit equivalent of "never ack a payment you did not settle", and it is why
      `AuditBatch.pending()` is a snapshot rather than a drain — draining first would take the lines with
      it on a failure.
    - **Duplicates tolerated, gaps not.** Within a batch the `eventId` collapses a redelivery to one line
      while **both** receipt handles are still acked (an un-acked duplicate would loop into the DLQ and
      have someone investigate a non-problem). Across batches a duplicate line is simply possible, and
      that is deliberate: a durable dedup gate (`pix_processed_events`) would have to be marked *before*
      the S3 write, so a marked-then-failed write would erase an audit line permanently. Recording a fact
      twice is a nuisance a reader filters by `eventId`; failing to record it once is the only real error.
    - **The partition is ingestion time, not event time.** One flush is one object, and an object must
      never be written into an hour a reader may already have scanned. The price is stated rather than
      hidden: an event delayed across the boundary lands in the next hour's prefix, so an
      exact-by-event-time query reads the neighbouring partition and filters on the `occurredAt` inside
      each line. The `<uuid>` in every key is what makes the write safe — keys are unique, so the trail
      only grows and a retry never overwrites (on a locked bucket an overwrite would instead pile up
      undeletable versions of the "same" file). The writer sets **no retention**: COMPLIANCE/1825 days is
      a *bucket default* S3 stamps on every object, so a writer cannot forget to retain a line.
  - **`StatementArchiver` (ledger-service) — the cold tier the 5-year requirement needs.** An hourly job
    copies entries older than `pix.archive.hot-window-days` into `s3://pix-statement-archive` as
    `account=<id>/yyyy-MM.jsonl` (Hive-style partition, so the archive is queryable as a table with no
    transformation step). At the planning volume that is ~3.6TB/year of hot data whose oldest 95% is read
    almost never (ARCHITECTURE §1); step 53's async export reads exactly these objects.
    - **It lives in ledger-service, and that was the one real design call of the step.** The step file
      says "a scheduled `StatementArchiver`" without naming a service; putting it in settlement-service
      (which already had the S3 client) would have meant a second service reading `pix_ledger` — a
      **third** exception to ADR-0006's "services never share tables", without the atomicity requirement
      that justifies the existing two. ARCHITECTURE's container diagram gained the `LED --> S3` arrow in
      the same change.
    - **Nothing is deleted from the ledger — locally, on purpose, and the reason is worth keeping.**
      Production finishes the job (a TTL attribute or a bounded delete pass once the object is written
      *and verified*), and that removal is what actually reclaims the storage the cold tier exists for.
      Here it is skipped twice over: the emulator's S3 state is ephemeral, so a `down -v` would take the
      archive with it and deleting would destroy history in exchange for nothing — and *no code path
      capable of deleting a ledger entry* is a stronger guarantee of append-only history (safety rule 5)
      than a careful one. Enforced structurally: `LedgerArchiveReader` can only read, `StatementArchive`
      can only write, and they are separate ports from `LedgerRepository` for exactly that reason.
    - **Rewriting a month whole is the update primitive**, because the archive is *derived* data (the
      ledger stays the source of truth). It makes the job idempotent, makes an interrupted run a
      non-event, and is how the boundary month grows as the window rolls forward. It is also why
      `pix-statement-archive` is a plain bucket while `pix-audit-log` is locked — on a locked bucket the
      same behaviour would accumulate undeletable versions of a regenerable file.
    - **The account list is a `Scan`, the entries are a `Query`** — and the asymmetry is the lesson.
      `pix_ledger` has no index of accounts (the BALANCE items *are* the list), so enumerating them is a
      filtered scan charged for the ENTRY items it discards; that is the honest cost of a whole-ledger
      batch job and why it runs hourly, off the request path. The entries need no scan at all: the sort
      key is `ENTRY#<isoTimestamp>#<txId>`, so "everything older than T" is the key range
      `BETWEEN 'ENTRY#' AND 'ENTRY#<T>'` — the same lexicographic-equals-chronological trick the
      newest-first statement uses, read forwards. The read is paged **to completion** on purpose: a
      truncated month would be written truncated and later runs would read the same first page again and
      never repair it.
    - **The archive line is not `LedgerEntry`.** `ArchivedEntry` carries its own `accountId` (in DynamoDB
      that is the partition key, but an archive object is read alone, years later, by a process that has
      only the file) and the `description` (which the statement API composes at its edge — an archive
      without it is a statement nobody can read back). Money stays **signed integer cents** into the file:
      an internal artefact is not an API edge, and decimal formatting is exactly the lossy convenience a
      five-year record must not carry.
  - **A regression the context test caught, not a review.** ledger-service's first `@EnableScheduling`
    registers Spring's own `taskScheduler`, which *is* an `Executor` — so step 40's by-type injection of
    the balance-eviction executor suddenly had two candidates and `ApplicationContextIT` stopped loading.
    Fixed by qualifying it (`@Qualifier("balanceCacheEvictionExecutor")`) rather than marking a
    `@Primary`: eviction must run on the small, bounded, discard-on-saturation pool built for it and
    never on the scheduler's threads, where a slow Redis would delay unrelated background jobs. Worth
    noting *which* test caught it — every IT extends `LocalStackTestBase`, which disables schedulers, so
    the whole IT suite was green; only the plain context test boots the service as it actually runs.
  - **Tests (10 new unit + 7 new IT).** `AuditBatchTest` (9) pins the policy in plain Java: neither
    threshold met, the count threshold, the age threshold measured from the oldest event, an empty batch
    never due, a duplicate as one line and two acks, arrival order, the deadline the long poll reads, and
    the full-batch backpressure signal. `RecordAuditEventsUseCaseTest` (6) pins the contract, including
    **a failed write acking nothing and keeping every line** for the next attempt. A test written wrong
    first taught something worth keeping: two duplicates do **not** fill a batch of two — the count
    threshold counts the lines that would be written, not the messages that arrived, so a redelivery
    storm of one event can never trigger a flush by volume (its `maxAge` still bounds the wait).
    `ArchiveOldEntriesUseCaseTest` (6) pins the cutoff, the per-account-and-month grouping, UTC month
    derivation, "nothing cold ⇒ no object", signed cents, and that running twice produces the same
    archive *because* the first run took nothing away. `AuditWriterIT` (3) drives the real SNS → SQS → S3
    path: a full batch as one partitioned JSONL object then acked, a lonely event written on its age
    alone, and a buffered message invisible to a competing receiver (the lease). `StatementArchiverIT` (4)
    covers the monthly objects, the second run rewriting rather than duplicating, "nothing cold ⇒ no
    object", and — the assertion that matters — **hot storage untouched**: all four postings still in the
    ledger, both balances unchanged.
    - `AuditWriterIT` deliberately publishes **no `PixDebited`**. The topic fans out to three queues and
      one of the others lives in this same module: such an event would also land on `settlement-queue`,
      name a transaction that does not exist, ride five receives into the DLQ and break
      `SettlementRetryIT`'s exact depth assertion — a failure caused entirely by a neighbouring test. The
      types it does publish are precisely the ones `settlement-queue`'s filter excludes, which is what
      makes them proof that the audit subscription filters nothing; that a `PixDebited` *also* arrives is
      pinned at the infrastructure level by `MessagingInitIT` (step 42), where it belongs.
  - **A local-build trap, now written down instead of rediscovered.** Two failures in this step looked
    like broken init scripts (`QueueDoesNotExist: audit-queue`, then `501 Service 's3' is not enabled`)
    and were the same thing: `mvn -pl <module> verify` **without `-am`** resolves `LocalStackTestBase`
    from a stale `common-lib` test-jar in `~/.m2`, whose older readiness marker releases the tests while
    the newest init script is still running. Documented in `docs/local-dev.md` §6 and added to the §7
    troubleshooting table, per the project's rule that a known environment quirk is fixed (or at least
    written down) rather than left as a command someone must remember. `mvn verify` from the root is
    always safe — it uses the reactor.
  - **Docs updated in the same change**, as the convention requires: ARCHITECTURE §2 (the new `LED --> S3`
    arrow and labelled audit arrow) and §6.10 (where each job lives and why, the two postures, the
    batching consequences, the ingestion-time partition, the deliberate non-deletion, plus a second
    sequence diagram for the archiver); `docs/data-model.md` gained **§8 Object storage (S3)** — the full
    immutability posture as a table of "why that and not the alternative", and the cold-archive shape
    (old §8 renumbered to §9); `docs/local-dev.md` §3 (11 new env vars), §5.8 (rewritten: how to see both
    jobs work, including proving the Object Lock retention is stamped by the bucket and that the delete is
    refused) and §6/§7 (the `-am` trap); both service READMEs.
  - **Compose knobs chosen for the sandbox, not copied from production**: ledger-service runs the archive
    job **every minute with a 0-day hot window**, because a freshly seeded stack has nothing older than
    the 90-day default and the demo would archive nothing and look broken.
  - **No endpoint is introduced**, so the Postman collection and the API explorer are untouched. Step 42
    promised the audit *journey* "when there is something to read back" — the honest answer is that
    reading it back is an AWS-CLI affair until an endpoint exists: a browser cannot list an S3 bucket
    without SigV4 signing or CORS the sandbox does not enable, and adding a read endpoint is neither in
    this step's tasks nor needed before step 53, which introduces exactly that read path. The journey
    moves there; the manual verification lives in `docs/local-dev.md` §5.8 and both READMEs.
  AI: est 3h / actual 2.6h / ~92% generated / 0 issues caught in human review (3 caught by the suite: the
  `Executor` ambiguity, a batch-count assumption in a test, and an IT that asserted on its neighbours)

- LocalStack init: audit-queue (all events) + S3 audit-log/statement-archive buckets with immutability
  config (step 42)
  **The third consumer off `pix-events` — and the only unfiltered one.** `09-audit.sh` creates
  `audit-queue` + `audit-queue-dlq` with the same tuning as the other two branches (redrive after 5
  receives, visibility 30s, long-poll 20s, DLQ retention 14d, the narrow `sqs:SendMessage` policy) and
  subscribes it to the topic with **no filter policy at all**. settlement (`PixDebited`) and notification
  (`PixSettled`/`PixReceived`/`PixReversed`) each name what they act on; audit does not act on events, it
  records that they happened — and a filter is a list somebody has to remember to extend, so the first
  unlisted event type would be missing from the trail silently, forever. The script therefore also
  *removes* a filter policy if one drifted in (empty `--attribute-value`): converging to "none" has to be
  an action, not an omission.
  - **S3 comes up now, for the flow that needs it.** `SERVICES` grows to `sns,sqs,s3` in compose and
    `withServices(…, Service.S3)` in `LocalStackTestBase` — the same change, as the init README requires,
    since an unlisted service answers `501` and would abort `ready.d` under `set -e` and hang every IT.
  - **`pix-audit-log` is created with `--object-lock-enabled-for-bucket` + a default retention of
    COMPLIANCE / 1825 days.** COMPLIANCE rather than GOVERNANCE on purpose: GOVERNANCE is bypassable by
    any principal holding `s3:BypassGovernanceRetention`, i.e. exactly the privileged operator an audit
    trail exists to keep honest. The retention is a *bucket default*, so every object inherits it with no
    caller opt-in — the audit writer of step 43 cannot forget to retain a line.
  - **A first draft failed and taught the real rule:** an explicit `put-bucket-versioning Status=Enabled`
    after the lock flag is rejected with `InvalidBucketState — an Object Lock configuration is present on
    this bucket, so the versioning state cannot be changed`, *even though it asks for the state the bucket
    is already in*. On a locked bucket, versioning is not configuration you converge; it is a property you
    inherit at creation and can never suspend — which is the guarantee we want, since suspending
    versioning would be the first move of anyone erasing a trail. The call is gone and the reason is
    written in the script.
  - **`pix-statement-archive` is deliberately a plain bucket** — no versioning, no lock. It holds derived,
    rebuildable data (the ledger remains the source of truth) whose monthly
    `account=<id>/yyyy-MM.jsonl` object step 43 rewrites as the window rolls; locking it would pile up
    undeletable versions of a regenerable file and buy no compliance at all.
  - **LocalStack turned out to *enforce* the posture, not merely accept it** (the step file assumed the
    weaker claim): deleting a retained version answers `AccessDenied`, and a new object comes back from
    `PutObject` already carrying `RetainUntilDate ≈ today + 5y`. `S3InitIT` asserts both, so the audit
    trail's "an audit line cannot be deleted" is a *tested* behaviour rather than a footnote. What stays
    AWS-only is everything below the API — WORM at the storage layer, surviving `docker compose down -v`
    (the emulator's state is ephemeral, so the local 5-year retention lasts exactly as long as the
    container), replication, and IAM actually denying anything (ADR-0013). Documented in `docs/local-dev.md`,
    the init README and ARCHITECTURE §6.10.
  - **Tests:** `MessagingInitIT` grows the third branch (queue + DLQ + redrive, the *absence* of a
    `FilterPolicy` asserted explicitly, and an end-to-end pass where both `PixDebited` and `PixSettled` —
    disjoint for the other two consumers — land on audit-queue). New `S3InitIT`: both buckets exist,
    versioning `Enabled`, lock `COMPLIANCE/1825`, the object retained, the version delete refused, and the
    archive bucket asserted *plain* so that decision stays deliberate. `mvn verify` green across all modules.
    - **CI caught a race the local run hid.** The audit test waited for *one* of the two published
      eventIds and then asserted on *both*: SNS→SQS delivery is neither ordered nor simultaneous, so the
      helper could return with the second event still in flight and throw it away — green on a developer
      machine, red on the first CI run. `receiveUntil` now takes the ids as varargs and only returns once
      **every** one of them has arrived (naming the missing ones when it gives up), which is the honest
      shape for any assertion about a fan-out.
  - **Both readiness markers moved to `09-audit.sh`** (it now sorts last): `LocalStackTestBase` waits on
    `[init] audit storage ready: …` and the compose healthcheck probes the last resource it creates
    (`s3api head-bucket --bucket pix-statement-archive`). Three older init scripts still claimed to *be*
    the marker (`06` since step 36, `07`, `08` as of now) — those stale comments were corrected in the
    same change rather than left as a trap for the next step.
  - No endpoint is introduced, so the Postman collection and the API explorer are untouched; the audit
    journey belongs to step 43, when there is something to read back.
  AI: est 1h / actual 1h / ~90% generated / 0 issues caught in human review

- **API explorer: journey tabs, and a navigation that fits on the page.** Delivered ahead of step 49 (which
  finalizes the explorer) because the step-39 review asked a question the tool could not answer: *how do I
  test receiving a Pix?* The honest answer was "open the mock-bacen-spi tab, but first go to account-service
  as bob and register a key, then watch it in a third tab" — three tabs and an invisible prerequisite, which
  is a wall of endpoints failing to tell a story.
  - **Five journeys**, each an ordered list of real calls against the running stack, carrying the ids the
    previous steps captured (shown as chips at the top) with a paragraph under each step saying what it
    *proves*: **Receive Pix** (login → register key → open the SSE stream → BACEN delivers → balance +R$
    77,77 → re-deliver the same `endToEndId` → `ALREADY_PROCESSED` → balance unchanged), **Send Pix ·
    internal** (resolve `internal=true` → send → `SETTLED` at once → both balances, sum of deltas = 0),
    **Send Pix · external** (202 `PROCESSING` → poll → `SETTLED`), **Reversal** (arm the SPI refusal →
    send → `REVERSED` → money restored to the cent → disarm) and **Idempotency** (same key replays the
    same `transactionId`, one debit; a different body under it ⇒ `409`). Run all, or any step on its own.
  - **Cleanup runs even when a journey stops.** Steps can be marked `always`, so a run that fails half-way
    still disarms the mock-BACEN reject knob — a knob left armed silently poisons every later run, which is
    exactly what happened while this was being built.
  - **Layout, measured rather than eyeballed:** nine service tabs in one row had `scrollWidth` 1175px inside
    an 876px container — names wrapping onto three lines, the last tab clipped, and the whole page scrolling
    horizontally (`body.scrollWidth` 1457 at a 1440 viewport). Fixed by grouping the navigation into
    **Journeys · Services · Phone** (each group's row is short by construction), widening `main` 940 → 1180px
    and making tabs `nowrap` with their own scroll. Verified in a headless browser: one row, no tab overflow,
    no page overflow. The page now opens on Journeys — "does the product work?" before "what endpoints exist?".
  - **The Phone stays, and appears where it is useful:** the same handset (one connection, one customer)
    renders beside the steps of every journey that produces a notification, so the push arrives while you
    read the next step instead of in another tab.
  - **Verified end to end in a headless browser against the live stack: 36/36 steps across the five
    journeys.** Two defects of my own were caught that way (a step missing its `path`, producing
    `http://localhost:8082undefined`; and the login step dumping the whole JWT into the response pane,
    breaking the explorer's own rule that a token is summarised and never printed — now
    `eyJ… (claims: u-bob · account acc-002)`), and one defect of the platform's, which has its own entry
    under **Fixed** above.
  AI: est 2h / actual ~2.5h / ~90% generated / 0 issues caught in human review

- Redis cache-aside for balance with invalidation on postings and a 5s TTL backstop (step 40)
  **`GET /v1/accounts/me/balance` (payment-service, 8084)** — the platform's highest-volume read, under a
  hard 300ms p99 budget. A hit is served from Redis (`balance:<accountId>`, TTL 5s); a miss reads
  ledger-service's strongly-consistent balance, populates the key and answers. **ledger-service deletes
  both legs' keys after every committed posting**, so the wire never shows a number the ledger has
  already moved past. Measured on the compose stack: **p50 3.9ms · p95 5.2ms · p99 9.8ms** over 200
  serial reads (a sanity check, not a load test — that is step 47).
  - **The rule that makes a cache legal on a money platform, enforced by the build.** The cache serves
    *display* reads only: `balanceCents >= :amount` is a condition expression **inside** the ledger's
    `TransactWriteItems` (step 14, Domain Safety Rule #3), so a stale, corrupt or absent cache changes
    what a customer *sees* for at most one TTL and can never change what the ledger *allows*. That is
    now a build failure rather than a review habit — `PaymentArchitectureTest` fails if any domain class
    other than `GetBalanceUseCase` so much as references the `BalanceCache` port, which is what makes a
    "check the balance before sending" shortcut in `SendPixUseCase` unmergeable. Proven from the other
    side too: `BalanceCacheInvalidationIT#aStaleCacheDoesNotAuthorizeAnOverdraft` stuffs Redis with ten
    times the real balance and still gets `422 INSUFFICIENT_FUNDS`, not a cent moved.
  - **Two ports, one per side of the seam, and neither can do the other's job.** payment-service's
    `BalanceCache` can read and write but **not evict**; ledger-service's `BalanceCacheInvalidator` can
    **only delete** — it cannot read a balance, so it cannot be misused to decide one. Invalidation
    belongs to the writer because only the writer knows the instant a cached balance became a lie, and
    it happens *after* the commit: evicting first opens a window where a concurrent reader repopulates
    the cache with the pre-commit number and nothing invalidates it again. The shared key format is
    documented as a two-service contract in `docs/data-model.md` §7 (new: the Redis keys).
  - **`asOf` is part of the contract, not decoration.** It reports *when the ledger was read*, and a
    cache hit keeps the original instant instead of re-stamping itself fresh — so a client (or a support
    engineer holding a screenshot) can tell how old the number is. The use case owns that clock.
  - **A cache that HANGS is worse than a cache that fails — found by the drill, not by reasoning.** The
    first version wrapped every Redis call in a `try/catch` and called it best-effort. Stopping the Redis
    container turned a balance read into a **114-second** request and, far worse, made a send answer
    **`503 LEDGER_UNAVAILABLE` for a debit that had already committed** (the eviction blocked past
    payment-service's 3s read timeout). Three fixes, in increasing order of importance: bounded
    `spring.data.redis.timeout`/`connect-timeout`; a **fail-fast Lettuce client** (`RedisFailFastConfig`
    — `TimeoutOptions.enabled(...)`, because Lettuce *queues* commands while reconnecting and queued
    commands ignore the command timeout, plus `DisconnectedBehavior.REJECT_COMMANDS`); and finally
    moving the eviction **off the posting's request thread** onto a bounded, discard-on-saturation
    executor. After: Redis stopped ⇒ balance reads `200` in ~13ms and sends `202` in ~200ms. Recorded as
    implementation notes on ADR-0008 (the decision is unchanged; what "best-effort" costs to build is
    not). Regression-tested by `RedisBalanceCacheIT#aRedisThatAcceptsAndThenNeverAnswersCostsTheTimeout…`
    (a `ServerSocket` that accepts and never replies) and by a wiring assertion in both services that
    the configured command timeout stays bounded.
  - **Failure posture, stated as a hierarchy:** Redis down ⇒ every read is a miss, straight to the
    ledger (latency, never errors); ledger down ⇒ reads still served for accounts touched in the last 5s;
    eviction lost ⇒ ≤5s of display staleness, which is exactly the job the short TTL exists to do.
  - `cache.hit` / `cache.miss` (tagged `cache=balance`) registered eagerly so both series read a real
    zero from boot. **Note:** `/actuator/prometheus` does not exist yet — the Prometheus registry lands
    in step 44 — so the runbook, README, Postman and explorer all point at
    `/actuator/metrics/cache.hit` for now; every doc that promised the Prometheus form was corrected.
  - **Docs corrected against reality in the same change** (CLAUDE.md's no-drift rule): `docs/api/openapi.yaml`
    gained the balance endpoint's description, `401`, `404 BALANCE_NOT_FOUND` and `503`; `docs/data-model.md`
    gained §7 (Redis keys, including fraud-service's, previously undocumented) and a retitled header;
    ARCHITECTURE §6.9 gained the off-thread eviction; and the step-40 ADR companion's stale-cache drill
    was rewritten to go at **ledger-service** — through payment-service the R$ 5,000 daily limit refuses
    such a send first, so as written it would have demonstrated `LIMIT_EXCEEDED` and never reached the
    guard under test.
  - Postman (2 requests) and the API explorer (2 cards) grew with the endpoint, per convention; the
    sprint's *journey* waits for step 41, which completes the balance-and-statement flow.
  AI: est 2.5h / actual ~4h / ~90% generated / 3 issues caught in human review

- **API explorer: the Balance & statement journey, and every journey step now shows its wire traffic.**
  Completes Sprint 9's flow in the tool (the journey step 40 deferred), and closes a gap the whole
  journey feature had since it landed: a step announced *what* it proved and printed a response body,
  but never showed the **request** that produced it — so the one artifact a reader needs to reproduce a
  call by hand was the one thing missing.
  - **Every step now renders REQUEST above RESPONSE**, for all 6 journeys and all 44 steps. It is
    captured by instrumenting the shared `send()` helper rather than by asking each step to describe its
    own call, which is what keeps it honest: the transcript is *the traffic that actually happened*, so a
    step cannot document one request and send another, and a step that fires seven sends shows seven
    without a line of extra code. Stacked, not side-by-side — a journey with the phone docked leaves that
    column ~540px, and two columns of JSON at that width are unreadable.
  - **Multi-call steps fold.** Beyond three calls the middle collapses into one clickable
    `⋯ N more calls ⋯` row, keeping the first and the last: the new mass-data step fires 7 and the
    settlement poll up to 25, and a card that dumps all of them buries the flow it exists to show. The
    count stays visible — collapsed, never hidden.
  - **Credentials still never print.** The bearer token is summarised to its claims
    (`Bearer eyJ… (claims: u-alice · account acc-001)`) and the shared webhook token is replaced
    outright, in both directions of the transcript — the sandbox-logging line ADR-0012 draws. The
    pre-existing `redactedLogin` now delegates to that one redaction rule, which also fixes a latent
    mislabel: it read the claims from the *global session* instead of from the response's own token, so a
    two-user journey labelled bob's login with alice's account.
  - **New journey · Balance & statement** (8 steps): read the balance twice inside one step and prove
    the second was a **cache hit by asserting the two `asOf` values are identical** — `asOf` reports when
    the *ledger* was read, so a hit returns the original instant instead of re-stamping itself fresh;
    **generate history** with 7 real internal Pix of R$ 1,00 (the mass-data step — three entries cannot
    demonstrate pagination, and each is a genuine payment through idempotency → limit → fraud → atomic
    posting, each with its own fresh `Idempotency-Key`); assert the balance fell by **exactly 700 cents**
    (invalidation, not TTL expiry); then page the statement with `limit=5`, assert newest-first, decimal
    amounts and a masked counterpart, follow the opaque cursor to page 2 and assert **zero overlapping
    txIds**, and finish on a tampered cursor → `400 INVALID_CURSOR`.
  - **Steps fold.** A run leaves eight cards of JSON on the page, so the header of every step now
    toggles the whole card shut, leaving the line that still carries the information — number, title,
    call, status — plus **Collapse all / Expand all** in the journey bar. Collapsed the journey reads as
    an eight-line index of what happened; expanded it reads as the tutorial it was written to be. A step
    that **fails auto-expands**: its transcript is the reason it failed, and hunting for a chevron is the
    wrong thing to ask of someone at that moment.
  - **A click on "Run step" is always visible now.** Re-running a step that already passed used to
    change nothing on screen — same response, re-rendered identically — so there was no way to tell the
    click had registered. Three things fix it: a **running** state while the call is in flight (spinner
    in the step number, disabled button, blue status — `.jstep.running` had been defined in CSS since
    the journeys landed and was never once applied), a **`run #N · HH:MM:SS` badge** in the header,
    which moves on every run even when the response is byte-identical, and a **toast** on completion.
    Per-step success toasts are suppressed during "Run all" — eight of them plus the summary is noise,
    and nobody clicked those steps individually.
  - **A real bug found by the verification, not by reading:** `phoneConnect` flipped `phone.state` to
    `connecting` *after* an `await`, while journey steps start it without awaiting and then poll
    `state !== 'live'`. With a connection already open the flag was still `live` from the previous one,
    so the poll exited immediately and the step reported success for the **old stream — opened with
    another user's token**, since a journey routinely reconnects the phone as somebody else. The state
    now flips before the first await; that ordering is load-bearing and says so in a comment.
  - **Verified in a headless browser against the live compose stack: 44/44 steps across all six
    journeys**, no page errors, no whole-JWT anywhere in the rendered panel, and no horizontal page
    overflow. The stream steps of three journeys went from *no* transcript to one — they were the
    steps hiding the bug above. The fold and the run feedback carry their own 13 assertions (collapse
    hides the body but keeps the title, Collapse all folds 8/8, a second click on an unchanged step
    moves the badge from `run #1` to `run #2`, a toast confirms the click).
  AI: est 2h / actual 2h / ~90% generated / 2 issues caught in human review

- Public statement API through payment-service with opaque cursor pagination and edge money formatting
  (step 41)
  **`GET /v1/accounts/me/statement?cursor=&limit=` (payment-service, 8084)** — the paginated history
  behind the balance, proxying ledger-service's internal statement seam (step 16) exactly the way step
  40's balance read proxies its ledger read. No cache in front of this one: a statement page is paged
  history, not a single hot value re-read on every screen. `limit` is clamped **again**, independently,
  at this public edge (default 20, max 100, floored at 1) — payment-service does not trust an internal
  collaborator to enforce the contract it promises its own callers; `cursor` stays opaque end to end and
  is never decoded here, only bound and forwarded.
  - **Two doc/code mismatches found and fixed in this change, not worked around (CLAUDE.md's no-drift
    rule).** `docs/api/openapi.yaml`'s `StatementEntry` promised `entryId` and `description` fields that
    never existed on ledger-service's actual internal response (`LedgerEntry` only ever carried `txId`,
    `direction`, `amountCents`, `counterpartAccountId`, `timestamp`, `entryType` — `description` is
    stored on the posting-guard item for idempotency comparison and never read back per entry; `entryId`
    was never a concept at all). The schema now matches what step 16 actually built and what this step's
    own task list already asked for (`txId`, `direction`, `amount`, `counterpart`, `timestamp`).
  - **Masking is a display transformation over the ledger's own account id, not a Pix-key lookup.**
    `counterpartAccountId` arriving from the ledger is a raw internal id (`acc-001`, or a system account
    like `SPI_CLEARING`/`SEED`) — resolving it to a Pix key or display name would be a call to
    account-service this step's prerequisites (step 16, step 18) never asked for. `StatementEntry.mask`
    (the `api/` edge, alongside the existing cents→decimal formatting) keeps a short prefix and suffix
    around a fixed `"***"`, falling back to "first character only" when the id is too short to hide
    anything meaningful between a prefix and a suffix — applied uniformly to real and system accounts,
    since neither should ever reach the wire whole.
  - **The cursor's cross-account guard is re-asserted at the edge for free, not re-implemented.**
    payment-service never decodes the opaque cursor (it is an AWS key only ledger-service can interpret)
    — it always calls the ledger with the caller's *own* `accountId` from the JWT, exactly like the
    balance read, so a cursor tampered to name another account can only fail ledger-service's own
    cross-account check (step 16) and come back `400 INVALID_CURSOR`. `HttpLedgerClient` maps that
    specific case to a new `InvalidStatementCursorException` rather than folding it into
    `LedgerUnavailableException`'s `503` — a malformed/foreign cursor is a client error that will never
    succeed on retry, not a transient one.
  - `timestamp` is carried as the exact string ledger-service already formatted with fixed-width
    milliseconds (step 14/16), never re-parsed into an `Instant` and re-rendered — a round trip through
    `Instant.toString()` would silently reintroduce the trailing-zero bug that motivated the fixed-width
    format in the first place.
  - Registered `GetStatementUseCase` in `PaymentBeansConfig` (ADR-0011's composition root) — caught by
    the module's own `ApplicationContextIT` failing to load with a `NoSuchBeanDefinitionException` before
    any HTTP test ran, exactly the fast, cheap signal that check exists to give.
  - Postman (3 requests: two pages plus the tampered-cursor case) and the API explorer (2 cards) grew
    with the endpoint, per convention, and the sprint's **journey** — deferred by step 40 because a
    balance card alone is not a flow — ships with it: see the entry below.
  AI: est 1.5h / actual 1.5h / ~85% generated / 2 issues caught in human review

- Real-time pushes wired end to end: PixSettled/PixReversed to sender, PixReceived to receiver (step 39)
  **Sprint 8 closes: the payload became a contract.** Step 38 proved a frame could reach the right
  customer, but it pushed each producer's event payload *verbatim* — three different shapes on one
  stream (an arrival naming a `creditorAccountId` and a `payerName`, an outbound outcome a
  `debtorAccountId` and a `creditorKey`), each carrying whatever else its producer happened to write. A
  client had to learn all three and would break the day a producer added a field. All three now converge
  on **one shape**, and it is a strict subset of the `Payment` schema `GET /v1/payments/{transactionId}`
  answers, so the push and the poll can never disagree about what a finished payment is called:
  `{transactionId, type, status, amount, counterpart, timestamp, failureReason}`.
  - **The external status vocabulary, reused rather than extended.** `PixSettled` and `PixReceived` are
    both `SETTLED`; `PixReversed` is `REVERSED` — words the status endpoint already answers (step 22).
    An arrival deliberately did **not** get a sixth word (`RECEIVED`): the money is here and it is final,
    which is what `SETTLED` means, and the direction is already carried by `type`. A status of its own
    would put one fact in two fields and let a client disagree with itself. The internal vocabulary keeps
    its own name for it (`RECEIVED_SETTLED` in `pix_transactions`) — exactly the sort of detail
    mapping-at-the-edge exists to keep off the wire.
  - **Money changes shape exactly once, and `infra/web/` is where that edge is.** Cents stay a `long`
    from the ledger through SNS, SQS and `domain/`; `NotificationPayload` formats `12550 → "125.50"` with
    a `BigDecimal` decimal-point shift (exact, base-10, no division, no rounding mode to get wrong). The
    DTO sits in `infra/web` rather than `api/` — unlike every other client-facing shape in the platform —
    because here the controller only hands MVC an open connection and returns; the frames are written
    later, from the consumer's thread, by the adapter that owns the transport. The shape belongs next to
    whoever writes it.
  - **Two policy questions, two domain services, asked in this order.** `NotificationRouting` answers
    *whose stream?* (unchanged from step 38); the new `NotificationVocabulary` answers *in what words?* —
    the status, the counterpart, and which of the event's three instants a customer is shown. Routing
    first is load-bearing: the vocabulary **refuses** an unknown event type (`IllegalArgumentException`,
    no silent default) and that refusal is unreachable precisely because an event nobody can be addressed
    for is dropped before it is ever described.
  - **The timestamp is when the *money* moved, not when we announced it** — `settledAt` for a settlement,
    the arrival instant for a receive, falling back to the outbox's `occurredAt`. They differ by
    milliseconds on a healthy day and by minutes the day the publisher backs up, which is exactly the day
    a receipt showing the wrong one becomes a complaint.
  - **`counterpart` is a display value and never an internal account id**: the payee's Pix key on a send
    (what the payer typed), the payer's name on an arrival, their ISPB when BACEN sent no name, `null`
    when neither travelled. Unlike `StatementEntry.counterpart` it is **not masked** — a push is
    ephemeral, authenticated and about the caller's own payment, while a statement is exportable and
    long-lived, which is what earns it the masking. No account id is on the wire at all: the stream is
    opened with a JWT and carries only that caller's events.
  - **`domain/` stopped receiving a `Map`.** The payload map now ends at the boundary: `NotificationMessage`
    (in `api/`) names every value it needs and hands over a wide, explicit command. Deciding what a
    customer sees by digging keys out of a JSON map inside `domain/` would be policy written against a
    shape this service does not own (ADR-0010).
  - **Reconnect UX, answered honestly.** Frames still carry `id:`, so a client sends `Last-Event-ID` — but
    this service keeps **no backlog**: events for a customer with nothing open are dropped and acked, so
    they do not arrive late on reconnect. `RealtimeJourneyIT` pins the whole behaviour, gap included: the
    reconnected stream is healthy immediately, and what was missed stays queryable on
    `GET /v1/payments/{transactionId}`. Buffering would mean holding messages for customers who may not
    open the app for a week, and eventually a DLQ full of work that can never succeed.
  - **Tests:** `NotificationVocabularyTest` (12) is the payload contract test — every status it can emit
    is asserted to be a word `PaymentResponse` also emits; `NotificationPayloadTest` (10) pins the money
    edge from 0 to R$ 9.999.999.999,99, including values past `Integer.MAX_VALUE` in cents;
    `RealtimeJourneyIT` (5) drives all three outcomes over a real socket, with the events minted through
    the *production* `OutboxEvent`/`EventEnvelope` code and published to **SNS** so the step-36 filter
    policy is part of what is under test. `mvn -pl services/notification-service verify` green: 53 unit +
    17 IT.
  - **Verified against the live stack**, all three within seconds of the money moving: alice's external
    send → `PixSettled` (`"amount":"12.34"`) on alice's stream; the reject-key knob armed → `PixReversed`
    on alice's; mock-bacen inbound → `PixReceived` (`"counterpart":"Carol Mendes"`) on bob's, and never on
    the other's.
  - **Twin harnesses:** the API explorer gains a **Phone tab** — the same endpoint and the same bytes,
    rendered as a phone lock screen (each card is one `data:` line; click one for the raw JSON). It ships
    no new API surface; it exists because the fastest way to judge a client contract is to build the
    client it was designed for, and it is deliberately dull to build. The connection survives a tab
    switch, so you can connect, go send a Pix in another tab, and come back to the notification. The
    Postman folder and the stream card document the frame shape (Postman cannot render a stream — it
    waits for a response that never finishes, which is itself worth knowing).
  - **Two gaps found and deliberately left to their owners.** (1) **`failureReason` is a sentence, not a
    code** — a forced refusal pushed `"The SPI refused the settlement: SETTLEMENT_REJECTED_BY_ADMIN |
    endToEndId=E1234…"` onto a customer's screen, because `HttpSpiSettlementClient#detailOf` (step 33)
    reads the SPI's problem+json **`detail`** while the same response also carries a machine-readable
    **`code`**. It was always in the event; step 39 is the first thing that *rendered* it. The fix is one
    line in settlement-service — money-path code, its own change. (2) **The payee of an *internal* send
    is still not notified**: that flow emits a single `PixSettled` meaning "your send completed", which
    belongs to the payer, and this consumer cannot manufacture an arrival honestly (the event carries no
    payer display name, only a `debtorAccountId` — the counterpart would be invented or one of our
    account ids). The fix belongs to the producer, payment-service emitting a `PixReceived` the way
    settlement-service already does for an inbound Pix; nothing downstream would change, since the
    subscription filter and the routing rule already handle it. Both are recorded in the service README
    and, for the first, in `openapi.yaml`.
  AI: est 3h / actual ~3h / ~90% generated / 0 issues caught in human review

- Inbound Pix flow: mock-bacen generator → settlement webhook, dedupe by endToEndId, credit posting,
  PixReceived (step 37)
  **The money path now runs in both directions, and receiving needed no new mechanism.** An outbound send
  debits the payer and credits `SPI_CLEARING`; an inbound one debits `SPI_CLEARING` and credits the user
  (`entryType=PIX_IN`) — same double entry, opposite direction, same clearing account standing in for the
  rest of the Pix network on our books. That the mirror fit without inventing anything is the design
  claim this step actually tests.
  - **settlement-service gains its first HTTP endpoint**, `POST /v1/inbound/pix`: validate the shared
    token → resolve the key against account-service's DICT → post `debit SPI_CLEARING / credit payee` →
    record the `INBOUND` transaction as `RECEIVED_SETTLED` **plus** its `PixReceived` outbox item in one
    conditional `TransactWriteItems`. `200 {outcome:"CREDITED"|"ALREADY_PROCESSED"}`; `401` / `422`
    (permanent) and `503 + Retry-After` (transient) as problem+json. It also gains the pieces its first
    route implies: `SettlementExceptionHandler`, `CorsConfig` ordered ahead of the JWT filter,
    `spring-boot-starter-validation`, and a Postman folder + API-explorer section (previously
    non-applicable for want of a browser-reachable route).
  - **`txId = in-<endToEndId>`, derived and never generated** — the load-bearing choice. Because the
    partition key embeds the rail's own id, `attribute_not_exists(pk)` *is* the endToEndId dedupe:
    strongly consistent and atomic, where the tempting alternative (query `gsi1`, write if absent) is a
    read-then-check over an *eventually consistent* index that two simultaneous deliveries could both
    pass. The same determinism makes the ledger posting idempotent by `txId`.
  - **The credit runs before the dedupe, deliberately — the step file said the opposite and the step file
    is now annotated with why.** Claiming first is right when the work has a non-idempotent external
    effect (exactly why `SettlePixUseCase` claims its `eventId` before calling BACEN). Here it would add a
    failure mode that otherwise does not exist: a crash between claim and posting leaves an `endToEndId`
    marked handled whose money never arrived, and every redelivery is refused by our own guard — the
    payment lost silently. Posting first inverts the residual risk into a harmless one (a committed credit
    with no transaction row, completed by the next delivery).
  - **JWT-exempt is not anonymous.** `/v1/inbound/**` is on `jwt.public-paths` (BACEN holds no
    PlatinumCoin token — a real participant presents mTLS + an ICP-Brasil certificate), but the route
    *credits money*, so it is guarded by the shared `SPI_WEBHOOK_TOKEN`, compared **constant-time** as the
    very first act of the use case — before any directory lookup or posting, so a forged call costs
    nothing and reveals nothing by side effect (threat model, boundary **B4**). The token defaults to
    **empty** and an empty token refuses every delivery: a misconfiguration on a money-crediting route
    fails closed. Neither token is logged or echoed (ADR-0012).
  - **The payee comes from our directory, never the payload** — the inbound mirror of Domain Safety
    Rule #1. The webhook body has no `creditorAccountId` field at all, so a caller holding a valid token
    still cannot address money to an account of its choosing.
  - **mock-bacen-spi gains `POST /simulate/inbound-pix`** `{pixKey, amount, payerName?, payerIspb?}`: mint
    an `endToEndId` stamped with the **payer's** ISPB (an id names the participant that *originated* the
    payment) and present it to the participant's webhook. **Retrying is the feature** — a rail that
    delivered once would never exercise the receiving side's dedupe, so a `5xx`/no answer re-presents the
    same id while a `4xx` stops at once and bounces (retrying a `401` forever is how a real integration
    wedges itself). `/simulate/…` not `/spi/…`: it stubs no real BACEN API, so it is named as the test
    hook it is. Money is a decimal string here (a human types it) and integer cents one hop later.
  - **Tests:** `ReceiveInboundPixUseCaseTest` (8, plain Java — a shared call-trace pins the *ordering*:
    a forged webhook resolves/posts/records nothing; the posting precedes the record; a directory outage
    is not an unknown key), `InboundPixIT` (5, real DynamoDB — credit + item + `gsi1`/`gsi2` +
    `PixReceived` in the sparse index; **redelivery ⇒ single credit**; **Σ balances invariant**; unknown
    key ⇒ `422`, nothing posted), `InboundWebhookAuthIT` (4 — missing/wrong/prefix token ⇒ `401` **and
    nothing happened**, the half a status-only assertion would miss), `InboundWebhookClientTest` (4,
    against a real socket) and `SpiInboundIT` (5). `mvn verify` green across all modules.
  - Docs: ARCHITECTURE §6.8 diagram corrected to the implemented order, data-model §4 gains the `INBOUND`
    item and settlement's new **create** right (behind its own port, so neither store can do the other's
    job), local-dev §3/§5.6 gain the env vars and the dedupe/401 drills, both service READMEs updated.
  - **Known gap, deferred to step 45's error-contract audit** (recorded in data-model §4):
    payment-service's `GET /v1/payments/{id}` parses `status` with `valueOf` into an enum that has no
    `RECEIVED_SETTLED` and reads `debtorAccountId` unconditionally, so a client that *guessed* an inbound
    `txId` would get `500` instead of `404`. A contract wart, not a money bug — unreachable through any
    flow, nothing written or moved — and fixing it in payment-service was out of this step's scope.
  AI: est 5h / actual ~4h / ~90% generated / 0 issues caught in human review

- notification-service: per-user SSE stream consuming notification-queue with heartbeats and cleanup
  (step 38)
  **The platform's first long-lived-connection service, and the honest ending to `202 Accepted`.** Every
  other service handles a request and lets the thread go; this one keeps state per connected human for as
  long as they hold the app open. That changes what "correct" means — the registry must shrink as
  reliably as it grows, writes arrive from servlet threads while reads come from the consumer and the
  heartbeat, and a client that vanishes has to be *discovered* rather than announced.
  - **`GET /v1/notifications/stream`** (JWT, `text/event-stream`, port 8087) registers an `SseEmitter`
    under the caller's account. Frames carry their routing in SSE's own fields — `event:` = the event
    type, `id:` = the `eventId` (so a reconnect resumes via `Last-Event-ID`) — leaving `data:` purely the
    business payload. The stream is the caller's own **by construction**: the account comes from the JWT
    `accountId` claim and no path, query or body field names an account, so "stream someone else's
    payments" is not a request the API can express (the read-side of Domain Safety Rule #1 — stronger
    than an ownership check, because nothing is left to check).
  - **`NotificationQueueConsumer`** long-polls `notification-queue`, dedupes by `eventId` against the
    shared `pix_processed_events` gate under `CONSUMER#notification-service`, and routes each event to
    the affected account's emitters. **Routing is the one policy decision here** and reads as one
    sentence: *an outcome of a send belongs to the payer, an arrival belongs to the payee* —
    `PixSettled`/`PixReversed` → `debtorAccountId`, `PixReceived` → `creditorAccountId`. Both accounts
    travel in the payload precisely so this consumer never re-resolves the directory: a synchronous
    lookup inside an asynchronous fan-out would let a directory outage stop unrelated notifications.
  - **Best-effort, written as code rather than intention.** Every outcome acks (`DELIVERED`,
    `NO_SUBSCRIBER`, `DUPLICATE`, `UNROUTABLE`); only a thrown exception leaves the message for
    redelivery, and the use case releases its dedup claim on the way out so the redelivery is real work.
    An event for a customer with nothing open is **dropped**, because the outcome stays queryable on
    `GET /payments/{transactionId}` and holding messages for someone who may not open the app for a week
    only fills the DLQ with work that can never succeed. It also **claims before acting** — the exact
    opposite of `ReceiveInboundPixUseCase`, which posts before recording: pushing twice is a visible
    defect while losing a push in a crash window costs nothing already answered elsewhere. Same
    mechanism, opposite ordering, because the risks are opposite.
  - **The heartbeat is a keepalive *and* the garbage collector** — the step's real lesson. Every 25s each
    stream gets an SSE comment (`:ping`), which every client including `EventSource` ignores for free.
    Outward it sits under the ~30s idle timeout common in proxies and carrier NATs, so a silent Pix
    stream is never reclaimed. Inward it is the only way this side learns a client is gone: **a customer
    closing the app sends the server nothing it will notice** — an async response that is not being
    written to never learns its socket died — so the next attempted write is what discovers it. A push
    service without a heartbeat leaks a registration per customer who ever connected;
    `SseIT#aDisconnectedClientIsRemovedFromTheRegistry` pins exactly that mechanism.
  - **The SSE handshake: the step-05 allow-list hook was resolved without being spent.** A browser's
    native `EventSource` cannot set request headers, so a header-only stream is a stream no `EventSource`
    can open. Rather than making the path public and verifying the token inside notification-service,
    `SseTokenHandshakeFilter` (ordered immediately before common-lib's `JwtAuthFilter`) promotes
    `?access_token=` into an `Authorization` header for **this one path**, and an explicit header always
    wins. The route stays fully protected and **common-lib remains the only code in the platform that
    decides whether a token is good** — a fix or a hardening lands once, not twice. The accepted cost is
    recorded in the class, the README and `JwtAuthProperties`: a token in a URL reaches access logs,
    `Referer` and browser history; bounded here by a 15-minute token, one path, and a sandbox, with a
    short-lived single-use stream ticket as the production posture.
  - **`SubscriberRegistry<S>` is generic, and that is the design point worth reading twice.** The
    controller must hand Spring MVC back an `SseEmitter` — a framework type `domain/` may not name — yet
    the object is created by the adapter that owns the transport, so it has to travel out *through* the
    use case. The type parameter lets the domain name that handle without knowing it: `domain/` stays
    plain Java, `api/` sees a concrete `OpenNotificationStreamUseCase<SseEmitter>`, nothing is laundered
    through `Object` and a cast, and `NotificationBeansConfig` is the single place SSE is named. Without
    it the natural way to build a push service is to let `SseEmitter` spread into the domain — and then
    the routing rule can only be tested with a servlet container running.
  - **Tests (40, all green): 28 unit + 12 integration.** `NotificationRoutingTest` and
    `DeliverNotificationUseCaseTest` pin the addressee and the dedupe in plain Java (including an
    explicit money invariant: R$ 1.234.567,89 survives as exact integer cents, chosen because it is past
    `Integer.MAX_VALUE` in cents — `NotificationMessage` reads the amount as `Number#longValue`, since
    Jackson binds an untyped JSON integer to `Integer` or `Long` depending on the *amount*).
    `SseEmitterRegistryTest` covers isolation, multi-device fan-out, eviction and a 200-thread
    subscribe/close storm. `SseIT` runs a **real socket against a real server** — MockMvc completes the
    exchange, which is the one thing this service never does — and asserts bob's `PixReceived` reaches
    bob and never alice, and vice versa. `SseHeartbeatIT` and `SseHandshakeAuthIT` cover the keepalive
    and all four auth shapes.
  - **Two defects the tests caught before review.** (1) `SseEmitterRegistry` called
    `emitter.completeWithError` on a failed write, but Tomcat *refuses* that once its async context has
    already errored — the `IllegalStateException` escaped and aborted the whole heartbeat sweep, so one
    dead connection would have cost every later stream its keepalive; tear-down is now best-effort while
    removal is not. (2) The first draft of the disconnect test assumed a closed client produces a
    server-side callback; it does not, which is what turned the heartbeat into the documented cleanup
    mechanism rather than a nice-to-have.
  - **A new `infra/web/` role folder** (ADR-0010 amended 2026-08-20, CLAUDE.md updated in the same
    change): a registry of live SSE emitters is not persistence (nothing is durable), not a `client/`
    (it calls no external service), not security and not config — a service that pushes to clients has an
    outbound *transport* adapter the four existing roles had no honest home for.
  - Docs: `services/notification-service/README.md`, compose entry + healthcheck, `docs/local-dev.md`
    §3 env vars and a §4 step-38 callout with the two-terminal drill, Postman folder (4 requests) and an
    API-explorer section whose stream card **actually streams** — `fetch` + `ReadableStream`, appending
    frames as they arrive, which is also what lets it demonstrate the header path a browser's
    `EventSource` cannot use. `docs/api/openapi.yaml` gains the optional `access_token` query parameter
    and the frame-shape description — the route itself was already contracted (mid-project consistency
    pass), but the handshake alternative this step introduces was not, and an undocumented way to
    authenticate is drift whichever direction it points.
  - **Deliberately left to step 39** (which owns them): the pushed `data:` line is the raw event payload,
    not yet the standardized DTO on the external status vocabulary; and the payee of an **internal** send
    is not notified — an internal Pix emits one `PixSettled`, routed to the payer, and the event →
    recipient mapping is step 39's task. Also noted, not fixed: the registry is per-instance (a second
    replica would only reach the customers connected to *it*; `NotificationChannel` is already the seam
    for a shared fan-out), and there is no cap on connections per account.
  AI: est 4h / actual ~2h / ~90% generated / 0 issues caught in human review
- LocalStack init: notification-queue (filtered) with DLQ (step 36)
  **Fan-out made concrete: a second consumer group off the same topic.** `08-messaging-notify.sh` hangs
  `notification-queue` + `notification-queue-dlq` off the existing `pix-events` topic, tuned exactly like
  settlement-queue (redrive after 5 receives → DLQ, visibility 30s, long-poll 20s, DLQ retention 14d,
  narrow `sqs:SendMessage` policy) but with a **disjoint** filter policy —
  `eventType ∈ {PixSettled, PixReceived, PixReversed}`, the user-facing outcomes only, never settlement's
  internal `PixDebited`. No new topic (fan-out happens at the subscription, ADR-0004); settlement's own
  policy is left untouched (the two are already disjoint). Deliberately **no** `inbound-pix-queue`: step 37
  processes the BACEN inbound webhook synchronously, and a queue with no consumer is worse than no queue.
  Being the new last `ready.d` script, `08` takes over the **readiness marker** from `07`
  (`[init] notify messaging ready: …`): both the Testcontainers harness (`LocalStackTestBase`) and the
  compose healthcheck now key off it — the probe switched from `describe-table pix_processed_events` to
  `sqs get-queue-url notification-queue`. `MessagingInitIT` gains three assertions (queue + DLQ exist,
  redrive to its own DLQ, filter policy is the three user-facing types and not `PixDebited`); runbook and
  init README mirrored.
  AI: est 1h / actual ~1h / ~92% generated / 0 issues caught in human review
- Reconciliation resolver (query SPI → finalize/reverse), idempotent, with the <5-min SLO alert (step 35)
  **The resolver half of reconciliation: turning "eventual" into "eventually *bounded*".** The step-34 scan
  finds stuck transactions; step 35 resolves them. `StuckTransactionResolver` (the real
  `StuckTransactionReconciler`, replacing step 34's logging placeholder) loads each stuck transaction, asks
  BACEN what became of it, and forces it to a terminal state — no external send stays undecided past the
  5-minute SLO (ADR-0003). This answers the failure half of design Question 4.
  - **Four rail answers, one decision each.** A new three-way `SpiSettlementClient.reconcile(endToEndId)`
    (distinct from step 32's binary `findSettlement`) returns `SETTLED` / `FAILED` / `UNKNOWN` /
    `UNREACHABLE`: SETTLED ⇒ finalize (clearing release + record SETTLED + `PixSettled`); FAILED ⇒ reverse
    immediately (compensating credit + record REVERSED + `PixReversed` + release the limit); UNKNOWN ⇒
    reverse **only past a safety window**, else leave; UNREACHABLE ⇒ leave. Collapsing FAILED and
    UNREACHABLE is exactly how a transfer gets reversed while the money is gone — the type keeps them apart.
  - **The safety window is a correctness mechanism, not just patience.** BACEN's rail is idempotent per
    `endToEndId`, so a genuine SETTLED and a genuine FAILED can never both be produced for one id and
    reconciliation cannot race the queue into double-moving money on those. The one branch that could is
    UNKNOWN: reversing the instant the rail reports "no record" could race a still-in-flight POST that then
    settles, and the `-rev`/`-rel` postings (different `txId`s, so posting idempotency does **not** cover
    them) would both draw the clearing account down — money created. Waiting out the window
    (`reverse-safety-window-seconds`, default 240s — past the 12s SPI timeout + retry backoff + DLQ
    threshold, inside the 300s SLO) closes it; the guarded transition is the backstop if two paths still
    collide.
  - **Idempotent by construction.** The resolver claims nothing and dedupes on nothing: its safety is the
    guarded transition (at most one path moves the state) plus posting idempotency (the `-rel`/`-rev` `txId`
    replays as a no-op). So a resolver run that races a late SQS redelivery or a DLQ redrive is harmless, and
    a re-run on an already-terminal transaction is a no-op it detects before even querying the rail.
  - **`SettlementFinalizer` extracted** (`domain/service/`) so the queue-driven settle and the resolver share
    **one** implementation of finalize and reverse — the ordering that keeps money from moving twice lives
    once. `SettlePixUseCase` delegates to it; all 18 of its unit tests stay green.
  - **The reversal guard widened** from strictly `SENT_TO_SPI` to *either* stuck state
    (`DEBITED OR SENT_TO_SPI`): a send whose settlement was never attempted still has money parked in
    clearing, so reversing from `DEBITED` is money-correct. Terminal states are still refused
    (`SettlementTransitionsIT`).
  - **`reconciliation.resolved{action}`** counter (settled|reversed), the reconciliation angle of the
    send/settle funnel (step 44), counted only when a run actually moved the state.
  - **`<5-min` SLO alert** (`ReconciliationSloAlert`): `reconciliation.oldest.seconds > slo-breach-seconds`
    (300s) fires (and resolves on catch-up) on the transition, logging one `ALERT … FIRING`/`RESOLVED` line.
    In-code here; step 44 points Prometheus at the same gauge and threshold, so the code and the dashboard
    never disagree on what "late" means.
  - **mock-bacen reject-key knob** (`bacen.reject-keys` / `POST /admin/config {"rejectKeys":[…]}`): a
    DICT-known creditor key can now be **refused at settlement**, the first send-reachable trigger for step
    33's reversal against the compose stack (previously reachable only via the automated `ReversalIT` stub).
  `ReconciliationIT` proves all four branches over real DynamoDB/SQS with a stubbed rail and ledger:
  settle-lost ⇒ finalize; genuine-fail ⇒ reverse + refund; rail-never-recorded-past-window ⇒ reverse; re-run
  ⇒ no double refund; resolver + a late queue delivery ⇒ single outcome; and the SLO alert firing then
  resolving end to end. `StuckTransactionResolverTest` pins the decision matrix in plain Java through the
  real finalizer. `SettlementArchitectureTest` stays green — the resolver is a `domain/service/` collaborator
  reached through the existing port; the scanner still calls one use case.
  AI: est 5h / actual <Yh> / ~88% generated / 0 issues caught in human review
- Stuck-transaction scanner (GSI2 status+age, 60s) feeding reconciliation, with an oldest-age metric (step 34)
  **The scanner half of reconciliation: finding what fell through the cracks.** SQS retries and the DLQ
  (step 32) catch messages that keep failing, but a transaction can go stuck with no live message behind it
  — a consumer that crashed after `markSentToSpi`, an SPI answer that never arrived. A `@Scheduled` scan
  (`StuckTransactionScanner`, every 60s) now queries `pix_transactions` GSI2 (`STATUS#DEBITED` and
  `STATUS#SENT_TO_SPI`, `gsi2sk = updatedAt < now-2min`) for exactly those, and hands each to the
  reconciliation path.
  - **The clock is policy and stays in the use case.** `ScanStuckTransactionsUseCase` computes the cutoff
    (`now − stuck-after-seconds`) from the injected `Clock` and passes it to the store as a query bound, so
    the "how old is too old" decision is pinned in a plain-Java test and the DynamoDB adapter reads no clock
    (ADR-0010/0011). It scans exactly the two non-terminal statuses — `SETTLED`/`REVERSED` can never be
    stuck — proven by `ScanStuckTransactionsUseCaseTest`.
  - **The hand-off is a port, not an inline log**, so the acceptance test asserts on *which* transactions
    were picked (a capturing fake) rather than on log text (ADR-0012). `StuckTransactionReconciler` ships a
    `LoggingStuckTransactionReconciler` placeholder; **step 35 replaces it** with real finalize-or-reverse
    resolution and nothing upstream changes.
  - **`reconciliation.oldest.seconds`** gauge (age of the oldest stuck tx, `0` when none) is the **leading**
    indicator of the <5-min SLO (ADR-0003) — it rises before anything reaches the DLQ; step 44 alerts on it.
    Registered in the `api/` scanner behind an `AtomicLong` (same shape as `settlement.dlq.depth`), so a
    Prometheus scrape never triggers a DynamoDB query.
  - **Bounded per tick** by a `Limit` (`max-per-tick`, default 200) per status: a backlog larger than the cap
    drains over successive ticks instead of blowing up one. Oldest-first, so the transactions nearest the SLO
    breach are always picked first. Scale-out note: shard the status GSI (`STATUS#DEBITED#<0-15>`) at very
    large scale; N=1 locally.
  `StuckScannerIT` seeds stale and fresh transactions in both stuck statuses over real DynamoDB and proves
  the scan picks exactly the stale ones, ignores the fresh, feeds them to the reconciliation path, and moves
  the oldest age onto the gauge. All money-safety invariants untouched (the scan is read-only; it moves no
  money). `SettlementArchitectureTest` stays green — the scanner calls one use case and reaches no port.
  AI: est 2h / actual 1.5h / ~88% generated / 1 issue caught in human review
- Settlement finalization: clearing release on SETTLED, compensating reversal (append-only) on FAILED with PixReversed (step 33)
  **The money loop closes on definitive outcomes.** Until step 33 an external send that BACEN permanently
  refused was left to redrive to the DLQ, and a settled one never drew its money out of the clearing
  account. Now settlement-service finalizes both branches through the ledger, and Σ balances stays
  invariant on each:
  - **SETTLED** → a `CLEARING_RELEASE` posting (`debit SPI_CLEARING / credit SPI_SETTLED`,
    `txId=<orig>-rel`) draws the parked money out of clearing into a new seeded system account
    `SPI_SETTLED` ("money settled out to the network"), posted **before** `markSettled` so a crash between
    the two replays harmlessly.
  - **Permanent refusal** (`SpiSettlementRejectedException`) → a compensating `PIX_REVERSAL` posting
    (`debit SPI_CLEARING / credit payer`, `txId=<orig>-rev`) returns the money to the payer, then a guarded
    `SENT_TO_SPI → REVERSED` transition + `PixReversed` outbox event commit in one `TransactWriteItems`, and
    the daily-limit reservation is released — **only when the guard wins on this invocation**, so a
    redelivery never double-refunds the counter. The ledger stays append-only: a reversal is a new posting,
    never an edit.
  Both postings are **idempotent by their deterministic `txId`**, which is what lets them precede the
  guarded status transition without ever double-moving money. **Task 4:** payment-service now persists the
  exact `clearingAccountId` the debit credited on the transaction and carries it on the `PixDebited` event,
  so a reversal debits the same account (the same shard, once step 52 shards `SPI_CLEARING`) rather than
  re-deriving it. settlement-service, being queue-driven, has no user token to forward, so a new
  `ServiceTokenIssuer` mints a short-lived HS256 service token (shared secret) for the JWT-protected ledger
  call — a sandbox stand-in for a real service credential (ADR-0013; step-45 hardening). `ReversalIT` proves
  the payer is refunded to their pre-send balance, the status reaches `REVERSED`, `PixReversed` is emitted,
  conservation holds and a re-run does not double-refund; `ClearingReleaseIT` proves the clearing nets to
  zero and Σ balances is conserved on the success branch too.
  AI: est 4h / actual <Yh> / ~90% generated / 0 issues caught in human review
- Settlement retries with query-before-retry, visibility backoff and DLQ redrive; DLQ depth metric (step 32)
  **Settlement becomes failure-proof.** On an SPI timeout/5xx the message is no longer just left on the
  queue: the consumer resets its visibility to an exponential backoff (`base·2^(receiveCount-1)`, default
  5/10/20/40/60s) so retries space out, and after five undeleted receives SQS redrives it to
  `settlement-queue-dlq` (step 26's policy). The subtle rule the whole flow turns on is now enforced:
  **before retrying a redelivery the consumer queries the rail first** — a new
  `SpiSettlementClient.findSettlement` calls `GET /spi/settlements/{endToEndId}`, and if BACEN reports the
  id `SETTLED` the Pix is finalized from that truth **without a second `POST`**. A blind re-`POST` would
  still be safe (`endToEndId` is the idempotency key, ADR-0002 §3), but the query is what closes a
  settled-but-unanswered Pix even when the rail keeps refusing fresh `POST`s as unavailable — which is
  what makes reconciliation bounded rather than hopeful (ADR-0003). The redelivery signal is SQS's own
  `ApproximateReceiveCount > 1`, read as a message system attribute, so no extra table read is needed.
  DLQ depth is exposed as the `settlement.dlq.depth` gauge (a scheduled `GetQueueAttributes` probe
  feeding an `AtomicLong`, the same shape as `outbox.lag`) — a DLQ message is *flagged*, not lost, and
  step 44 alerts on a sustained non-zero depth. `SettlementRetryIT` proves all three against real SQS:
  a transiently-failing rail retried until it settles, a timeout-that-actually-settled caught by
  query-before-retry with exactly one `POST`, and a permanent failure redriving to the DLQ after five
  receives with the gauge reflecting it. No money moves here; reversal of a permanent refusal is step 33.
  AI: est 3h / actual <Yh> / ~90% generated / 0 issues caught in human review
- settlement-service: consume settlement-queue, call SPI, guarded transition to SETTLED with PixSettled
  event (step 31)
  **The external send now finishes.** Since step 27 an external Pix has answered `202` with the money
  parked in the clearing account and nothing to complete it; the new service on port 8086 is that
  something. It long-polls `settlement-queue`, dedupes by `eventId`, and walks the transaction
  `DEBITED → SENT_TO_SPI → SETTLED`, writing `PixSettled` into the outbox in the **same**
  `TransactWriteItems` as the status change. The walking skeleton of the asynchronous half is complete for
  the sunny day.
  **The first service nobody calls.** Its only inbound adapter is a queue consumer, so it scales with
  queue depth rather than with user traffic (ADR-0006) — and it therefore ships with no business endpoint,
  no CORS, and nothing to add to Postman or the API explorer (the twin harnesses cover public endpoints;
  this service has none until step 37's inbound Pix). The consumer lives in `api/` all the same: a queue
  is a way of *entering* the application, so it obeys the controller rules — bind the wire shape, call one
  use case, map the result, hold no policy — and the ArchUnit rule that forbids `api/ → interface in
  domain/` is what stops it from growing a second, untested settlement path.
  **Three decisions carry the correctness.** *(i)* `SENT_TO_SPI` is written **before** the rail is called,
  so a consumer that dies mid-call leaves the durable statement "we asked BACEN" — without it a settlement
  that timed out (BACEN may well have completed it) is indistinguishable from one never attempted, and the
  two demand opposite reactions. *(ii)* Both transitions are guarded **inside** the write
  (`ConditionExpression`), never read-then-check: a redelivery, a second instance and step 35's
  reconciliation loop can all race, exactly one wins, and a `SETTLED` transaction can never be dragged
  back onto the rail — that would be the same money sent twice. *(iii)* The rail's three answers are three
  **types**, not a status field: a settlement is a value, a `422` is `SpiSettlementRejectedException`
  (permanent, step 33 reverses), and a `503`/timeout is `SpiCallFailedException` meaning *unknown* — the
  distinction the whole flow turns on, since a timeout treated as failure would reverse a payment whose
  money already left.
  **`ProcessedEventStore` gains `release`** (common-lib). The claim is taken before the side effect
  (Domain Safety Rule #2) but now means *"I am handling this"*; only a completed settlement turns it into
  *"this is done"*. Without the release the dedup gate would disarm SQS's own retry mechanism — the
  message returns, the gate says "already processed", the consumer acks, the payment never settles — and
  step 32 could not be written at all. The failure direction is deliberate: a crash between a failed
  attempt and its release leaves a stale claim, that delivery is skipped, and the transaction falls to the
  reconciliation loop (ADR-0003, <5 min). Losing a retry to a safety net beats two workers settling one Pix.
  **Nobody publishes twice.** settlement *writes* `PixSettled`; payment-service's polling publisher
  delivers it, because the sparse `gsi3` index is a property of the table and it already drains all of it.
  A second publisher on the same single `OUTBOX#UNPUBLISHED` partition would republish the other's events
  — self-inflicted duplicates — so independence would cost a per-writer index, i.e. a data-model change.
  The trade-off is recorded rather than hidden: settlement's events go out only while payment-service runs.
  **The seams left open on purpose** (happy path only, per the step): a failed or timed-out settlement just
  leaves the message on the queue — no query-before-retry, no visibility backoff, no DLQ metric (step 32);
  a permanent `422` is recognised and left for the reversal of step 33; no reconciliation yet (steps 34–35).
  payment-service gains `SENT_TO_SPI` in its `TransactionStatus` (it must be able to *read* a state
  settlement writes) and maps it to `PROCESSING` — the `switch` with no `default` broke the build until it
  was given an external face, exactly as designed, and not one client learned a new word.
  **LocalStack's compose healthcheck now means "seeded", not "answering".** Caught by running the stack:
  the emulator reports UP before its `ready.d` scripts finish, and settlement-service — the first service
  that touches an AWS *resource at startup* rather than lazily on the first request — died on boot with
  `QueueDoesNotExist`. The probe now also asserts a resource created by the last init script exists, which
  is what the Testcontainers harness already did (it waits on that script's final log line), so compose
  and the tests finally agree on what readiness means.
  Docs updated in the same change: `docs/data-model.md` §4 (the two guarded transitions and the
  settlement-confirmation fields `settledAt`/`creditorIspb`) and §6 (claim vs. completion),
  `docs/local-dev.md` (step-31 note, the healthcheck change, and how to watch a payment settle).
  AI: est 3.5h / actual 2h / ~90% generated / 3 issues caught in human review
- mock-bacen-spi: settlement + status + admin-config endpoints and external DICT resolution (step 30)
  The platform gains a dependency it can **break on purpose**, and with it the last missing piece of the
  external send path. Two roles in one stub on port 9090: the **SPI rail** (`POST /spi/settlements`,
  idempotent by `endToEndId`; `GET /spi/settlements/{endToEndId}`; `POST|GET /admin/config`) and **BACEN's
  DICT** (`GET /spi/dict/{key}`), which closes the delegation seam step 11 left marked.
  **Why a controllable dependency is a deliverable, not a test fixture.** Every reliability claim Sprint 7
  will make — retries with backoff, DLQ redrive, query-before-retry, reconciliation inside 5 minutes — is a
  claim about *what happens when BACEN misbehaves*. You cannot test that against something that always
  works, and you cannot ask the real SPI to fail on cue. So the failure modes became first-class and
  **armable while the stack runs**: the drills are sequences ("send a payment, *then* break BACEN, watch the
  retries, un-break it"), and a boot-time-only configuration cannot express "fail the next five attempts" —
  restarting the container to change a value would also wipe the SPI's memory of every settlement, which is
  the very state the drill is about. `POST /admin/config` is deliberately **partial** (an absent field is
  left unchanged, so arming one knob does not reset another) and refuses out-of-range values with a `400`
  rather than clamping them: a rate above 1 or a latency past the real SPI's 10s SLA would let a drill pass
  against a fiction. `0.0`/`1.0` are **exact**, short-circuited before any random draw — every drill and
  every IT is written with those two values, so they must be guarantees, not near-certainties.
  **The three failure injections are three different truths, and conflating any two would break Sprint 7.**
  An injected `503` records **nothing** (transient: the *transport* failed, not the transfer — so the same
  `endToEndId` still settles on a retry; recording it as `FAILED` would make step 32's retry drill
  structurally impossible). A rejection — creditor key held by no participant in the DICT — is **terminal**
  `422 SPI_REJECTED` recorded as `FAILED`, because retrying cannot change a business refusal and the payer
  must be made whole by a compensating posting (step 33). And a rolled **timeout settles first and then
  hangs**, manufacturing the nastiest state in distributed payments: BACEN moved the money and the caller
  believes nothing happened. That third one is the whole reason step 32's *query-before-retry* rule exists,
  and it cannot be written against a dependency that does not lie in exactly this way.
  **Idempotency by `endToEndId`, made observable.** The first terminal outcome for an id wins forever and a
  replay is **byte-for-byte** the original response, `recordedAt` included — indistinguishability is what
  makes "retry after a timeout" *safe* rather than merely likely to work, since the caller needs no special
  case for "maybe it already happened". A retry carrying a *different* amount replays the amount actually
  settled and logs the mismatch loudly (an `endToEndId` identifies one transfer; a second amount is not a
  correction). The outcome is computed inside `computeIfAbsent` so 32 concurrent retries settle **once**
  (proven by a storm test that counts decisions, not reads) — while the configured latency is slept
  *outside* the store, since a 2s sleep holding a map bin would serialise unrelated ids under load.
  **`GET /spi/settlements/{id}` always answers `200`, never `404`.** "I have never heard of this id" is an
  answer reconciliation must be able to act on, and a `404` is indistinguishable from a wrong URL or the
  wrong service; so absence is reported in the body as `UNKNOWN`, carrying **no** amount — a fabricated `0`
  for a transfer the SPI never saw is exactly the kind of lie reconciliation must not act on.
  **The DICT is deliberately outside the injection.** Key resolution sits on the *synchronous* send path
  with the payer waiting on it (p99 < 2s); settlement is the asynchronous half nobody waits for. Slowing the
  directory would blow the send SLO and prove nothing about settlement resilience.
  **account-service now delegates, and fails closed (the step-11 seam, closed).** `ExternalDirectory` (a new
  outbound port) + `HttpExternalDirectory` turn an unknown key into a DICT lookup, so
  `bob@otherbank.com` finally answers `{internal:false, externalBank:"99999999"}` and the **external send
  works end to end** — the one gap steps 27–29 had left open. The local table is tried **first**: the
  hottest read on the send path pays zero network hops for a key we already hold, and a key registered here
  is authoritatively ours. Resolution now has *four* answers, and the fourth is the interesting decision: a
  DICT that cannot be consulted (unreachable / timeout / `5xx`) is **`503 DIRECTORY_UNAVAILABLE` +
  `Retry-After`**, never the tempting `404`. No money moves either way, so this is an honesty decision, and
  the deliberate **opposite** of the fraud fail-*open* (ADR-0005): there, proceeding unscored carries
  bounded, quantified risk and blocking every payment would be worse; here there is no destination to
  proceed to, so the only question left is what to tell the caller — and a `404` would say "your payee's key
  does not exist" on the strength of *our* outage, reading as final and discouraging the one action that
  helps. The client carries a hard budget (connect 500ms / read 1500ms) for the same reason, and forwards
  **no** bearer token: BACEN is outside PlatinumCoin's trust domain (a real participant presents mTLS + an
  ICP-Brasil certificate), which is also why the stub neutralises the inherited `JwtAuthFilter` by
  configuration (`jwt.public-paths: /**`) instead of dropping the dependency — the reason it is open is then
  written down rather than implied by an absence. It keeps common-lib for one property that matters: the
  `[cid=… tx=…]` pattern (ADR-0012), verified live — one `grep <correlationId>` now spans payment →
  account → **the SPI** → back.
  **ADR-0010's stub exemption, used deliberately:** `api/` + a thin `spi/` core + `config/`, with no ports,
  no `domain/`, no use-case layer and no ArchUnit test. Those layers exist to protect money invariants and
  there are none here; inventing a hexagonal domain for a fake would be ceremony that makes the codebase
  harder to read. The exemption is bounded — module + POM, Dockerfile, compose block, README, CORS, Postman
  folder (9 requests) and API-explorer section (7 cards) all shipped as usual.
  Tests: `SpiSettlementIT` (12 — settle then report SETTLED, byte-identical replay, a retry that changes the
  amount, `UNKNOWN` without a `404`, the injected `503` recording nothing *and then settling*, the timeout
  that settles-then-hangs, permanent rejection staying rejected, latency actually burned, partial admin
  update, out-of-range refusal, a non-positive amount refused before any settlement, and the no-token trust
  boundary stated as a decision), `SpiDictIT` (5 — including that the DICT ignores the settlement dial),
  `SettlementStoreTest` (4 — incl. a 32-thread storm settling once), `SpiBehaviorTest` (4 — 500 draws proving
  the exact extremes), and on the account side `ExternalDictIT` (5, driving the real adapter over a JDK
  `HttpServer` stub: external resolution, normalised delegation, a foreign key kind degrading to `null`
  rather than sinking a payable destination, `404` from both directories, and an internal key never touching
  the DICT) plus `ResolvePixKeyUseCaseTest` (7, its step-11 red assertion now flipped green).
  `KeyResolutionIT`'s unknown-key case **changed meaning rather than disappearing**: with the DICT
  unreachable it is now the fail-closed test (`503`), while the `404` case moved to `ExternalDictIT` where a
  directory is actually running — two contexts, two distinct truths. `mvn verify` green across all six
  modules, and verified on the live stack: external key resolved, R$200 parked in `SPI_CLEARING` from a real
  `202`, the failure/rejection/unknown paths and the BACEN-down `503` all reproduced by hand.
  **Known gap, noted not fixed (CLAUDE.md: don't fix adjacent things silently):** payment-service rethrows
  any non-`404` from the resolve lookup unmapped, so the new `503 DIRECTORY_UNAVAILABLE` reaches the payer as
  a generic `500` instead of a `503 + Retry-After`. A contract wart, not a money bug — resolution runs first
  (resolve → limit → fraud → debit), so nothing is reserved and nothing is debited, and the in-progress
  idempotency claim left behind is the ordinary claim-crash window ADR-0002 already covers. Filed against the
  step-45 error-contract audit. Also still idle by design: nothing consumes `POST /spi/settlements` until
  settlement-service lands in step 31.
  **Doc drift closed in the same change:** the "external keys do not resolve until step 30" notes in
  payment-service's README and three javadocs, account-service's README (four answers + the new config), the
  `mock-bacen-spi` row and the Sprint-2 note in `ARCHITECTURE.md`, `docs/local-dev.md` (§3 env table incl. the
  corrected `503`-not-`500` behaviour and the new `BACEN_TIMEOUT_HANG_MS`, a §4 step-30 note, §5.2's four
  resolution answers, §5.3's external send), and `docs/steps/step-30.md` itself — whose verify command
  omitted the bearer token and therefore answered `401` as written (`/internal/**` is not public).
  AI: est 3.5h / actual <Yh> / ~90% generated / 0 issues caught in human review
- Outbox polling publisher (sparse GSI → SNS) with publish-then-mark and a ProcessedEventStore consumer-dedup table (step 29)
  The events step 28 made *durable* now become *delivered* — ADR-0004 is complete. `OutboxPublisher`
  (`@Scheduled` 1s) asks `PublishOutboxEventsUseCase` for a bounded batch off the sparse `gsi3`
  (oldest first), publishes each to SNS `pix-events`, and only **then** removes `gsi3pk`.
  **The ordering is the decision, not an implementation detail.** Both orderings can crash halfway, so
  the question is *which way to fail*: publish-then-mark costs a **duplicate** (recoverable — every
  consumer dedupes by `eventId`), mark-then-publish costs a **lost event** (unrecoverable — for an
  external send, money parked in `SPI_CLEARING` with no settlement flow that will ever pick it up).
  Delivery is therefore deliberately **at-least-once**, and `OutboxPublisherIT` proves exactly that
  window: an event whose `gsi3pk` survived the publish is republished on the next tick, same `eventId`.
  **Why the index is sparse.** A GSI only holds items carrying its key attributes, so "published" *is*
  "no longer in the index" — the poll costs O(in-flight), never O(history), which is what makes a 1s
  tick affordable against five years of settled payments. A `published=true` flag would have inverted
  that. The item itself stays in its transaction's partition as the audit record of what was announced.
  **Why polling and not Streams:** against a 10s SPI SLA and reconciliation measured in minutes, a 1s
  poll is invisible, while Streams would be the most complex consumer in the project (shard iterators,
  checkpoints, resharding, 24h expiry). Kept as the documented evolution — and the swap replaces
  `OutboxPublisher` + `SnsEventPublisher` and *nothing else*: not the outbox write, not the envelope,
  not a consumer (`EventPublisher` is a one-method port that names no broker).
  **Routing is a message attribute, never the body**: `eventType`/`eventId`/`correlationId` are set as
  SNS attributes, because SNS filter policies match attributes — which is what lets `settlement-queue`
  subscribe to `PixDebited` alone and pay nothing for the rest (step 26's policy, now exercised for real).
  **A failed publish never blocks the batch.** No ordering is promised across redeliveries (consumers
  rely on guarded status transitions), so aborting would buy nothing and cost head-of-line blocking: one
  unpublishable event holding back every payment behind it. The stuck event surfaces through the new
  **`outbox.lag`** gauge (seconds the oldest waiting event has waited) — the publisher-liveness signal
  step 44 alerts on, by *silence* as much as by threshold.
  **`ProcessedEventStore` (common-lib) + `pix_processed_events`** close the loop on the consumer side:
  a conditional put on `CONSUMER#<name>#EVT#<eventId>` **before** the side effect turns at-least-once
  into effectively-once. The consumer name is in the *key* (ADR-0006's deliberate shared-table
  exception), so settlement, notification and audit never dedupe each other out. TTL is 7 days and
  DynamoDB's lazy deletion errs the safe way here — an expired-but-present record still reads
  "duplicate", i.e. *skip* a side effect rather than repeat one, the exact opposite of `pix_idempotency`
  (§5), where an expired record must read as absent. New init script `07-processed-events.sh`, which
  also **moved the Testcontainers readiness marker** (`LocalStackTestBase` now waits on its last line).
  **Convention added (CLAUDE.md):** `api/` is *inbound adapters*, not only controllers — a scheduled job
  enters the application like a request does, so it lives there and inherits the ArchUnit rule that
  forbids reaching an outbound port (the publisher must go through a use case). Every background job is
  `@ConditionalOnProperty("pix.schedulers.enabled")` and is **off in ITs**: Spring caches contexts across
  test classes, so a live 1s poller would drain the shared table while `OutboxWriteIT` asserts an event
  is still unpublished. The IT that covers a job drives its tick explicitly — deterministic, no sleeps.
  **ADR-0012 gap found on the live stack and closed here:** publisher lines were logged on the scheduler
  thread with `[cid=n/a tx=n/a]`, so `grep <correlationId>` returned an incomplete path. `CorrelationId`
  gains `restore(correlationId, txId)` / `clear()`, and the use case adopts the event's own ids for the
  duration of its publish (cleared in a `finally` — pooled threads must not leak an id onto the next
  event). One `grep` now spans request → ledger → atomic write → publish → mark, verified live.
  Tests: `PublishOutboxEventsUseCaseTest` (8 — publish-then-mark order, oldest-first drain, a failed
  publish left in the index and retried, a poison event not blocking the batch, lag incl. the
  clock-skew floor, bounded batch), `OutboxPublisherIT` (3 — event reaches `settlement-queue` past the
  filter policy with raw delivery and integer-cent money, leaves the sparse index but stays in its
  partition; the crash window republishes; the gauge reports a 5-minute-old event as ~300s),
  `ProcessedEventStoreIT` (3) and `CorrelationIdTest` (3). `mvn verify` green across all modules
  (common-lib 36 unit + 14 IT; payment-service 69 unit + 39 IT), and verified on the live compose stack:
  the 1s tick published a real send's event (SNS `messageId` logged), the sparse index went to 0,
  `outbox.lag` read `0.0` seconds, and the published item stayed in its partition without `gsi3pk`.
  **Known gap, not a defect:** no *live* `PixDebited` reaches `settlement-queue` yet — external keys only
  resolve once mock-bacen's DICT lands (step 30), and internal sends announce `PixSettled`, which the
  subscription filters out by design; the full path is proven in `OutboxPublisherIT`.
  AI: est 3.5h / actual <Yh> / ~90% generated / 0 issues caught in human review
- Transactional outbox: status transition + event written atomically in one TransactWriteItems (step 28)
  The send flow stops being able to lie. Until now the transaction was saved with a `PutItem` and
  "announce it" was a future second write — the **dual-write problem** (ADR-0004): a crash between the
  two either loses the event (for an external send, money parked in `SPI_CLEARING` that no settlement
  flow will ever pick up) or announces a payment that never committed. The outbox pattern does not
  shrink that window, it **deletes** it: the event is written as an *item next to the state it
  describes*, in the same `TX#<txId>` partition, so `TX#<txId>/META` + one `TX#<txId>/OUTBOX#<eventId>`
  per event commit in **one `TransactWriteItems`**. Delivery becomes a separate, retryable problem that
  a lost publish cannot corrupt (step 29 drains it; consumers dedupe by `eventId`). Nothing publishes
  yet — the sparse index only fills.
  **The port carries the guarantee**: `TransactionRepository.create(Transaction, List<OutboxEvent>)`
  makes "save the state without its events" unexpressible, rather than merely discouraged.
  **Which events, and why it is a domain decision** (`domain/service/PixOutboxEvents`, not the adapter):
  external ⇒ `PixDebited` (the type step 26's settlement-queue filter policy subscribes to); internal ⇒
  `PixSettled`, **never** `PixDebited` — the atomic posting *was* the settlement (step 21), so
  announcing a debit would put an already-finished payment on the settlement-queue and have BACEN asked
  to settle a transfer that never left the bank; audit and notification consume that `PixSettled`
  exactly like an external settlement's, which is the point — a consumer never learns where the payee
  banks. A fail-open fraud skip (ADR-0005) adds a **second** event, `FraudCheckSkipped`, to the same
  transaction, so "we let an unscored payment through" is as durable as the payment itself; step 25's
  TODO seam is now wired.
  **The envelope is broker-agnostic by construction**: `OutboxEvent(eventId, eventType, payload,
  occurredAt, correlationId)` + `EventEnvelope` land in `common-lib` and name no broker (same precedent
  as `CanonicalJson` — Jackson may not be imported from a service's `domain/`). `payload` is stored as
  an **opaque JSON string**, so DynamoDB never queries inside it and a new event type needs no schema
  change; money crosses it as integer cents. `correlationId` (new `CorrelationId.current()`, reading the
  MDC) rides along, which is what keeps ADR-0012's promise alive **after the flow goes asynchronous**:
  one `grep <correlationId>` still reconstructs request → debit → settlement across processes — verified
  on the live stack.
  **The fixed-width timestamp trap, a second time.** `gsi3sk = occurredAt` is a **sort key** the
  publisher drains oldest-first, so it is formatted `uuuu-MM-dd'T'HH:mm:ss.SSS'Z'` and never
  `Instant.toString()`: the latter drops trailing zeros, so an event on a round second renders
  `12:34:30Z` and sorts *after* one 500 ms later (`'Z'` 0x5A > `'.'` 0x2E) — the exact defect the ledger's
  entry timestamps document, here inverting the drain order of the outbox instead of a statement page.
  **Two doc divergences recorded and fixed in `docs/steps/step-28.md`.** (1) Task 2 assumed step 27 had
  left a *status transition* to guard; it had not — payment-service writes `META` **once**, already at
  its final-for-now status, and the guarded `status = :expectedFrom` update is settlement-service's
  write in step 31 (ARCHITECTURE §6.6). The atomic write built here is therefore a guarded **create**
  (`ConditionExpression: attribute_not_exists(pk)`), which satisfies the DoD's *no out-of-order regress*
  for this step's only write — a create can never overwrite a transaction a later step has advanced (a
  `SETTLED` payment reset to `DEBITED` would be settled twice, i.e. the same money sent twice) — and no
  `transition()` method was added without a caller. (2) The index is `gsi3`, not `GSI3`; the step's
  verify command is corrected. `docs/data-model.md` §4 (outbox item shape, the atomic write, the guard,
  the timestamp note), `ARCHITECTURE.md` §6.4 (an internal send's write now carries its outbox event)
  and the payment-service README are updated in the same change.
  **Tests.** New `OutboxWriteIT` (5) proves the pairs: state **and** exactly one unpublished
  `PixDebited` in the same partition with `gsi3pk`/fixed-width `gsi3sk`/`correlationId`/integer-cents
  payload; the event is reachable through the sparse `gsi3` query the publisher will use; an internal
  send announces `PixSettled` instead; a skipped fraud check writes two events in one write; and the
  **atomicity proof** — force the guard to fire and *neither* the regressed state nor its event lands
  (one event in the partition, not two: had these been two writes, step 29 would publish a `PixDebited`
  for a settled payment). Plus `OutboxEventTest` (6) / `EventEnvelopeTest` (5) in common-lib — including
  the round-second ordering trap and money as an integral JSON number — `PixOutboxEventsTest` (6) and 5
  new `SendPixUseCaseTest` cases (a refused send announces nothing; an idempotent replay announces no
  second event). `mvn verify` green (common-lib 33 unit + 10 IT; payment-service 61 unit + 36 IT), and
  verified on the live compose stack: an internal send left `META` + its `OUTBOX#` sibling in one
  partition, the replay added no second event, and a real cold-start fraud timeout produced a genuine
  `FraudCheckSkipped` alongside `PixSettled` — the fail-open, recorded durably, on the first try.
  AI: est 3h / actual <Yh> / ~90% generated / 0 issues caught in human review
- External Pix orchestration: atomic debit payer / credit SPI_CLEARING, status DEBITED (step 27)
  The send flow gains its second destination. `PixKeyResolver` now answers **where** a key lives
  (`KeyResolution{internal, accountId, externalBank}`) instead of only "which internal account", and
  `SendPixUseCase` branches on it — **only at the last stage**: resolve → limit → fraud is byte-identical
  for both, because authority, limits and fraud are properties of the *payer*, not of where the payee
  banks. An external destination is debited `payer → ACCOUNT#SPI_CLEARING` (`entryType=PIX_OUT`, same
  atomic `TransactWriteItems`, same `txId` guard) and persisted `status=DEBITED`, `creditorInternal=false`,
  no `creditorAccountId`, no `settledAt`; the client still gets `202 PROCESSING`.
  **Why a clearing account:** no ACID transaction can span PlatinumCoin and another PSP, so the money is
  taken from the payer and *parked in flight* in an internal system account (exempt from the ledger's
  non-negative rule). The posting stays balanced, so **Σ balances is invariant** — conservation holds
  *during* the flight, not only at its ends, which is exactly what makes a mid-flight crash auditable.
  **Why `DEBITED` and not `SETTLED`:** the payer's money is gone but the payee has not been paid, and only
  BACEN can close that gap; `PaymentResponse` maps `DEBITED → PROCESSING` (the internal machine grows, the
  client's vocabulary does not). The clearing id is **configuration** (`pix.clearing-account-id`, default
  `SPI_CLEARING`) passed as an argument down to the ledger port, so step 52's write sharding
  (`SPI_CLEARING#00..#15`) changes *which id is passed* and nothing else — proven by a test that wires the
  use case with `SPI_CLEARING#07`. `creditorInternal` is written on **every** transaction, internal ones
  included (a boolean has no "absent" state, and the settlement/reconciliation reads that filter on it
  must not miss items lacking the attribute) — `docs/data-model.md` §4 updated accordingly.
  Nothing is published or settled here: the outbox event is step 28, its publisher step 29, the consumer
  step 31. **Doc divergence recorded:** the step's "Verify locally" curl cannot return `202` yet — external
  keys only *resolve* once mock-bacen's DICT lands (step 30) — so `docs/steps/step-27.md` carries a note,
  and the branch is proven on the resolver port by `ExternalSendIT` (payer debited **and** clearing
  credited, DEBITED without `settledAt`, `gsi2pk=STATUS#DEBITED`, conservation, idempotent retry with no
  second debit) plus 5 new `SendPixUseCaseTest` cases. `mvn verify` green (payment-service: 50 unit + 31 IT).
  AI: est 2.5h / actual <Yh> / ~90% generated / 0 issues caught in human review
- LocalStack init: SNS pix-events + settlement-queue with DLQ/redrive and filtered subscription (step 26)
  `06-messaging-core.sh` brings up the platform's **first asynchronous infrastructure** — everything
  through Sprint 5 was synchronous. LocalStack now runs `SERVICES=dynamodb,sns,sqs` and creates the SNS
  topic `pix-events`, the `settlement-queue` + `settlement-queue-dlq`, and the SNS→SQS subscription.
  Nothing publishes or consumes yet (producer = the outbox publisher, step 29; consumer =
  settlement-service, step 31), so the queues come up **empty on purpose**.
  **The naming convention set here** (documented in `infra/localstack/init/README.md` and
  `docs/local-dev.md` §4): **one** topic for the whole platform, `pix-events` — fan-out happens at the
  *subscription*, never by adding topics; one queue **per consuming service**, `<purpose>-queue`, whose
  dead-letter queue is the same name plus `-dlq`. That shape is the SNS/SQS analogue of a Kafka topic
  with one consumer group per service (`docs/messaging-kafka-appendix.md`).
  **Four configuration decisions, each with a failure it prevents.** *(1) Redrive* — `maxReceiveCount=5`
  → DLQ, native to SQS and the reason a DLQ costs nothing here (in Kafka it is application code). A
  message in the DLQ is *flagged, not lost*: reconciliation (step 35) and the depth alert (step 44) own
  it, and 14-day retention (the SQS maximum) means it survives a long weekend. *(2) `VisibilityTimeout=30s`*
  — must exceed the settlement consumer's 12s SPI call (step 31); a shorter window would redeliver a
  message still being worked on and race two workers on the same transaction. It doubles as the retry
  backoff of step 32. *(3) `ReceiveMessageWaitTimeSeconds=20`* — long polling, so the consumer blocks
  instead of hammering the queue with empty receives. *(4) An explicit queue `Policy`* allowing only
  `pix-events` to `sqs:SendMessage`: the console adds this for you, the API does not, and its absence
  fails **silently** — SNS accepts the publish and delivery is denied, so the message simply never arrives.
  **Filter policy + raw delivery.** The subscription carries `FilterPolicy={"eventType":["PixDebited"]}`
  — broker-side routing on the message attribute the publisher will set (ADR-0004), so settlement never
  pays a receive for an event it does not handle (step 36 adds notification-queue with its own policy;
  step 42 adds an unfiltered audit-queue — the topic itself never changes). `RawMessageDelivery=true`
  delivers the event JSON as published rather than wrapped in the SNS notification envelope, so the
  consumer parses the same envelope the publisher wrote and stays broker-agnostic.
  **Idempotent and self-healing**: create-if-absent, then *always* converge attributes
  (`set-queue-attributes` / `set-subscription-attributes`), so a re-run repairs drift instead of
  failing; the subscription is created guarded, since a duplicate subscription would deliver a second
  copy of every event — self-inflicted, on top of the at-least-once we already design for.
  **Harness widened in the same change (required, not adjacent):** `06-` now sorts last, so
  `LocalStackTestBase`'s readiness wait moves from `[seed] ledger ready` to `[init] messaging ready`,
  and its `withServices(...)` gains SNS + SQS. LocalStack **enforces** `SERVICES` — an unlisted service
  answers `501 Service 'sqs' is not enabled` — so without the widening the script would abort under
  `set -e` and every IT in the repo would hang on the readiness wait.
  **Tests.** New `MessagingInitIT` (5) in common-lib asserts the resources, the redrive policy
  (`maxReceiveCount=5` → the DLQ ARN), the consumer timings, exactly one subscription with its filter
  policy — and, end to end, that a `PixDebited` published to the topic arrives **raw** on the queue while
  a `PixSettled` published *first* never does. The filter assertion was mutation-checked: widening the
  policy to `["PixDebited","PixSettled"]` makes it fail. SNS/SQS SDKs added to common-lib in **test**
  scope only (it stays THIN at runtime). Verified on the live compose container too: init log, the
  step's `list-topics`/`list-queues`, a manual publish/filter drill, a redrive drill (6 receives ⇒ DLQ
  depth 1), and the script re-run twice inside the running container ⇒ still 2 queues / 1 subscription.
  Full `mvn verify` green (all modules, 263 tests).
  **Human review raised two findings**, both about *posture rather than behaviour*, recorded as
  **ADR-0013** and scheduled for step 45 (see `### Changed`): (1) the queue resource policy added here
  is unenforced by LocalStack and was not marked as production-semantics-only, and (2) the AWS clients
  carry static credentials in a shape indistinguishable from the production anti-pattern. Neither
  changes what this step ships.
  AI: est 1.5h / actual 1.25h / ~90% generated / 2 issues caught in human review
- Fraud integration with a 200ms budget and fail-open (fraudSkipped flag), RECEIVED→FRAUD_CHECKED
  transition (step 25)
  payment-service now scores every send against fraud-service **between the limit reservation and the
  ledger debit** (ARCHITECTURE §6.5), finalizing ADR-0005 — the project's single most debated design
  call. The step is not about the engine (step 24 built that) but about **the caller's behaviour under a
  deadline**. Pieces:
  **(1) The 200ms hard budget lives in the adapter.** `HttpFraudScorer` (a `RestClient` to
  `POST /internal/fraud/score`) sets connect 50ms + read 150ms = the ADR-0005 budget, so a hung
  fraud-service surfaces as a timeout, never a pinned request thread. fraud-service targets p99 < 150ms,
  leaving margin under the 200ms cap. The bearer token and correlation id are forwarded like the other
  service-to-service hops.
  **(2) Fail-open at the boundary, not in the use case.** The new `FraudScorer` port **never throws** for
  a slow/broken fraud-service: the adapter catches any timeout/transport/5xx (and an empty 2xx body) and
  returns a fourth verdict, `FraudDecision.SKIPPED`, minted only on this side. This is the deliberate
  hexagonal split — "the call took too long / the host is down" is an infrastructure fact only the
  boundary observes, so translating it into `SKIPPED` there keeps `SendPixUseCase` a straight-line policy
  that knows nothing of HTTP: `DENY` blocks, `APPROVE`/`REVIEW`/`SKIPPED` all proceed.
  **(3) The three outcomes.** `DENY` ⇒ `422 FRAUD_DENIED` and the daily-limit reservation taken moments
  earlier is **released** (a denied send leaves the counter exactly as it found it, mirroring the
  insufficient-funds release — Domain Safety Rules intact: no money moved, nothing persisted). `REVIEW` ⇒
  proceed **flagged** for an analyst. `APPROVE` ⇒ proceed. On a timeout/error the send proceeds unscored,
  flagged `fraudSkipped=true` / `fraudDecision=SKIPPED`, with a `// outbox: FraudCheckSkipped` seam for
  async re-scoring (wired once the outbox exists, step 28/29) — availability of payments wins *at this
  layer*, residual risk bounded by daily limits + async re-score.
  **(4) The transition is recorded as durable fields, not a new status.** An internal send settles in one
  atomic posting straight to `SETTLED`, so there is no intermediate item to stamp `FRAUD_CHECKED` on;
  instead the verdict rides onto the transaction as `fraudDecision` + `fraudSkipped` (persisted in
  `pix_transactions`, `fraudSkipped` always written since a boolean has no "absent"), which *is* the
  durable record that the `RECEIVED → FRAUD_CHECKED` stage ran. A `DENY` never reaches the item.
  Verified: `HttpFraudScorerTest` proves the budget against a **real** slow JDK `HttpServer` (a 2s server
  delay still returns `SKIPPED` in < 1s), and 5xx/unreachable also fail open; `SendPixUseCaseTest` and
  `FraudIntegrationIT` prove APPROVE proceeds, DENY ⇒ 422 + no debit + limit released, and the fail-open
  skip proceeds flagged. `docs/data-model.md` corrected (the `fraud*` fields are written on internal
  sends too, since fraud sits in the shared send path). Both `PaymentArchitectureTest` rules stay green.
  `mvn verify` green (44 unit + 28 IT).
  AI: est 2.5h / actual 1.4h / ~88% generated / 1 issues caught in human review
- fraud-service rule-based `/score` (velocity, amount, novelty, hours) engineered for p99 < 150ms (step 24)
  The endpoint-less skeleton from step 23 grows its first business operation: `POST /internal/fraud/score`
  (authenticated, `/internal/**`), body `{accountId, pixKey, amountCents, timestamp?}` → `{decision:
  APPROVE|REVIEW|DENY, score, reasons[]}`. Four **cheap, in-path** rules read only pre-computed Redis
  features — no model, no DB, no network hop beyond Redis — which is *the* design point: heavy/ML scoring
  runs asynchronously off the event stream and feeds block-lists this check would read, so the in-path
  cost stays a handful of sub-millisecond ops well inside the 150ms internal target (leaving margin under
  the caller's 200ms budget, step 25). Pieces:
  **(1) The rule engine as a framework-free use case.** `ScoreFraudUseCase` (plain Java, ArchUnit-guarded)
  reads three features via the `FraudSignalStore` port then evaluates: HIGH_AMOUNT (single value >
  `high-amount-cents`), VELOCITY_COUNT (per-minute count ≥ threshold), VELOCITY_AMOUNT (per-hour rolling
  sum > threshold — the "vs the account's own recent profile" signal, since fraud-service has no DynamoDB),
  NEW_PAYEE (this account never paid this key) and ODD_HOURS (00:00–05:00 America/Sao_Paulo, Pix being
  domestic). Each fired reason adds its weight to a capped 0–100 score; `≥ deny-band (70)` ⇒ DENY,
  `≥ review-band (40)` ⇒ REVIEW, else APPROVE — so a single huge amount (weight 70) denies on its own.
  All knobs live in `fraud.rules.*` bound by `FraudProperties` and handed to the domain as a plain
  `FraudRules`, keeping `@ConfigurationProperties` out of `domain/`; the `Clock` is injected (odd-hours
  fallback when the caller omits the timestamp — no `Instant.now()` in the use case, ADR-0011).
  **(2) Redis velocity as `INCR`/`INCRBY` + `EXPIRE`, novelty as one `SADD`.** `RedisFraudSignalStore`
  (`@Repository`, confined to `infra/`) arms each window's TTL only on the first increment, making it a
  **tumbling** window — the accepted cheap-signal simplification over a sorted-set sliding window, a
  drop-in upgrade behind the same port if ever needed. `SADD` returns whether the payee was newly added,
  so novelty is a single round-trip with no read-then-write race; the payee set is persistent ("never
  paid before", the chosen semantics). **Chosen order: record-then-decide** — this transfer counts itself
  into velocity, so the N-th of a burst sees `count == N`; the trade-off is that scoring is *not*
  idempotent (a retried `/score` double-counts), acceptable because velocity is a soft signal and the
  caller fails open (revisited in step 25).
  **(3) The latency budget as a standing metric.** The controller records every call through a dedicated
  Micrometer `Timer` (`fraud.score`, with p50/p95/p99), so the target is observable in `/actuator/metrics`
  (scraped by Prometheus in step 44) — not just a one-off test assertion. Tests: `ScoreFraudUseCaseTest`
  (7 pure-domain cases against an in-memory fake — each rule and band in isolation), `FraudScoreIT`
  (7 cases over a real Redis via Testcontainers — the four rule families, 400 on a non-positive amount,
  401 without a token, plus a warm-p99-under-150ms sanity check). The `allowEmptyShould(true)` skeleton
  crutches on `FraudArchitectureTest` were dropped now that `..api..`/`..domain..` match real classes.
  Postman + API-explorer both gained a `Score a transfer` entry; README documents the endpoint, the rule
  table and the tuning knobs. `mvn verify` green.
  AI: est 3h / actual 2h / ~88% generated / 1 issue caught in human review
- Redis container (ElastiCache stand-in) + fraud-service skeleton + RedisTestBase harness (step 23)
  Sprint 5 opens by bringing up the infrastructure fraud needs and scaffolding the service that will use
  it — **no business endpoint yet** (the rule-based `POST /internal/fraud/score` is step 24; the 200ms
  fail-open call from payment-service is step 25). Three pieces:
  **(1) Redis as its own container (ADR-0008).** `redis:7-alpine` joins `infra/docker-compose.yml` with a
  `redis-cli ping` healthcheck, on `pix-net`, no named volume (it is a cache — `down -v` starts it clean).
  It comes up **now**, not earlier, because fraud is the first flow that needs it (per-account velocity
  counters); Sprint 9's balance cache reuses the **same** container. The one-line rationale that justifies
  a whole extra container: **LocalStack does not emulate ElastiCache**, so Redis cannot ride inside the
  emulator the way DynamoDB/SNS/SQS/S3 do — in production this maps 1:1 to ElastiCache for Redis.
  **(2) fraud-service skeleton (port 8083), full new-service checklist.** module + POM in the parent
  `<modules>`, `Application`, `application.yml` (Redis via `spring.data.redis.*` ← `REDIS_HOST`/`REDIS_PORT`,
  JWT validation, local-dev CORS, actuator probes), `Dockerfile`, a compose block that **gates its own
  startup on `redis` being healthy** (`depends_on: service_healthy`) rather than LocalStack — it reads
  Redis, not DynamoDB — `README.md`, a `FraudBeansConfig` composition root, `CorsConfig` ordered ahead of
  the JWT filter, and `FraudArchitectureTest` carrying **both** ArchUnit rules from day one. The Redis
  client is **Spring Data Redis** (Lettuce), confined to `infra/` — the ArchUnit domain rule now guards a
  Spring dependency the way account/ledger/payment guard the AWS SDK.
  **The interesting scaffold decision: the skeleton has no `api/` or `domain/` yet.** Every prior service
  shipped its first endpoint in its scaffold step; fraud-service genuinely cannot, because step 23's spec
  is infra-only and `/score` is step 24. Per ADR-0010 ("a service carries only the roles it actually has")
  the honest skeleton therefore has **only** `infra/config/` — no invented placeholder use case, no empty
  `domain/model|port|usecase`. Those layers (and the `ScoreFraudUseCase` + Redis-counter port + adapter)
  arrive in step 24 with the real endpoint. Consequence handled: ArchUnit fails a rule that matches **zero**
  classes (its typo'd-package guard), so both rules carry `allowEmptyShould(true)` **for the skeleton only**,
  documented and droppable once step 24's `..api..`/`..domain..` classes make the match non-empty. The
  rules still exist from day one — they catch step 24's first violation, not a reviewer's memory.
  **(3) `RedisTestBase` in the common-lib harness.** Mirrors `LocalStackTestBase`: a singleton static
  `GenericContainer("redis:7-alpine")` (Testcontainers ships no dedicated Redis module, and using
  `GenericContainer` keeps common-lib THIN — **no** Redis client is added to the shared lib), reaped by
  Ryuk on JVM exit, publishing `spring.data.redis.host`/`.port` via `@DynamicPropertySource` so any
  service's `@SpringBootTest` IT connects with zero code. Waits on the `Ready to accept connections` log
  line, not just the open port, so a `PING` succeeds the instant it is "started". Published in the
  existing test-jar; consuming modules re-declare the Testcontainers test dep (test-jar test-deps are not
  transitive, the accepted trade-off).
  **Tests.** common-lib gains `RedisHarnessIT` (2, client-free — `execInContainer("redis-cli","ping")`
  ⇒ `PONG`, and a `set`/`get` round-trip). fraud-service ships `FraudArchitectureTest` (2, both rules,
  vacuous today) and `ApplicationContextIT` (1, extends `RedisTestBase` — proves the context boots **with
  a live Redis connection** by round-tripping a key through the auto-configured `StringRedisTemplate`,
  the DoD's "boots with a Redis connection"). `mvn -pl services/common-lib,services/fraud-service -am
  verify` green — common-lib 22 unit + 5 IT, fraud-service 2 unit/arch + 1 IT.
  No `docs/api/openapi.yaml` change (no public surface yet). `docs/local-dev.md` §2/§3 already listed port
  8083 and `REDIS_HOST`/`REDIS_PORT` (written ahead); §4 gains the Redis `ping` + fraud health checks and
  the health loop now spans 8081–8085. Postman gains a `fraud-service` folder (health) and the API explorer
  a `fraud-service` section (health card) — the twin harnesses grow with the service even before its first
  business endpoint, so step 24 only adds the `/score` entry.
  AI: est 1.5h / actual 1h / ~90% generated / 0 issues caught in human review
- `GET /payments/{id}` status endpoint with owner-only access and internal→external status mapping (step 22)
  The transaction's public read side, and the first place the platform **translates its internal state
  machine to the external status vocabulary**. Clients see `PROCESSING / SETTLED / FAILED / REVERSED /
  REJECTED`; internal names like `DEBITED` or `SENT_TO_SPI` never cross the wire, so Sprint 6 can grow
  the machine without a single mobile client learning a new word (API-versioning discipline). For an
  **internal** send this endpoint is already terminal — the `202` said `PROCESSING`, but the poll reads
  back `SETTLED` with `settledAt`, the honest state the send response deliberately withheld; for external
  Pix (Sprint 6) it becomes the poll target while settlement runs.
  **Owner-only, and not-found ≡ not-yours (Domain Safety Rule #1).** The ownership check is the use
  case's decision (ADR-0011), taken against the JWT `accountId` the controller forwards — never a path
  or body field. A transaction that does not exist **and** one that exists but was debited from another
  account both raise `PaymentNotFoundException` → `404 PAYMENT_NOT_FOUND`; answering `403` for
  "exists-but-not-yours" would confirm a foreign id is real and let a caller enumerate other accounts'
  transactions, so the two cases are made indistinguishable.
  **The mapping is an exhaustive `switch` with no `default`** (`PaymentResponse.externalStatusOf`): the
  moment steps 27/33 add `DEBITED`/`SENT_TO_SPI`/`FAILED`/`REVERSED`/`REJECTED` to `TransactionStatus`,
  the code stops compiling until each new state is given its external face — the mapping can never
  silently fall through to a wrong default. Today's two states are exhaustive (`RECEIVED`→`PROCESSING`,
  `SETTLED`→`SETTLED`). `failureReason` is `null` until the `FAILED` states exist (step 33); both it and
  `settledAt` are always present in the JSON (as `null` when absent) so the shape never shifts under the
  client.
  **Where the logic lives (ADR-0010/0011).** New `GetPaymentStatusUseCase` (ownership + not-found
  decision) keeps the controller a three-liner — bind the path variable, call one use case, map the
  returned `Transaction` to `PaymentResponse` (the `Payment` schema; cents→decimal and status mapping at
  this one edge). `TransactionRepository` gains a `findById` port method; `DynamoTransactionRepository`
  implements it as a **strongly consistent** `GetItem` (read-your-writes: a client polls the payment it
  just created — an eventually-consistent read could briefly 404 a committed transaction). ArchUnit stays
  green: the use case is a class, `PaymentResponse` depends only on the `Transaction` record + enum, so
  `api/` still reaches no port. `PaymentExceptionHandler` maps the new `404`.
  **Tests.** `GetPaymentStatusUseCaseTest` (plain-Java, fake port): owner reads their own; unknown id and
  another account's id both raise `PaymentNotFoundException`. `PaymentResponseTest`: every current
  internal→external transition + the cents→decimal rendering. `StatusQueryIT` (MockMvc on LocalStack,
  same stub ledger/DICT as the send ITs): after a real internal send the owner gets `200 SETTLED` with
  the schema fields; a different account's token ⇒ `404`; an unknown id ⇒ `404`. `mvn -pl
  services/payment-service -am verify` green — 35 unit+architecture, 25 integration.
  **One fix along the way:** the new `@PathVariable` needed an explicit name (`@PathVariable("transactionId")`)
  because the build does not emit `-parameters`; without it Spring cannot bind the argument and every GET
  500s. No `docs/api/openapi.yaml` change — the `Payment` schema and the `404` were written ahead, so code
  conforms with no drift. Postman + API explorer each gain the status card (200) and a `404` card (the new
  endpoint is added to both harnesses in the step that introduces it); the service README and
  `docs/local-dev.md` §4 gain the status curls.
  AI: est 1.5h / actual 1.5h / ~90% generated / 0 issues caught in human review
- Internal Pix orchestration: key resolution + atomic ledger debit (credit payee directly), terminal status SETTLED (step 21)
  The first flow that moves a **user's real money**, end to end and synchronously. `POST /v1/payments/pix`
  gains its money-moving core for the **internal** case: inside the won idempotency claim it now runs
  `resolve → limit → debit → persist` — the orchestration shape the external, asynchronous flow (Sprint 6)
  will extend. Both legs are inside PlatinumCoin, so a single atomic ledger posting **is** the settlement:
  the terminal status is `SETTLED`, never a `DEBITED` that step 22 would map to an eternal `PROCESSING`.
  **Resolve first, on purpose.** The destination key is resolved against account-service's DICT
  (`GET /internal/pix-keys/resolve`) → the creditor's internal `accountId` **before** the daily-limit
  counter is touched, so an unknown key is `422 KEY_NOT_FOUND` with nothing to unwind. Only internal
  creditors are payable this step; a non-internal resolution is treated as not-found (external routing is
  step 27/30). **Debit atomically.** ledger-service is commanded via `POST /internal/ledger/postings`
  (`entryType=PIX_INTERNAL`, keyed by `txId`, Domain Safety Rules #2 & #4) to move debit+credit in one
  `TransactWriteItems`. **Failure mapping is the interesting part:** `INSUFFICIENT_FUNDS` ⇒ `422` **and the
  daily-limit reservation is released** (the guard lives inside the ledger transaction, so no money moved);
  ledger unreachable / read-timeout / `503 LEDGER_CONFLICT` ⇒ `503 LEDGER_UNAVAILABLE` + `Retry-After: 5`
  (nothing debited, and the `txId`-keyed idempotency makes re-sending the same key safe). A **circuit
  breaker** on repeated ledger failures is deliberately deferred to Sprint 7 / step 32 (a documented seam
  at the adapter); the HTTP client carries connect/read timeouts so a hung ledger surfaces as a timeout
  rather than pinning the request thread.
  **The reservation-release seam from step 20 is now consumed** — `DailyLimitReservation.release` is called
  on insufficient funds so the debtor's headroom is handed back exactly. A ledger-unavailable `503` does
  **not** release: the client retries the same idempotency key and that retry re-drives the flow, accepting
  the same conservative over-count edge ADR-0007/step 20 already documents (never overspend, self-heals next
  calendar day). On success the transaction is persisted **once** as `SETTLED` (a single `PutItem` after the
  posting commits — no `RECEIVED`-then-update), carrying `settledAt` and the resolved `creditorAccountId`;
  the idempotency record is then completed, so a replay re-serves the response and **never re-debits**
  (proven both plain-Java and on LocalStack). The `202` wire body keeps `status:"PROCESSING"` (the send
  contract is "accepted for processing"); the honest terminal `SETTLED` is served by `GET /payments/{id}`
  (step 22).
  **Where the logic lives (ADR-0010/0011).** Two new outbound ports keep `domain/` framework-free —
  `PixKeyResolver` (HTTP → account-service DICT) and `LedgerClient` (HTTP → ledger-service), both
  token-forwarding `RestClient` adapters that map the peer's error contract onto plain-Java domain
  exceptions (`KeyNotFoundException`, `InsufficientFundsException`, `LedgerUnavailableException`). The
  orchestration is in `SendPixUseCase` (a controller cannot touch a port — ArchUnit still green); the
  controller stays a three-liner. `TransactionStatus` gains `SETTLED`; `Transaction` gains
  `creditorAccountId` + `settledAt` (written only when set — a not-yet-settled item carries neither).
  `PaymentExceptionHandler` maps the three new codes (`Retry-After: 5` on the `503`).
  **Tests.** `SendPixUseCaseTest` grew to 17 (plain-Java, fake ports, pinned clock): resolve→debit→SETTLED
  moves money and stamps `settledAt`; unknown key ⇒ `KEY_NOT_FOUND` with no reservation taken (resolve runs
  before the limit); insufficient funds ⇒ release-to-zero; ledger-unavailable ⇒ **not** released (the
  over-count edge, asserted); a replay does not re-resolve or re-post. `InternalSendIT` (3, MockMvc on real
  LocalStack with in-memory stub ledger/DICT — the money-movement is asserted on the stub's double-entry
  balances, the transaction/idempotency/limit counter on the real tables): a send moves money on **both
  legs**, persists `SETTLED` + `settledAt` + `creditorAccountId`, and an idempotent retry replays the same
  `txId` without double-debiting; unknown key ⇒ `422 KEY_NOT_FOUND`; insufficient funds ⇒ `422` with the
  real LIMIT counter back to **0 used**. `SendSkeletonIT` updated (the persisted item is now `SETTLED`);
  `IdempotencyIT`/`DailyLimitIT` pass unchanged behind permissive stub defaults. Real ledger atomicity is
  ledger-service's step 14/15 suite; the true cross-service journey is step 46. `mvn -pl
  services/payment-service -am verify` green — 29 unit+architecture, 22 integration.
  No `docs/api/openapi.yaml` change: the contract already specified `202`, the `422` set
  (`KEY_NOT_FOUND`/`INSUFFICIENT_FUNDS`) and the `503` + `Retry-After: 5` (written ahead), so code conforms
  with no drift. `docs/data-model.md` §4 gains the internal-send fields (`creditorAccountId`, `settledAt`),
  `docs/local-dev.md` §4 a step-21 manual-verify block, and the service README the orchestration + new
  env (`LEDGER_SERVICE_BASE_URL`). Postman / API explorer unchanged — the endpoint was introduced in step
  18; this step changes behaviour, not the surface.
  **Scope note (added in review):** `POST /v1/payments/pix` today accepts **internal Pix only** — the
  destination key must resolve to a PlatinumCoin account, and the single atomic ledger posting *is* the
  settlement (`SETTLED` directly, no SPI leg). This is deliberate, not a limitation: the **external** case
  (destination at another bank) reuses the **same endpoint and the same orchestration shape** and only
  *thickens the methods* — the resolve step gains BACEN-DICT delegation, the debit credits the
  `SPI_CLEARING` account instead of the payee, and settlement becomes asynchronous — in steps 27–35. No
  new endpoint, no client-visible surface change is planned; the internal-vs-external branch is a use-case
  decision to be added where `acceptAndComplete` orchestrates, when those steps land.
  AI: est 3.5h / actual 2h / ~90% generated / 1 issue caught in human review
- Daily limit enforcement (calendar-day reservation counter) with a decision-object MFA seam mapping REQUIRE_STEP_UP to deny (step 20)
  Before any money moves, payment-service now reads the debtor's `dailyLimitCents` from account-service
  (`GET /internal/accounts/{id}`, forwarding the caller's bearer token — ADR-0007) and **reserves** the
  amount against a per-account, per-calendar-day counter (`LIMIT#<accountId>`/`DAY#<yyyy-MM-dd>` in
  `pix_transactions`, window = the **America/São Paulo** calendar day). Over the limit ⇒ `422
  LIMIT_EXCEEDED` with **nothing persisted**.
  **A maintained counter, not a query-and-sum.** `pix_transactions` deliberately has no index by debtor
  account, so "today's outbound total" is not a supported access pattern. Reserve is a conditional
  `UpdateItem ADD usedCents :amount` with `attribute_not_exists(usedCents) OR usedCents <=
  :limitMinusAmount` — an atomic increment bounded by the limit — and a counter is what makes **release**
  (`ADD -:amount`) well-defined: a later rejection/reversal (steps 21/25/33) hands back exactly what it
  reserved. The comparison value is computed in Java (condition expressions cannot do arithmetic); a
  **first-send guard** denies an amount that alone exceeds the whole limit before the counter is touched,
  since on the day's first send `attribute_not_exists(usedCents)` would otherwise wave any amount through
  (docs/data-model.md §4 amended).
  **The MFA seam is explicit (ADR-0007).** The check returns a `LimitDecision` object
  (`ALLOW`/`DENY`/`REQUIRE_STEP_UP`), not a boolean; `REQUIRE_STEP_UP` maps to the same deny path as
  `DENY` today, so plugging in a step-up challenge later changes **one branch, not the flow** —
  unit-tested to currently deny.
  **The reservation sits inside the won idempotency claim**, so a double-tap (one claim winner) or a
  replay (`COMPLETED`) never reserves twice. Two conservative, documented edges: a stale-reclaim after a
  crash between reserve and complete may over-count usage (never overspend, self-heals next day), and a
  `DENY` leaves the idempotency record `IN_PROGRESS` so an immediate retry gets `409 REQUEST_IN_PROGRESS`
  until the 60s stale window turns it into a deterministic `422`.
  Ports `AccountLimitClient` (HTTP, `RestClient` with token forwarding) and `DailyLimitReservation`
  (DynamoDB) keep `domain/` framework-free (ArchUnit still green). TTL on `pix_transactions.expiresAt`
  enabled in the step-17 init script (only `LIMIT#` items carry `expiresAt`; tx/outbox items untouched).
  Tests: `DynamoDailyLimitReservationIT` (reserve/deny/day-boundary/release-restores on LocalStack),
  `DailyLimitIT` (202 under limit, `422 LIMIT_EXCEEDED` on crossing, no tx advanced), and use-case units
  for the ALLOW/DENY/REQUIRE_STEP_UP branches.
  AI: est 3h / actual 2.25h / ~85% generated / 0 issues caught in human review
- Idempotency layer on send: conditional claim, response replay, 409 on key reuse with different payload (step 19)
  ADR-0002's **first layer** on `POST /v1/payments/pix` — the API-level answer to "the user tapped twice
  / the network retried". The `Idempotency-Key` header is now enforced and the send is de-duplicated per
  `(accountId, key)` in `pix_idempotency`, so a retry never mints a second transaction. Step 18 shipped
  the header **accepted-and-ignored**; this step closes that documented deviation from Domain Safety
  Rule #2.
  **The lifecycle — claim, execute, memoize, replay.** The amount is parsed *before* any idempotency
  write (a malformed request must leave no record behind), then a **conditional `PutItem`**
  (`attribute_not_exists(pk) OR expiresAt < :now`) atomically wins or loses the claim — lock and memo in
  one write, immune to check-then-act races. The winner does the acceptance work (mint ids, persist
  `RECEIVED`) and `complete`s the record with the HTTP status + a small response snapshot; a loser loads
  the live record and decides: same hash + `COMPLETED` ⇒ **replay** the memoized response (same
  `transactionId`, byte-identical body); different hash ⇒ `409 IDEMPOTENCY_KEY_REUSED`; same hash +
  `IN_PROGRESS` ⇒ `409 REQUEST_IN_PROGRESS` + `Retry-After: 2`. A missing header is `400
  IDEMPOTENCY_KEY_REQUIRED`.
  **The two sharp edges ADR-0002 names, both handled.** (1) *The claim-crash window.* An `IN_PROGRESS`
  claim whose `claimedAt` is older than a 60s staleness window is treated as crash-orphaned and
  **re-claimed** by the retry (a conditional `UpdateItem` on `claimedAt`, so exactly one racer wins),
  instead of blocking the client until the 24h TTL. (2) *Lazy TTL.* DynamoDB's TTL deletion can lag
  hours, so `get` treats an expired-but-present record as **absent**, and the claim's `OR expiresAt <
  :now` lets a fresh request re-claim an expired key immediately — the 24h window is enforced by the
  application, not by the deletion.
  **The request-hash is canonical.** Replay-vs-`409` compares a **canonical-JSON SHA-256** over the
  normalized request fields (`pixKey`, `amount`, `description`), computed by the use case via a new
  `common-lib` `CanonicalJson` — key order and whitespace never change the hash, a different amount does.
  Hashing the *normalized fields* (not raw bytes) was a deliberate choice: immune to cosmetic
  reformatting and to irrelevant extra keys, with no raw-body plumbing; the debtor is not hashed because
  the record is already scoped per account by its key.
  **Where the logic lives (ADR-0010/0011).** The ArchUnit rule "`api/` never depends on an interface in
  `domain/`" **forced** the orchestration into the use case: a controller cannot touch the
  `IdempotencyRepository` port, so `SendPixUseCase` became the idempotent orchestrator (one use case per
  inbound op) and the controller stayed a three-liner. The result type `SendPixOutcome` is a **record**
  (with a `replayed` flag), not a sealed interface — a result interface in `domain/` would have tripped
  that same ArchUnit rule. The response snapshot stores only the two ids (+ httpStatus); the wire
  vocabulary (`"PROCESSING"`) is re-applied at the `api/` edge, so no wire concern leaks into `domain/`
  and a fresh `202` and a replay render through the identical path.
  **Domain.** New port `IdempotencyRepository` (`claim`/`get`/`complete`/`reclaim`), `IdempotencyRecord`
  + `IdempotencyStatus`, three plain-Java exceptions (`IdempotencyKeyRequired`/`…KeyReuse`/
  `RequestInProgress`), `SendPixOutcome`, and `idempotencyKey` added to `SendPixCommand`. **infra/**
  `DynamoIdempotencyRepository` (the two conditions above; Jackson serializes the snapshot, staying out
  of `domain/`). **api/** the controller reads the header and renders the outcome; the exception handler
  maps the three codes and attaches `Retry-After`.
  **Tests.** `CanonicalJsonTest` (9, common-lib) pins the invariance (order/whitespace) and the
  sensitivity (value); `SendPixUseCaseTest` (10) drives every branch plain-Java with fake ports and a
  pinned clock — replay creates exactly one transaction, reuse `409`s, a fresh in-progress `409`s, a
  stale one re-claims and completes; `IdempotencyIT` (6, MockMvc on real LocalStack) proves the same over
  the real tables incl. a **concurrent double-fire** creating exactly one transaction (the DB is the
  arbiter, counted by scan) and an explicit `Retry-After: 2` on the in-flight `409`. `SendSkeletonIT`
  passes **unchanged** (it already sent the header). `mvn -pl services/payment-service -am verify` green.
  Verified on the **running compose stack**, not only in tests: alice's first send returns a `tx-…`; the
  identical retry returns the **same** `transactionId`; the same key with `99.00` returns `409
  IDEMPOTENCY_KEY_REUSED`; a missing header returns `400 IDEMPOTENCY_KEY_REQUIRED`.
  No `docs/api/openapi.yaml` change: the contract already specified the required `Idempotency-Key`, the
  `409` and the `Retry-After` on retry (written ahead), so code conforms to it with no drift. Postman /
  API explorer unchanged — the endpoint was introduced in step 18; this step changes behaviour, not the
  surface, and Postman already auto-generates the key on the money-moving POST.
  AI: est 3h / actual 1.5h / ~90% generated / 0 issues caught in human review
- payment-service POST /payments/pix walking skeleton: validation, txId/endToEndId, 202 + Location (step 18)
  The platform's fifth service (port 8084) and the **client-facing send-Pix entry point** — the one
  endpoint an end user reaches to move money. This step ships the *walking skeleton*: a real,
  persisted, JWT-protected request with the correct **shape** (status codes, headers, ids, the
  debtor-from-JWT rule) before any behaviour thickens it. No idempotency claim (step 19), no daily
  limit (step 20), no key resolution or ledger debit (step 21) — deliberately.
  **The endpoint.** `POST /v1/payments/pix` validates the body, mints a `txId` (`tx-<uuid>`) and a
  Pix-standard `endToEndId`, persists the transaction as `RECEIVED` in `pix_transactions`, and returns
  `202 Accepted` + `Location: /v1/payments/{txId}` + `{transactionId, endToEndId, status:"PROCESSING"}`.
  The wire status is `PROCESSING` (the external vocabulary) even though the item is stored as
  `RECEIVED` (the internal state machine): `202` means "accepted for processing, not settled", and
  step 22 will map the full internal→external vocabulary.
  **Domain Safety Rule #1, made inexpressible.** `SendPixRequest` carries `pixKey`, `amount`,
  `description` and **no source-account field**; the debtor is `AuthenticatedUser.accountId()` from the
  validated JWT. An extra JSON key (`debtorAccountId`) is silently dropped, never bound — proven by a
  dedicated IT that sends `acc-999` in the body and asserts the stored debtor is still the token's
  `acc-001`. The safest enforcement of "never from the payload" is to make the wrong thing
  unsendable.
  **Money is integer cents, and the parse never touches a `double`.** `amount` arrives as a decimal
  string; the wire `@Pattern` (`^\d{1,9}\.\d{2}$`) bounds its shape (≤ 9 integer digits, comfortably
  inside a `long`), and `Money.toCents` converts it with `BigDecimal.movePointRight(2).longValueExact()`,
  enforcing the two rules the pattern cannot: **strictly positive** (`"0.00"` ⇒ `400 INVALID_AMOUNT`,
  a distinct code from the shape-level `400 VALIDATION_ERROR`) and **no sub-cent** (`"1.005"` ⇒
  refused rather than rounded). `MoneyTest` pins these plus the exactness of the pattern's ceiling
  (`999999999.99` → `99999999999` cents, a value a `double` could not hold).
  **The `endToEndId` is a contract.** Format `E<ISPB(8)><yyyyMMddHHmm-UTC(12)><random(11)>` — a fixed
  32 chars, minted now because it is stable for the transaction's whole life and later becomes the
  idempotency key toward BACEN (ADR-0002's third layer). The timestamp is UTC from an injected
  `Clock` (deterministic, pinnable — it is an opaque id, never shown to a user); the ISPB is
  configuration (`pix.ispb`, default `12345678`), and `EndToEndIdGenerator` fails fast at wiring if it
  is not 8 digits. `EndToEndIdTest` pins the shape, the embedded UTC minute and that the random suffix
  differs across calls.
  **The persisted item is index-consistent from the first write.** The `TX#<txId> / META` item carries
  `gsi1pk = E2E#<endToEndId>` (reconciliation / inbound-dedup lookup) and `gsi2pk = STATUS#RECEIVED` +
  `gsi2sk = updatedAt` (the stuck-transaction scan), so steps 27/34's access patterns work with no
  backfill; fields a later step owns (resolved creditor + `creditorInternal`, fraud verdict,
  settlement, the `OUTBOX#` sibling) are deliberately not invented. The write is an unconditional
  `PutItem`: the `txId` is a fresh UUID and request-level de-dup is step 19's layer.
  **Idempotency-Key: accepted and ignored, on purpose.** The header is required by the OpenAPI
  contract, but the conditional claim + response replay + `409`-on-hash-mismatch is step 19 (a
  hand-written-test zone). The controller neither reads nor enforces it this step, with a
  `// step 19` seam noted — a knowing, documented deviation from Domain Safety Rule #2 for the
  skeleton, closed next step.
  **Full new-service checklist per CLAUDE.md:** module + POM in the parent `<modules>`, `Application`,
  `application.yml`, Dockerfile, compose block (gated on `localstack: service_healthy`, which also
  guarantees step 17's tables exist), `README.md`, the three packages with one `SendPixUseCase` and a
  `PaymentBeansConfig` composition root (Clock + EndToEndIdGenerator wired there), `PaymentArchitectureTest`
  with **both** ArchUnit rules from day one, CORS ahead of the JWT filter, and the endpoint added to
  **both** the Postman collection (a `payment-service` folder: send/zero/malformed/health, with an
  auto-`{{$guid}}` Idempotency-Key on the money-moving POST) and the API explorer (a `payment-service`
  tab, with the auto-UUID Idempotency-Key helper finally wired into the send path — it had been a
  dormant field since the auth-service card, waiting for "the payment flow"). `docs/local-dev.md` §4
  gains a step-18 manual-verification block. No `docs/api/openapi.yaml` change: the contract already
  specified `/payments/pix`, `PaymentAccepted`, the bounded strictly-positive `amount` and the
  required `Idempotency-Key` (written ahead), so code conforms to it with no drift.
  **Tests.** 23 total (17 unit + architecture, 6 integration): `MoneyTest` (6), `EndToEndIdTest` (4),
  `SendPixUseCaseTest` (5, plain-Java with a fake port + fixed clock — pins that the debtor comes from
  the command and never the payload, and that `"0.00"` persists nothing), `PaymentArchitectureTest`
  (2); `SendSkeletonIT` (5, MockMvc on real LocalStack — 202 + Location + item read back as RECEIVED
  with debtor `acc-001`, the source-account-in-body injection, `"0.00"`⇒400, malformed⇒400,
  no-token⇒401) and `ApplicationContextIT` (1). `mvn -pl services/payment-service -am verify` green;
  full reactor `mvn package` green.
  AI: est 2h / actual 1.5h / ~90% generated / 1 issue caught in human review
- LocalStack init: pix_transactions (GSI1/GSI2/sparse GSI3) and pix_idempotency (TTL) tables (step 17)
  `03-dynamodb-payment.sh` creates the two payment-service tables the internal-send flow (steps 18–21)
  needs — infra only, no seed rows (transactions are born from the flow, not seeded). Numbered **03**
  so it sorts before the `04`/`05` seeds: these tables carry nothing to seed, so the harness's
  readiness marker (the last line of `05-seed-ledger.sh`) stays put — no marker to move.
  **`pix_transactions`** (PK `TX#<txId>`, SK `META` | `OUTBOX#<eventId>`) is created with **all three**
  GSIs now, even though only some are used this sprint — the interesting design call of the step. GSI1
  (`E2E#<endToEndId>`) is the reconciliation / inbound-dedup lookup; GSI2 (`STATUS#<status>` +
  `updatedAt`) is the stuck-transaction scan; GSI3 (`OUTBOX#UNPUBLISHED` + `occurredAt`) is the outbox
  publisher's work queue and is **sparse** — only unpublished outbox items carry `gsi3pk` (it is
  `REMOVE`d after the SNS publish), so the index stays O(in-flight), never O(history). Creating all
  three up front is a *choice, not a constraint*: unlike LSIs, **GSIs can be added to a live table
  later** (`UpdateTable` + backfill), but backfilling a fat table is slow and costly and the key schema
  is already fully designed — so we pay the cost now while the table is empty. The single-table design
  (tx `META` and its `OUTBOX#` items in the same `TX#` partition) is what lets step 28 write the
  transaction and its outbox event in one `TransactWriteItems`. All GSIs `Projection: ALL` (consistent
  with `pix_ledger`'s `gsi1`).
  **`pix_idempotency`** (PK `IDEM#<accountId>#<idempotencyKey>`, SK `META`) gets **TTL on `expiresAt`**
  — a separate `update-time-to-live` call, not part of `create-table`; the replay window is 24h and
  DynamoDB's deletion is **lazy**, so step 19's reads must still treat an expired-but-present record as
  absent (ADR-0002). The TTL enable is guarded by `describe-time-to-live` so re-running the script is a
  no-op once enabled, matching the `describe-table || create-table` idiom of the sibling scripts.
  `infra/localstack/init/README.md` gains the `03-…` entry; `docs/local-dev.md` §4 mirrors both
  `create-table` commands, the `update-time-to-live`, and the two verify commands. `docs/data-model.md`
  §4/§5 already specified this exact schema (written ahead), so no doc drift to correct.
  Verified on the **running LocalStack container**, not only by reading the script: `down -v` + a fresh
  `up` created the tables in lexical order (`03` before the `04`/`05` seeds, seed readiness marker
  firing last); `describe-table` shows `gsi1`/`gsi2`/`gsi3` with the right key schemas and
  `describe-time-to-live` shows `ENABLED` on `expiresAt`; and re-running `03-dynamodb-payment.sh`
  in-container skipped every resource including the TTL guard (idempotent). No tests added — the step
  defers verification to the payment-service ITs (steps 18–21) and the runbook check, and the DoD asks
  for none.
  AI: est 0.75h / actual 0.5h / ~90% generated / 0 issues caught in human review
- Ledger statement query: newest-first entries with opaque DynamoDB cursor pagination (step 16)
  `GET /internal/ledger/accounts/{id}/entries?cursor=&limit=` → `{entries:[...], nextCursor}`, the
  internal seam the public statement API (step 41) will proxy. The statement is **free from the key
  design**: the sort key is `ENTRY#<isoTimestamp>#<txId>`, so a `begins_with(sk,"ENTRY#")` range query
  scanned backwards (`ScanIndexForward=false`) is already reverse-chronological — no sort in DynamoDB
  or in memory. This is what the step-14 fixed-width millisecond timestamp was *for*: lexicographic
  order must equal chronological order, or a round-second entry would page wrong.
  **Cursor pagination the DynamoDB way.** There is no offset in DynamoDB, so the cursor is the base64
  of the query's `LastEvaluatedKey`, **opaque** to the client, and `nextCursor` is `null` exactly when
  the last query returned no continuation. The token is serialized to the same `{name:{S|N:…}}` JSON
  DynamoDB itself uses (so it round-trips any key attribute type) and is decoded **only in the
  adapter** — because only the adapter can, it is an AWS key. That placement is the interesting design
  call of the step: the **cross-account guard** (a cursor embeds `ACCOUNT#<id>`, so a forged token
  must never page another partition) is inseparable from decoding the AWS-typed key, so the adapter
  owns it and throws the plain-Java `InvalidCursorException` — the same pattern by which
  `DynamoLedgerRepository` already raises `InsufficientFunds`/`LedgerAccountNotFound`. Malformed base64
  and a well-formed cursor naming a different account are the **same** failure — `400 INVALID_CURSOR`
  — so a tampered token is refused, never silently redirected. `limit` is the use case's policy
  (default 20, ceiling 100, floored at 1 so a nonsensical `0`/negative is coerced rather than passed
  to DynamoDB, which rejects a non-positive `Limit`); the controller does no clamping and no cursor
  parsing, per ADR-0011.
  **Domain.** `StatementPage(entries, nextCursor)`, `InvalidCursorException`, a
  `getEntries(accountId, cursor, limit)` on the `LedgerRepository` port, and `GetStatementUseCase`
  (clamp + delegate + business-stage logging). **api/** gains `StatementResponse` and `StatementEntry`
  — the wire edge where signed cents become a signed decimal string (`"-125.50"` on a DEBIT) beside
  the `amountCents` integer, mirroring `BalanceResponse`; the timestamp is rendered with the same
  fixed-width millisecond format the sort key carries. The controller gained the `/entries` mapping,
  the exception handler the `400 INVALID_CURSOR` case.
  **Tests.** `GetStatementUseCaseTest` (6) pins the limit policy (default/cap/floor/pass-through) and
  that account+cursor reach the port untouched; `DynamoLedgerRepositoryTest` (+5) pins the **request
  shape** the emulator cannot expose — `ScanIndexForward=false`, `Limit`, `begins_with`, the
  `nextCursor→ExclusiveStartKey` round-trip, "no continuation ⇒ null cursor", and both cursor
  refusals (fail-closed before any query for a malformed one); `StatementQueryIT` (5) drives the whole
  HTTP endpoint on real LocalStack — posts a 12-entry history, pages it at `limit=5` asserting
  newest-first order, **no overlap and no gap** (every txId once), `nextCursor` null only on the last
  page, and both tampered and cross-account cursors as `400`. Module suite **68 tests** (37 unit +
  architecture, 31 integration), `mvn -pl services/ledger-service -am verify` green.
  Verified on the **running compose stack**, not only in tests: seven demo postings grow acc-001's
  history to 8 legs (the seven plus the seeded `tx-seed-alice` credit as the oldest); `limit=3` pages
  3+3+2 newest-first with the eight txIds distinct and no gaps; the entry shape carries `amount`
  `-1.07`/`amountCents` `-107`; a tampered cursor and a cursor minted for acc-002 both return
  `400 INVALID_CURSOR`, and no token `401` — every one reconstructed end to end by a single
  `grep cid=…` across auth-service + ledger-service (JWT accepted → use case → `Query` with the exact
  pk/beginsWith/scanIndexForward → outcome).
  No `docs/api/openapi.yaml` change: `/internal/**` is by contract not part of the public surface, as
  that file already states for the other internal seams. `docs/data-model.md` §3 already specified
  this exact query and cursor (written ahead in the schema), so no doc drift to correct.
  AI: est 2h / actual 2h / ~90% generated / 0 issues caught in human review
- Ledger invariant suite: concurrent storm proving no-negative-balance, no-double-spend and conservation of money (step 15)
  **✍️ Hand-written zone** — the entire `LedgerInvariantsIT` was written by the human, by hand, without
  AI code generation (AI assisted with Java syntax only); Claude's role was limited to reviewing the
  finished suite, running it, and wiring the docs. So the metrics line below is **inverted** from every
  other entry: `issues caught in human review` normally counts defects the human found in AI code — here
  it counts defects **Claude found in the human's code** (zero; three trivial non-defect notes recorded
  below). This is the deliberate-practice payoff the hand-written zones exist for: `ExecutorService` +
  `CountDownLatch` to release N threads at once, worker threads that **return** rather than assert (an
  `AssertionError` on a pool thread never reaches JUnit — a test that goes green while testing nothing),
  and **system-level** invariants (Σ balances constant, Σ entry legs == 0) rather than per-call outcomes.
  Four ITs, all on real LocalStack, each on its **own fixture accounts** (isolated partitions, so the
  step-13 absolute seeded-supply assertions are never disturbed by a shared container):
  **(1) debit storm** — balance 1000_00, 50 parallel postings of 100_00 → **exactly 10** succeed, 40
  fail `INSUFFICIENT_FUNDS`, final balance 0, history exactly 10 debit + 10 credit legs, Σ per account
  matching. The `==` on the success count is the whole point: `<=` would pass a storm that refused
  affordable payments, `>=` one that overdrew. **(2) conservation** — a random transfer storm (24
  threads × 8 transfers, fixed per-thread seed so the sequence is reproducible though the interleaving
  is not) among 5 user accounts + a `SPI_CLEARING#…` account allowed to go **negative** (a stronger
  conservation claim than an all-positive one): Σ balances before == after, Σ of every entry leg == 0,
  **and** per account `balance == opening + Σ its legs`. **(3) replay under concurrency** — the same
  `txId` posted from 10 threads at 10 different instants → exactly one commit, the money moves once, all
  ten replies carry the **committed** `postedAt` (not the retry's), one set of entries. **(4) never seen
  negative** — a sampler thread reading the payer's balance throughout a storm: never negative, never
  above the opening, always a multiple of the posting amount (a value no legitimate posting sequence can
  leave). A smoke detector, not a proof — the real proof is that the guard lives inside the transaction;
  but the first thing to go red if anyone ever moves that condition into Java. A `postWithResends` helper
  re-sends the same command on `503 LEDGER_CONFLICT` (nothing was written, so the same `txId` is safe to
  resend), with jitter — without it, threads that burn the adapter's 3-attempt budget vanish and the
  counts stop adding up non-deterministically. Wired into `mvn verify` for free: the class is a `*IT`,
  so failsafe runs it on a plain `mvn verify` with no flag and it is not skippable (DoD #3). No `pom.xml`
  or config change was needed. Verified: `mvn -pl services/ledger-service -am verify` green —
  `LedgerInvariantsIT` 4/0/0/0, module total **68 tests** (37 unit + architecture, 31 integration),
  `BUILD SUCCESS`.
  AI: est 4h / actual 6h / ~0% generated (hand-written zone; AI: Java syntax help only) / 0 defects found in Claude's review
  Notes from Claude's review (all non-defects, left as-is by design):
  1. `legsOf(txId)` reads **GSI1** with `.hasSize(2)` in the replay test; a GSI is eventually
     consistent, so on real DynamoDB that one assertion could flake (on single-node LocalStack it is
     effectively synchronous, hence green). The "one set of entries" claim is already proven by the
     `entriesOf` assertions, which read the **base table** with `ConsistentRead`; `legsOf` only
     corroborates. Left as-is knowingly.
  2. `import java.util.concurrent.ExecutorService` is unused (the pools are held in `var`); the build
     does not enforce unused-imports, so it does not fail. A trivial cleanup for a later pass.
  3. `Outcome.BUSY` exists only so the `busy == 0` assertion reads as "contention, not a broken
     invariant" — never expected to occur thanks to `MAX_RESENDS`. Intentional.
- Atomic double-entry ledger posting via TransactWriteItems with conditional no-negative-balance and txId idempotency (step 14)
  `POST /internal/ledger/postings` — the operation the whole platform is built around, and the direct
  answer to *"how do you guarantee money is never debited without being credited?"*: the debit and the
  credit are literally the same DynamoDB transaction, so no code path can write one leg. Both accounts
  are **explicit inputs** (the seam step 52 needs to shard `SPI_CLEARING` without touching a caller);
  the "debited account comes from the JWT" rule binds payment-service, which is the endpoint a client
  can actually reach.
  **The spec was amended during implementation, and the reason is the interesting part.** The step file
  and `docs/data-model.md` §3 specified **four** writes, with double-post protection resting on
  `attribute_not_exists(pk)` on the two ENTRY puts. That does not make the posting idempotent: an
  entry's key is `ENTRY#<timestamp>#<txId>` and the timestamp comes from the clock, so a caller
  retrying after a timeout sends the same `txId`, lands at a **new instant**, writes a **different**
  key, passes the condition — and the payer is debited twice, in exactly the scenario idempotency
  exists for. The transaction is now **five** items: the fifth is a `TX#<txId> / POSTING` guard keyed
  on the `txId` **alone**, which removes the clock from the identity of a posting. It doubles as the
  stored posting record: with `ReturnValuesOnConditionCheckFailure=ALL_OLD`, a cancelled guard hands
  the committed command back **inside the cancellation**, so the replay/mismatch verdict is strongly
  consistent and costs **no extra read** — retiring the eventually-consistent GSI1 re-read the step
  originally described and leaving GSI1 a pure audit index (the guard carries no `gsi1pk`, so "both
  legs of TX#t" still returns exactly two items). `docs/data-model.md` §3, `ARCHITECTURE.md` §6.3 and
  `docs/steps/step-14.md` were updated in this same commit, the step file carrying an explicit
  amendment note so the trail of *why the first design did not hold* survives.
  **The five items and their conditions.** (1) debit BALANCE — `attribute_exists(pk) AND balanceCents
  >= :amount`; (2) credit BALANCE — `attribute_exists(pk)`; (3)(4) the two ENTRY legs —
  `attribute_not_exists(pk)`, DEBIT negative / CREDIT positive so Σ of a posting is zero; (5) the
  guard. Two conditions were **added** to the data model and are worth the words: `UpdateItem` is an
  *upsert*, so without `attribute_exists` a typo'd payee would silently **create** a ledger account and
  park the money there. And `ALL_OLD` on the two balance updates is what makes the failures
  distinguishable — a cancelled debit that comes back **with** the item is `422 INSUFFICIENT_FUNDS`
  (and by how much it fell short), **without** one is `404`. Without it, "you have no money" and "that
  account does not exist" arrive as the same anonymous `ConditionalCheckFailed`.
  **Reading `cancellationReasons()` is a business decision, not a mapping.** Guard first: a replay
  that would *also* now be short of funds is still a replay, because the money it names moved when it
  first committed and a 422 would report as failed a payment that succeeded. Then funds/existence,
  then a stale entry without a guard (`409` — the shape the step-12 seed postings have), then
  `TransactionConflict`, which is contention rather than a rule violation: 3 attempts with jittered
  backoff (the jitter matters as much as the delay — without it everything that collided once retries
  in the same millisecond), then `503 LEDGER_CONFLICT`, on which the caller may safely re-send the
  same `txId`. The request object is built **once** and re-sent unchanged on retry, so the entry keys
  never move.
  **Wire contract.** `200` for both a fresh posting and a replay, distinguished by `replayed` and
  always carrying the *original* `postedAt`. Answering differently would train callers to treat a
  retry as a failure and mint a new `txId` — the one reaction that actually double-spends. Errors:
  `422 INSUFFICIENT_FUNDS`, `422 INVALID_POSTING` (amount ≤ 0, blank identity, both legs on one
  account — which DynamoDB would otherwise reject as two operations on one item, i.e. a 500 for a
  business rule), `409 POSTING_TXID_MISMATCH`, `404 LEDGER_ACCOUNT_NOT_FOUND`, `503 LEDGER_CONFLICT`,
  `400 VALIDATION_ERROR`. No `Idempotency-Key` header: the ledger's idempotency key is the `txId` in
  the body — the identity of the posting itself, not an HTTP de-duplication of one client's request
  (that is payment-service's layer, ADR-0002).
  **Domain.** `PostingCommand` / `PostingResult`, `AccountPolicy` (the single switch exempting `SEED`
  and `SPI_CLEARING*` from the funds guard — a **prefix** rule, so step 52's clearing shards do not
  silently become balance-guarded user accounts), `PostDoubleEntryUseCase` (validity, the injected
  `Clock`, description normalization — never the guards, which are conditions inside the transaction),
  and four plain-Java exceptions. The instant is **truncated to milliseconds** and formatted
  fixed-width (`…ss.SSS'Z'`), because `Instant.toString()` omits trailing zeros and `'Z'` (0x5A) sorts
  after `'.'` (0x2E): a round-second entry would sort *after* one 500 ms later, and step 16's
  newest-first statement — which relies on nothing but lexicographic order — would return the wrong
  page.
  **Tests.** 43 new (26 unit + 17 integration): `DynamoLedgerPostingTest` (17) pins the shape of the transaction against a
  hand-written SDK stub — every condition, `ALL_OLD`, the system-account exemption, the sort-key
  format, the retry budget — and drives every cancellation branch from constructed
  `TransactionCanceledException`s, which is the only way to cover them without racing an emulator;
  `LedgerPostingIT` (9) and `InternalLedgerPostingIT` (8) run against real LocalStack, asserting on
  every failure path both the exception **and zero writes** (no balance moved, no leg appended, no
  guard left behind), plus the retry-at-a-later-instant that would double-post without the guard.
  `AccountPolicyTest` and `PostDoubleEntryUseCaseTest` cover the policy and the refusals. Concurrency
  is deliberately absent: the debit storm and Σ-conservation under contention are step 15's
  hand-written suite. The posting ITs open their **own fixture accounts** rather than spending alice's
  seeded money — the step-13 ITs assert the seeded supply in absolute terms and all `*IT` classes share
  one container, so moving that money would have made the suite order-dependent (it did, once, and
  that is how the fixture was found).
  Verified on the **running compose stack**, not only in tests: the step's curl returns `"replayed":
  false` and moves alice 10000.00 → 9874.50 with bob up the same, Σ over the four accounts still **0**;
  the identical request again returns `"replayed": true` with the first `postedAt` and alice still at
  9874.50; `409/422/422/404/400/401` all answer as specified and **left no guard item, no leg and no
  `ACCOUNT#acc-404`** behind (checked with raw `get-item`/`query`), and GSI1 returns exactly the two
  legs. LocalStack does return the `ALL_OLD` payload, so the defensive re-read never fired. Suite:
  **138 tests** (71 unit + architecture, 67 integration), all green on a plain `mvn verify` — counted
  from the surefire/failsafe reports of that run, not extrapolated from the previous step's entry.
  AI: est 3h / actual 1.5h / ~95% generated / 0 issues caught in human review

### Changed
- Logging reworked for a human reader: correlation id in the pattern, prose messages, real values (ADR-0012)
  Not a PLAN step — a cross-cutting change requested in review, applied to every service built so far.
  **ADR-0012** is new and states the posture, including the LGPD trade-off and the table of exactly
  what production reverses. Four changes, all owned by `common-lib` so a new service inherits them by
  depending on it:
  (1) **The correlation id moved into the log pattern.** `logback-spring.xml` sets Spring Boot's own
  `LOG_CORRELATION_PATTERN` hook, so every record — ours, Spring's, Tomcat's, the AWS SDK's — is
  prefixed `[cid=… tx=…]`. Consequently `CorrelationIdFilter`'s `INFO http.request …` line is
  **removed**: it existed to give the id a home, and with it went the `/actuator` special-case that
  kept healthchecks from drowning the log. `grep cid=<id>` now returns strictly more than that line
  did. The filter keeps read-or-generate + MDC + response header; `CorrelationIdFilterTest` is
  unchanged and still passes, which is the point — the behaviour that mattered did not move.
  (2) **Human-readable console is the default**, the logstash JSON encoder is
  `SPRING_PROFILES_ACTIVE=json-logs` (the `dev` profile no longer affects logging). Compose activated
  no profile, so `docker compose logs` had been printing JSON to a human.
  (3) **Message convention replaced.** The dotted `<domain>.<action>.<outcome>` tokens are gone;
  a line is now an English sentence naming the decision *and its consequence*, then ` | key=value`
  pairs — `Pix-key deletion refused, the key belongs to another account, returning 403 | keyValue=… callerAccountId=… ownerAccountId=…`
  instead of `account.key.delete.forbidden`. Prose for the reader, pairs for the grep.
  (4) **Values are logged, secrets are not.** Pix keys are now logged raw *and* normalized side by
  side (`ResolvePixKeyUseCase` previously logged neither, which made a DICT trace unable to answer
  the only question asked of it); accounts print every field; rejected request fields print their
  values; `com.platinumcoin.pix` runs at **DEBUG by default** so the DynamoDB calls and their keys are
  visible without knowing a flag exists. Passwords, bcrypt hashes, compact JWTs and AWS credentials
  remain unlogged at every level — `JwtIssuer` logs the claims it signed, never the token.
  Coverage gaps closed while there — the guiding rule being **every non-2xx the platform returns has
  a line under its correlationId**, which the removed per-request line used to provide by accident:
  `GlobalExceptionHandler` logs domain 4xx (code + status) and which field failed validation with its
  value, and a new `handleExceptionInternal` override covers everything **Spring MVC rejects before a
  handler runs** — unknown path, wrong method, unsupported media type, unreadable body. That last one
  was found by running the stack: a typo'd URL returned 404 and produced *zero* log lines, because no
  controller, use case or filter was ever reached. `AccountExceptionHandler` now logs the outcome
  (status + code) next to the use case's reason, matching what auth-service already did.
  `JwtAuthFilter` logs method/path on a 401 and a DEBUG line per authenticated call (`userId`,
  `accountId`, method, path), and auth-service's `CorsConfig` gained the startup breadcrumb
  account-service already had. Login now distinguishes `unknown_user` from `bad_password`
  **in the log** while the response stays a single generic 401 — the asymmetry is deliberate and
  commented. Docs squared in the same change: CLAUDE.md's logging convention rewritten (it previously
  mandated the dotted names and forbade removing the request line), ARCHITECTURE §6.11/§7.7, the
  threat model's "sensitive payloads in logs" row and PII note, `docs/local-dev.md` §4.1 (new — how to
  read/trace/quiet the logs), both service READMEs, the `money-safety-review` skill (a logged *secret*
  is a finding; a logged personal value is not), and the log-line examples in the step specs written
  ahead of the code (16, 19, 25, 29, 35, 44, 45).
  Verified: `mvn package` green; 13 common-lib + 6 auth + 15 account unit/architecture tests and the
  10 auth-service ITs pass. Then verified **on the running compose stack**, which is what found the
  silent-404 gap above: one `X-Correlation-Id` sent through login → `/auth/me` → `/accounts/me` →
  register key → list keys → resolve key, plus the 401 (no token), 401 (bad password), 422 (bad CPF),
  409 (duplicate key) and 404 (unknown key *and* unmapped route) paths — every one of them
  reconstructed end to end, across both services, by a single `grep "cid=<id>"`. Full suite green
  afterwards: **72 tests** (34 unit + architecture, 38 integration) via
  `mvn verify -DargLine="-Dapi.version=1.44"` — the Testcontainers ITs included, unchanged.
  AI: est 1h / actual 2.5h / ~95% generated / 5 issues caught in human review
  Issues caught in human review (fixed in this change):
  1. **Kept the dotted event names when asked for verbose logs.** The first pass enriched the values
     but left `account.key.register.created`-style tokens, i.e. it made the machine-readable half
     better and the human-readable half unchanged — while the whole request was "easier to
     understand". Human re-specified: descriptive English sentences. The convention flip then also
     forced the doc sweep across the six step specs, which the first pass would have left to drift.
  2. **Declared the work verified without running the artefact.** The claim "every non-2xx has a
     line" rested on reading the diff and on tests that only exercise paths which *reach* the
     application. Human asked to bring the stack up; the first typo'd URL of the demo returned a 404
     with no log line at all — the exact failure mode the removal of the per-request line introduces,
     invisible to unit and integration tests. Fixed with the `handleExceptionInternal` override; the
     lesson is the one step 08 already recorded in a different form: use the artefact, don't read it.
  3. **Third occurrence of the same Docker misdiagnosis — and this time it was written into the
     CHANGELOG as fact.** The Testcontainers ITs failed with `Could not find a valid Docker
     environment`, and the entry above originally read "no Docker daemon available in this
     environment". Docker was up the whole time — `docker compose up` ran on it minutes later, on the
     human's request. The correct reading was the one this file already contains twice (steps 08 and
     10): the client/daemon API negotiation quirk, fixed by `-DargLine="-Dapi.version=1.44"`. Applying
     it ran all 38 ITs green. Beyond the repeated mistake, the worse failure is the *shape* of the
     claim: an unverified environmental excuse stated as a verified fact, in the one document whose
     job is to be trustworthy about what was and wasn't checked. Rule taken from it: a "could not
     run" sentence in the CHANGELOG must name what was tried, not what was assumed.
  4. **Verified the new log pattern against a stale artifact and nearly believed it.** The first IT
     run used `mvn -pl services/auth-service verify` without `-am`, so `common-lib` resolved from
     `~/.m2` — the *previous* jar, with the old `logback-spring.xml`. The output was JSON, i.e. the
     old config, on a run whose entire purpose was to prove the new config renders. It was caught
     only because the shape was visibly wrong; had the change been subtler (a field, a level) it
     would have passed as verified. Any single-module `verify` that exercises shared code needs
     `-am`, or an `install` of the dependency first.
  5. **Asked two clarifying questions up front and missed the one that mattered.** The questions
     covered output format (console vs JSON) and level (DEBUG by default) — both real, both answered
     — while the human's actual complaint was the *message style*: dotted `account.key.register.created`
     tokens that only a reader of this codebase can parse. Result: a complete second pass over every
     log statement in three modules, plus a doc sweep of six step specs that the first pass would have
     left to drift. Asking about the mechanism is easy; the harder question was "what makes these hard
     to understand for you", and it was never asked.

- Explicit use-case layer per inbound operation; no business policy in controllers (ADR-0011)
  Not a PLAN step — a cross-cutting architecture change requested in review, applied retroactively to
  every service built so far so none is left on the old shape. **ADR-0011** amends ADR-0010 on one
  point: its rejection of a use-case ring. Every inbound operation is now a `<Verb><Noun>UseCase`
  class in `domain/usecase/` with a single `execute(...)`, so `ls domain/usecase/` is the service's
  capability list; `api/` is left with three jobs (bind + bean-validate, call one use case, map
  result/exception to HTTP). ADR-0010 is **not** rewritten — it keeps its original reasoning plus an
  amendment notice, so the trail of *why the first trade-off did not hold* survives.
  account-service gains `GetMyAccountUseCase`, `GetAccountUseCase`, `RegisterPixKeyUseCase`,
  `ListPixKeysUseCase`, `DeletePixKeyUseCase` and `ResolvePixKeyUseCase` (renamed from
  `KeyResolutionService`); auth-service's `AuthenticationService` becomes `LoginUseCase`. Business
  policy moved out of `api/`: EVP server-generation, e-mail normalization, format validation, the
  global-uniqueness outcome, the delete ownership guard and every not-found decision. `Instant.now()`
  is gone from the controllers — `RegisterPixKeyUseCase` takes an injected `java.time.Clock` (new
  bean), which matters before steps 19/20/34 make time a decision input rather than a stamp. Domain
  failures are now plain-Java exceptions in `domain/` (`AccountNotFound`, `InvalidPixKey`,
  `PixKeyAlreadyExists`, `PixKeyNotFound`, `PixKeyNotOwned`) mapped by a new `AccountExceptionHandler`,
  mirroring auth-service's existing `InvalidCredentialsException` pattern — **the wire contract is
  unchanged** (same codes, same statuses: 404/422/409/404/403). Business-stage INFO/WARN logging moved
  with the policy into the use cases, so the `<domain>.<action>.<outcome>` event names are emitted
  where the stage actually happens; `CorrelationIdFilter`'s per-request line is untouched.
  Enforcement, not just convention: each `*ArchitectureTest` gains a second ArchUnit rule failing the
  build when a class in `..api..` depends on an **interface** in `..domain..` — every port is an
  interface and every use case is a class, so a controller reaching a repository cannot be merged.
  New plain-Java unit tests (`RegisterPixKeyUseCaseTest`, `DeletePixKeyUseCaseTest`,
  `GetMyAccountUseCaseTest`, `ResolvePixKeyUseCaseTest`) exercise with a fake port and a fixed clock
  what previously needed MockMvc — including "EVP ignores the client-supplied value", which is a
  security rule of the same family as Domain Safety Rule #1. Docs squared in the same change:
  CLAUDE.md conventions, ARCHITECTURE.md §3 "Inside a service", both service READMEs, the
  `run-step` and `money-safety-review` skills, and a superseded-name note in the step-11 spec.
  Verified: **72 tests green** (34 unit + architecture, 38 integration) via
  `mvn verify -DargLine="-Dapi.version=1.44"` — every pre-existing `*IT` passes **unchanged**, which
  is the strongest evidence the wire contract did not move; the new ArchUnit rule was itself verified
  to fail on a deliberately injected violation (a rule that cannot fail is not a rule); and the seven
  endpoints plus every error code were exercised against the running compose stack.
  AI: est 2h / actual 3.5h / ~95% generated / 4 issues caught in human review
  Issues caught in human review (fixed in this change):
  4. **Repeated the exact mistake step 10 already recorded** — re-diagnosed the known Docker Desktop
     API-negotiation quirk from scratch (sockets, group membership, `git stash` bisect) and reported
     the ITs as unrunnable, instead of checking the CHANGELOG, which documents the fix
     (`-DargLine="-Dapi.version=1.44"`, step 08). Human pointed back at the changelog a second time;
     all 38 ITs then ran green, unchanged. Root cause of the *recurrence*, now fixed: the workaround
     lived only in `services/account-service/README.md` — a per-service card — while
     `docs/local-dev.md` §6 "Running tests" (where anyone actually looks) said a bare `mvn verify`,
     and the failure message (`Could not find a valid Docker environment`) points at the socket,
     which is the wrong place. The runbook now carries the flag in §6 plus a troubleshooting row.
  <!-- The first three, all found by using the artefact rather than reading the diff:
       (1) docs/local-dev.md told the reader to run `docker compose logs -f localstack-init`, a service
           that never existed — the init scripts run inside the `localstack` container (found by
           actually booting the stack; §4 also listed 8 health ports when only 2 services exist);
       (2) business logic in controllers / the missing use-case layer, which produced this ADR;
       (3) the spec-side gap: NO step doc mentioned ArchUnit or the `*ArchitectureTest`, and the six
           scaffold steps (13/18/23/30/31/38) expanded to only "skeleton + Dockerfile + compose +
           README" — so a future service, built to the letter of its spec, would have shipped with no
           architecture test and controllers calling repositories, ADR notwithstanding. Closed with
           the new-service checklist in CLAUDE.md plus a pointer in each scaffold step. -->

### Added
- ledger-service balance reads with strongly consistent GetItem on the single-table ledger (step 13)
  The platform's fourth service (port 8085) and the **only writer of `pix_ledger`** (ADR-0006) —
  though it writes nothing yet: this step deliberately ships the *read* half, so the domain model is
  validated against the money supply seeded in step 12 while nothing is at stake. Step 14's first
  `TransactWriteItems` is then not also the first time an item shape is exercised.
  **The endpoint.** `GET /internal/ledger/accounts/{accountId}/balance` →
  `{accountId, balance, balanceCents, version}`; unknown account ⇒ `404 LEDGER_ACCOUNT_NOT_FOUND` in
  problem+json. The service has **no `/v1` surface at all** and `/internal/**` is deliberately absent
  from `jwt.public-paths`, so every call needs a token: no end user talks to the ledger,
  payment-service does on their behalf.
  **Three decisions worth the words.** (1) `ConsistentRead=true`, always — DynamoDB reads are
  eventually consistent by default (they cost half as much), but the ledger must read its own writes;
  a stale balance shows money that is already spent. LocalStack is a single node and would return the
  right value either way, so the flag can only be proven on the *request*: `DynamoLedgerRepositoryTest`
  asserts it there, with a hand-written `DynamoDbClient` stub. It is also why the balance lives at a
  base-table key — a GSI is always eventually consistent. (2) The wire carries **both money
  representations**: `balance` as a decimal string for the human running the runbook curl,
  `balanceCents` as an integer for the services that do arithmetic on it (step 21, the step-40 cache)
  — one `long` in the domain, formatted in exactly one place (`BalanceResponse`), the same reasoning
  that keeps account-service's internal view on integer `dailyLimitCents`. (3) An absent BALANCE item
  is a **404, never a zero**: in a ledger "no such account" and "no money" are opposite facts and must
  not look alike on the wire.
  **Domain.** `Balance(accountId, balanceCents, version)`, `LedgerEntry(txId, direction, amountCents,
  counterpartAccountId, timestamp, entryType)` and `Direction` (enum — a closed two-valued vocabulary
  that carries the sign convention; `entryType` stays a string because it grows with every flow), the
  `LedgerRepository` port and `LedgerAccountNotFoundException`. `LedgerEntry` is written for the first
  time in step 14 and exists now because the model is what this step validates. The `version` field is
  documented at length as a **change counter, not a lock** — nothing reads it, decides and writes back
  conditioned on it; conflicting writers are serialized by DynamoDB transactions themselves
  (ARCHITECTURE §6.3), and the version-as-optimistic-lock strategy is the *lab's* job (ADR-0009).
  Full new-service checklist per CLAUDE.md: module + POM, `Application`, `application.yml`, Dockerfile,
  compose block (gated on `localstack: service_healthy`, which is also what guarantees the seed ran),
  `README.md`, the three packages with one `GetBalanceUseCase` and a `LedgerBeansConfig` composition
  root, `LedgerArchitectureTest` with **both** ArchUnit rules from day one, CORS ahead of the JWT
  filter, and the endpoint added to **both** the Postman collection and the API explorer in this same
  step (three cards each: alice's balance, `SEED` as the negative money supply, and the 404).
  `docs/local-dev.md` §4 gained the service-level twin of the raw `get-item` loop — the same Σ = 0,
  now read through the API. No `docs/api/openapi.yaml` change: `/internal/**` is by contract not part
  of the public surface, as that file already states for account-service's internal lookup.
  Verified on the **running compose stack**, not only in tests: the step's curl returns
  `{"balance":"10000.00","balanceCents":1000000,"version":0}`, the four balances still sum to zero
  through the API, alice's token reads bob's balance (the internal seam is authenticated but not
  account-scoped, on purpose), and the 404/401/unmapped-route paths all answer as specified — a single
  `grep "cid=<id>"` across `auth-service` + `ledger-service` reconstructs login → JWT accepted → use
  case → `GetItem` (with the exact key) → outcome, for every one of them. Suite: **96 tests**
  (46 unit + architecture, 50 integration), all green on a plain `mvn verify`.
  Correction made during the step, recorded because the CHANGELOG's job is to be trustworthy about
  what was checked: the adapter test's javadoc first claimed `mock(DynamoDbClient.class)` cost ~170s
  and justified the hand-written stub with that number. The 167s was a **clock jump on this WSL2 box**
  (an older account-service report shows the same magnitude as a *negative* duration); measured
  directly, the Mockito mock costs 740ms. The stub stayed — for the real reason, which is that
  `getItem` is overloaded on request and builder-consumer, so mocking it needs a type-witnessed
  matcher and an unchecked cast — and the false performance claim was removed from the code.
  AI: est 2.5h / actual 1h / ~95% generated / 1 issue caught in human review
- LocalStack init: pix_ledger table (GSI1) + seed balances and system accounts SPI_CLEARING/SEED (step 12)
  `02-dynamodb-ledger.sh` creates `pix_ledger` exactly per `docs/data-model.md` §3 — PK
  `ACCOUNT#<accountId>`, SK `BALANCE` | `ENTRY#<isoTs>#<txId>`, on-demand, plus `gsi1` on
  `TX#<txId>`, which is **naturally sparse** (only `ENTRY` items carry `gsi1pk`) and exists for the
  one pattern the base table cannot serve: both legs of a posting live in two different account
  partitions. One partition per account holding both shapes is what lets step 14 update the balance
  and append its entry in a single `TransactWriteItems`, and what makes the statement a plain
  `begins_with(sk, "ENTRY#")` query ordered for free by the timestamp prefix.
  `05-seed-ledger.sh` seeds the money supply the only way money is ever allowed to appear here — as
  a **double-entry funding operation**: alice/bob at `1000000` cents each (credit legs of
  `tx-seed-alice`/`tx-seed-bob`), `ACCOUNT#SEED` at `-2000000` (the two debit legs),
  `ACCOUNT#SPI_CLEARING` at `0`, `version=0`, plus the four matching `SEED_FUNDING` `ENTRY` items.
  **Σ balanceCents = 0** is therefore the baseline the conservation invariant starts from: seeding
  users without the counterpart would still have been a constant, but a magic one — this way the
  invariant is a plain sum over every account, which is what step 15 asserts under a debit storm.
  Every put is **conditional on `attribute_not_exists(pk)`** (stricter than the account seed's
  unconditional `put-item`): re-running the seed against a table whose balances real postings have
  already moved must not reset them while their `ENTRY` items survive — verified by hand (moved
  alice to `987650/v3`, re-ran the script, balance untouched). Fixed timestamps, no clock read, so
  `down -v && up` reseeds byte-identically. `entryType=SEED_FUNDING` and the system-account
  exemption are now written into `docs/data-model.md` §3, the DDL + the balance/GSI1 read commands
  mirrored in `docs/local-dev.md` §4, both scripts described in the init README.
  Harness: `LocalStackTestBase`'s readiness wait moved to the *new* last script's final line —
  waiting on the accounts seed would have let every future ledger IT race the seeding — and
  `LocalStackHarnessIT` grew two ITs pinning the seed: alice at `1000000`/`version=0`, and Σ over the
  four accounts `== 0`. Suite: **74 tests** (34 unit + architecture, 40 integration), all green.
  AI: est 0.75h / actual 1.25h / ~90% generated / 1 issue caught in human review
  Issues caught in human review (fixed in this change):
  1. **Fourth occurrence of the same Docker misdiagnosis — now fixed at the root instead of
     re-documented.** The ITs failed with `Could not find a valid Docker environment` and the
     assistant again started probing the socket/`docker context` rather than reading the CHANGELOG,
     which has recorded the real cause and its workaround since step 08 (docker-java's default API
     v1.32 vs a modern engine's `MinAPIVersion`). The human pointed at the changelog and, correctly,
     refused another round of "document the flag": a fix that lives in a command someone must
     remember is not a fix. The API version is now **pinned in the build** — parent-POM property
     `<docker.api.version>` (default `1.44`) handed to the failsafe-forked JVM as the `api.version`
     system property — so `mvn verify` runs green with no flag on any module, overridable with
     `-Ddocker.api.version=<v>` for an older engine. `-DargLine="-Dapi.version=1.44"` is gone from
     `docs/local-dev.md` §6/§7 and the account-service README, and CLAUDE.md now states the rule the
     episode taught: **a known environment quirk is fixed in the build, never in a remembered
     flag**, and check `CHANGELOG.md` / `docs/local-dev.md` for the symptom before diagnosing any
     environment failure.
- Internal Pix key resolution endpoint (DICT role), external delegation seam left for step 30 (step 11)
  account-service gains `GET /internal/pix-keys/resolve?key=…` — the platform's own **DICT** for keys
  living inside PlatinumCoin, the hot lookup on the send path (step 21 resolves the destination key
  first). New plain-Java `KeyResolutionService` in `domain/` (wired by a new `AccountBeansConfig`, so
  the domain stays Spring-free — ArchUnit still green) and a `KeyResolution(internal, accountId,
  externalBank, keyType)` record returned directly as the wire shape (no mirror DTO, ADR-0010). The
  response uses the **final** `{internal, accountId?, externalBank?, keyType}` shape now: an internal
  key ⇒ `{internal:true, accountId, keyType}`; an unknown key ⇒ `404 KEY_NOT_FOUND`. External-PSP
  delegation is deferred to step 30 (no mock-bacen yet) via an explicitly marked
  `// TODO(step 30)` seam in `resolveExternal`, exercised by a unit test asserting the branch is
  currently a not-found (a red test step 30 turns green). The incoming key is lowercase-normalized
  before lookup, mirroring registration, so a mixed-case e-mail still resolves its lowercased
  registration. Kept behind the shared `JwtAuthFilter` like the other `/internal/**` seam (step-09
  posture): requires a valid token, 401 otherwise. Docs/tooling squared in the same change: README
  endpoint row + "DICT role" semantics + verify curls; step-11 spec's verify block corrected to pass a
  token and register the key first (pix_keys is not seeded); Postman + API explorer each grow a resolve
  entry under account-service.
  AI: est 1.5h / actual 1h / ~90% generated / 0 issues caught in human review
- Pix key register/list/delete with global uniqueness via conditional PutItem (step 10)
  account-service gains `POST /v1/pix-keys` (CPF/EMAIL/PHONE/EVP), `GET /v1/pix-keys` and
  `DELETE /v1/pix-keys/{keyValue}` on the step-07 `pix_keys` table. Global uniqueness is a
  conditional `PutItem` (`attribute_not_exists(pk)`) on `KEY#<keyValue>` — the DynamoDB UNIQUE-
  constraint idiom: two accounts racing for the same value, exactly one wins, the other gets
  `409 KEY_ALREADY_EXISTS` (the `ConditionalCheckFailedException` stays inside `infra/`; the port
  exposes it as a boolean, so the domain never sees an AWS type). EVP keys are server-generated
  UUIDs (client `keyValue` ignored); EMAIL is normalized (trim + lowercase) so casing cannot
  duplicate a key; per-type format validation yields `422 INVALID_PIX_KEY`. List is scoped to the
  caller's JWT account (GSI1 query); delete is ownership-guarded and deliberately reveals existence
  (`403 KEY_FORBIDDEN` on a foreign key, `404 KEY_NOT_FOUND` when absent) — Pix keys are globally
  resolvable identifiers, unlike a transaction whose existence is secret (`404`, step 22). New
  `PixKey` record + `PixKeyType` enum + `PixKeyRepository` port in `domain/`,
  `DynamoPixKeyRepository` adapter in `infra/`, `PixKeyController` in `api/` (INFO business-stage
  logs `account.key.*`, WARN on duplicate/forbidden/invalid). account-service pom adds
  `spring-boot-starter-validation` for the request-body `@NotNull`. Docs/tooling squared up in the
  same change: OpenAPI already carried `/pix-keys*`; README endpoint table + Pix-key semantics
  section; Postman + API explorer each grow register/list/delete under account-service.
  AI: est 2.5h / actual 2.5h / ~90% generated / 1 issue caught in human review
  Issues caught in human review (fixed in this change):
  1. Re-diagnosed a solved environment quirk instead of reusing the documented fix — when the
     Testcontainers ITs failed with the Docker Desktop HTTP-400 (docker-java default API v1.32 vs
     MinAPIVersion 1.40), the assistant started debugging sockets/API versions from scratch rather
     than checking the CHANGELOG, which already records the fix (`-DargLine="-Dapi.version=1.44"`,
     step 08). Human pointed back at the changelog; ITs then run green with the documented flag. No
     code change — the lesson (check CHANGELOG/docs for known env quirks first) is now also noted in
     the account-service README's local-Docker note.
- account-service with accounts repository, GET /accounts/me and internal account lookup (step 09)
  First DynamoDB-backed service (port 8082): `AccountRepository` port in `domain/`,
  `DynamoAccountRepository` adapter in `infra/` (the only place the AWS SDK appears, enforced by
  `AccountArchitectureTest`). `GET /v1/accounts/me` derives the account from the JWT (`dailyLimit`
  formatted as a decimal BRL string at the API edge); `GET /internal/accounts/{accountId}` is a
  service-to-service seam (ADR-0006) that keeps `dailyLimitCents` as integer cents. Both endpoints
  require a valid token (the internal one is behind `JwtAuthFilter`, not on the public allow-list).
  Dockerfile + compose entry (depends_on localstack healthy) + README + local-dev CORS
  (`CorsConfig`). Docs/tooling squared up in the same change: `docs/api/openapi.yaml` gains
  `/accounts/me` (account-service 8082); step-09 spec's verify note records the internal-JWT
  decision; Postman + API explorer each grow an `account-service` section (`/me`, internal lookup,
  health), the explorer extended with per-service editable base URLs.
  AI: est 2.5h / actual 4h / ~85% generated / 5 issues caught in human review
  Issues caught in human review (fixed in this change):
  1. Logging gap — each endpoint logged a single INFO on entry only, so a `correlationId` could
     not reconstruct the flow's *outcome* stages (resolved vs missing) the way CLAUDE.md's logging
     convention requires ("every meaningful stage of a flow logs at INFO"). Added outcome logs
     (`account.me.resolved` / `account.internal.resolved`), a WARN on the valid-token-but-missing-
     account degradation (`account.me.missing`) and the ordinary internal lookup miss
     (`account.internal.miss`), plus DEBUG adapter logs for the GetItem/Query in
     `DynamoAccountRepository`.
  2. Logs not observable in containers — the new adapter logs were DEBUG, so with the root level at
     INFO they never appeared in `docker compose logs`, and there was no startup breadcrumb showing
     which DynamoDB endpoint the service connected to. Added INFO startup logs in `DynamoConfig`
     (`dynamodb.client.init endpoint=… region=…`) and `CorsConfig` (`cors.filter.registered …`).
  3. DEBUG was the wrong lever for call tracing — the fix for #2 raised the whole account package to
     DEBUG so the adapter lines would show, but call tracing must be legible at INFO (DEBUG is deep
     detail, off by default). Reverted `logging.level.com.platinumcoin.pix.account: DEBUG`; the call
     story now lives entirely at INFO, with the DynamoDB adapter lines remaining DEBUG-on-demand.
  4. No uniform per-call INFO across services — only account-service had ad-hoc INFO logs, so calls
     to auth-service/common-lib were not observable at INFO; there was no platform-wide "one line
     per call". Added a shared `INFO http.request method=… path=… status=… durationMs=…` line in
     common-lib's `CorrelationIdFilter` (inherited by every service, actuator skipped) and an
     `auth.me` INFO in auth-service's `MeController`. Codified the two-layer INFO logging convention
     (shared request line + per-service business-stage events, DEBUG for adapter detail only) in
     CLAUDE.md so every future service and endpoint follows it in the step that introduces it.
  Notable: the local Docker engine (Desktop 29.3.0, API 1.54, MinAPIVersion 1.40) rejects
  Testcontainers/docker-java's default API v1.32 with HTTP 400; ITs run with
  `-DargLine="-Dapi.version=1.44"` (environment quirk, no code change).
- Testcontainers LocalStack harness in common-lib running the real init scripts (step 08)
  AI: est 1.5h / actual 1.5h / ~90% generated / 0 issues caught in human review
- Single-file HTML API explorer bootstrapped as a living artifact (`tools/api-explorer/index.html`),
  mirroring the Postman collection 1:1 for auth-service (login alice/bob, bad-credentials 401,
  `/v1/auth/me`, health) — in-memory token auto-attached, auto-UUID idempotency helper, guided
  journey (login → me). Reframes the tooling to match the Postman lifecycle: **created early, grown
  one card per endpoint in its own step, finalized in step 49** (was a step-49 big-bang "create").
  Professional fintech-style dark UI (neutral charcoal, single emerald accent with blue primary
  buttons, method-colored chips, SVG icons; the Tibia platinum-coin image embedded as a base64
  data-URI so the file stays offline/self-contained). Local-dev CORS enabled on auth-service
  (`CorsConfig`, ordered ahead of `JwtAuthFilter` so pre-flight `OPTIONS` isn't 401'd) so the
  open-from-disk explorer (Origin `null`) can reach it. Docs squared up to convey both harnesses are
  incremental: CLAUDE.md convention now mandates BOTH per endpoint; README + ARCHITECTURE §6.13 +
  the Postman README reframed as living/twins; step-49 spec + PLAN reframed to "finalize";
  local-dev runbook updated.
  AI: est 1h / actual 2.5h / ~90% generated / 0 issues caught in human review (UI theme went through
  several human-directed design iterations — preference, not defects)
- LocalStack init: pix_accounts and pix_keys tables (GSIs) + seed accounts (step 07)
  AI: est 0.5h / actual 0.5h / ~90% generated / 1 issues caught in human review
  Issues caught in human review (fixed in this change):
  1. Doc drift — the step task title and `infra/localstack/init/README.md` described these
     tables as having "GSIs and TTL", but `docs/data-model.md` (the schema source of truth)
     defines no TTL on `pix_accounts`/`pix_keys` (TTL is only on `pix_idempotency` /
     `pix_processed_events`). Corrected the init README wording to match the data model; the
     scripts create no TTL.
- docker-compose LocalStack (DynamoDB) with healthchecks, infra network and env template (step 06)
  AI: est 0.5h / actual 0.5h / ~90% generated / 1 issues caught in human review
  Issues caught in human review (fixed in this change):
  1. Verification gap — the DoD item "AWS CLI against 4566 answers for dynamodb" was first
     checked with the in-container `awslocal` wrapper because the host had no AWS CLI, not with
     the runbook's own command. Installed AWS CLI v2 and re-ran the exact runbook command
     `aws --endpoint-url=http://localhost:4566 dynamodb list-tables` → `{"TableNames": []}`,
     closing the gap with the real tool the runbook prescribes.
- common-lib JWT validation filter and AuthenticatedUser principal, protecting service endpoints (step 05)
  AI: est 2h / actual 0.9h / ~85% generated / 0 issues caught in human review
- auth-service login endpoint issuing HS256 JWT for seeded users (step 04)
  AI: est 1.5h / actual 0.6h / ~90% generated / 3 issues caught in human review
  Issues caught in human review (fixed in this change):
  1. Spec gap — no per-service README convention. Added `services/<name>/README.md` (auth-service
     is the template) and made it a standing rule in CLAUDE.md + the service-scaffold step DoDs
     (09, 13, 18, 23, 30, 31, 38).
  2. Spec gap — no incremental Postman collection. Created `tools/postman/` (one folder per
     service, token auto-saved on login) with a rule that every endpoint is added in its own step;
     step 48 reframed from "create from scratch" to "finalize".
  3. Naming — the outbound port `UserDirectory` renamed to `UserRepository`
     (`InMemoryUserRepository`), matching its repository role in the ADR-0010 vocabulary.
- auth-service Spring Boot skeleton with Actuator health, Dockerfile and compose wiring (step 03)
  AI: est 1h / actual 0.7h / ~85% generated / 2 issues caught in human review
- Shared error model (RFC 7807), correlation-id propagation and structured JSON logging in common-lib (step 02)
  AI: est 1.5h / actual 0.6h / ~90% generated / 2 issues caught in human review
- Maven multi-module scaffold with parent POM (Java 21, Spring Boot & AWS BOMs) and common-lib module (step 01)
  AI: est 0.5h / actual 0.4h / ~90% generated / 1 issues caught in human review
- Planning & documentation baseline: ARCHITECTURE.md, ADRs 0001–0009, data model,
  OpenAPI contract, local-dev runbook, CLAUDE.md, PLAN.md and the full step specs.
- Sprint 14 (Block Q, steps 50–53): relational ledger counterpart lab (`labs/ledger-pg`,
  ADR-0009) with pessimistic/optimistic strategies, invariant parity + EXPLAIN/index/
  deadlock study + contention benchmark; clearing-account write sharding proven under
  the Black Friday k6 profile; async cold statement export (202 + polling status URL).
- `docs/messaging-kafka-appendix.md`: SNS/SQS ↔ Kafka concept mapping, referenced
  from ADR-0004 and the README.
- CLAUDE.md workflow additions: hand-written zones (✍️ steps 15, 19, 51) and
  mandatory per-step AI metrics line in CHANGELOG entries.
- `docs/brief.md`: the exercise brief and the **seven design questions stated verbatim**
  — previously the docs referenced "the brief" ~10 times without it existing in-repo,
  so the answers could not be judged against the questions.

### Changed
- **Delivery approach reframed from horizontal to vertical (flow-per-sprint).** The
  roadmap is no longer "scaffold everything → all infra → each layer across all
  services". It is now **14 sprints, each delivering one complete, testable,
  documented flow** and bringing up only the infrastructure that flow needs (no
  big-bang). Rationale and the sprint dependency + cumulative-infra diagrams are in
  ARCHITECTURE.md §6.0.
  - `PLAN.md` rewritten as sprints S1–S14; the 47 previous steps were re-sequenced,
    split where they were horizontal (old 02/04/05/20), and renumbered 01–53 in
    dependency-correct execution order (ledger before the first money-moving Pix;
    internal synchronous Pix before external asynchronous settlement).
  - ARCHITECTURE.md restructured into **Part I (complete design)** and **Part II —
    §6 (implementation journey, flow by flow)**, adding Mermaid sequence diagrams for
    login, key resolution, ledger posting, internal Pix, fraud, balance cache and
    audit, plus a sprint dependency graph and a cumulative-infrastructure diagram.
  - Hand-written zones renumbered: invariant suite (step 15), idempotency tests
    (step 19), relational findings (step 51).
- ARCHITECTURE §6.3: clearing-account write sharding upgraded from "documented,
  N=1 locally" to implemented and load-proven (step 52), with reversal-shard pinning.
- ADR-0001 now cross-references the measured relational counterpart (ADR-0009).
- **Spec consistency pass (pre–step 01)** — a full-repo review resolved contradictions
  between specs before any code exists:
  - Internal Pix now terminates in `SETTLED` (was `DEBITED`, which step 22 maps to
    `PROCESSING` — an internal send would have looked "processing" forever). State
    machine gains the internal short branch; the terminal transition emits `PixSettled`
    (ARCHITECTURE §4/§6.4, steps 21/22/28, PLAN).
  - Daily limit re-specified as a **calendar-day reservation counter**
    (`LIMIT#<accountId>`/`DAY#<date>` in `pix_transactions`, reserve/release via atomic
    `ADD`) — the previous "sum today's transactions" had no supported access pattern
    (no index by debtor account) and "rolling window" contradicted the calendar-day
    test (data-model §4, step 20, PLAN).
  - ADR-0006 now documents the two deliberate shared-table exceptions (settlement's
    guarded outbox writes to `pix_transactions`; `pix_processed_events`) instead of
    contradicting the design in steps 31/33/34/37.
  - Dropped the never-consumed `inbound-pix-queue` (step 36, ARCHITECTURE §6.8, PLAN,
    README, local-dev); the inbound webhook is authenticated with `SPI_WEBHOOK_TOKEN`
    (step 37, threat model — a forged webhook could mint spendable balance).
  - Idempotency `IN_PROGRESS` orphans: stale claims (>60s) are reclaimable and
    `expiresAt` is checked on read (DynamoDB TTL is lazy) — a crash no longer blocks
    the client until the 24h TTL (ADR-0002, step 19, data-model §5).

### Fixed
- README quick-start example sent `amount` as a JSON number (`125.50`); the contract
  requires a decimal **string** (`"125.50"`) — example corrected to match
  `docs/api/openapi.yaml`.
- OpenAPI contract gaps: added the missing `GET /notifications/stream` (SSE), per-path
  `servers` mapping each route group to its local port (no gateway), problem+json
  bodies on 401/404/409/503, the `counterpart` field step 41 maps into
  `StatementEntry`, and a bounded strictly-positive `amount` pattern (`"0.00"` and
  overflow-sized values were previously accepted by the contract).
- ARCHITECTURE.md audit (syntax + completeness):
  - Broken intro anchor to §10 (the em dash slugs to a double hyphen on GitHub); raw
    `<placeholders>` inside 4 Mermaid diagrams (`<JWT>`, `KEY#<value>`,
    `balance:<accountId>`, `<service>-<uuid>`) that GitHub's HTML sanitizer strips
    from the rendered diagram — escaped as `&lt;…&gt;`; Part I/II demoted from H1 to
    H2 (single-H1 outline).
  - Part II now actually maps 1:1 to PLAN: added §6.12 (quality gate), §6.13 (DX
    tooling) and §6.14 (Block Q, with the cold-export sequence diagram); §6.11 gained
    its observability diagram; the cumulative-infra diagram and the "no new infra"
    note now account for Sprint 14 (export queue + bucket, lab-only Postgres).
  - Container diagram matched to the flows it summarizes: added the SET→DDB edge (the
    ADR-0006 documented exception), NOT→DDB (event dedup), the statement-export
    queue, the exports bucket and a C4 level-1 context diagram; §4 gained the missing
    `processed_events` row and the limit/export item types; §5 gained the step-53
    export endpoints; §6.4/§7.3/§7.6 aligned with the limit-reservation and
    webhook-token changes from this pass; data-model gained the export request item.
- Factual/wording corrections from the consistency pass: GSIs *can* be added after
  table creation — it's LSIs that can't (step 17, scripts also renumbered to avoid a
  double `03-`); the ledger balance `version` is a change counter, not optimistic
  locking (ARCHITECTURE §6.3, step 13); partition math restated in WCU with the 2×
  transactional-write cost (§1.4/§6.3 — the clearing ceiling is ~500 tx/s, not 1,000,
  strengthening the sharding argument); `SEED` seeds Σ balances to zero and is exempt
  from the non-negative condition alongside `SPI_CLEARING` (steps 12/14, data-model);
  statement cursors are validated against the authenticated account — the base64
  `LastEvaluatedKey` embeds the partition key (steps 16/41, threat model); container
  diagrams gained the missing FRAUD→Redis and LED→Redis edges; step 53 declares the
  `pix-statement-exports` bucket it writes to; `docs/observability.md` added to the
  repo maps (CLAUDE.md, README).

<!--
Template for step entries (append under the matching category):

### Added | Changed | Fixed
- <what shipped> (step XX)
  AI: est <Xh> / actual <Yh> / ~<Z>% generated / <N> issues caught in human review
-->
