# Step 46 — Clearing-account write sharding, proven under the Black Friday profile

## Objective
Replace the single `ACCOUNT#SPI_CLEARING` hot item with `CLEARING_SHARDS` (default 16) sub-accounts `SPI_CLEARING#00..#15` selected by hash of `txId`, keep every invariant intact (including reversal correctness), and demonstrate the before/after under the 500+ TPS k6 profile in `docs/sharding-findings.md`.

## Why / what you'll learn
The write-sharding pattern end to end — not the diagram version, the one with the sharp edge: **a compensating reversal must hit the same shard that was credited**, or money silently migrates between shards and reconciliation breaks. You'll also learn what a hot partition actually looks like in metrics (throttle/conflict counts, p99 climb) and how to prove a mitigation instead of asserting it. This closes the "documented, N=1 locally" gap — the difference between citing a pattern and having operated it.

## Prerequisites
Steps 15, 29, 41 (invariants, reversal flow, k6 Black Friday profile).

## Tasks
1. `ClearingAccountResolver` in ledger-service: `SPI_CLEARING#%02d` by `hash(txId) % CLEARING_SHARDS` (env, default 16; `1` reproduces the old behavior for the baseline run).
2. Persist `clearingShard` on the transaction item at debit time; settlement reversal (step 29) reads it and compensates **that** shard — never re-derives.
3. Logical clearing balance = sum of shards: internal endpoint `GET /internal/clearing-balance` + the conservation-of-money invariant updated to sum shards.
4. Seed script creates the shard balance items; N is init-time config.
5. Invariant suite extended: random storm with reversals mixed in → per-shard Σ and global Σ both close.
6. **Findings**: run k6 `black-friday` with `CLEARING_SHARDS=1` then `=16`; capture p99, error/conflict/throttle counts, and the relevant Grafana panels into `docs/sharding-findings.md` (honest note on what LocalStack does and doesn't emulate about partition throttling — measure conflicts/latency, not AWS internals).

## Tests (TDD)
- Resolver: stable mapping, uniform-ish distribution over 10k txIds.
- Reversal-shard correctness: force a settlement failure → reversal compensates the exact shard credited (assert per-shard balances).
- Conservation invariant green with N=16 under storm.

## Verify locally
```bash
CLEARING_SHARDS=16 docker compose -f infra/docker-compose.yml up -d
mvn -q -pl services/ledger-service verify
k6 run load/k6/black-friday.js
```

## Definition of Done
- [ ] Reversal always compensates the originating shard (test-proven)
- [ ] Conservation of money holds across shards under storm
- [ ] `docs/sharding-findings.md` shows the N=1 vs N=16 comparison with real numbers
- [ ] ARCHITECTURE §6.3 cross-reference is accurate

## CHANGELOG entry
`### Added` → `Clearing-account write sharding (N configurable) with reversal-shard pinning, proven under the Black Friday k6 profile (step 46)`
