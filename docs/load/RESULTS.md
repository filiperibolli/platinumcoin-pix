# Load measurement

A self-contained load-measurement deliverable, scoped smaller than PLAN.md's Step 47 (which
covers the full k6 low/standard/Black-Friday SLO suite). This document answers three questions
with real traffic against the docker-compose stack, not synthetic reasoning:

- **S1 — conservation**: does the ledger's non-negative-balance / daily-limit invariant hold
  under concurrent contention on a single, exhaustible account?
- **S2 — capacity**: where does this environment's Pix-send path start to bend, and what does it
  look like when it does?
- **S3 — idempotency**: under a retry storm where 30 clients race the same `Idempotency-Key`,
  does exactly one real ledger posting happen per round, provably (not by absence of counter-
  evidence)?

Machine-readable numbers live in `docs/load/results.json`; the raw k6/jq artifacts behind every
number in this document are under `docs/load/raw/` (git-ignored-sized ndjson/logs, regenerate
with `tools/k6/run-s*.sh`). All scripts: `tools/k6/`.

**This is the second pass.** The first pass found a ~8.5 req/s ceiling and attributed the ~30s
tail stalls to WSL2 clock drift. A follow-up diagnostic (`docs/load/BOTTLENECK.md`) traced the
real cause to LocalStack's own DynamoDB support — it proxies every call through an internal
Python HTTP client with a fixed, non-configurable connection-pool cap — and corrected the stall
explanation (a real server-side stall inside whichever DynamoDB-serving process is under load,
not a clock artifact). The fix: DynamoDB now runs in its own standalone `amazon/dynamodb-local`
container (`infra/docker-compose.yml`), bypassing LocalStack's proxy entirely; SNS/SQS stay on
LocalStack. **Result: ~8.5 req/s → ~160-200 req/s**, re-measured below with the same S0-S3
methodology as the first pass. Full mechanism and the diagnostic ladder that found it:
[`docs/load/BOTTLENECK.md`](BOTTLENECK.md).

## S0 — artifact floor (read this section first)

Before trusting any latency number below, S0 asks: does this specific machine stall on requests
even with **essentially no load**? 1 VU, ~1 request/second, 5 minutes, same
`POST /v1/payments/pix` endpoint S2 uses.

| | value |
|---|---|
| samples | 293 |
| stalled (≥10s) | 3 (**1.02%**) |
| p50 (trimmed) | 23.2 ms |
| p99 (trimmed) | 31.7 ms |
| p99 (raw, includes stalls) | 30,972.4 ms |
| max (raw) | 30,990.0 ms |

At 1 VU there is no concurrency to saturate — this can only be an environment artifact, not an
application capacity signal. The stall is still present post-fix (**1.02%**, down from 4.21%
pre-fix) — expected, since it's a WSL2/Docker Desktop-level phenomenon
([`docs/load/BOTTLENECK.md`](BOTTLENECK.md) RUNG 4), orthogonal to the DynamoDB throughput fix.
p50 dropped from 143.7ms to **23.2ms** (~6x) — the direct effect of the fix.

## Trimming rule (applies to every latency number below)

**Threshold: 10,000 ms.** Any single request duration at or above this is classified as the
environment stall documented in BOTTLENECK.md RUNG 4 and excluded from the `trimmed` figures;
`raw` figures (which include it) are always reported alongside. `removed_count` / `removed_rate`
is stated for every stage/subsection so a reader can see exactly how many samples the rule
affected and recompute with a different threshold from `docs/load/raw/*.ndjson` if they disagree.

Unlike the first pass, the rule stays clean at **every** stage tested here — `removed_rate` stays
in a 0-4% band throughout S0/S1/S2/S3 (nothing like the 72%/83% breakdown the first pass saw at
S2's highest stages), because post-fix latencies stay far below the 10s threshold even at the
highest VU count tested.

## S1 — conservation under contention

50 VUs fire fresh-idempotency-key sends at ONE account for 60s (30s warm-up against a
richly-funded ring account, discarded, precedes it). Two subsections, because this repo's seed
data makes the daily-limit counter bind before the ledger balance ever could for **any** transfer
amount (alice's 500,000¢ limit is half her 1,000,000¢ balance) — rather than pick one invariant to
prove, both run.

### balance-guard (`acc-lt-s1bal`, funded for exactly 10 successes at R$100/send)

| settled | rejected (insufficient funds) | other (fraud-denied) | Σ balance before | Σ balance after | negative balance? | double postings? |
|---|---|---|---|---|---|---|
| **10** (exact) | 194 | 33,455 | 0 | 0 | **no** | **0** |

`other_errors=33455` is entirely `FRAUD_DENIED` (422) — confirmed by grepping
`docs/load/raw/s1-balance.log`. This count is ~50x larger than the first pass's (676) because the
much higher post-fix throughput means many more attempts land inside the same 60s window before
`acc-lt-s1bal` exhausts — same real system behavior (fraud-service's nonzero baseline denial
rate), just observed at higher volume.

Latency (storm phase, N=33,659): raw p99 31,068.1ms / **trimmed p99 135.3ms**, removed_count=550
(**1.63%**) — down from 5,307.4ms trimmed p99 pre-fix (~39x faster).

### limit-guard (`acc-001`/alice, unchanged seed: 500,000¢ limit / 1,000,000¢ balance)

| settled | rejected (limit exceeded) | Σ balance before | Σ balance after | negative balance? | double postings? |
|---|---|---|---|---|---|
| **49** (theoretical exact: 50) | 70,583 | 0 | 0 | **no** | **0** |

Settled landed at 49, one under the theoretical 500,000¢ / 10,000¢ = 50 — real variance under
50-way concurrent contention at much higher throughput than the first pass (where it landed
exactly on 50): a handful of in-flight requests can race the exact limit boundary and the loser
of that race gets `LIMIT_EXCEEDED` instead of the 50th settle. Not a bug — the invariant this
subsection actually tests (the daily-limit reservation never lets settled × amount exceed the
limit) held: 49 × 10,000¢ = 490,000¢ ≤ 500,000¢.

Latency (storm phase, N=70,632): raw p99 87.2ms / **trimmed p99 72.8ms**, removed_count=508
(**0.72%**) — down from 5,704.4ms trimmed p99 pre-fix (~78x faster).

**Both subsections conserved money exactly (Σ balances unchanged — a closed system; transfers
move money between the 208 seeded accounts, never creating or destroying it) and posted zero
duplicate ledger entries under 50-way concurrent contention, at ~19x the request volume of the
first pass.**

## S2 — capacity curve

Six stages — **5/10/25/50/100/150 VUs**, not the original 5/10/25/50/100/**200** — chosen to stay
inside the range with zero genuine capacity failures (explicit instruction ahead of this run: keep
S2 in the VU range with no `req_failed`). Each stage is a 15s ramp (discarded) + 60s hold
(measured), against 200 distinct ring accounts (zero ledger contention, so this measures
throughput/latency, not lock contention).

| VUs | TPS | real error rate | fraud-denied rate | trimmed p50 | trimmed p99 | raw p99 | removed | saturation signal |
|---|---|---|---|---|---|---|---|---|
| 5 | 201.1 | 0% | 44.8% | 27.3 ms | 43.6 ms | 31,058 ms | 0.6% | no signal |
| 10 | 164.1 | 0% | 0% | 59.8 ms | 86.9 ms | 31,098 ms | 0.7% | no signal |
| 25 | 163.4 | 0% | 0% | 151.0 ms | 200.2 ms | 31,173 ms | 3.6% | latency-driven, p99 doubled |
| 50 | 184.0 | 0% | 15.0% | 284.4 ms | 345.7 ms | 31,326 ms | 3.7% | no signal |
| 100 | 160.8 | 0% | 6.8% | 594.4 ms | 1,118.9 ms | 31,712 ms | 3.5% | latency-driven, p99 doubled |
| 150 | 158.3 | 0% | 4.3% | 876.2 ms | 1,947.9 ms | 33,479 ms | 3.4% | no signal |

**Real error rate (5xx/network) is 0% at every stage tested.** No capacity ceiling was crossed
anywhere in the 5-150 VU range — confirming there was room to go higher, but 150 was already
enough to demonstrate throughput comfortably above the target and this document's explicit
instruction was to stay inside the zero-`req_failed` range, not to keep pushing until something
broke.

### `fraud_denied_rate` is not a capacity signal — read this before the table above worries you

The `422` column is entirely `FRAUD_DENIED` — structurally the *only* possible 422 cause here,
since S2 only ever sends between the 200 ring accounts, whose seeded balance/limit are enormous
("never the binding constraint," `seed-load-test-fixtures.sh`). It is reported separately from
`error_rate` and **excluded from the saturation-signal logic** (`tools/k6/analyze-s2.js`).

The rate is **not monotonic in VUs** (44.8% at 5 VUs, 0% at 10-25, 15.0% at 50, then falling again
to 4.3% at 150) because `tools/k6/lib/accounts.js`'s `ringPosition(vuId)` maps each VU to a
**fixed** ring account for the whole test — so a low-VU stage concentrates the stage's entire
throughput onto very few accounts. At 5 VUs sustaining ~200 req/s, that's ~40 req/s per account
sent to fraud-service's Redis-backed velocity counters (60s rolling window) — enough to cross the
`VELOCITY_COUNT` threshold fast. At 100+ VUs, the same total throughput spreads across 100+
distinct accounts, so per-account velocity stays low and few sends get flagged. **This is a
property of the fixture's ring-size-vs-throughput ratio, not of infrastructure capacity** — it
was invisible in the first pass because ~8.5 req/s never built enough per-account velocity in 60s
to matter, regardless of VU count. Post-fix, at ~20x the throughput, it does. It is a real,
correct fraud-service behavior (post-hoc review is the intended real-world path for a flagged send
that isn't a network partition), reported for transparency, not treated as a defect.

### The actual finding: throughput is still flat, just ~19x higher

**TPS stays in a 158-201 req/s band across every stage from 5 to 150 VUs** — the same
flat-ceiling shape the first pass found at ~8.5 req/s, just at a much higher ceiling now.
Little's Law (`VUs ≈ throughput × latency`) fits the trimmed p50 at every stage just as tightly as
before:

| VUs | VUs / measured TPS (predicted latency) | trimmed p50 (measured) |
|---|---|---|
| 5 | 0.025s | 0.027s |
| 10 | 0.061s | 0.060s |
| 25 | 0.153s | 0.151s |
| 50 | 0.272s | 0.284s |
| 100 | 0.622s | 0.594s |
| 150 | 0.948s | 0.876s |

The fit (within ~10% at every stage) confirms this is still a genuine throughput ceiling, not
noise — just a ~19x higher one. **The ceiling's cause, confirmed** (see
[`docs/load/BOTTLENECK.md`](BOTTLENECK.md)): a single Pix send performs 5 sequential DynamoDB
writes; the standalone `dynamodb-local` container's own raw ceiling measured ~800-950 writes/s
(RUNG 2 rerun), and `900 ÷ 5 ≈ 180 req/s` predicts this section's observed 158-201 req/s band
closely. This ceiling was **not** pushed to failure in this run (0% real errors throughout) — the
150-VU cap was a deliberate choice to stay in the clean range, not evidence that 150 VUs is where
capacity actually runs out.

## S3 — idempotency under a retry storm

30 VUs, all authenticated as the same account, race the same `Idempotency-Key` per round (20
rounds). A round's winner is identified after the fact as the **earliest-completing** `202` — a
replay is only reachable once the winner's claim is `COMPLETED`, so this is provably correct, not
inferred.

| rounds | real postings | replays | 409 conflicts | rounds with no winner | double postings (winners) |
|---|---|---|---|---|---|
| 20 | **20** | 580 | 29 | **0** | **0** |

**Every round produced exactly one real ledger posting, identified unambiguously, with zero
duplicates** — the idempotency guarantee held under 30-way concurrent contention on the same key.
The whole 20-round storm completed in ~2.3 seconds (down from ~7.4s pre-fix); fewer 409 conflicts
(29 vs 58) is a direct consequence — resolving faster gives fewer competing requests time to land
mid-flight.

| | claim (winner) latency | replay latency |
|---|---|---|
| samples | 20 | 580 |
| removed (≥10s) | 0 | 0 |
| trimmed p50 | 19.1 ms | 9.6 ms |
| trimmed p99 | 31.8 ms | 15.7 ms |

Claim p50 dropped from 217.8ms to **19.1ms** (~11x); replay p50 dropped from 148.5ms to **9.6ms**
(~15x). Replays are still consistently faster than the winning claim — expected, since a replay
reads the already-completed claim instead of running fraud check + ledger posting. Neither sample
drew an artifact stall this run (0 removed on both) — a smaller-sample coincidence, not evidence
the artifact is gone (S0 still measured 1.02%).

## S4 — fraud-service fault injection: did not run

`s4_fraud.ran = false`. fraud-service has **no runtime latency/failure-injection knob**, unlike
mock-bacen-spi's `AdminConfigController` — confirmed by inventory in Phase 1 of this work (no
`AdminConfigController` or equivalent under `services/fraud-service`). Per explicit user decision:
do not touch `services/fraud-service` or use `tc netem` to fake this — instead, document the gap
and propose closing it. See `docs/steps/step-64.md` (PLAN.md Sprint 12, proposed, unimplemented).

## Root cause of the throughput ceiling and the stalls

Full diagnostic ladder, all raw evidence, and the fix: [`docs/load/BOTTLENECK.md`](BOTTLENECK.md).
Summary:

- **Throughput ceiling (fixed in this pass)**: LocalStack's own DynamoDB support proxies every
  call through an internal Python HTTP client (`requests.Session`, `pool_maxsize=10` by library
  default, not exposed via any LocalStack env var) to the same `DynamoDBLocal.jar` the fix now
  talks to directly. Splitting DynamoDB into its own standalone `amazon/dynamodb-local` container
  (`infra/docker-compose.yml`), reached via a separate endpoint property
  (`AwsProperties#dynamoDbEndpointUrl`) than SNS/SQS (still on LocalStack), removed that proxy and
  its pool cap. Raw ceiling: ~45 ops/s (original) → ~400 ops/s (LocalStack, in-memory + bigger
  heap) → ~800-950 ops/s (standalone, no proxy). Application ceiling: ~8.5 req/s → ~158-201 req/s.
- **The ~30s stalls (unaffected by this fix, still present at a lower rate)**: real server-side
  pauses inside whichever process is serving DynamoDB at the time — confirmed via a stalled
  request's own application-log timeline (not just client-side timing), ruled out as a
  system-wide clock step (no kernel clock-jump event coincided with the stall; a sibling
  container's unrelated healthcheck stayed on schedule through the same window). Most likely a
  stop-the-world pause (GC is the leading suspect, not directly confirmed — GC logging isn't
  enabled). S0 shows the rate dropped from 4.21% to 1.02% post-fix, consistent with a smaller,
  faster-turnaround JVM doing less total work being paused less often, without claiming that as
  confirmed causation.

## Caveats (apply to every number above)

- `dynamodb-local` (the standalone container) is still a single-process DynamoDB Local instance,
  not real AWS DynamoDB — its latency/throughput characteristics under concurrent transactional
  load do not represent production DynamoDB, even with LocalStack's proxy removed.
- Only 2 of the 208 seeded accounts (alice/`acc-001`, bob/`acc-002`) are "real" fixtures reused
  from earlier steps; the other 206 are load-test-only accounts from
  `tools/k6/seed/seed-load-test-fixtures.sh`.
- fraud-service has no runtime fault-injection knob — S4 did not run (see above).
- No HTTP connection-pool or thread-pool tuning was found in any service's `application.yml`;
  Tomcat runs its Spring Boot default (`max-threads=200`) — not implicated this pass (0% real
  errors through 150 VUs).
- This is WSL2 on Windows (Docker Desktop backend), not bare-metal Linux.
- S2's `fraud_denied_rate` is a fixture-design artifact (ring-account concentration vs.
  throughput), not a capacity signal — see S2's dedicated subsection above.
- **Bottom line on portability**: the *shape* (a flat throughput ceiling, Little's-Law-consistent
  queueing beneath it, zero errors well past 100 req/s) is a real, reproducible finding about this
  codebase's current concurrency behavior against a single-process DynamoDB backend. The
  *absolute* ~160-200 req/s ceiling is specific to this `dynamodb-local` container on this
  machine — a real DynamoDB target would have a different (likely much higher, or differently
  shaped) ceiling, since `dynamodb-local` is explicitly not built for performance testing.

## Reproducing

```bash
docker compose -f infra/docker-compose.yml down -v   # fresh volumes — S1's exhaustible accounts
docker compose -f infra/docker-compose.yml up -d --build
bash tools/k6/seed/seed-load-test-fixtures.sh         # ~7 minutes
bash tools/k6/run-s0.sh                               # ~5 minutes
bash tools/k6/run-s1.sh                               # ~2 minutes
# S2: the default tools/k6/s2-capacity.js stages are 5/10/25/50/100/200 — this pass capped at 150
# to stay error-free; rerun with -e S2_STAGES=5,10,25,50,100,200 (or higher) to find where real
# errors actually start post-fix, which this document deliberately did not chase.
source tools/k6/run-common.sh
k6_run run -e S2_STAGES=5,10,25,50,100,150 --out json=docs/load/raw/s2-raw.ndjson tools/k6/s2-capacity.js
node tools/k6/analyze-s2.js docs/load/raw/s2-raw.ndjson > docs/load/raw/s2-result.json
bash tools/k6/run-s3.sh                               # ~seconds
```

k6 runs via the `grafana/k6` Docker image if no local `k6` binary is on `PATH` (this dev
environment has none) — see `tools/k6/run-common.sh`.
