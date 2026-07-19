# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

One entry is added per completed implementation step (see `PLAN.md` / `docs/steps/`).
Each step file specifies the exact entry to add under `[Unreleased]` on completion.

## [Unreleased]

### Added
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
