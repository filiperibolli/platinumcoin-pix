# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

One entry is added per completed implementation step (see `PLAN.md` / `docs/steps/`).
Each step file specifies the exact entry to add under `[Unreleased]` on completion.

## [Unreleased]

### Added
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
  AI: est 2.5h / actual 1h / ~95% generated / 0 issues caught in human review
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
  AI: est 2.5h / actual 4h / ~85% generated / 4 issues caught in human review
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
  AI: est 1h / actual 0.7h / ~85% generated / 1 issues caught in human review
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
