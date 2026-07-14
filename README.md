# PIX Payment Platform — PlatinumCoin

[![CI](https://github.com/filiperibolli/platinumcoin-pix/actions/workflows/ci.yml/badge.svg)](https://github.com/filiperibolli/platinumcoin-pix/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21_LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)

A **Pix instant-payment platform** designed and built from scratch as a system-design + implementation exercise: send and receive Pix, ACID double-entry ledger, idempotent APIs, asynchronous settlement against a mock BACEN SPI, real-time fraud scoring under a 200ms budget, real-time notifications, cached balance/statement, and an immutable audit trail.

Everything runs **100% locally** on a single machine via `docker-compose`, using **LocalStack** to emulate AWS (DynamoDB, SQS, SNS, S3) and a **Redis** container standing in for ElastiCache.

## How I would build Pix

This is my answer to a concrete architecture question: *if I owned Pix at a fintech and started from a blank page, how would I build it?* It's the design I would bring to a staff-level review — a realistic instant-payments platform where every non-trivial decision is written down with its trade-off instead of being hand-waved, and where the code exists to prove the design survives contact with reality (a slow rail, a duplicated retry, a stuck transaction).

The stance behind it:

- **Correctness of money is non-negotiable; everything else is a budget.** The ledger carries the strongest invariants — atomic double-entry, no negative balance, no double-spend, all enforced *inside* the write, not in application code. Fraud, settlement, notifications and caches are then free to degrade, retry or lag, as long as the money stays exact.
- **Design for the failure mode, not the happy path.** A ≤10s BACEN rail, a fraud engine that times out, a client that retries, a transaction that gets stuck — each has an explicit, tested answer: async settlement behind `202 Accepted`, fail-open fraud under a hard 200ms budget, client idempotency keys, and a <5-min reconciliation loop.
- **Make the reasoning auditable, not just the code runnable.** ADRs capture the honest trade-offs (DynamoDB vs. relational, outbox vs. CDC, microservices vs. monolith), and observability is built to answer *business* questions — a payment funnel a product owner can read — not only "is the CPU ok". A reviewer should be able to interrogate *why*, not just watch it work.
- **Size the design to the SLOs, and no larger.** 50M users, 5M tx/day, ~58 TPS average, 500+ TPS peak, 99.99% target — the architecture is sized to those numbers. No Kubernetes, no speculative generality; every moving part earns its place against a requirement.

It runs 100% locally (LocalStack + Redis via `docker-compose`) so the whole design is reproducible end-to-end on a single machine — the platform is the artifact, and it's meant to be run, read and argued with.

## The problem

PlatinumCoin (a fintech) needs its Pix infrastructure built from a blank page:

- Users send Pix to any Pix key (CPF, e-mail, phone, random key) at any bank.
- Users receive Pix from any bank, with **real-time notification**.
- Balance and paginated statement, balance reads **< 300ms p99**.
- Pix key management (register / list / delete).
- Daily limits; account to debit always derived **from the JWT, never from the payload**.
- **Ledger with strong consistency**: debit and credit are atomic — no double-spend, no negative balance, zero transaction loss.
- **Idempotency**: client retries never duplicate charges.
- BACEN SPI settles in **up to 10 seconds** → user gets `202 Accepted` in < 2s p99; settlement is asynchronous.
- Stuck transactions reconciled in **< 5 minutes**.
- Fraud scoring adds **at most 200ms** to the main flow.
- Reference scale: 50M users, 5M tx/day, ~58 TPS average, 500+ TPS peak, 99.99% availability.

Full requirements analysis and the answers to the 7 key design questions: [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Architecture at a glance

```mermaid
graph TB
    APP[Mobile / API client]

    subgraph Services
        AUTH[auth-service<br/>JWT issuance]
        ACC[account-service<br/>accounts + Pix keys]
        PAY[payment-service<br/>orchestration, idempotency, limits]
        FRAUD[fraud-service<br/>score &lt; 200ms]
        LED[ledger-service<br/>double-entry, ACID]
        SET[settlement-service<br/>SPI connector, retries, reconciliation]
        NOT[notification-service<br/>real-time push]
    end

    subgraph Infra [LocalStack + Redis]
        DDB[(DynamoDB<br/>ledger, tx, keys, idempotency)]
        SNS{{SNS pix-events}}
        SQS[[SQS queues + DLQs]]
        S3[(S3 audit / cold archive)]
        REDIS[(Redis balance cache)]
    end

    BACEN[mock-bacen-spi<br/>latency & failure injection]

    APP -->|login| AUTH
    APP -->|JWT| PAY
    APP -->|JWT| ACC
    PAY --> FRAUD
    PAY --> ACC
    PAY --> LED
    LED --> DDB
    PAY -.->|outbox polling publisher| SNS
    SNS --> SQS
    SQS --> SET
    SQS --> NOT
    SET <--> BACEN
    BACEN -->|inbound Pix| SET
    PAY --> REDIS
    FRAUD --> REDIS
    LED --> REDIS
    SET --> S3
    NOT -->|SSE/WebSocket| APP
```

## Why one domain (and why fraud lives next to the ledger)

Eight deployables, but **one bounded context**. The whole platform models a *single* business capability — "move a Pix from A to B, correctly and fast" — with one ubiquitous language (accounts, keys, transactions, ledger entries, settlement) owned by one team in one repo. The services are a decomposition **inside** that domain along technical seams — where **consistency, latency and scaling profiles differ** — not a split into separate business domains. The ledger needs serializable-ish writes; fraud needs single-digit-ms reads and independent failure isolation; settlement is IO-bound on a slow external rail; notifications hold long-lived connections. Those are different *shapes of the same problem*, so they become different services — but not different domains (ADR-0006).

**Why keep fraud in the same domain as the ledger?** At a large fintech, anti-fraud is often its own org — a shared risk platform spanning cards, onboarding and Pix. I deliberately did *not* model it that way here, because at this scale a cross-domain boundary would cost more than it buys:

- **The fraud engine is Pix-specific.** Its signals are velocity, amount, new-payee and odd-hours *over Pix transactions*. There is no other product to share it with yet — a shared platform would be abstraction ahead of a second consumer.
- **It sits on the money path under a 200ms budget.** Fraud scoring is called synchronously between limit-check and ledger debit. A separate domain means a network + contract + versioning boundary in the hottest latency path — pure cost against the p99 < 2s send SLO.
- **Failure isolation is already handled without a domain split.** Fraud is fail-open (ADR-0005): if it's slow or down, the payment proceeds and the case is reviewed post-hoc. That gives the blast-radius separation a domain boundary would give — without the coupling.

**Trade-offs I'm accepting:**

| Keeping it one domain buys | The cost / risk | How it's mitigated |
|---|---|---|
| One ubiquitous language; no cross-domain contract & versioning tax | Can't be independently owned/scaled by separate orgs | Right call for one team at this scale (Conway); revisit when a second team appears |
| Cheap refactoring across service seams (still one repo) | Risk of drifting into a **distributed monolith** | `common-lib` kept deliberately thin; **no shared tables** except two documented exceptions (ADR-0006); APIs/events only |
| `correlationId` reconstructs a whole transaction across all services trivially | Fraud can't yet be reused by other products | Fraud sits behind a port — it can **graduate to its own domain** later without touching the payment orchestrator |
| One deploy pipeline, one failure story to reason about | 8 JVMs of operational surface for one team | 512MB heap caps, one-command `docker-compose`, fail-open so fraud down ≠ payments down |

The seams are drawn so this *can* split later — fraud into a risk platform, settlement into a rail-integration domain — the day the org (not the code) demands it. Splitting earlier would be paying an organizational tax nobody is collecting yet.

## Roadmap — 14 sprints (vertical, flow by flow)

This project is built as **vertical slices, not horizontal layers**: each sprint ships **one complete,
testable, documented flow** and brings up **only the infrastructure that flow needs** — no big-bang.
Ordering is dependency-correct (ledger before the first money-moving Pix; internal synchronous Pix
before external asynchronous settlement). Full breakdown in [`PLAN.md`](PLAN.md); each flow is drawn as
a Mermaid sequence diagram in [`ARCHITECTURE.md`](ARCHITECTURE.md) §6.

| Sprint | Flow delivered | Infra que sobe (novo) |
|---|---|---|
| **S1** | Identity — login → JWT | none (AWS-free; seeded users) |
| **S2** | Accounts & Pix keys — register / resolve a key | LocalStack **DynamoDB** + Testcontainers |
| **S3** | Ledger — atomic double-entry, balance, invariants | DynamoDB `pix_ledger` |
| **S4** | Send Pix **internal** (synchronous, moves real money) | DynamoDB `pix_transactions` + `pix_idempotency` |
| **S5** | Fraud scoring in the path (≤200ms, fail-open) | **Redis** |
| **S6** | Send Pix **external** (async settlement via SPI) | **SNS + SQS(+DLQ)** + mock-bacen-spi |
| **S7** | Resilience — retries, DLQ, reconciliation (<5min) | none new (schedulers) |
| **S8** | Receive Pix + real-time SSE notification | notification-queue, SSE |
| **S9** | Balance & statement with cache (<300ms p99) | none new (Redis cache-aside) |
| **S10** | Immutable audit trail + cold archive | audit-queue, **S3** |
| **S11** | Observability (technical + business funnel) | **Prometheus + Grafana** |
| **S12** | Hardening, E2E journey & k6 load tests | k6 |
| **S13** | DX tooling — Postman collection + HTML API explorer | none |
| **S14** | Relational counterpart lab + sharding + cold export (Block Q) | PostgreSQL (Testcontainers, lab) |

## Stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 LTS |
| Framework | Spring Boot 3.x |
| Build | Maven multi-module |
| Ledger & data | DynamoDB (`TransactWriteItems` + conditional writes) via LocalStack |
| Messaging | SNS fan-out + SQS queues + DLQs; transactional outbox with polling publisher |
| Cache | Redis container (represents ElastiCache — LocalStack does not emulate it) |
| Object storage | S3 (immutable audit log, cold statement archive) |
| Orchestration | docker-compose only (no Kubernetes) |
| Tests | JUnit 5, Testcontainers (LocalStack, Redis) |
| Observability | SLF4J structured JSON logs with correlation id (full request path traceable), Actuator + Micrometer → Prometheus, Grafana dashboards (technical + business funnel), silence/reconciliation alerts |

## What this project demonstrates

- **Financial-grade consistency on DynamoDB**: double-entry ledger with atomic debit/credit via `TransactWriteItems`, conditional writes preventing negative balance and double-spend — plus an explicit, honest trade-off analysis vs. a relational database (ADR-0001).
- **Idempotency done properly**: client-supplied `Idempotency-Key`, request-hash comparison, stored response replay, `409` on key reuse with a different payload. *Where does the key come from?* The **frontend mints it** — a random UUIDv4 (`crypto.randomUUID()` in a browser, `UUID.randomUUID()` on Android, `UUID().uuidString` on iOS) generated **once, the moment the user taps "confirm", and bound to that payment intent — not to the HTTP request**. Every automatic retry of that same payment (network timeout, a lost `2xx`, the app being relaunched) sends the *same* key, so the debit happens at most once; a genuinely new payment mints a new key. The client persists the key alongside the pending-payment draft, so even a killed-and-reopened app resumes under the same key rather than risking a double charge. The server never trusts the client for correctness — it treats the key only as a dedup token (scoped per `accountId`), and the ledger's `txId` conditional write is the second, independent line of defense (ADR-0002).
- **Asynchronous settlement**: `202 Accepted` UX decoupled from a slow (≤10s) external rail, with retries, DLQs and a < 5-minute reconciliation loop for stuck transactions.
- **Reliable event publishing**: transactional outbox (state + event committed atomically) drained by a polling publisher — no dual-write problem; Streams/CDC documented as the production evolution (ADR-0004).
- **Latency-budgeted fraud scoring**: 200ms hard budget with fail-open fallback and post-hoc review (trade-off in ADR-0005).
- **Microservice decomposition by domain**, spec-driven implementation, TDD, and AI-assisted development discipline (`CLAUDE.md`).
- **Observability that answers business questions**: Grafana dashboards including a payment funnel (received → fraud-checked → debited → settled) on top of Prometheus metrics, plus SLF4J structured logs that let you follow one transaction across every service by `correlationId`.
- **Load testing against the stated SLOs**: three k6 profiles (low, standard ~58 TPS, Black Friday 500+ TPS) asserting the p99 targets.
- **API DX**: a unified Postman collection and a self-contained HTML API explorer with pre-filled valid requests.
- **The relational counterpart, measured**: a `labs/ledger-pg` module implements the same ledger port on PostgreSQL with **both** locking strategies (pessimistic `SELECT FOR UPDATE` and optimistic versioning), passes the same invariant storm suite, and documents `EXPLAIN` plans, index write-cost and a contention benchmark vs. the DynamoDB path (ADR-0009, Block Q).
- **Async cold-statement retrieval**: historical statement export as `202 Accepted` + polling status URL + downloadable artifact — the standard fintech pattern for slow reads (step 53).
- **Messaging portability**: an explicit [SNS/SQS ↔ Kafka appendix](docs/messaging-kafka-appendix.md) mapping every concept used here to its Kafka equivalent.

## OKRs & KPIs

Framed as if this were a real platform: **OKRs** are the outcomes the architecture commits to (each Key Result is a hard, testable number — the k6 profiles and Grafana dashboards turn them into pass/fail), and **KPIs** are the steady-state signals you'd watch on the wall afterwards. The KRs are the SLOs from [The problem](#the-problem) made measurable.

**Objective 1 — Never lose or corrupt money.** *(the non-negotiable one)*
- **KR1.1** 0 negative-balance and 0 double-spend defects — the invariant-storm suite (concurrent debits) stays 100% green.
- **KR1.2** 0 duplicate debits under client retries — every money-moving POST is idempotent end to end.
- **KR1.3** Ledger stays append-only — 0 updates/deletes of entries; corrections exist only as compensating postings.

**Objective 2 — Keep the user-facing path fast.**
- **KR2.1** Send-Pix acknowledgement **p99 < 2s**.
- **KR2.2** Balance read **p99 < 300ms**.
- **KR2.3** Fraud scoring adds **≤ 200ms** to the flow (engineered for fraud p99 < 150ms).

**Objective 3 — Stay reliable behind a slow, flaky external rail.**
- **KR3.1** Stuck transactions detected and reconciled in **< 5 min**.
- **KR3.2** DLQ depth returns to 0 after a simulated SPI outage — retries with backoff drain the backlog, nothing is lost.
- **KR3.3** 99.99% availability target for the send/receive path (documented production posture; single-instance locally).

**Objective 4 — Make it operable and auditable.**
- **KR4.1** A single `correlationId` reconstructs 100% of a transaction's path across all services.
- **KR4.2** The business funnel (RECEIVED → FRAUD_CHECKED → DEBITED → SENT_TO_SPI → SETTLED) is observable end to end in Grafana.
- **KR4.3** Every architectural decision is backed by an ADR with an explicit trade-off.

**KPIs (steady-state health):**

| KPI | What it tells you | Healthy signal |
|---|---|---|
| Payment success rate (settled ÷ accepted) | End-to-end money delivery | ≈ 100% minus legitimate rejections |
| Funnel conversion per stage | *Where* payments drop off (fraud vs. settlement) | No unexpected cliff between stages |
| Send p99 / Balance p99 | User-facing latency vs. budget | < 2s / < 300ms |
| Fraud decision mix + fail-open rate | Risk posture & how often the budget is blown | Stable mix; fail-open rare |
| Reconciliation actions/day & mean stuck-age | Health of the async settlement loop | Low count; age well under 5 min |
| DLQ depth / retry rate / SPI error rate | Reliability of the external-rail integration | DLQ trends to 0; retries bounded |
| Balance cache hit rate | Effectiveness of the read path | High enough to hold the 300ms budget |
| Money volume settled/day | Business throughput | Tracks expected traffic |

## Repository layout

```
.
├── README.md                  ← you are here
├── ARCHITECTURE.md            ← full system design + answers to the 7 questions
├── CLAUDE.md                  ← context & rules for Claude Code
├── PLAN.md                    ← implementation roadmap (index of steps)
├── CHANGELOG.md               ← Keep a Changelog; one entry per completed step
├── docs/
│   ├── brief.md               ← the exercise brief + the 7 design questions, verbatim
│   ├── adr/                   ← Architecture Decision Records (0001–0009)
│   ├── api/openapi.yaml       ← REST contract
│   ├── data-model.md          ← DynamoDB tables, keys, GSIs, ledger invariants
│   ├── messaging-kafka-appendix.md ← SNS/SQS ↔ Kafka concept mapping
│   ├── observability.md       ← metric catalog + alert rules (created in step 44)
│   ├── local-dev.md           ← local environment runbook
│   └── steps/                 ← one fine-grained implementation step per file
├── services/                  ← (common-lib in step 01; each service added in its sprint) Maven modules
├── labs/ledger-pg/            ← (steps 50-51) non-deployable lab: relational ledger counterpart
├── infra/                     ← (created in step 06) docker-compose, LocalStack init
│   └── observability/         ← (step 44) Prometheus config + Grafana provisioning/dashboards
├── load/k6/                   ← (step 47) k6 load-test scripts: low, standard, black-friday
├── tools/postman/             ← (step 48) unified Postman collection + environment
├── tools/api-explorer/        ← (step 49) single-file HTML API explorer with valid sample calls
└── pom.xml                    ← (created in step 01) parent POM
```

## Running locally (once implemented)

Prerequisites: Docker + Docker Compose, Java 21, Maven 3.9+. Sized for a 32GB RAM / 6-core desktop.

```bash
# 1. Build all services
mvn clean package -DskipTests

# 2. Start infrastructure + services
docker compose -f infra/docker-compose.yml up -d

# 3. LocalStack init scripts create tables/queues/topics/buckets automatically
#    (infra/localstack/init/*.sh run on container ready)

# 4. Smoke test
curl -s http://localhost:8081/actuator/health   # auth-service
curl -s http://localhost:8084/actuator/health   # payment-service

# 5. Send your first Pix (full walkthrough in docs/local-dev.md)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

curl -s -X POST localhost:8084/v1/payments/pix \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}'
# → 202 Accepted { "transactionId": "...", "status": "PROCESSING" }
```

Detailed runbook, ports, env vars and per-flow test commands: [`docs/local-dev.md`](docs/local-dev.md).

## How this repo is meant to be worked on

1. Open [`PLAN.md`](PLAN.md), pick the next unchecked step.
2. Read its spec in `docs/steps/step-XX.md` — it defines objective, tasks, tests (TDD) and acceptance criteria.
3. Implement **one step at a time** (rules in [`CLAUDE.md`](CLAUDE.md)), tests first.
4. When tests pass and acceptance criteria are met: update `CHANGELOG.md`, check the box in `PLAN.md`, commit (Conventional Commits).

## Security

Security policy and responsible disclosure: [`SECURITY.md`](SECURITY.md).
STRIDE threat model over the money-moving flows: [`docs/threat-model.md`](docs/threat-model.md).

## License

Released under the [MIT License](LICENSE) — © 2026 Filipe Ribolli.
