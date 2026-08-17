# Load measurement — "the price of correctness"

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

## S0 — artifact floor (read this section first)

Before trusting any latency number below, S0 asks: does this specific machine stall on requests
even with **essentially no load**? 1 VU, ~1 request/second, 5 minutes, same
`POST /v1/payments/pix` endpoint S2 uses.

| | value |
|---|---|
| samples | 261 |
| stalled (≥10s) | 11 (**4.21%**) |
| p50 (trimmed) | 143.7 ms |
| p99 (trimmed) | 202.8 ms |
| p99 (raw, includes stalls) | 30,458.7 ms |
| max (raw) | 30,507.8 ms |

At 1 VU there is no concurrency to saturate — this can only be an environment artifact, not an
application capacity signal. **4.21% of requests stalled ~30s regardless of load.** That is the
evidence that licenses trimming this artifact out of S1/S2/S3's latency figures instead of
reading it as a capacity finding. Full explanation: [Environment limitation](#environment-limitation-wsl2-clock-drift).

## Trimming rule (applies to every latency number below)

**Threshold: 10,000 ms.** Any single request duration at or above this is classified as a
clock-jump artifact and excluded from the `trimmed` figures; `raw` figures (which include it) are
always reported alongside — nothing is trimmed silently. `removed_count` / `removed_rate` is
stated for every stage/subsection so a reader can see exactly how many samples the rule affected
and recompute with a different threshold from `docs/load/raw/*.ndjson` if they disagree.

**Why 10,000ms exactly**: before running any load test, the pre-existing dry-run artifacts
(`docs/load/artifacts/s2-dry-run-*.ndjson`) showed a clean bimodal split — every sample fell
either under ~2.5s or in a 30-33s band (matching `timedatectl`'s observed ~-30s clock offset).
10,000ms sits in the **empty gap** between those two populations, so at low concurrency the split
is exact, not a percentile guess.

**Where the rule breaks down**: at S2's `vus=100` and `vus=200` stages, `removed_rate` jumps to
71.8% and 83.0% (versus 4-8% everywhere else, matching S0's 4.21% baseline). That jump itself is
a finding — real queueing latency has grown large enough to overlap and exceed the 10s threshold,
so the trim stops separating "artifact" from "real" and starts just clipping the top of a
genuinely-growing distribution. **`trimmed.*` at those two stages is not meaningful — read
`raw.*`** (aware it still carries the ~5% baseline artifact on top of genuine saturation).

## S1 — conservation under contention

50 VUs fire fresh-idempotency-key sends at ONE account for 60s (30s warm-up against a
richly-funded ring account, discarded, precedes it). Two subsections, because this repo's seed
data makes the daily-limit counter bind before the ledger balance ever could for **any** transfer
amount (alice's 500,000¢ limit is half her 1,000,000¢ balance) — rather than pick one invariant to
prove, both run.

### balance-guard (`acc-lt-s1bal`, funded for exactly 10 successes at R$100/send)

| settled | rejected (insufficient funds) | other (fraud-denied) | Σ balance before | Σ balance after | negative balance? | double postings? |
|---|---|---|---|---|---|---|
| **10** (exact) | 194 | 676 | 0 | 0 | **no** | **0** |

`other_errors=676` is entirely `FRAUD_DENIED` (422) — fraud-service has a nonzero baseline
denial rate independent of load, confirmed by grepping `docs/load/raw/s1-balance.log`; it's real
system behavior, not a measurement bug, and it explains why `settled + rejected_insufficient_funds
≠ total requests`.

Latency (storm phase, N=880): raw p99 33,927.6ms / **trimmed p99 5,307.4ms**, removed_count=48
(**5.45%**) — matches the S0 baseline band.

### limit-guard (`acc-001`/alice, unchanged seed: 500,000¢ limit / 1,000,000¢ balance)

| settled | rejected (limit exceeded) | Σ balance before | Σ balance after | negative balance? | double postings? |
|---|---|---|---|---|---|
| **50** (exact — 500,000¢ / 10,000¢) | 2314 | 0 | 0 | **no** | **0** |

Latency (storm phase, N=2364): raw p99 31,769.9ms / **trimmed p99 5,704.4ms**, removed_count=174
(**7.36%**) — same band.

**Both subsections hit their invariant boundary at exactly the predicted N, conserved money
exactly (Σ balances unchanged; this is a closed system — transfers move money between the 208
seeded accounts, they never create or destroy it), and posted zero duplicate ledger entries under
50-way concurrent contention.**

## S2 — capacity curve

Six stages (5/10/25/50/100/200 VUs), each a 15s ramp (discarded) + 60s hold (measured), against
200 distinct ring accounts (zero ledger contention, so this measures throughput/latency, not
lock contention).

| VUs | TPS | error rate | trimmed p50 | trimmed p99 | raw p99 | removed | saturation signal |
|---|---|---|---|---|---|---|---|
| 5 | 8.25 | 0% | 605 ms | 736 ms | 31,020 ms | 5.7% | already elevated at lowest stage |
| 10 | 8.42 | 0% | 1,195 ms | 1,728 ms | 31,576 ms | 5.2% | latency-driven, p99 doubled |
| 25 | 8.67 | 0% | 2,960 ms | 3,578 ms | 33,395 ms | 8.1% | latency-driven, p99 doubled |
| 50 | 8.85 | 0% | 6,006 ms | 7,423 ms | 36,430 ms | 4.9% | latency-driven, p99 doubled |
| 100 | 8.82 | **1.51%** | 9,170 ms † | 9,988 ms † | 43,966 ms | **71.8%** | **error-driven: capacity exceeded** |
| 200 | 8.63 | **25.1%** | 0.17 ms † | 9,955 ms † | 62,073 ms | **83.0%** | **error-driven: capacity exceeded** |

† trim rule invalid at this stage (see above) — treat as illustrative, not measurement; read the
raw column instead.

### The actual finding: throughput is flat, not the p99

**TPS stays in an 8.25–8.85 req/s band at every stage from 5 to 200 VUs.** That is not
five separate measurements of "how much load the system can take" — it's the same ceiling,
measured five times. Applying Little's Law (`VUs ≈ throughput × latency`) to the trimmed p50 at
each stage confirms it almost exactly:

| VUs | VUs / measured TPS (predicted latency) | trimmed p50 (measured) |
|---|---|---|
| 5 | 0.61s | 0.605s |
| 10 | 1.19s | 1.195s |
| 25 | 2.88s | 2.960s |
| 50 | 5.65s | 6.006s |
| 200 | 23.17s | 22.04s (**raw** p50, trim invalid here) |

The fit is tight enough (within ~6% at every stage) that this isn't a coincidence: **this
environment's Pix-send path has a hard throughput ceiling around 8.5-8.9 req/s, and it is already
at that ceiling at the lowest stage tested (5 VUs).** Every VU added beyond what's needed to
saturate ~8.5 req/s buys nothing but queueing delay — until, around 100 VUs of concurrent
in-flight requests, the queue itself starts producing outright failures (HTTP 500, `error_rate`
jumping from 0% to 1.5% to 25.1%).

**Capacity-knee finding, stated plainly**: the knee isn't a curve that bends gently upward and
then breaks — it's already flat from the first stage measured. Whether the true ceiling is above
5 VUs' worth of concurrency (untested — no stage below 5 was run) is unknown; what's confirmed is
that 5 VUs already saturates it. The **shape** of this (flat-then-failing) is a real, portable
finding. The **absolute** 8.5-8.9 req/s number is not portable off this WSL2/LocalStack/dev-machine
combination — see caveats below.

**Leading suspects for the ceiling** (not confirmed — no thread-pool or DynamoDB-internal metrics
were collected in this pass): LocalStack's DynamoDB emulator is single-process, and every Pix send
does several sequential transactional DynamoDB calls (idempotency claim, ledger double-entry
posting, fraud check); no connection-pool or thread-pool tuning was found in any service's
`application.yml`, so Tomcat runs its Spring Boot default `max-threads=200` — which numerically
coincides with where errors start (100-200 VUs), a plausible but unconfirmed contributor.

## S3 — idempotency under a retry storm

30 VUs, all authenticated as the same account, race the same `Idempotency-Key` per round (20
rounds). A round's winner is identified after the fact as the **earliest-completing** `202` — a
replay is only reachable once the winner's claim is `COMPLETED`, so this is provably correct, not
inferred.

| rounds | real postings | replays | 409 conflicts | rounds with no winner | double postings (winners) |
|---|---|---|---|---|---|
| 20 | **20** | 580 | 58 | **0** | **0** |

**Every round produced exactly one real ledger posting, identified unambiguously, with zero
duplicates** — the idempotency guarantee held under 30-way concurrent contention on the same key.

| | claim (winner) latency | replay latency |
|---|---|---|
| samples | 20 | 580 |
| removed (≥10s) | 0 | 29 (**5.0%** — matches S0 baseline) |
| trimmed p50 | 217.8 ms | 148.5 ms |
| trimmed p99 | 678.2 ms | 196.4 ms |
| raw p99 | 678.2 ms | 30,526.6 ms |

Replays are consistently faster than the winning claim (148.5ms vs 217.8ms trimmed p50) — expected,
since a replay reads the already-completed claim instead of running fraud check + ledger posting.
The claim-latency sample (N=20) happened not to draw an artifact stall this run; the replay sample
(N=580, larger) did, at the same ~5% rate as everywhere else.

## S4 — fraud-service fault injection: did not run

`s4_fraud.ran = false`. fraud-service has **no runtime latency/failure-injection knob**, unlike
mock-bacen-spi's `AdminConfigController` — confirmed by inventory in Phase 1 of this work (no
`AdminConfigController` or equivalent under `services/fraud-service`). Per explicit user decision:
do not touch `services/fraud-service` or use `tc netem` to fake this — instead, document the gap
and propose closing it. See `docs/steps/step-64.md` (PLAN.md Sprint 12, proposed, unimplemented).

## Environment limitation: WSL2 clock drift

`journalctl -k` shows recurring `"Time jumped backwards, rotating"`, and `timedatectl
timesync-status` reports `System clock synchronized: no` with an offset around **-30s**, even
after a full `wsl --shutdown` from the Windows host (offset and jitter were essentially unchanged
before/after). `sudo systemctl restart systemd-timesyncd` also did not resolve it. This is a
property of the WSL2 VM's clocksource, not of NTP being stale.

**What this rules out as the cause of the ~30s stalls**: `http_req_blocked` stayed under 0.5ms on
every sample (rules out k6 client-side/connection-level causes — the client isn't waiting to open
a connection). Every stalled request in the dry runs still returned `202` (rules out an
application-level failure — the request succeeds, just 30s late).

**Decision**: per explicit instruction, further root-cause investigation of the clock itself was
stopped here — the goal was characterised data today, not a clean environment next week. The
effect is characterised: load-independent (S0: 4.21% at 1 VU with no concurrency), consistent
magnitude across S1/S2's low stages/S3 (4.2%-8.1%), ~30-33s duration, zero effect on correctness
(every stalled request still completed and posted correctly).

## Caveats (apply to every number above)

- LocalStack's DynamoDB emulator is single-process, not real AWS DynamoDB — its latency behavior
  under concurrent transactional load does not represent production DynamoDB.
- Only 2 of the 208 seeded accounts (alice/`acc-001`, bob/`acc-002`) are "real" fixtures reused
  from earlier steps; the other 206 are load-test-only accounts from
  `tools/k6/seed/seed-load-test-fixtures.sh`.
- fraud-service has no runtime fault-injection knob — S4 did not run (see above).
- No HTTP connection-pool or thread-pool tuning was found in any service's `application.yml`;
  Tomcat runs its Spring Boot default (`max-threads=200`).
- This is WSL2 on Windows, not bare-metal Linux — both the CPU/IO scheduling characteristics and
  the clock issue above are properties of that virtualization layer, not of the application.
- **Bottom line on portability**: the *shape* of the capacity curve (flat throughput ceiling,
  errors appearing once queueing crosses a depth around 100 concurrent requests) is a real,
  reproducible finding about this codebase's current concurrency behavior. The *absolute* TPS and
  latency numbers are specific to this WSL2 VM on this day and are not the numbers to quote as
  "this system does N req/s" — for that, PLAN.md Step 47's full k6 suite against a more
  representative environment (or real AWS) is the right follow-up, not this deliverable.

## Reproducing

```bash
docker compose -f infra/docker-compose.yml down -v   # fresh volumes — S1's exhaustible accounts
docker compose -f infra/docker-compose.yml up -d --build
bash tools/k6/seed/seed-load-test-fixtures.sh         # ~7 minutes
bash tools/k6/run-s0.sh                               # ~5 minutes
bash tools/k6/run-s1.sh                               # ~3 minutes
bash tools/k6/run-s2.sh                               # ~9 minutes
bash tools/k6/run-s3.sh                               # seconds
```

k6 runs via the `grafana/k6` Docker image if no local `k6` binary is on `PATH` (this dev
environment has none) — see `tools/k6/run-common.sh`.

### A screenshot-worthy S2 summary

```bash
K6_FORCE_DOCKER=1 bash -c 'source tools/k6/run-common.sh && \
  k6_run run --out json=docs/load/raw/s2-raw.ndjson tools/k6/s2-capacity.js'
```

### The most defensible number for an interview

Not "8.6 TPS" alone — say: *"On a LocalStack/WSL2 dev stack, this system's Pix-send path holds a
flat ~8.5 req/s throughput ceiling from 5 concurrent clients up through 50, with p50 latency
tracking Little's Law almost exactly (queueing delay ≈ concurrency / 8.6 req/s) — meaning the
bottleneck is a fixed-capacity resource, not raw compute, and it's saturated well before any
realistic single-dev-machine concurrency. Errors only start once ~100 requests are queued
simultaneously. I did not chase the exact bottleneck (leading suspect: LocalStack's
single-process DynamoDB emulator, since no connection-pool tuning exists anywhere in the
codebase) — that's the next thing I'd instrument."* That's a claim about *behavior under
Little's-Law-verified queueing*, not a raw number — and it survives someone asking "did you
control for X" for every X in the caveats section above.
