# Step 52 — Clearing-account write sharding, proven under Black Friday

> **Sprint 14 — Relational counterpart & extensions (Block Q)** · **Flow:** hot-partition mitigation · **Infra que sobe:** none new

## Objective
Replace the single `ACCOUNT#SPI_CLEARING` hot item with `CLEARING_SHARDS` (default 16) sub-accounts `SPI_CLEARING#00..#15` selected by hash of `txId`, keep every invariant intact (including reversal correctness), and demonstrate the before/after under the 500+ TPS k6 profile in `docs/sharding-findings.md`.

## Why this step exists
The write-sharding pattern end to end — not the diagram version, the one with the sharp edge: **a compensating reversal must hit the same shard that was credited**, or money silently migrates between shards and reconciliation breaks. The mechanism that removes the coupling is persisting `clearingShard` on the transaction at debit time (step 33 already reads "the exact clearing account used") — the reversal reads it, never re-derives. You'll also learn what a hot partition looks like in metrics (throttle/conflict counts, p99 climb) and how to *prove* a mitigation instead of asserting it — closing the "documented, N=1 locally" gap.

## Prerequisites
Steps 15, 33 (reversal targets the exact clearing account), 47 (Black Friday k6 profile).

## Tasks
1. `ClearingAccountResolver` — `SPI_CLEARING#%02d` by `CRC32(txId) % CLEARING_SHARDS` (env, default 16; `1` reproduces the old behavior for the baseline run).
2. Persist the resolved clearing account on the transaction at debit time; settlement reversal (step 33) reads it and compensates **that** shard — never re-derives.
3. Logical clearing balance = sum of shards: internal `GET /internal/ledger/clearing-balance`; conservation invariant updated to sum shards.
4. Seed script creates the shard balance items; N is init-time config.
5. Invariant suite extended: random storm with reversals mixed in → per-shard Σ and global Σ both close.
6. **Findings**: run k6 `black-friday` with `CLEARING_SHARDS=1` then `=16`; capture p99, error/conflict/throttle counts and the relevant Grafana panels into `docs/sharding-findings.md` (honest note on what LocalStack does/doesn't emulate about partition throttling — measure conflicts/latency, not AWS internals).

### Three corrections made while implementing (2026-08-28)

The tasks above are the amended text. What changed and why:

1. **The resolver lives in `common-lib`, not in ledger-service** (task 1 originally said ledger-service).
   ARCHITECTURE §6.3 defines the design the other way round — *"introducing shards later changes only
   which clearing id **the caller** passes"* — and the callers are payment-service (outbound debit) and
   settlement-service (inbound credit). Putting the function in ledger-service would force either a
   second copy of the hash in payment-service (two definitions of "which shard" is how money lands in a
   sub-account nobody compensates) or a ledger that silently rewrites the `creditAccount` it was handed,
   which breaks the explicit-accounts posting contract from step 14. ledger-service consumes the same
   class to *enumerate* the shards it sums.
2. **No new `clearingShard` field** (task 2 asked for one). `clearingAccountId` — persisted since step 33
   — already holds *the exact clearing account the debit credited*, and it is strictly better than a
   shard index: an index of `7` resolves to a different account the moment N changes, while the full id
   does not. That is what makes `CLEARING_SHARDS` a capacity knob rather than a correctness-critical
   constant.
3. **The path is `/internal/ledger/clearing-balance`** (task 3 wrote `/internal/clearing-balance`).
   ledger-service namespaces every internal route under `/internal/ledger/**`; consistency with the
   service beat matching the step's shorthand.

### And one thing the step could not have known

`load/k6/black-friday.js` ships with `EXTERNAL_SHARE=0`, so run verbatim it sends **no external
traffic** and never touches the clearing account — the N=1 vs N=16 comparison in task 6 would compare
two runs that both write zero clearing postings. Both runs are therefore driven with
`EXTERNAL_SHARE=1.0`. `docs/sharding-findings.md` §1 states the consequence: the numbers are comparable
to each other, not to `load/RESULTS.md`.

## Tests (TDD)
- Resolver: stable mapping, uniform-ish distribution over 10k txIds.
- Reversal-shard correctness: force a settlement failure → reversal compensates the exact shard credited (assert per-shard balances).
- Conservation invariant green with N=16 under storm.

## Verify locally
```bash
CLEARING_SHARDS=16 docker compose -f infra/docker-compose.yml up -d
mvn -q -pl services/ledger-service verify
docker run --rm -i --network=host grafana/k6 run - < load/k6/black-friday.js
```

## Definition of Done
- [x] Reversal always compensates the originating shard (test-proven)
- [x] Conservation of money holds across shards under storm
- [x] `docs/sharding-findings.md` shows the N=1 vs N=16 comparison with real numbers
- [x] ARCHITECTURE §6.3 cross-reference is accurate

## CHANGELOG entry
`### Added` → `Clearing-account write sharding (N configurable) with reversal-shard pinning, proven under the Black Friday k6 profile (step 52)`
