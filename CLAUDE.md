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
tools/api-explorer/    single-file HTML API explorer with valid sample requests (step 49)
```

## Conventions

- **Java 21**, records for DTOs/value objects, `var` where it aids readability. Money is **always integer cents (`long`)** internally; never `double`/`float` for money.
- Maven standard layout; package root `com.platinumcoin.pix.<service>`; inside: `api/` (inbound adapters — controllers, request/response records, exception mapping), `domain/` (entities & value objects, domain services, ports), `infra/` (outbound adapters — DynamoDB/SQS/SNS/Redis/HTTP — + Spring config). **Clean/hexagonal-lite per service (ADR-0010)** — pragmatic, not the full ceremony:
  - **Dependency rule points inward**: `api → domain` and `infra → domain`; **`domain` depends on nothing outward** — no `org.springframework.web.*`, `software.amazon.awssdk.*`, `jakarta.servlet.*` or Jackson-binding imports in `domain/`. Domain is plain Java (records + services).
  - **Ports only for outbound infra** (repositories, external clients, publishers) — the domain declares the interface, `infra/` implements it. No port for internal-only collaborators or single-impl non-boundaries.
  - **DTO only when the wire shape diverges** from the domain type; if identical, reuse it — no mirror-DTO-per-entity. Money formats to a decimal string only at the `api/` edge; it stays `long` cents in `domain/`.
  - **Enforced**: each service ships one `*ArchitectureTest` (ArchUnit) failing the build if `domain/` imports a framework/infra package. `common-lib` is exempt (it *is* the shared adapter layer).
- REST: resources under `/v1/...`; errors as RFC 7807 `application/problem+json` with a `code` field (e.g. `LIMIT_EXCEEDED`) and `correlationId`. Never leak stack traces.
- Naming: tables `pix_*`; queues `<purpose>-queue` + `<purpose>-queue-dlq`; SNS topic `pix-events`; events in PascalCase past tense (`PixDebited`, `PixSettled`, `PixReceived`, `FraudCheckSkipped`).
- **Every service module ships a `services/<name>/README.md`.** Creating a new service (its skeleton step) is not done until its README exists. Keep it a short, consistent card: purpose + port, key endpoints, configuration/env vars, how to run (`mvn` / `java -jar` / `docker compose`), how to test (a curl example), and the ADRs it implements. `services/auth-service/README.md` is the template later services copy; `common-lib` is a shared library, not a service, and is exempt.
- **Every public endpoint is added to the Postman collection (`tools/postman/pix-platform.postman_collection.json`) in the same step that introduces it**, under its service's folder, with a working local request: base URL via the `{{<service>BaseUrl}}` env var (never a hard-coded host), `Authorization: Bearer {{accessToken}}` when authenticated, a minimal test-script assertion (and an auto-generated idempotency key on money-moving POSTs). The collection is a living manual-test harness, not a step-48-only artifact; **step 48 only finalizes it** (pre-request auth, richer happy/error examples). Adding an endpoint without adding its request is doc/code drift.
- Logging: **SLF4J everywhere** (never `System.out`, never a concrete logger API in code), structured JSON via the logback encoder. Every log line carries `correlationId` (+ `txId` when present) through MDC, and every meaningful stage of a flow logs at INFO with consistent event names — the explicit goal is that **one `correlationId` reconstructs the full path of a request across all services**. DEBUG for payloads (masked), WARN for retries/degradations, ERROR only for actionable failures.
- Tests: JUnit 5. Unit tests colocated per module (`*Test`); integration tests (`*IT`) use **Testcontainers** (LocalStack module, Redis) — never depend on the compose stack being up. Every money invariant has an explicit test.

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

Some deliverables are marked **✍️ hand-written zone**: the human writes them personally, without AI code/text generation and without autocomplete on the first pass; Claude's role there is limited to reviewing the finished work and pointing out defects. Current zones: the step-15 invariant suite, the step-19 test set, and the step-51 findings doc + psql session. Purpose: these artifacts double as deliberate practice of language mechanics under realistic conditions. Do not generate code for a hand-written deliverable even if asked casually — instead remind the human it is a marked zone.

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
aws --endpoint-url=http://localhost:4566 dynamodb list-tables
aws --endpoint-url=http://localhost:4566 sqs list-queues
```

## Domain safety rules — NEVER violate

1. **The debited account comes from the JWT (`accountId` claim), never from the client payload.** The request body must not even have a source-account field.
2. **Idempotency always**: `Idempotency-Key` required on money-moving POSTs; ledger postings conditionally keyed by `txId`; every event consumer dedupes by `eventId`.
3. **Never allow negative balance**: the `balanceCents >= :amount` condition lives inside the `TransactWriteItems` — never as a separate read-then-check.
4. **Debit and credit are one atomic transaction** — no code path may write one leg without the other.
5. **Ledger history is append-only**: corrections are compensating postings, never updates or deletes of entries.
6. Money is integer cents end to end internally; formatting to decimal happens only at the API edge.
