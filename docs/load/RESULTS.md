# Load measurement

A self-contained load-measurement deliverable, scoped smaller than PLAN.md's Step 47 (which
covers the full k6 low/standard/Black-Friday SLO suite). Its purpose in this pass is **not**
throughput — it is producing credible, defensible evidence for the ledger's **correctness
guarantees** under real concurrent traffic against the docker-compose stack: atomic double-entry,
conservation of money (synchronous *and* asynchronous), non-negative balance under contention,
exact daily-limit reservation, and idempotency. Throughput is **context**, reported later, not the
headline.

**Method discipline this pass.** Every expected value was written down in
[`EXPECTATIONS.md`](EXPECTATIONS.md) and **committed before the run**, derived from the code and
seed data, not from any previous run. The [verdict table](#verdict--expected-vs-observed) below
compares observed against those pre-registered expectations; a FAIL is reported as a FAIL, never
reconciled by adjusting the expectation afterward.

Machine-readable numbers: [`docs/load/results.json`](results.json). Raw k6/jq artifacts:
`docs/load/raw/` (large ndjson is git-ignored; regenerate with `tools/k6/run-s*.sh`). All scripts:
`tools/k6/`.

**Run metadata.** Third pass — a clean re-run on a freshly reseeded environment (`docker compose
down -v` → rebuild → reseed). **Calendar day: 2026-08-17, America/Sao_Paulo** (the daily-limit's
own zone, `SendPixUseCase.LIMIT_ZONE`; the whole run stayed within one calendar day). Before the
run, the whole-table `Σ balanceCents = 0` across 208 accounts and alice's
`LIMIT#acc-001/DAY#2026-08-17` counter was **absent** (zero same-day consumption) — the clean
starting state the limit-guard derivation requires.

Which account each scenario ran against (recorded so same-day limit consumption is traceable
without guesswork):

| Scenario | Account(s) |
|---|---|
| S0 | `acc-lt-001` (ring position 1) |
| S1 balance-guard | `acc-lt-s1bal` |
| S1 limit-guard | `acc-001` (alice) |
| S2 | `acc-lt-001` … `acc-lt-200` (ring) |
| S3 | `acc-lt-001` (a ring account — **distinct** from S1's `acc-001`/alice) |
| S5 / S5b | ring accounts (senders); `bob@otherbank.com` / `carol@otherbank.com` (external destinations) |

---

## Final verdict — concept → validation

The one-line answer: **every core correctness concept the platform is built on was exercised under
concurrent load and held, with the evidence backing each drawn from the raw artifacts, not asserted.**

| # | Concept | Validated under load? | Evidence (numbers observed) |
|---|---|---|---|
| 1 | **Atomic double-entry** — never a debit without its credit | ✅ | 82,663 txIds / 165,326 entries = exactly 2 each; **0** orphaned legs, **0** duplicates, **0** malformed |
| 2 | **Conservation of money (internal, synchronous)** | ✅ | S1: whole-table Σ delta **0**, **0** negative accounts |
| 3 | **Conservation of money (external, async — settlement)** | ✅ | S5: 515 sends → 515 SETTLED, Σ delta **0**, clearing 0→0, SPI_SETTLED delta 515,000¢ = 515×1,000¢ |
| 4 | **Conservation of money (external, async — reversal)** | ✅ | S5b: 541 → 271 SETTLED + 270 REVERSED; **270/270** reversals returned the exact amount to their **own** payer; clearing 0→0; Σ delta **0** |
| 5 | **Non-negative balance under contention** | ✅ | S1: funded-for-10 account settled **exactly 10** under 50 VUs; **0** negative balances |
| 6 | **Exact, leak-free daily-limit reservation** | ✅ | S1: settled **exactly 50** = 500,000¢/10,000¢ on a counter verified absent; no leak (resolves the prior run's 49) |
| 7 | **Idempotency under a retry storm** | ✅ | S3: 20 rounds × 30 clients on one key → **exactly 20** real postings, **0** double postings, **0** rounds with no winner |
| — | Throughput (context, not the headline) | measured | flat ~150–172 req/s, **0** real 5xx/network errors through 150 VUs (S2) |

Full expected-vs-observed breakdown per invariant is in the [verdict table](#verdict--expected-vs-observed)
below; every row is **PASS**, and no money-safety invariant failed.

---

## What was proven

Each claim below is backed by an exact artifact, and where the instruction was "assert from data,
not from an absent error counter," the query and its result are shown in the scenario section.

1. **Atomic double-entry — never a half-posting.** A whole-table scan of `pix_ledger` after all
   scenarios found **82,663 distinct txIds across 165,326 ENTRY items = exactly 2 per txId**, every
   one a healthy `{DEBIT, CREDIT}` pair: **0 orphaned legs, 0 duplicate postings, 0 malformed
   pairs** (`docs/load/raw/ledger-integrity-final.json`). This is a positive assertion over the
   real data, not the absence of an error.

2. **Conservation of money, synchronous path.** S1 (both subsections): whole-table `Σ balanceCents
   = 0` before and after (delta 0), 0 accounts negative — a closed system across all 208 accounts.

3. **Conservation of money, asynchronous path — both branches** (the harder claim, and the one
   worth publishing). Money parked in clearing at accept-time always leaves it: to `SPI_SETTLED` on
   settlement **or back to the payer on reversal**, never stranded.
   - *Settlement branch* (S5, no fault injection): 515 external sends walked debit → clearing →
     outbox → SNS → SQS → settlement → SETTLED; `Σ` delta 0; `SPI_CLEARING` 0→0; `SPI_SETTLED` delta
     = **515,000¢ = 515 × 1,000¢**; no orphaned clearing money.
   - *Reversal branch* (S5b, mock-bacen `reject-keys` + `failure-rate 0.2`): 541 accepted → **271
     SETTLED + 270 REVERSED**, 0 in flight; `Σ` delta 0; `SPI_CLEARING` 0→0; `SPI_SETTLED` delta =
     **271,000¢ = settled × 1,000¢ only** (a reversal credits the *payer*, not `SPI_SETTLED`); and
     **per transaction, all 270 reversals returned the exact original amount to their own payer**
     (270/270, 0 mismatches), each a complete `debit clearing / credit payer` pair.

4. **Non-negative balance under contention.** S1 balance-guard: an account funded for exactly 10
   sends settled **exactly 10** under a 50-VU storm, the other 11,586 attempts refused
   `INSUFFICIENT_FUNDS`, 0 negative balances.

5. **Exact, leak-free daily-limit reservation.** S1 limit-guard on a freshly reseeded alice
   (counter confirmed absent) settled **exactly 50** = 500,000¢ / 10,000¢; her counter read exactly
   500,000¢ afterward. This **resolves the prior run's 49** as leftover same-day usage, not a
   reservation leak (see [the 49-vs-50 resolution](#the-49-vs-50-question-resolved)).

6. **Idempotency under a retry storm.** S3: 30 clients racing one `Idempotency-Key` per round × 20
   rounds produced **exactly 20 real postings** (one per round), 580 byte-identical replays, 29
   in-progress 409s, **0 rounds with no winner, 0 double postings**.

---

## Verdict — expected vs observed

Expectations are from [`EXPECTATIONS.md`](EXPECTATIONS.md), committed before the run.

| Invariant | Expected | Observed | Result |
|---|---|---|---|
| S1 balance-guard settled | 10 (`100,000¢ / 10,000¢`) | 10 | **PASS** |
| S1 limit-guard settled | 50 (`500,000¢ / 10,000¢`, counter absent) | 50 | **PASS** |
| S1 conservation (Σ delta, both subsections) | 0 | 0 | **PASS** |
| S1 negative balances | 0 | 0 | **PASS** |
| S1 duplicate / orphaned postings (whole-ledger query) | 0 | 0 | **PASS** |
| S3 real postings per round | 1 | 1 | **PASS** |
| S3 total real postings (20 rounds) | 20 | 20 | **PASS** |
| S3 rounds with no winner | 0 | 0 | **PASS** |
| S3 double postings on winners | 0 | 0 | **PASS** |
| S5 accepted sends reaching SETTLED | all (515) | 515 | **PASS** |
| S5 REVERSED / still-in-flight at final snapshot | 0 | 0 | **PASS** |
| S5 conservation (Σ delta incl. clearing) | 0 | 0 | **PASS** |
| S5 orphaned clearing money | none | none | **PASS** |

Every pre-registered money-safety invariant passed. No FAIL to report.

### The 49-vs-50 question, resolved

The previous pass settled **49** transfers against alice's 500,000¢ limit at 10,000¢ each, one
under the arithmetic 50, and its writeup argued *by elimination* that this was leftover same-day
usage rather than a reservation leak. The decision rule pre-registered in `EXPECTATIONS.md` was:

> limit-guard settling 50 confirms the prior run's 49 was leftover same-day usage. Settling 49
> again on a freshly reseeded environment rules that out and makes a reservation leak the leading
> hypothesis — investigate before any of these numbers are published.

**This run settled exactly 50**, on a freshly reseeded environment where alice's
`LIMIT#acc-001/DAY#2026-08-17` counter was **directly confirmed absent before the run** and read
exactly **500,000¢** after (= 50 × 10,000¢). Per the rule, this **confirms the prior 49 was
leftover same-day consumption** — a request against alice earlier that same calendar day — and
**rules out a reservation leak**.

*Limitation, stated honestly:* the prior run's DynamoDB volumes were already destroyed before this
audit began, so the leftover-usage hypothesis could not be confirmed against *that* run's counter
directly — it rests on (a) this run's clean 50 from a verified-absent counter and (b) the code
review that shows `DynamoDailyLimitReservation.reserve` is a single conditional `UpdateItem` with
no read-then-write race window and no failure path that takes a reservation without releasing it.
The two together are conclusive for *this* run; the prior run's exact prior request is
unrecoverable and is not claimed with more certainty than that.

---

## S0 — artifact floor (read this section first)

Before trusting any latency number, S0 asks: does this machine stall even with **essentially no
load**? 1 VU, ~1 req/s, 5 minutes, same `POST /v1/payments/pix` endpoint S2 uses, against
`acc-lt-001`.

| | value |
|---|---|
| samples | 288 |
| stalled (≥10s) | 3 (**1.04%**) |
| **p99, raw (headline)** | **31,317.2 ms** |
| **max, raw** | **31,329.8 ms** |
| p50, trimmed (secondary) | 39.8 ms |
| p99, trimmed (secondary) | 61.7 ms |
| statuses | 288 × `202` |

At 1 VU there is no concurrency to saturate — a stall here can only be the environment.
**What a stall means for a user: the request still succeeds — the same `202 Accepted` — it just
arrives ~31 seconds late.** Money safety is unaffected, but a 31-second `202` blows the platform's
own <2s p99 send target (ARCHITECTURE §1.2) on ~1% of requests, and this stall recurs throughout
the report. All 288 S0 requests returned `202` (no fraud denials — at 1 req/s the velocity rule
reaches `REVIEW`, which still approves).

## Trimming rule (applies to every latency number)

**Raw is the headline figure everywhere; trimmed is a secondary, clearly-labelled view, never
quoted alone.** `docs/load/BOTTLENECK.md` RUNG 4 confirmed the ~31s stalls are **real,
application-visible latency** (from the server's own log timestamps, not client timing), so they
are not a measurement error to be "corrected" away. Trimmed (excluding samples ≥10,000 ms) isolates
what the *application code* does once this one confirmed, environment-local defect is set aside —
useful for the S2 saturation-shape analysis and for comparing against a future run on
non-defective infrastructure. Raw is reported alongside it every time, never omitted.
`removed_count` / `removed_rate` is stated per stage so a reader can recompute from
`docs/load/raw/*.ndjson` with a different threshold.

`removed_rate` stayed in a **0–4% band** across S0/S1/S2/S3 this pass — but 0–4% is not
negligible: it means 0–4% of *every* latency figure below is a request that genuinely took ~31s.

---

## S1 — conservation under contention

50 VUs fire fresh-idempotency-key sends at ONE account for 60s (a 30s warm-up against a
richly-funded ring account precedes it, discarded). Two subsections, because this repo's seed data
makes the daily-limit counter bind before the ledger balance ever could for alice — so rather than
pick one invariant, both run: one account chosen to bind on **balance**, one (alice) on **limit**.

**S1 ran under the fraud-service `loadtest` profile** ([see below](#fraud-scoring-under-load--the-loadtest-profile)):
without it, ~99% of a single-account storm is `FRAUD_DENIED` by the velocity rule and the storm
measures fraud instead of the ledger. The profile raises only the two velocity thresholds; every
other fraud rule stays active.

### balance-guard (`acc-lt-s1bal`, funded for exactly 10 successes at R$100/send)

| settled | rejected (insufficient funds) | fraud-denied | Σ balance before | Σ balance after | negative balance? | double postings? |
|---|---|---|---|---|---|---|
| **10** (expected 10 ✓) | 11,586 | 0 | 0 | 0 | **no** | **0** |

`other_errors = 0` confirms the `loadtest` profile did its job: the storm reached the ledger's
balance guard instead of being turned away by fraud. The 11,586 `INSUFFICIENT_FUNDS` rejections (up
from 194 last pass, when fraud was denying ~99%) are exactly the requests that now reach the balance
check and are correctly refused once the account is drained.

Latency (storm phase, N=11,596): **raw p99 31,589.1 ms** (headline — 3.54% of this window stalled,
enough to push the p99th sample into stall territory) / trimmed p99 348.6 ms (secondary),
removed 411 (**3.54%**).

### limit-guard (`acc-001`/alice, unchanged seed: 500,000¢ limit / 1,000,000¢ balance)

| settled | rejected (limit exceeded) | fraud-denied | Σ balance before | Σ balance after | negative balance? | double postings? |
|---|---|---|---|---|---|---|
| **50** (expected 50 ✓) | 73,684 | 0 | 0 | 0 | **no** | **0** |

**Settled exactly 50**, matching the pre-registered expectation and resolving the prior pass's 49
(see [the 49-vs-50 resolution](#the-49-vs-50-question-resolved)). Alice's counter read exactly
**500,000¢** afterward (50 × 10,000¢) — the reservation is exact to the cent.

Latency (storm phase, N=73,734): **raw p99 111.3 ms** (headline — here only 0.87% stalled, below
the p99 cut, so raw and trimmed nearly agree at p99) / trimmed p99 67.5 ms (secondary), removed 644
(**0.87%**).

### Duplicate & orphaned-posting query (asserted from data)

Per instruction — "I want *zero duplicates* to be a positive assertion from the data, not the
absence of an error counter." After all scenarios, the whole `pix_ledger` table's `ENTRY#` items
were scanned, grouped by `txId` (the `gsi1pk = TX#<txId>` attribute), and each group's leg count and
direction multiset asserted:

```
scan pix_ledger where begins_with(sk, "ENTRY#")  projecting txId, direction
  → group_by txId
  → assert every group has exactly 2 legs AND directions == {DEBIT, CREDIT}
  (a group of 1 = an orphaned leg; a group of ≥3 = a duplicate posting)
```

Result (`docs/load/raw/ledger-integrity-final.json`):

| total txIds | total entries | healthy {DEBIT,CREDIT} pairs | duplicate postings | orphaned legs | malformed pairs |
|---|---|---|---|---|---|
| **82,663** | **165,326** | **82,663** | **0** | **0** | **0** |

165,326 = 82,663 × 2 exactly: **every posting in the entire ledger is a perfect pair, with zero
exceptions** — across the seed, S0, S1, S2, S5 and the smoke/validation sends.

**Both subsections conserved money exactly (Σ balances unchanged) and produced zero duplicate or
orphaned postings under 50-way concurrent contention.**

---

## S3 — idempotency under a retry storm (`acc-lt-001`)

30 VUs, all authenticated as `acc-lt-001` (distinct from S1's alice/`acc-001`), race the same
`Idempotency-Key` per round, 20 rounds. A round's winner is identified after the fact as the
**earliest-completing** `202` — a replay is only reachable once the winner's claim is `COMPLETED`,
so this is provably correct, not inferred.

| rounds | real postings | replays | 409 conflicts | rounds with no winner | double postings (winners) |
|---|---|---|---|---|---|
| 20 | **20** (expected 20 ✓) | 580 | 29 | **0** | **0** |

**Every round produced exactly one real ledger posting, with zero duplicates** — the idempotency
guarantee held under 30-way contention on one key.

| | claim (winner) | replay |
|---|---|---|
| samples | 20 | 580 |
| removed (≥10s) | 0 | 0 |
| raw p50 | 19.2 ms | 10.2 ms |
| raw p99 | 27.6 ms | 21.8 ms |

Neither sample drew the environment stall this run (0 removed) — a small-sample coincidence (20 and
580 samples can't reliably hit a ~1% event), not evidence the stall is gone (S0's larger sample
measured it at 1.04%). Raw and trimmed are identical here.

---

## S5 — conservation across the asynchronous path (external Pix)

The internal conservation proof (S1) covers synchronous Pix, where one atomic `TransactWriteItems`
*is* the settlement. S5 is the harder claim: **external** sends, where the money leaves the payer
into the clearing account at `202`-time and only reaches its terminal state minutes later through
`debit → SPI_CLEARING → outbox → SNS → SQS → settlement-service → SPI → SETTLED` (+ a
`CLEARING_RELEASE` posting that empties clearing into `SPI_SETTLED`), ARCHITECTURE §6.6/§6.7.

Load: a moderate **constant arrival rate of 3 sends/s for 3 minutes**, deliberately at/under the
settlement consumer's drain rate so the pipeline keeps up and nothing crosses the 120s stuck
threshold — the healthy happy path. Senders spread across the 200-account ring (so per-account
fraud velocity never trips); destinations are the two DICT-registered external keys
(`bob@otherbank.com` / `carol@otherbank.com`, ISPB 99999999). **No failure/timeout injection**
(BACEN `failure-rate`/`timeout-rate` at their 0.0 defaults), so this proves the happy-path
conservation property; the reversal path is exercised separately (see the note below).

**Settlement-pipeline conditions** (documented load-test-only knobs, restored after the run — see
[the outbox-backlog finding](#context-2--the-outbox-publisher-cannot-keep-pace-with-internal-send-throughput)):
BACEN settlement latency lowered to 100 ms via `/admin/config`; outbox publisher batch bumped to
800 (`infra/docker-compose.s5.yml`) so S5's `PixDebited` events publish promptly instead of queuing
behind S0–S3's internal-event backlog. These change throughput, not correctness.

**End states** — every accepted send's `transactionId` was logged and, after the pipeline drained,
joined against a full scan of `pix_transactions` (asserted from data, not a counter):

| accepted (`202`) | SETTLED | REVERSED | still in flight (DEBITED/SENT_TO_SPI) | fraud-denied | other |
|---|---|---|---|---|---|
| **515** | **515** | 0 | 0 | 26 | 0 |

**Conservation** (whole `pix_ledger` table + the clearing/settled system accounts, before vs after):

| | Σ balances | `SPI_CLEARING` | `SPI_SETTLED` | negative accounts |
|---|---|---|---|---|
| before | 0 | 0 | 36,000¢ | 0 |
| after | 0 | 0 | 551,000¢ | 0 |
| delta | **0** | **0** | **+515,000¢** | 0 |

The `SPI_SETTLED` delta (**515,000¢ = 515 × 1,000¢**) exactly equals the settled count times the
per-send amount, and `SPI_CLEARING` returned to **0** with **0** transactions in flight — so **no
money was orphaned in clearing**. (The 36,000¢ `SPI_SETTLED` starting value is residual from a
pre-S5 validation batch of 36 external sends; the *delta* is S5's contribution.) The 26 fraud
denials never created a transaction and are irrelevant to conservation.

**A reversal *was* observed, and it also conserved money** — on a pre-S5 smoke send that got stuck.
While the S0–S3 outbox backlog (below) blocked its `PixDebited` event, that one external send sat
in `DEBITED` past the 120s stuck threshold; the reconciliation scanner found it, queried the SPI
(which had no record, since it was never published/sent), and correctly **compensated** it — money
returned to the payer, `SPI_CLEARING` back to 0, `Σ` still 0, status `REVERSED`. That is the
reconciliation safety net working end-to-end, and it is *why* S5's main run keeps settlement ahead
of the 120s threshold: to measure the happy path deliberately rather than accidentally.

## S5b — the reversal branch, exercised on purpose (external Pix that BACEN refuses)

*(S5b ran the following day, 2026-08-18 America/Sao_Paulo; it is external-only, so the daily-limit
counter's calendar-day scope does not affect it.)* S5 above ran with mock-bacen's
`failure-rate`/`timeout-rate` at 0.0, so 515 accepted / 515 settled / **0 reversed** — the happy
path only. The claim's other half ("money parked in clearing goes
**back to the payer on reversal**, never stranded") was unproven, and it is the half that matters:
clearing strands money when things *fail*, not when they succeed. S5b re-runs S5 (same 3/s, 3 min,
1,000¢) with reversals actually happening.

**What actually triggers a `REVERSED` (established from the code before running).** Only
**`reject-keys`** does: a creditor key the SPI refuses → the settlement is recorded `FAILED` → HTTP
**422** → `SpiSettlementRejectedException` → `SettlementFinalizer.reverse()` posts a `<txId>-rev`
compensating entry (`debit clearing / credit payer`) and moves the tx to `REVERSED`. By contrast
**`failure-rate`** (503, transient) and **`timeout`** both map to `SpiCallFailedException` =
*UNKNOWN* → retry (a timeout even resolves to `SETTLED` via query-before-retry) — **neither
reverses**. So `failure-rate 0.2` alone *cannot* produce a reversal in this codebase; S5b uses
`reject-keys=[carol@otherbank.com]` as the reversal driver **and** `failure-rate 0.2` on top to also
exercise the transient-retry path. `timeout-rate` is left 0 (it resolves to SETTLED, and at a 15s
hang on the single-threaded consumer it would swamp a 3-minute run) — reported, not run.

Destinations alternate `bob@otherbank.com` (settles) and `carol@otherbank.com` (in reject-keys →
reverses), so the run produces both terminal outcomes.

**End states** (bucketed from a DynamoDB scan, not a counter):

| accepted (`202`) | SETTLED | REVERSED | in flight | missing/other |
|---|---|---|---|---|
| **541** | **271** | **270** | 0 | 0 |

`settled + reversed = 271 + 270 = 541 = accepted`, with nothing left in `DEBITED`/`SENT_TO_SPI`.

**Conservation** (whole table + system accounts, before vs after):

| | Σ balances | `SPI_CLEARING` | `SPI_SETTLED` |
|---|---|---|---|
| before | 0 | 0 | 552,000¢ |
| after | 0 | 0 | 823,000¢ |
| delta | **0** | **0** | **+271,000¢** |

`SPI_SETTLED` delta = **271,000¢ = 271 × 1,000¢ = settled only** — a reversal credits the *payer*,
not `SPI_SETTLED`, so the 270 reversals contribute nothing to it, exactly as the ledger design
requires. `SPI_CLEARING` returned to 0: every one of the 270 parked amounts was drawn back out.

**Per-transaction reversal check (asserted per tx, not on the aggregate).** An aggregate sum could
hide equal-and-opposite errors across two accounts, so for **every** reversed `tx-X` the `tx-X-rev`
posting was joined against the transaction's own `debtorAccountId` and `amountCents`:

| reversed | `-rev` posting found | credited the **own** payer the **exact** amount | complete `debit clearing / credit payer` pair | mismatches |
|---|---|---|---|---|
| 270 | 270 | **270** | **270** | **0** |

Every reversal returned the exact original amount to the exact original payer, and every reversal
is itself a complete DEBIT+CREDIT pair (`docs/load/raw/s5b-reversal-pertx.json`) — so the
whole-ledger integrity claim (no orphaned legs, no duplicates) extends to the compensating postings.
No money was left in clearing and no returned amount differed from its original: the stop-and-report
condition did not trigger.

---

## Context 1 — capacity curve (S2)

Throughput is context in this pass, not the finding. Six stages — **5/10/25/50/100/150 VUs**, 15s
ramp (discarded) + 60s hold (measured) each, against 200 distinct ring accounts (zero ledger
contention). Default fraud profile.

| VUs | TPS | real error rate | fraud-denied rate | **raw p99 (headline)** | trimmed p50 | trimmed p99 | removed |
|---|---|---|---|---|---|---|---|
| 5 | 149.6 | 0% | 0% | **90.0 ms** | 32.6 ms | 45.8 ms | 0.7% |
| 10 | 170.4 | 0% | 0% | **31,381.8 ms** | 57.2 ms | 86.2 ms | 1.4% |
| 25 | 168.9 | 0% | 0% | **31,511.7 ms** | 145.3 ms | 228.5 ms | 2.5% |
| 50 | 170.6 | 0% | 2.0% | **31,654.5 ms** | 292.7 ms | 399.6 ms | 3.3% |
| 100 | 172.3 | 0% | 7.1% | **31,996.9 ms** | 555.4 ms | 1,034.9 ms | 2.8% |
| 150 | 163.2 | 0% | 4.4% | **32,465.2 ms** | 854.2 ms | 1,860.9 ms | 3.8% |

**Real error rate (5xx/network) is 0% at every stage.** No capacity ceiling was crossed in the
5–150 VU range — the cap was a deliberate choice to stay in the zero-`req_failed` range, not where
capacity ran out. Read the raw p99 column plainly: from 10 VUs up, 1.4–3.8% of requests took ~31s,
so the **headline p99 is ~31s at nearly every stage** — the <2s target is not met under the
environment stall, independent of the application. Trimmed p50 fits Little's Law
(`VUs ≈ throughput × latency`) tightly at every stage, confirming a genuine flat throughput ceiling
(~150–172 req/s, ~19× the first pass's ~8.5 req/s) rather than noise.

`fraud_denied_rate` is **not** a capacity signal and is excluded from saturation logic: S2 only
sends between the 200 ring accounts (whose limits/balances never bind), so a 422 there can only be
`FRAUD_DENIED`, and its non-monotonic shape (0% at low VUs here, peaking mid-range) is a property of
`ringPosition(vuId)` concentrating throughput onto fewer accounts at some stages — a fixture
artifact, not infrastructure capacity.

## Context 2 — the outbox publisher cannot keep pace with internal-send throughput

A real finding surfaced when the first external smoke send would not settle. The outbox polling
publisher (payment-service, default **batch 25 / 1s tick ≈ 25/s**) cannot keep up with sustained
internal-send throughput (~150 req/s in S2): after S0–S3 the sparse `gsi3` index held **55,538
unpublished `PixSettled` events**, ~37 minutes to drain at rest. An external `PixDebited` event
queued behind that backlog stays `DEBITED` long enough to cross the 120s stuck threshold and be
`REVERSED` by reconciliation instead of settling (exactly what happened to the smoke send above).

**Correctness impact: none.** Publish-then-mark on the sparse GSI is at-least-once and loses
nothing; the internal `PixSettled` events match no existing subscription (`settlement-queue` filters
`eventType=PixDebited`), so they fan out to SNS and go nowhere until the Sprint 8/10 queues exist.
The impact is purely latency. For S5 the batch was bumped to 800 to drain the backlog in ~2 min, and
BACEN latency lowered so the single-threaded settlement consumer kept pace; both restored to
defaults afterward. In production the publisher tick/batch and a multi-instance settlement consumer
would be sized to the send rate — the single-process, single-consumer local stack is the constraint,
not the design.

## Context 3 — throughput vs the first pass

The first load-measurement pass found a ~8.5 req/s ceiling and traced it to LocalStack's own
DynamoDB proxy (a fixed `pool_maxsize=10` connection cap). The fix — DynamoDB in its own standalone
`amazon/dynamodb-local` container, SNS/SQS still on LocalStack — lifted the application ceiling to
**~150–172 req/s** (~19×), re-confirmed here. Full diagnostic ladder and the corrected root cause of
the ~31s stalls: [`docs/load/BOTTLENECK.md`](BOTTLENECK.md).

---

## S4 — fraud-service fault injection: did not run

`s4_fraud.ran = false`. fraud-service has **no runtime latency/failure-injection knob**, unlike
mock-bacen-spi's `AdminConfigController`. Per explicit user decision: do not add one here or fake it
with `tc netem` — document the gap and propose closing it (`docs/steps/step-64.md`, PLAN.md Sprint
12, proposed, unimplemented).

---

## Fraud scoring under load — the `loadtest` profile

A 50-VU storm concentrated on one account crosses fraud-service's `VELOCITY_COUNT` /
`VELOCITY_AMOUNT` thresholds almost immediately — correct rule behaviour (many transfers from one
account in a short window is exactly what that rule catches), but it means the storm measures the
velocity rule instead of the ledger. fraud-service's thresholds and weights are fully externalized
into `FraudProperties` (`@ConfigurationProperties(prefix = "fraud.rules")`), and `FraudPropertiesTest`
proves the defaults reproduce the exact `FraudRules` the domain unit tests hand-build. A `loadtest`
Spring profile raises **only** the two velocity thresholds:

| Property | Default | `loadtest` |
|---|---|---|
| `fraud.rules.velocity-count-threshold` | 5 | 1,000,000 |
| `fraud.rules.velocity-amount-threshold-cents` | 2,000,000 (R$20,000) | 100,000,000,000 (R$1,000,000,000.00) |

Every other threshold, weight, and both decision bands are untouched
(`FraudPropertiesTest#loadtestProfileRaisesOnlyTheVelocityThresholds` asserts this), so fraud
scoring stays fully active during S1 — only the one rule whose design intent is "single-account
burst" is relaxed. **Never on by default:** `SPRING_PROFILES_ACTIVE` is empty in compose unless the
host shell exports it; S1 was the only scenario run under it (S0/S2/S3/S5 used the default profile).

---

## Caveats (apply to every number above)

- `dynamodb-local` is a single-process DynamoDB Local instance, not real AWS DynamoDB — its
  latency/throughput under concurrent transactional load does not represent production.
- settlement-service runs a **single-threaded, sequential** SQS consumer (batch 5); at BACEN's
  default 2s latency it settles only ~0.5/s. S5 lowered BACEN latency to keep the pipeline ahead of
  its 120s stuck threshold. A local sizing constraint, not a design limit.
- Only 2 of the 208 seeded accounts (alice/`acc-001`, bob/`acc-002`) are "real" fixtures; the other
  206 are load-test-only accounts from `tools/k6/seed/seed-load-test-fixtures.sh`.
- fraud-service has no runtime fault-injection knob — S4 did not run.
- This is WSL2 on Windows (Docker Desktop backend), not bare-metal Linux.
- The ~31s stalls are a real, confirmed environment defect (`docs/load/BOTTLENECK.md` RUNG 4), not a
  measurement artifact — raw p99 is the honest headline and the <2s p99 target is not met under it.
- **Portability:** the *shape* (flat throughput ceiling, Little's-Law queueing, 0 real errors past
  100 req/s, every money invariant holding) is a real property of this codebase; the *absolute*
  ~150–172 req/s ceiling and the ~31s stall are specific to this machine.

## Reproducing

```bash
docker compose -f infra/docker-compose.yml down -v          # fresh volumes — S1's exhaustible accounts
docker compose -f infra/docker-compose.yml up -d --build
bash tools/k6/seed/seed-load-test-fixtures.sh               # ~1 min

bash tools/k6/run-s0.sh                                     # ~5 min (default fraud profile)

# S1 under the loadtest fraud profile (velocity thresholds only):
SPRING_PROFILES_ACTIVE=loadtest docker compose -f infra/docker-compose.yml up -d --no-deps fraud-service
bash tools/k6/run-s1.sh                                     # ~2 min
SPRING_PROFILES_ACTIVE= docker compose -f infra/docker-compose.yml up -d --no-deps fraud-service  # disarm

source tools/k6/run-common.sh
k6_run run -e S2_STAGES=5,10,25,50,100,150 --out json=docs/load/raw/s2-raw.ndjson tools/k6/s2-capacity.js
node tools/k6/analyze-s2.js docs/load/raw/s2-raw.ndjson > docs/load/raw/s2-result.json

bash tools/k6/run-s3.sh                                     # ~seconds

# S5: drain the outbox backlog first, lower BACEN latency, then run; restore both after.
docker compose -f infra/docker-compose.yml -f infra/docker-compose.s5.yml up -d --no-deps payment-service
curl -s -X POST localhost:9090/admin/config -d '{"latencyMs":100}' -H 'Content-Type: application/json'
# wait for the sparse gsi3 unpublished count to reach ~0, then:
bash tools/k6/run-s5.sh                                     # ~3 min + drain wait
curl -s -X POST localhost:9090/admin/config -d '{"latencyMs":2000}' -H 'Content-Type: application/json'
docker compose -f infra/docker-compose.yml up -d --no-deps payment-service                        # restore batch
```

k6 runs via the `grafana/k6` Docker image (no local k6 needed) — see `tools/k6/run-common.sh`.
