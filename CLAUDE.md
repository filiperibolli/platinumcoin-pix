# CLAUDE.md — Context for Claude Code

## Project purpose

My staff/architect-level answer to one question: *if I owned Pix at a fintech and started from a blank page, how would I build it?* The artifact is a realistic instant-payments platform where money correctness is non-negotiable and everything else is an explicit budget, every non-trivial decision is written down with its trade-off (ADRs), and the code exists to prove the design survives real failure modes. The build doubles as deliberate practice with the underlying stack (AWS/LocalStack, DynamoDB modeling, messaging, distributed-systems patterns, observability) — so **every step still explains the *why***, and decisions optimize for clarity and reviewability over cleverness.

## Project in one paragraph

A Pix instant-payment platform (PlatinumCoin) built as domain microservices in **Java 21 LTS + Spring Boot 3 + Maven multi-module**, running 100% locally via **docker-compose**: **DynamoDB, SQS, SNS, S3 emulated by LocalStack**, **Redis** as its own container (stands in for ElastiCache), and a **mock BACEN SPI** with configurable latency/failures. No Kubernetes. Core flows: send Pix (idempotent, `202 Accepted`, atomic double-entry debit), asynchronous settlement with retries/DLQ and <5-min reconciliation, receive Pix with real-time SSE notification, cached balance/statement, fraud scoring under a 200ms budget (fail-open), immutable S3 audit trail, Prometheus + Grafana observability (technical dashboards + business funnel), k6 load tests for the stated SLOs, and unified API tooling (Postman collection + HTML API explorer).

## Where everything lives

```
ARCHITECTURE.md        system design + answers to the 7 key design questions — read before designing anything
docs/brief.md          the exercise brief + the 7 design questions, verbatim
docs/adr/              decision records — do not contradict them; propose a new ADR to change one
docs/data-model.md     DynamoDB tables, keys, GSIs, ledger invariants — the schema source of truth
docs/messaging-kafka-appendix.md  SNS/SQS ↔ Kafka concept mapping (broker portability)
docs/observability.md  metric catalog + alert rules (created in step 44)
docs/api/openapi.yaml  REST contract — the API source of truth; code conforms to it, not vice versa
docs/local-dev.md      runbook: ports, env vars, manual test commands
docs/steps/step-XX.md  the spec of each implementation step
PLAN.md                roadmap: 14 sprints (one flow each), status checkboxes — vertical, not big-bang
CHANGELOG.md           Keep a Changelog; one entry per completed step
services/<name>/       one Maven module per service (added incrementally per sprint, common-lib first in step 01); each ships a README.md
services/<name>/README.md  per-service card: purpose, port, endpoints, config/env, run & test, ADRs — services/auth-service/README.md is the template
services/common-lib/   shared: error model, JWT validation, logging, event envelope — keep it THIN
labs/ledger-pg/        non-deployable relational ledger lab (ADR-0009, steps 50-51) — never wired to the platform
infra/                 docker-compose.yml, localstack init scripts, seed data
infra/observability/   Prometheus config, Grafana provisioning + dashboards (step 44)
load/k6/               k6 load-test scripts: low / standard / black-friday (step 47)
tools/postman/         Postman collection + environment — created early (step 04), grown incrementally (one folder per service; each new endpoint added in its own step); finalized in step 48
tools/api-explorer/    single-file HTML API explorer with valid sample requests — created early alongside Postman, grown incrementally (one card per endpoint, added in its own step); finalized in step 49
```

## Conventions

- **Java 21**, records for DTOs/value objects, `var` where it aids readability. Money is **always integer cents (`long`)** internally; never `double`/`float` for money.
- Maven standard layout; package root `com.platinumcoin.pix.<service>`; inside: `api/` (inbound adapters — controllers, request/response records, exception mapping), `domain/` (plain Java, **grouped by role** — `model/` entities & value objects, `port/` outbound interfaces, `exception/` domain exceptions, `service/` concrete framework-free domain services/helpers that aren't use cases, `usecase/` the `<Verb><Noun>UseCase` classes + their command/outcome records), `infra/` (outbound adapters, **grouped by role** — `persistence/` DynamoDB/Redis/in-memory repositories, `client/` HTTP/SPI adapters, `security/` crypto/token adapters, `config/` Spring config + `@ConfigurationProperties`). **Clean/hexagonal-lite per service (ADR-0010, amended by ADR-0011; internal sub-package layout amended 2026-08-10)** — pragmatic, not the full ceremony. The sub-packages are navigation only: the ArchUnit matchers target the whole subtree (`..domain..`/`..api..`/`..infra..`), so grouping never affects the dependency rules. **One folder per role, always — even a role with a single file gets its folder** (a lone port still lives in `port/`); a service only carries the folders for roles it actually has, and **no `.java` sits loose at the root of `domain/` or `infra/`** (only `Application.java` stays at the service-package root):
  - **Dependency rule points inward**: `api → domain` and `infra → domain`; **`domain` depends on nothing outward** — no `org.springframework.web.*`, `software.amazon.awssdk.*`, `jakarta.servlet.*` or Jackson-binding imports in `domain/`. Domain is plain Java (records + use cases).
  - **One use case per inbound operation (ADR-0011)** — a `<Verb><Noun>UseCase` class in `domain/usecase/` with a single public `execute(...)`, named for the business intent. `ls domain/usecase/` is the service's capability list. This applies to thin operations too; uniformity is the point.
  - **`api/` is *inbound adapters*, not only controllers** (step 29). A scheduled job (`@Scheduled`) or a queue consumer is a way of *entering* the application, so it lives in `api/` next to the controllers and obeys the same rules: call one use case, hold no policy, and — being under the ArchUnit `api/ → no interface in domain/` rule — never reach an outbound port directly. Every background job is `@ConditionalOnProperty("pix.schedulers.enabled")` (default true) and is **off in integration tests** (`LocalStackTestBase` sets it false, since Spring caches contexts and a live poller corrupts unrelated ITs); the IT that covers a job invokes its tick explicitly.
  - **No business policy in `api/`.** A controller does exactly three things: bind + bean-validate the wire shape, call **one** use case, map result/exception to HTTP. Value normalization & generation, ownership checks, not-found decisions, limit rules and **reading the clock** live in the use case — inject `java.time.Clock`, never `Instant.now()` in a handler. A controller that touches no port and applies no policy (e.g. echoing the JWT principal) needs no use case.
  - **Ports only for outbound infra** (repositories, external clients, publishers) — the domain declares the interface, `infra/` implements it. No port for internal-only collaborators or single-impl non-boundaries; **a use case is a class, never an interface**.
  - **Domain failures are plain-Java exceptions** in `domain/` (no `HttpStatus`); a `*ExceptionHandler` in `api/` maps each to its `code` + status + problem+json.
  - **DTO only when the wire shape diverges** from the domain type; if identical, reuse it — no mirror-DTO-per-entity. Money formats to a decimal string only at the `api/` edge; it stays `long` cents in `domain/`.
  - **Enforced**: each service ships one `*ArchitectureTest` (ArchUnit) with two rules — `domain/` imports no framework/infra package, and **`api/` never depends on an interface in `domain/`** (which is what makes "controller may not reach a port" a build failure). `common-lib` is exempt (it *is* the shared adapter layer).
- REST: resources under `/v1/...`; errors as RFC 7807 `application/problem+json` with a `code` field (e.g. `LIMIT_EXCEEDED`) and `correlationId`. Never leak stack traces.
- Naming: tables `pix_*`; queues `<purpose>-queue` + `<purpose>-queue-dlq`; SNS topic `pix-events`; events in PascalCase past tense (`PixDebited`, `PixSettled`, `PixReceived`, `FraudCheckSkipped`).
- **New-service checklist (the "scaffold" task of any skeleton step is not done until all of these exist).** Every scaffold step says only *"skeleton + Dockerfile + compose + README"*; that shorthand expands to:
  1. `services/<name>/` module + POM in the parent `<modules>`, `Application`, `application.yml` (port per `docs/local-dev.md` §2), Actuator health with probes.
  2. `Dockerfile` + a `docker-compose.yml` entry copying the auth-service block (network, healthcheck, `depends_on` where needed).
  3. `services/<name>/README.md` — the card (`services/auth-service/README.md` is the template).
  4. The three packages `api/` · `domain/` · `infra/`, each **grouped by role — one folder per role, always, no `.java` loose at the `domain/`/`infra/` root** (`domain/` → `model/` · `port/` · `exception/` · `service/` · `usecase/`; `infra/` → `persistence/` · `client/` · `security/` · `config/`, carrying only the roles the service has — ADR-0010 amendment 2026-08-10), with **one `<Verb><Noun>UseCase` per inbound operation** and a `*BeansConfig` composition root (ADR-0011).
  5. **`<Name>ArchitectureTest`** (ArchUnit) with **both** rules from day one: `domain/` imports nothing outward, and `api/` never depends on an interface in `domain/`. Copy `AccountArchitectureTest`.
  6. CORS config for local dev (ordered ahead of the JWT filter), Postman folder + API-explorer section.
  `common-lib` is a shared library, not a service, and is exempt. `mock-bacen-spi` (step 30) and `labs/ledger-pg` are stubs/labs — items 4 and 5 are optional there (ADR-0010 scope note); everything else still applies.
- **Every service module ships a `services/<name>/README.md`.** Creating a new service (its skeleton step) is not done until its README exists. Keep it a short, consistent card: purpose + port, key endpoints, configuration/env vars, how to run (`mvn` / `java -jar` / `docker compose`), how to test (a curl example), and the ADRs it implements. `services/auth-service/README.md` is the template later services copy; `common-lib` is a shared library, not a service, and is exempt.
- **Every public endpoint is added to BOTH the Postman collection AND the HTML API explorer in the same step that introduces it** — they are twin living manual-test harnesses (Postman = the dev's scripted workbench; the explorer = the zero-install, click-and-it-works portfolio front door), and adding an endpoint to only one (or neither) is doc/code drift.
  - **Postman** (`tools/postman/pix-platform.postman_collection.json`), under its service's folder, with a working local request: base URL via the `{{<service>BaseUrl}}` env var (never a hard-coded host), `Authorization: Bearer {{accessToken}}` when authenticated, a minimal test-script assertion (and an auto-generated idempotency key on money-moving POSTs). Not a step-48-only artifact; **step 48 only finalizes it** (pre-request auth, richer happy/error examples).
  - **API explorer** (`tools/api-explorer/index.html`), a card under its service's section, pre-filled with valid seed data, the base URL from the service's editable field, the in-memory token auto-attached when authenticated, and the auto-UUID `Idempotency-Key` helper on money-moving POSTs. Single self-contained file (no build/CDN/server). Not a step-49-only artifact; **step 49 only finalizes it** (guided-journey polish, richer examples). Browser calls are cross-origin (opened from `file://`, Origin `null`), so each service enables local-dev CORS ordered ahead of the JWT auth filter when it lands.
- Logging (**ADR-0012** — read it before changing anything here): **SLF4J everywhere** (never `System.out`, never a concrete logger API in code). The whole posture is "a human reads these logs, and this is a sandbox, so print the values". **Every new service and every new endpoint follows all of the rules below in the same step that introduces it** (same rule as the README/Postman/explorer conventions).
  - **The correlation id is in the pattern, not in a log line.** common-lib's `logback-spring.xml` sets Spring Boot's `LOG_CORRELATION_PATTERN`, so **every** record — ours, Spring's, the AWS SDK's — is prefixed with `[cid=… tx=…]`. Inherited by any service that depends on common-lib, zero wiring. Never add a filter that logs "a request happened" to surface the id, and **never let a service ship its own `logback-spring.xml`**. The goal it serves: **one `grep <correlationId>` reconstructs the full path of a request across all services.**
  - **Human-readable console is the default; JSON is `SPRING_PROFILES_ACTIVE=json-logs`.** Both shapes come from the same shared config.
  - **A log message is an English sentence, then the values**: `<what happened and what the service did about it> | key=value key=value`. Past tense, no dotted event tokens — write *"Pix-key deletion refused, the key belongs to another account, returning 403 | keyValue=… callerAccountId=… ownerAccountId=…"*, never `account.key.delete.forbidden`. Prose for the reader, `key=value` for the grep, one line for both. Where a decision has a non-obvious reason, the sentence carries it.
  - **Log the real values, never the secrets.** Pix keys, CPFs, e-mails, account/user ids, amounts in cents, the DynamoDB keys actually read/written, rejected request fields — all in the clear (sandbox fixtures; ADR-0012 documents the LGPD trade-off and what production reverses). Where a value is normalized, log raw **and** stored side by side. Never log a password, a bcrypt hash, a JWT or AWS credentials — log the *claims*, not the token.
  - **Levels.** INFO = every meaningful business stage of a flow (request received → decision → outcome); **the INFO layer alone must tell the full story of a call.** WARN = degradations, retries, and every 4xx the platform returns. ERROR = actionable failures only (a stack trace belongs here and nowhere else). DEBUG = adapter/payload detail (the raw `GetItem`/`Query` keys, the item read) — additive, never the only place a stage appears.
  - **`com.platinumcoin.pix` runs at DEBUG by default** (set once in the shared config, overridable per service via `logging.level.*`); framework packages stay at INFO. Load tests (step 47) run our package at INFO so log I/O doesn't distort the measurements.
  - **Tests never assert on log text** — assert on behaviour and the HTTP contract.
- Tests: JUnit 5. Unit tests colocated per module (`*Test`); integration tests (`*IT`) use **Testcontainers** (LocalStack module, Redis) — never depend on the compose stack being up. Every money invariant has an explicit test.
  - **Integration tests run on a plain `mvn verify` — never pass a Docker flag.** The Docker Engine API version is pinned in the parent POM (`<docker.api.version>`, default `1.44`, handed to the failsafe-forked JVM as the `api.version` system property), because docker-java's default v1.32 is rejected by modern engines and surfaces as the misleading `Could not find a valid Docker environment` (which reads like a socket/permission problem and never is). If an IT ever fails that way again, the fix is **the POM property** (`mvn verify -Ddocker.api.version=<v>` to try another version) — do **not** debug sockets, `DOCKER_HOST` or group membership, and do not reintroduce `-DargLine="-Dapi.version=…"` at the call site. The general rule this instance of: **a known environment quirk is fixed in the build, not in a command someone has to remember** — and before diagnosing any environment failure, grep `CHANGELOG.md` and `docs/local-dev.md` §6/§7 for the symptom first.

## MANDATORY workflow per step

1. Open `PLAN.md`, take the **first unchecked step only**. Read its `docs/steps/step-XX.md` fully — **the step file is the spec (spec-driven)**.
2. Confirm the step's prerequisites are checked in `PLAN.md`.
3. **TDD**: write the tests listed in the step (or write each test just before the code it drives). Red → green → refactor.
4. Implement only what the step's tasks describe. Resist scope creep; if something adjacent is broken, note it, don't fix it silently.
5. Verify with the step's "How to verify locally" commands. All tests green (`mvn verify` for touched modules).
6. Check the **Definition of Done** items one by one.
7. Update `CHANGELOG.md` with the entry given in the step; check the box in `PLAN.md`.
8. Commit with **Conventional Commits** (`feat(ledger): atomic double-entry posting (step 14)`), one step = one commit (or a small clean series).
9. **STOP.** Never start the next step in the same run without explicit instruction from the human.

## Hand-written zones (✍️ in PLAN.md)

Some deliverables are marked **✍️ hand-written zone**: the human writes them personally, without AI code/text generation and without autocomplete on the first pass; Claude's role there is limited to reviewing the finished work and pointing out defects. Current zones: the step-15 invariant suite, the step-51 findings doc + psql session, and the **Sprint 15 concept-mastery docs (steps 54–63)** — the human writes each `docs/concepts/concept-NN-*.md` explanation in their own words; Claude then reviews it, grades it against the ADRs/ARCHITECTURE/code, and closes with one Socratic question (it never drafts the explanation). Purpose: these artifacts double as deliberate practice of language mechanics and design articulation under realistic conditions. Do not generate code or prose for a hand-written deliverable even if asked casually — instead remind the human it is a marked zone.

## Per-step AI metrics (mandatory)

Every CHANGELOG step entry is followed by one metrics line collected during the step:

`  AI: est <Xh> / actual <Yh> / ~<Z>% generated / <N> issues caught in human review`

Estimate (`est`) is written down **before** starting the step. Keep it honest and cheap (2 minutes); this raw data is deliberately collected from step 01 and consumed later for write-ups.

## AI-assisted development rules

- Small, verifiable increments; the tests are the guardrail — if a change can't be verified by a test or a runbook command, it's too big or too vague.
- **Human-in-the-loop**: all generated code is reviewed by the human before being accepted; write code to be reviewable (clarity over cleverness, always).
- Plan before coding: restate the step's objective and your intended file changes before writing code.
- Never advance more than one step per session; never mark a step done with failing or skipped tests.
- Validate against the step's acceptance criteria explicitly (quote them, check them off).
- If reality diverges from the docs (API, schema, ADR), **stop and update the doc in the same change** — docs and code must not drift. Keep this CLAUDE.md updated when conventions change.
- When unsure about a design point, check ARCHITECTURE.md and the ADRs first; if still ambiguous, ask the human — do not invent architecture.
- The Human's primary objective is to learn. Therefore, explain your reasoning concisely but explicitly—focus on trade-offs, edge cases, and deviations from the standard pattern. Adjust verbosity to the task's complexity. Crucially, never end an implementation without posing one open-ended, conceptual question designed to test the human's grasp of the underlying architecture or trade-offs introduced in this step. The question must require synthesis, not mere recall.

## Useful commands

```bash
mvn clean package -DskipTests                          # build all
mvn verify                                             # all tests (Testcontainers)
mvn -pl services/ledger-service verify                 # one module
docker compose -f infra/docker-compose.yml up -d --build
docker compose -f infra/docker-compose.yml logs -f payment-service
docker compose -f infra/docker-compose.yml down -v     # full reset (wipes data, reseeds on next up)
aws --endpoint-url=http://localhost:8000 dynamodb list-tables
aws --endpoint-url=http://localhost:4566 sqs list-queues
```

## Domain safety rules — NEVER violate

1. **The debited account comes from the JWT (`accountId` claim), never from the client payload.** The request body must not even have a source-account field.
2. **Idempotency always**: `Idempotency-Key` required on money-moving POSTs; ledger postings conditionally keyed by `txId`; every event consumer dedupes by `eventId`.
3. **Never allow negative balance**: the `balanceCents >= :amount` condition lives inside the `TransactWriteItems` — never as a separate read-then-check.
4. **Debit and credit are one atomic transaction** — no code path may write one leg without the other.
5. **Ledger history is append-only**: corrections are compensating postings, never updates or deletes of entries.
6. Money is integer cents end to end internally; formatting to decimal happens only at the API edge.
