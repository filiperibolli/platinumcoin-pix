# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

One entry is added per completed implementation step (see `PLAN.md` / `docs/steps/`).
Each step file specifies the exact entry to add under `[Unreleased]` on completion.

## [Unreleased]

### Added
- Planning & documentation baseline: ARCHITECTURE.md, ADRs 0001–0008, data model,
  OpenAPI contract, local-dev runbook, CLAUDE.md, PLAN.md and 43 step specs.
- Block Q (steps 44–47): relational ledger counterpart lab (`labs/ledger-pg`, ADR-0009)
  with pessimistic/optimistic strategies, invariant parity + EXPLAIN/index/deadlock
  study + contention benchmark; clearing-account write sharding proven under the
  Black Friday k6 profile; async cold statement export (202 + polling status URL).
- `docs/messaging-kafka-appendix.md`: SNS/SQS ↔ Kafka concept mapping, referenced
  from ADR-0004 and the README.
- CLAUDE.md workflow additions: hand-written zones (✍️ steps 15, 18, 45) and
  mandatory per-step AI metrics line in CHANGELOG entries.

### Changed
- ARCHITECTURE §6.3: clearing-account write sharding upgraded from "documented,
  N=1 locally" to implemented and load-proven (step 46), with reversal-shard pinning.
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
