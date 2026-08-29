# Clearing-account write sharding — N=1 vs N=16 under the Black Friday profile

Step 52's measurement half. The implementation half is `ClearingAccountResolver` (common-lib),
`ARCHITECTURE.md` §6.3 and the invariant suite; this document is the evidence that the mitigation was
**measured** rather than asserted, and — just as importantly — an honest account of **what this
environment cannot show**.

> **Read this first.** The headline is not a throughput win. On this host the sharded and un-sharded
> platforms perform within noise of each other, and the document explains *why that is the expected
> result here* and *what would have to change for the difference to appear*. A findings doc that only
> reports the result it hoped for is a marketing page.

---

## 1. What is being tested, and why the profile had to be changed to test it

The hot item is `ACCOUNT#SPI_CLEARING / BALANCE`. It is touched by exactly two flows:

| Flow | Effect on clearing |
|---|---|
| **external** send (`POST /v1/payments/pix` to a key at another PSP) | **credit** at debit time; later a `CLEARING_RELEASE` debit on settle, or a `PIX_REVERSAL` debit on refusal |
| **inbound** Pix (an arrival from the rail) | **debit**, crediting the payee |
| internal send | **none** — payer to payee directly, clearing is never named |

`load/k6/black-friday.js` ships with `EXTERNAL_SHARE=0` (see `load/k6/lib.js:44`) — deliberately, because
step 47 measures the *acknowledgement* SLO and external traffic would drag rail latency into it. Run
verbatim, therefore, the profile **never touches the clearing account at all**, and an N=1 vs N=16
comparison over it would compare two runs that both write zero clearing postings.

So both runs here are driven with **`EXTERNAL_SHARE=1.0`**: every send is external, every send credits
clearing. That is a deviation from the step's literal instruction and it is the only way the instruction
means anything. The consequence to keep in mind: **these numbers are not comparable to
`load/RESULTS.md`'s SLO numbers** — different traffic mix, different code path. They are comparable to
each other, which is what a before/after needs.

An external send answers `202` **before** the rail is called (ADR-0003), so the measured request latency
is still the acceptance path — resolve, limit, fraud, ledger debit — with the clearing credit inside it.
The rail's own latency lands in settlement, off the measured request.

## 2. Method

Both runs on the same build, the same seed, the same machine, back to back.

```bash
# baseline — the un-sharded platform, reproduced by configuration rather than by an old commit
CLEARING_SHARDS=1  docker compose -f infra/docker-compose.yml up -d
EXTERNAL_SHARE=1.0 ARTIFACT_PREFIX=sharding-n1 bash load/k6/run.sh black-friday

# the mitigation
CLEARING_SHARDS=16 docker compose -f infra/docker-compose.yml up -d
EXTERNAL_SHARE=1.0 ARTIFACT_PREFIX=sharding-n16 bash load/k6/run.sh black-friday
```

`CLEARING_SHARDS=1` is what makes this a fair comparison: the "before" picture is the **same binary**
with one number changed, not a checkout of the previous commit. `ClearingAccountResolver` returns the
bare `SPI_CLEARING` at N=1, so the baseline run is byte-for-byte the pre-step-52 behaviour on the money
path.

Artifacts per run, written by `load/k6/run.sh` into `load/results/`:
`<prefix>-summary.json`, `<prefix>-raw.ndjson`, `<prefix>-dependency-p99.md`,
`<prefix>-<service>-{before,after}.txt` (Prometheus scrapes), `<prefix>-<service>.log`.

## 3. Expectations, written down before the runs

Pre-registered here — the same discipline as `docs/load/EXPECTATIONS.md`, for the same reason: an
expectation written afterwards is a description, not a prediction.

| # | Expectation | Reasoning |
|---|---|---|
| E1 | **Throughput will not improve measurably.** | The host's ceiling is dynamodb-local's own write serialization (~800-950 raw write ops/s, `docs/load/BOTTLENECK.md` RESOLUTION), reached long before any single-item limit. Sharding removes a *partition-level* constraint that this emulator does not have. |
| E2 | **DynamoDB Local emulates no partition throttling at all**, so `ProvisionedThroughputExceeded`/throttle counts will be zero in both runs. | It is a single-process JVM over an in-memory store; WCU per partition is an AWS capacity concept with no local analogue. |
| E3 | **Item-level `TransactionConflict` is the only contention signal that can move**, visible as `LEDGER_BUSY`/503s and ledger retry latency. It should be lower at N=16 if it is non-zero at N=1. | `TransactWriteItems` conflict detection *is* implemented locally, and at N=1 every concurrent external send contends on one item. |
| E4 | **Conservation holds in both runs**, and at N=16 the per-shard sums close individually. | The point of the invariant suite; a load run that broke it would be the finding. |
| E5 | **The clearing position nets to ~0 after the soak**, spread over ~16 shards at N=16 and concentrated on 1 at N=1. | Every send either settles (release) or reverses; the shape of the distribution is the direct evidence that sharding did what it says. |

## 4. Results

Run on 2026-08-28, WSL2 / Docker Desktop, the machine `docs/load/RESULTS.md` describes. Both runs from a
pristine `docker compose down -v` → `up` → reseed, so neither inherits the other's settlement backlog.

### 4.1 The headline: writes per item

This is the number the whole step is about, and it does not depend on any capacity model the emulator
might or might not implement — it is a plain count of how many times each DynamoDB item was written,
read straight off the `version` counter every posting increments.

| | writes on the hottest clearing item | items sharing the load |
|---|---:|---:|
| **N=1** | **55,729** | 1 |
| **N=16** | **3,770** | 16 (total 57,974, min 3,542, max 3,770 — **6.4 % spread**) |

**A 14.8× reduction in per-item write pressure, on the same traffic.** The bare `SPI_CLEARING` item ends
the N=16 run at `version=0`: it was never touched, which is the direct evidence that every send went to
a shard. The 6.4 % spread across the sixteen is CRC32 doing its job — no shard is a hot partition of its
own, which is the failure mode a weaker hash over `"tx-" + UUID` would have produced.

Note the totals differ slightly (55,729 vs 57,974) because the two runs accepted slightly different
numbers of payments (52,387 vs 54,573) and because each settlement release or reversal writes clearing a
second time. Per accepted send the write count is the same; only its *concentration* changed.

### 4.2 What the load numbers say (and do not)

| Metric | N=1 | N=16 | Δ |
|---|---:|---:|---:|
| achieved request rate | 165.85 /s | 165.99 /s | +0.1 % |
| iterations completed | 105,818 | 107,946 | +2.0 % |
| `dropped_iterations` (open model: demand the platform could not start) | 77,359 | 75,234 | −2.7 % |
| sends accepted (`202`) | 52,387 | 54,573 | +4.2 % |
| send p50 | 4,605 ms | 4,553 ms | −1.1 % |
| send p95 | 24,920 ms | 24,741 ms | −0.7 % |
| send p99 | 38,814 ms | 41,400 ms | +6.7 % |
| `server_errors` (5xx + network) | 3.73 % | 3.63 % | −0.1 pp |
| ledger `POST /internal/ledger/postings` p99 | 33.6 ms | 33.6 ms | 0 |
| `TransactWriteItems` p99 | 1,431.7 ms | 1,431.7 ms | 0 |
| DynamoDB throttling events | **0** | **0** | — |
| `LEDGER_BUSY` / `TransactionConflict` | **0** | **0** | — |

**Read that table as a confirmation of E1-E3, not as a disappointment.** Every difference except the
write concentration is inside this host's run-to-run noise, and the p99 moving the "wrong" way by 6.7 %
is the clearest sign of that: at 165 req/s against a 500 req/s demand curve, p99 is dominated by how long
requests queued above the ceiling, and a run that accepted 4.2 % more payments necessarily queued some of
them longer. The `TransactWriteItems` p99 — the one number that would carry item contention — is
identical to the bucket boundary in both runs.

### 4.3 Conservation, under real load, with sharding on

After the N=16 run, over all **224** accounts in the table:

```
Σ balanceCents = 0
```

Money moved 54,573 times through sixteen clearing sub-accounts, and none was created or destroyed
(**E4**). The per-shard balances at the end of the k6 phase were 3,303,000-3,524,000 cents each — money
legitimately in flight, settling out over the following minutes (**E5**).

## 5. What this environment can and cannot show

**It cannot show partition throttling, and no amount of load will change that.** DynamoDB Local is a
single-process JVM over an in-memory store; "1,000 WCU/s per partition" is an AWS *capacity accounting*
concept with no local implementation. Both runs recorded exactly zero throttling events, and a document
that reported "no throttling after sharding!" as a win would be reporting the emulator's silence as the
platform's health. **The AWS-side claim of this step is therefore explicitly not measured here** — it is
argued from the documented limit and evidenced by the write-concentration number, which is the input that
limit is applied to.

**It cannot reach 500 TPS.** The ceiling is ~165 req/s and it is dynamodb-local's own write
serialization, diagnosed in `docs/load/BOTTLENECK.md`. Both runs sat exactly on it (165.85 vs 165.99),
which is itself useful: it says the bottleneck is *upstream of* whatever the clearing item was doing, so
this host cannot make the clearing item the constraint however hard it is pushed. Sharding a resource
that is not the bottleneck cannot make the system faster — that is not a defect of the mitigation, it is
arithmetic, and pretending otherwise is how a benchmark lies.

**It could have shown item-level `TransactionConflict`, and there was none.** DynamoDBLocal *does*
implement transactional conflict detection, so E3 was a real possibility, and the answer is that at ~50
clearing writes/s the single item never conflicted enough to surface as a `LEDGER_BUSY`. The mechanism
would appear at a higher clearing write rate than this host can produce.

**What the numbers do establish, and it is the substantive half:**

1. The concentration the mitigation targets is real and was measured: **55,729 writes on one item**.
2. Sharding removes it, evenly (**14.8× lower, 6.4 % spread**), with no change to any latency or error
   metric — i.e. it is **free** here, which is what you want from a mitigation you are deploying ahead of
   the load that needs it.
3. The money invariants survive it under real concurrency: Σ = 0 over 224 accounts after 54,573 sends.

### A finding this run produced that is not about sharding

Both runs logged ~3,900 `500 INTERNAL_ERROR` responses from payment-service (3.7 % of requests), and they
are the same in both: `SdkClientException: Unable to execute HTTP request: Timeout waiting for connection
from pool` — payment-service's DynamoDB SDK connection pool exhausted while ~600 VUs pushed three times
the host's capacity at it. It is a saturation artifact of driving 500 TPS at a 165 TPS machine, not a
sharding effect, and it is identical on both sides of the comparison so it does not distort it. Noted
here rather than silently, because "the platform answers 500 when its DynamoDB connection pool is
exhausted" is a real observation about the code under overload; it is out of this step's scope and
belongs in a backlog entry of its own.

## 6. Conclusion

Write sharding is **implemented and measured**. On this host it is a 14.8× reduction in per-item write
pressure at zero cost to latency, throughput or error rate, with money conservation intact under real
concurrent load. The AWS-side consequence — staying under the 1,000 WCU/s partition ceiling at 500 TPS —
follows from that measurement and the documented limit, and is **not** something a local emulator can
demonstrate; this document says so rather than implying otherwise.

The part that mattered most was never the throughput anyway. It is that a compensating reversal must
find the shard that was credited, which is a correctness property no benchmark would have caught: the
wrong-shard reversal is perfectly balanced, leaves global Σ untouched, and is invisible in every number
in §4. That one is proven by `ReversalShardIT` — and proven to be non-vacuous by mutation: making the
finalizer re-derive the shard instead of reading it turns the test red with

```
[the shard the debit credited is emptied by its own reversal]
expected: 0L
 but was: 20000L
```

— the credited shard still holding the money while a different one went negative.

