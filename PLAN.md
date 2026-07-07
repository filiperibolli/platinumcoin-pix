# PLAN — Implementation Roadmap (sprint- & flow-based)

This project is built as **vertical slices**, not horizontal layers. Each **sprint delivers one
complete, testable, documented flow** — the smallest end-to-end capability that a human can run
and demo — and brings up **only the infrastructure that flow needs**. No big-bang: infra rises
progressively, sprint by sprint (see the cumulative-infra diagram in `ARCHITECTURE.md` §6.0).

- One **step** = one small, verifiable increment with its own spec, tests and acceptance criteria.
- One **sprint** = one flow, ending in a runnable, demoable state; every flow is drawn as a
  Mermaid sequence diagram in `ARCHITECTURE.md` (Part II — §6).
- Ordering is **dependency-correct**: `ledger` before the first money-moving Pix; internal
  (synchronous) Pix before external (asynchronous) settlement.
- Work top-to-bottom; a step may only start when its prerequisites are checked.
- Rules of engagement: see [CLAUDE.md](CLAUDE.md).

> **Legend** — ✍️ = hand-written zone (human writes it without AI code generation; AI reviews only).
> "Infra que sobe" = infrastructure that comes up **for the first time** in that sprint; it stays up afterwards.

---

## Sprint 1 — Foundation & Identity
**Flow delivered:** login → JWT (a client can authenticate and receive a validated token).
**Infra que sobe:** none (AWS-free; seeded users, tested with MockMvc). · **Diagram:** ARCHITECTURE §6.1

- [ ] [Step 01](docs/steps/step-01.md) — Git repo + Maven multi-module parent POM + common-lib skeleton
- [ ] [Step 02](docs/steps/step-02.md) — common-lib: RFC 7807 error model + correlation-id filter + JSON logging
- [ ] [Step 03](docs/steps/step-03.md) — auth-service Spring Boot skeleton with Actuator health
- [ ] [Step 04](docs/steps/step-04.md) — auth-service: login endpoint issuing HS256 JWT; seeded users
- [ ] [Step 05](docs/steps/step-05.md) — common-lib JWT validation filter + `AuthenticatedUser` principal

## Sprint 2 — Accounts & Pix Keys
**Flow delivered:** register / list / delete a Pix key and resolve an internal key → account.
**Infra que sobe:** LocalStack (DynamoDB) + Testcontainers harness. · **Diagram:** ARCHITECTURE §6.2

- [ ] [Step 06](docs/steps/step-06.md) — docker-compose: LocalStack (DynamoDB) with healthchecks (infra only)
- [ ] [Step 07](docs/steps/step-07.md) — LocalStack init: `pix_accounts` + `pix_keys` tables (GSIs, TTL) + seed data
- [ ] [Step 08](docs/steps/step-08.md) — Testcontainers integration-test harness (LocalStack) in common-lib
- [ ] [Step 09](docs/steps/step-09.md) — account-service: accounts repository + `GET /accounts/me` + internal lookup
- [ ] [Step 10](docs/steps/step-10.md) — Pix key registration with global uniqueness (conditional put) + list/delete
- [ ] [Step 11](docs/steps/step-11.md) — internal key resolution endpoint (DICT role for internal keys)

## Sprint 3 — Ledger (the heart)
**Flow delivered:** atomic double-entry posting + balance read + statement, invariants proven.
**Infra que sobe:** DynamoDB `pix_ledger` table. · **Diagram:** ARCHITECTURE §6.3

- [ ] [Step 12](docs/steps/step-12.md) — LocalStack init: `pix_ledger` table + seed postings
- [ ] [Step 13](docs/steps/step-13.md) — ledger-service: data model + balance read (strongly consistent)
- [ ] [Step 14](docs/steps/step-14.md) — atomic double-entry posting via TransactWriteItems (debit+credit+2 entries)
- [ ] [Step 15](docs/steps/step-15.md) — invariant test suite: concurrency storm, no-negative-balance, no-double-post, conservation of money **✍️ hand-written zone**
- [ ] [Step 16](docs/steps/step-16.md) — statement query (paginated, newest first) + posting API polish

## Sprint 4 — Send Pix (internal, synchronous)
**Flow delivered:** alice → bob (internal key) moves real money end-to-end, idempotent, limited.
**Infra que sobe:** DynamoDB `pix_transactions` + `pix_idempotency` tables. · **Diagram:** ARCHITECTURE §6.4

- [ ] [Step 17](docs/steps/step-17.md) — LocalStack init: `pix_transactions` (+GSIs) + `pix_idempotency` tables
- [ ] [Step 18](docs/steps/step-18.md) — payment-service: `POST /payments/pix` walking skeleton (validation, txId/endToEndId, 202)
- [ ] [Step 19](docs/steps/step-19.md) — idempotency layer: conditional claim, response replay, 409 on hash mismatch **✍️ hand-written zone (tests)**
- [ ] [Step 20](docs/steps/step-20.md) — daily limit enforcement (rolling day window, decision-object seam for future MFA)
- [ ] [Step 21](docs/steps/step-21.md) — internal orchestration: key resolution + ledger debit (credit payee directly) + status DEBITED
- [ ] [Step 22](docs/steps/step-22.md) — `GET /payments/{id}` status endpoint

## Sprint 5 — Fraud in the path
**Flow delivered:** synchronous fraud score inside the send flow, under a 200ms budget, fail-open.
**Infra que sobe:** Redis (velocity counters). · **Diagram:** ARCHITECTURE §6.5

- [ ] [Step 23](docs/steps/step-23.md) — docker-compose Redis + fraud-service skeleton
- [ ] [Step 24](docs/steps/step-24.md) — fraud-service: rule-based `POST /score` (velocity, amount, novelty, hours), p99 < 150ms
- [ ] [Step 25](docs/steps/step-25.md) — payment-service integration: 200ms hard timeout, fail-open + `FRAUD_SKIPPED` flag & event

## Sprint 6 — Send Pix (external, asynchronous settlement)
**Flow delivered:** external Pix debits to clearing, settles via BACEN SPI, reaches SETTLED.
**Infra que sobe:** SNS `pix-events` + SQS `settlement-queue`(+DLQ) + mock-bacen-spi. · **Diagram:** ARCHITECTURE §6.6

- [ ] [Step 26](docs/steps/step-26.md) — LocalStack init: SNS `pix-events` + `settlement-queue` (+DLQ, redrive, filter policy)
- [ ] [Step 27](docs/steps/step-27.md) — external orchestration: ledger debit → `SPI_CLEARING`; status DEBITED
- [ ] [Step 28](docs/steps/step-28.md) — transactional outbox: tx + outbox item in one TransactWriteItems
- [ ] [Step 29](docs/steps/step-29.md) — outbox polling publisher: sparse GSI → SNS; `ProcessedEventStore` (consumer dedup)
- [ ] [Step 30](docs/steps/step-30.md) — mock-bacen-spi: settlement endpoint (latency/failure/timeout config) + external DICT resolve
- [ ] [Step 31](docs/steps/step-31.md) — settlement-service: consume settlement-queue, call SPI, mark SETTLED (happy path)

## Sprint 7 — Resilience & reconciliation
**Flow delivered:** timeouts/failures never lose or double money; stuck tx resolved in < 5 min.
**Infra que sobe:** none new (schedulers + DLQ redrive). · **Diagram:** ARCHITECTURE §6.7

- [ ] [Step 32](docs/steps/step-32.md) — retries with query-before-retry, visibility backoff, DLQ redrive
- [ ] [Step 33](docs/steps/step-33.md) — settlement finalization: SETTLED clearing release; FAILED → REVERSED (compensating posting)
- [ ] [Step 34](docs/steps/step-34.md) — stuck-transaction scanner (GSI2 status+age) on a 60s schedule
- [ ] [Step 35](docs/steps/step-35.md) — reconciliation resolution: query SPI, finalize or reverse; < 5-min SLO metric + alert

## Sprint 8 — Receive Pix & real-time notification
**Flow delivered:** inbound Pix credits the user and pushes an SSE notification in real time.
**Infra que sobe:** SQS `notification-queue` + `inbound-pix-queue`; SSE. · **Diagram:** ARCHITECTURE §6.8

- [ ] [Step 36](docs/steps/step-36.md) — LocalStack init: `notification-queue` + `inbound-pix-queue` (+DLQs, subscriptions)
- [ ] [Step 37](docs/steps/step-37.md) — mock-bacen inbound generator → inbound flow: dedupe by endToEndId, credit posting
- [ ] [Step 38](docs/steps/step-38.md) — notification-service: consume notification-queue, SSE stream per user
- [ ] [Step 39](docs/steps/step-39.md) — wire PixSettled/PixReceived/PixReversed to real-time pushes end to end

## Sprint 9 — Balance & statement with cache
**Flow delivered:** balance < 300ms p99 from cache; paginated statement through payment-service.
**Infra que sobe:** none new (Redis cache-aside on existing Redis). · **Diagram:** ARCHITECTURE §6.9

- [ ] [Step 40](docs/steps/step-40.md) — Redis cache-aside for balance + invalidation on postings + 5s TTL backstop
- [ ] [Step 41](docs/steps/step-41.md) — statement API through payment-service with opaque cursor pagination

## Sprint 10 — Immutable audit trail
**Flow delivered:** every state transition lands as an immutable S3 record; cold statement archive.
**Infra que sobe:** SQS `audit-queue` + S3 buckets. · **Diagram:** ARCHITECTURE §6.10

- [ ] [Step 42](docs/steps/step-42.md) — LocalStack init: `audit-queue` (+DLQ, all-events subscription) + S3 buckets
- [ ] [Step 43](docs/steps/step-43.md) — immutable audit trail: audit-queue consumer → S3 JSON lines; statement cold-archive job

## Sprint 11 — Observability
**Flow delivered:** technical + business-funnel dashboards; silence alerts; correlationId path tracing.
**Infra que sobe:** Prometheus + Grafana. · **Diagram:** ARCHITECTURE §6.11

- [ ] [Step 44](docs/steps/step-44.md) — Prometheus + Grafana dashboards (technical + business funnel) + silence alerts (settlement watchdog, DLQ depth, reconciliation age)

## Sprint 12 — Hardening, E2E & load
**Flow delivered:** the full journey proven under an automated E2E + failure drill + SLO load tests.
**Infra que sobe:** k6.

- [ ] [Step 45](docs/steps/step-45.md) — hardening: API versioning review, guarded status transitions, error contract audit, security checklist
- [ ] [Step 46](docs/steps/step-46.md) — end-to-end test: full journey send→settle→receive→notify→statement, incl. failure drill
- [ ] [Step 47](docs/steps/step-47.md) — k6 load tests: low, standard (~58 TPS) and Black Friday (500+ TPS) profiles with SLO thresholds

## Sprint 13 — API tooling & DX
**Flow delivered:** unified Postman collection + single-file HTML API explorer.
**Infra que sobe:** none.

- [ ] [Step 48](docs/steps/step-48.md) — unified Postman collection (all services, auth pre-request, happy/error examples)
- [ ] [Step 49](docs/steps/step-49.md) — single-file HTML API explorer: unified swagger-like page with valid, clickable sample requests

## Sprint 14 — Relational counterpart & interview-grade extensions (Block Q)
> Steps 50–51 may be taken any time after Sprint 3; 52 requires 47; 53 requires 41 & 43.
**Flow delivered:** the same ledger, measured on PostgreSQL; clearing sharding proven; async cold export.
**Infra que sobe:** PostgreSQL (Testcontainers, lab only — never wired to the platform).

- [ ] [Step 50](docs/steps/step-50.md) — `labs/ledger-pg`: same ledger port on PostgreSQL with pessimistic (`SELECT FOR UPDATE`) and optimistic (version column) strategies (ADR-0009)
- [ ] [Step 51](docs/steps/step-51.md) — invariant parity on Postgres + `EXPLAIN`/index/deadlock study + contention benchmark vs DynamoDB **✍️ hand-written zone (findings doc + psql session)**
- [ ] [Step 52](docs/steps/step-52.md) — clearing-account write sharding (N=16) proven with the Black Friday k6 profile (before/after)
- [ ] [Step 53](docs/steps/step-53.md) — cold statement retrieval: async export with `202` + polling status URL + download artifact
