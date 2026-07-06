# PLAN — Implementation Roadmap

One step = one small, verifiable increment with its own spec, tests and acceptance criteria.
Work top-to-bottom; a step may only start when its prerequisites are checked.
Rules of engagement: see [CLAUDE.md](CLAUDE.md).

## Block A — Scaffolding
- [ ] [Step 01](docs/steps/step-01.md) — Git repo + Maven multi-module parent POM + common-lib skeleton
- [ ] [Step 02](docs/steps/step-02.md) — Spring Boot skeletons for all 8 services with Actuator health
- [ ] [Step 03](docs/steps/step-03.md) — common-lib: RFC 7807 error model + correlation-id filter + JSON logging

## Block B — Local infrastructure
- [ ] [Step 04](docs/steps/step-04.md) — docker-compose with LocalStack + Redis (infra only) and smoke checks
- [ ] [Step 05](docs/steps/step-05.md) — LocalStack init scripts: DynamoDB tables, SNS/SQS(+DLQ), S3, seed data
- [ ] [Step 06](docs/steps/step-06.md) — Testcontainers integration-test harness (LocalStack + Redis) in common-lib
- [ ] [Step 07](docs/steps/step-07.md) — Dockerize services; full-stack compose up with health gates

## Block C — Identity
- [ ] [Step 08](docs/steps/step-08.md) — auth-service: login endpoint issuing HS256 JWT; seeded users
- [ ] [Step 09](docs/steps/step-09.md) — common-lib JWT validation filter; protect all service endpoints

## Block D — Accounts & Pix keys
- [ ] [Step 10](docs/steps/step-10.md) — account-service: accounts repository + GET account/limits
- [ ] [Step 11](docs/steps/step-11.md) — Pix key registration with global uniqueness (conditional put) + list/delete
- [ ] [Step 12](docs/steps/step-12.md) — key resolution endpoint (internal DICT) + external-key delegation to mock-bacen

## Block E — Ledger (the heart)
- [ ] [Step 13](docs/steps/step-13.md) — ledger data model + balance read + seed postings
- [ ] [Step 14](docs/steps/step-14.md) — atomic double-entry posting via TransactWriteItems (debit+credit+2 entries)
- [ ] [Step 15](docs/steps/step-15.md) — invariant test suite: concurrency storm, no-negative-balance, no-double-post, conservation of money **✍️ hand-written zone**
- [ ] [Step 16](docs/steps/step-16.md) — statement query (paginated, newest first) + posting API polish

## Block F — Payments (send)
- [ ] [Step 17](docs/steps/step-17.md) — payment-service: POST /payments/pix walking skeleton (validation, txId/endToEndId, 202)
- [ ] [Step 18](docs/steps/step-18.md) — idempotency layer: conditional claim, response replay, 409 on hash mismatch **✍️ hand-written zone (tests)**
- [ ] [Step 19](docs/steps/step-19.md) — daily limit enforcement (rolling day window, decision-object seam for future MFA)
- [ ] [Step 20](docs/steps/step-20.md) — orchestration: key resolution + ledger debit (clearing account) + status DEBITED
- [ ] [Step 21](docs/steps/step-21.md) — transactional outbox: tx + outbox item in one TransactWriteItems
- [ ] [Step 22](docs/steps/step-22.md) — outbox polling publisher: sparse GSI → SNS pix-events; consumer dedup table
- [ ] [Step 23](docs/steps/step-23.md) — GET /payments/{id} status endpoint

## Block G — Fraud
- [ ] [Step 24](docs/steps/step-24.md) — fraud-service: rule-based POST /score (velocity, amount, novelty, hours)
- [ ] [Step 25](docs/steps/step-25.md) — payment-service integration: 200ms hard timeout, fail-open + FRAUD_SKIPPED flag & event

## Block H — Settlement & BACEN
- [ ] [Step 26](docs/steps/step-26.md) — mock-bacen-spi: settlement endpoint with configurable latency/failure/timeout + admin config
- [ ] [Step 27](docs/steps/step-27.md) — settlement-service: consume settlement-queue, call SPI, mark SETTLED
- [ ] [Step 28](docs/steps/step-28.md) — retries with query-before-retry, visibility backoff, DLQ redrive
- [ ] [Step 29](docs/steps/step-29.md) — settlement finalization events → user-facing status + failure reversal (compensating posting)

## Block I — Reconciliation
- [ ] [Step 30](docs/steps/step-30.md) — stuck-transaction scanner (GSI2 status+age) on a 60s schedule
- [ ] [Step 31](docs/steps/step-31.md) — reconciliation resolution: query SPI, finalize or reverse; <5-min SLO metric + alert

## Block J — Receive & notify
- [ ] [Step 32](docs/steps/step-32.md) — mock-bacen inbound-Pix generator → inbound flow: dedupe by endToEndId, credit posting
- [ ] [Step 33](docs/steps/step-33.md) — notification-service: consume notification-queue, SSE stream per user
- [ ] [Step 34](docs/steps/step-34.md) — wire PixSettled/PixReceived/PixReversed to real-time pushes end to end

## Block K — Balance & statement with cache
- [ ] [Step 35](docs/steps/step-35.md) — Redis cache-aside for balance + invalidation on postings + 5s TTL backstop
- [ ] [Step 36](docs/steps/step-36.md) — statement API through payment-service with opaque cursor pagination

## Block L — Audit
- [ ] [Step 37](docs/steps/step-37.md) — immutable audit trail: audit-queue consumer → S3 JSON lines; statement cold-archive job

## Block M — Observability
- [ ] [Step 38](docs/steps/step-38.md) — Prometheus + Grafana dashboards (technical + business funnel) + silence alerts (settlement watchdog, DLQ depth, reconciliation age)

## Block N — Hardening & E2E
- [ ] [Step 39](docs/steps/step-39.md) — hardening: API versioning review, guarded status transitions, error contract audit, security checklist
- [ ] [Step 40](docs/steps/step-40.md) — end-to-end test: full journey send→settle→receive→notify→statement, incl. failure drill

## Block O — Load testing
- [ ] [Step 41](docs/steps/step-41.md) — k6 load tests: low, standard (~58 TPS) and Black Friday (500+ TPS) profiles with SLO thresholds

## Block P — API tooling & DX
- [ ] [Step 42](docs/steps/step-42.md) — unified Postman collection (all services, auth pre-request, happy/error examples)
- [ ] [Step 43](docs/steps/step-43.md) — single-file HTML API explorer: unified swagger-like page with valid, clickable sample requests

## Block Q — Relational counterpart & interview-grade extensions
> Steps 44–45 may be taken any time after Block E; 46 requires 41; 47 requires 36–37.
> **✍️ hand-written zone** = the human writes the marked deliverable without AI code generation (AI may review afterwards). Rationale in `CLAUDE.md`.

- [ ] [Step 44](docs/steps/step-44.md) — `labs/ledger-pg`: same ledger port on PostgreSQL with pessimistic (`SELECT FOR UPDATE`) and optimistic (version column) strategies (ADR-0009)
- [ ] [Step 45](docs/steps/step-45.md) — invariant parity on Postgres + `EXPLAIN`/index/deadlock study + contention benchmark vs DynamoDB **✍️ hand-written zone (findings doc + psql session)**
- [ ] [Step 46](docs/steps/step-46.md) — clearing-account write sharding (N=16) proven with the Black Friday k6 profile (before/after)
- [ ] [Step 47](docs/steps/step-47.md) — cold statement retrieval: async export with `202` + polling status URL + download artifact
