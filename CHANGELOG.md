# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

One entry is added per completed implementation step (see `PLAN.md` / `docs/steps/`).
Each step file specifies the exact entry to add under `[Unreleased]` on completion.

## [Unreleased]

### Added
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

### Fixed
- README quick-start example sent `amount` as a JSON number (`125.50`); the contract
  requires a decimal **string** (`"125.50"`) — example corrected to match
  `docs/api/openapi.yaml`.

<!--
Template for step entries (append under the matching category):

### Added | Changed | Fixed
- <what shipped> (step XX)
  AI: est <Xh> / actual <Yh> / ~<Z>% generated / <N> issues caught in human review
-->
