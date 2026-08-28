# SLO load profiles — results (step 47)

Three named k6 profiles whose **thresholds fail the run**, plus a degradation drill. This document
records what they measured, what passed, what failed, and — where a number is the machine's rather than
the platform's — says so plainly instead of quoting it as a platform result.

**Headline, in one paragraph.** The platform meets both stated budgets at the quiet rate and misses both
badly at the average rate, and the reason is **not** the one this project expected. The prior
measurement pass blamed a confirmed ~31s WSL2 environment stall
([`docs/load/BOTTLENECK.md`](../docs/load/BOTTLENECK.md) RUNG 4), and that stall is real and present here
too. But the per-dependency breakdown this step added points somewhere else: at 58 TPS the send path
spends its p99 waiting for **an AWS SDK HTTP connection to DynamoDB**, and when the wait exceeds the
pool's acquisition timeout the request fails with `500 INTERNAL_ERROR` — 8.4% of sends at 58 TPS, 39.8%
at the Black Friday peak. That is a limit in *this application's* configuration, not in the host and not
in DynamoDB. The same pool is shared with the background outbox publishers, which is why the platform is
still losing 84% of sends **at 5 TPS an hour after the peak**, draining a backlog nobody is waiting
for (§5.1). Money stayed correct throughout — **Σ balances = 0 across all 208 accounts, zero negative
balances**, through thousands of real mid-flight failures — and the one thing that did go wrong is the
more interesting kind: **R$740 sitting in the clearing account that Σ cannot see and no component
sweeps** (§7).

| | |
|---|---|
| Date | 2026-08-25 |
| Host | WSL2 (kernel 6.6.87.2-microsoft-standard-WSL2) on Windows, Docker Desktop backend |
| Stack | `infra/docker-compose.yml` — 8 services, standalone `dynamodb-local`, LocalStack (SNS/SQS), Redis, Prometheus/Grafana/OTel/Jaeger |
| Fixtures | 200-account ring + `acc-lt-s1bal`/`acc-lt-sink` (`tools/k6/seed/seed-load-test-fixtures.sh`) on a freshly `down -v`'d stack |
| Order | `low` → `standard` → degradation drill → `black-friday`, on one stack, tables growing monotonically throughout — see §3 |
| Scripts | [`load/k6/`](k6/) · runner [`load/k6/run.sh`](k6/run.sh) · artifacts `load/results/` |

---

## 1. The gate

The brief's targets are written as k6 `thresholds`, so a breach makes `k6 run` exit non-zero and
`run.sh` propagates that exit code. This is the deliverable: not a graph, a check.

```js
'http_req_duration{endpoint:send}':    'p(99)<2000'   // KR2.1
'http_req_duration{endpoint:balance}': 'p(99)<300'    // KR2.2
'server_errors{endpoint:*}':           'rate<0.01'
```

**`server_errors` is a custom metric, not k6's `http_req_failed`, and the difference is a design
decision.** The built-in counts every non-2xx as a failure, which for this platform is wrong: a `422
LIMIT_EXCEEDED` or `FRAUD_DENIED` is the system *working*, and gating an SLO on it would fail a run for
refusing payments it is supposed to refuse. `server_errors` counts only 5xx and connection-level
failures; business refusals are counted separately (`business_rejections`), reported, and never gated.

`statement` carries no latency budget. The brief states two, and inventing a third would be inventing an
SLO — its latency is reported below, where a number with no promise attached belongs.

---

## 2. Results

| Profile | Target | **Achieved** | Dropped iters | send p99 | balance p99 | statement p99 | 5xx on send | Exit |
|---|---:|---:|---:|---:|---:|---:|---:|:--:|
| `low` | 5 TPS | **5.38/s** | 0 | **98 ms** ✅ | **11 ms** ✅ | 21 ms | **0%** ✅ | `0` **PASS** |
| `standard` | 58 TPS | 51.17/s | 4,142 | **33,742 ms** ❌ | **23,080 ms** ❌ | 23,111 ms | **8.4%** ❌ | `99` **FAIL** |
| `black-friday` | → 500 TPS | 88.8/s | 124,828 | **52,504 ms** ❌ | **38,226 ms** ❌ | 40,206 ms | **39.8%** ❌ | `99` **FAIL** |
| `low` again, **after** all of the above (§5.1) | 5 TPS | 3.1/s | 309 | **67,782 ms** ❌ | 12,955 ms ❌ | 13,040 ms | **84%** ❌ | `99` **FAIL** |

Achieved rate is `http_reqs / duration`; "dropped iterations" is k6 refusing to start work it has no VU
for — the open-model signal that the platform is behind the schedule it was asked to keep. `black-friday`
ran **last**, on the largest tables of the session, and its numbers should be read with §3's ordering
note: an earlier Black Friday run on smaller tables measured 122.5/s and 15.0% 5xx. The recorded one is
the one with an artifact behind it.

**`low` passes both budgets with two orders of magnitude of headroom** (98 ms against 2,000 ms; 11 ms
against 300 ms) and returns zero 5xx. That is what makes it the control the other two are read against:
a breach at 58 TPS cannot be blamed on the application being slow per se, and cannot be blamed on the
host being incapable — at 5 TPS the same code on the same machine is comfortably inside budget.

> **The one caveat on `low`:** its `max` was 10,947 ms — a single request that took eleven seconds at a
> rate of five per second. That is the environment stall of `docs/load/BOTTLENECK.md` RUNG 4, present but
> too rare at 901 requests to reach the 99th percentile. It is why `low` is a floor check and not a
> proof of anything at scale.

### Where the target rate went

`standard` asked for 58 TPS and got 51.17. `black-friday` asked for a peak of 500 and got 122.52
sustained. **The 500 TPS target is not reachable on this host and this step does not claim it is** — see
§3. What the open arrival-rate model buys is that the shortfall is *visible*: 102,285 dropped iterations
is the platform being asked for work it could not take, recorded as such, rather than a closed-model run
quietly slowing its own clients down and reporting a comfortable latency.

---

## 3. Infrastructure — what a representative run needs, and what this one was

Task 5 of the step asks for representative infrastructure *or the deviation written down*. This run is
the deviation, and there are three of them. They are separated because they are not the same kind of
problem and only two are the machine's fault.

**Deviation 1 — the host.** WSL2 on Docker Desktop, not bare metal or EC2. `docs/load/BOTTLENECK.md`
RUNG 4 established that this host stalls for ~31 seconds on ~1–4% of requests, confirmed from the
*server's own log timestamps*, so it is real application-visible latency and not a client measurement
artifact. It is present in these runs (`low` max 10.9 s at 5 TPS; negative `min` values appear in the
`standard` and `black-friday` summaries, which is a wall clock stepping backwards mid-request). A
representative run needs a host without it.

**Deviation 2 — the database.** `dynamodb-local` is one JVM process over an embedded store, not
DynamoDB. It has no partitions, no adaptive capacity and no horizontal scaling, and it visibly degrades
as tables grow: a full `pix_ledger` scan that took seconds at the start of this session took **minutes**
by the end, which is why the degradation drill in §7 verifies the clearing position by key instead of
summing the table. A representative run needs real DynamoDB, provisioned or on-demand, sized to the
write rate in §6.

**Deviation 3 — and this one is NOT the infrastructure.** The 5xx that fail these runs are
`software.amazon.awssdk.core.exception.SdkClientException: Unable to execute HTTP request: Timeout
waiting for connection from pool`. That is the AWS SDK's own HTTP connection pool inside our services,
exhausted, on its default size. It would happen on a perfect host against real DynamoDB at the same
concurrency. **It is a platform finding and §5 treats it as one.**

> **The runs degrade in sequence, and that is recorded rather than smoothed.** Every profile ran on the
> same stack without a reset, so each met a `pix_ledger` holding everything its predecessors wrote, on a
> `dynamodb-local` that gets slower as tables grow. Two measurements of the same profile bracket the
> effect: an early `standard` attempt (discarded for a tooling reason, not for its numbers) read 21.5 s
> p99 / 2.0% 5xx where the recorded one read 33.7 s / 8.4%; an early Black Friday read 122.5 req/s and
> 15.0% 5xx where the recorded one, last of the session, read 88.8 req/s and 39.8%.
>
> **Run-to-run variance here is the same order of magnitude as the effect being measured.** Two
> consequences, and both are why this document is written the way it is: trust the **ranking** of the
> findings (fraud is fast, storage is slow, the pool is the wall) far more than any individual
> millisecond; and prefer the **differential** measurements, which cancel the drift — §7's
> internal-vs-external comparison inside one run is the only latency claim here that is robust to all of
> this, which is precisely why the degradation drill was built to make that comparison.

---

## 4. p99 per dependency — the send path, attributed

Task 7. Read from payment-service's own Prometheus histograms rather than from sampled spans: `run.sh`
force-recreates payment-service before each profile, so its cumulative histogram covers exactly the run
and there is no window to choose. `http.client.requests` only grew a percentile histogram in this step
(`CommonMetricsAutoConfiguration`) — before it, the outbound meter exported count/sum/max and **no p99
existed to read**, which is the difference between observing a breach and attributing one.

Each figure is the smallest bucket bound containing 99% of observations — an upper bound on the true
p99, never an under-estimate. `load/k6/dependency-p99.js` prints these.

### `standard` (58 TPS) — send p99 was 33.7 s at the client, 28.6 s at the server

| Hop | Calls | p99 (upper bound) |
|---|---:|---:|
| **`POST /v1/payments/pix` (server-side, the whole send)** | 20,402 | **28,633 ms** |
| DynamoDB `PutItem` (the idempotency claim) | 20,402 | **17,180 ms** |
| DynamoDB `UpdateItem` (limit reserve + claim phases + outbox mark) | 94,179 | 1,790 ms |
| DynamoDB `TransactWriteItems` (transaction + outbox, one atomic write) | 18,768 | 984 ms |
| → ledger-service `POST /internal/ledger/postings` | 18,768 | 447 ms |
| → account-service `GET /internal/accounts/{id}` | 19,204 | 805 ms |
| → account-service key resolution (`uri=none`, see note) | 19,204 | 805 ms |
| **→ fraud-service `POST /internal/fraud/score`** | 18,768 | **10 ms** |
| SNS `Publish` | 18,773 | 62 ms |
| Redis `GET` / `SETEX` | 6,838 / 6,294 | 1.0 / 1.7 ms |

### `black-friday` (peak 500 TPS requested) — same shape, more of it

| Hop | Calls | p99 (upper bound) |
|---|---:|---:|
| **`POST /v1/payments/pix` (server-side)** | 26,098 | **14,317 ms** |
| DynamoDB `PutItem` | 26,098 | **22,907 ms** |
| DynamoDB `TransactWriteItems` | 16,492 | 5,727 ms |
| DynamoDB `UpdateItem` | 68,492 | 4,295 ms |
| → ledger-service balance read | 15,796 | 3,221 ms |
| → account-service `GET /internal/accounts/{id}` | 17,442 | 1,790 ms |
| → ledger-service `POST /internal/ledger/postings` | 16,511 | **56 ms** |
| **→ fraud-service `POST /internal/fraud/score`** | 16,498 | **11 ms** |
| SNS `Publish` | 2,288 | 45 ms |

> **Why `PutItem`'s p99 (22.9 s) exceeds the endpoint's own (14.3 s).** `pix_dependency_seconds` is a
> service-wide timer: it counts DynamoDB calls made by the **background outbox publishers** as well as by
> request threads. Those threads are queueing for the same exhausted pool and nobody is waiting on them,
> so they wait longest. That the drain suffers worst is visible in the same table — **2,288 SNS publishes
> against 16,492 transactions written**, a drain running at an eighth of the rate it was sized for.

**Three things this breakdown settles.**

1. **Fraud is not the problem, and it is the one everybody expects to be.** The 200 ms budget of ADR-0005
   is the most conspicuous deadline in the send path, and fraud-service answers in **10 ms at both
   rates** — 5% of its budget, unmoved by a twenty-fold change in load. The fail-open machinery never
   had to fire. Had the outbound meter shipped without a histogram, "the send p99 is 33 seconds" would
   have been the whole story and this would have been a suspect.
2. **The p99 is one DynamoDB call, and it is the *first* one.** The idempotency claim `PutItem` carries
   17.2 s of the send's 28.6 s at 58 TPS, and at Black Friday it *is* the whole number (15.7 s, equal to
   the endpoint's own p99 to the bucket). The claim is the first DynamoDB call a send makes — it is the
   one that queues for a connection.
3. **The service hops are fast; the storage hop is not.** ledger-service answers a full atomic
   double-entry posting in 34–447 ms while the client-side `TransactWriteItems` timer in payment-service
   reads 984–1,790 ms for a *smaller* write. Two processes, one database, the same wall.

> **A measurement defect this exposed, recorded rather than fixed here.** Two of the six outbound calls
> report `uri="none"`: `HttpPixKeyResolver` (account-service key resolution) and the statement query in
> `HttpLedgerClient`. Both build their URI with a `uriBuilder ->` lambda, from which Micrometer cannot
> recover a URI template, so the *route* is unattributable even though the *dependency* is. Call counts
> identify them unambiguously here (19,204 = one per send; 3,420 = one per statement), but that is
> inference, not instrumentation. Noted, not changed — it is a client-code change and outside this step.

---

## 5. The finding: the send path's ceiling is an SDK connection pool

Every 5xx in every failing run is the same exception, from the same place:

```
software.amazon.awssdk.core.exception.SdkClientException:
    Unable to execute HTTP request: Timeout waiting for connection from pool
```

Where they land, counted from the stack traces of the 1,710 failures in the `standard` run:

| Failed at | Count | What had already happened | Recoverable how |
|---|---:|---|---|
| `DynamoIdempotencyRepository.claim` | 1,198 | **nothing** — no claim, no `txId` | the client retries the same `Idempotency-Key`; a fresh attempt |
| `DynamoDailyLimitReservation.reserve` | 436 | the claim exists, carrying its `txId` | a retry resumes under the *stored* identity (ADR-0014) |
| `DynamoTransactionRepository.create` | 51 | **the ledger posting committed** | a retry re-posts the same `txId`; the ledger answers `replayed` (ADR-0015) |
| `DynamoIdempotencyRepository.complete` | 25 | everything; only the response snapshot is missing | a retry replays the stored response |

**Two independent problems live in that table.**

**(a) Capacity.** Every service builds its `DynamoDbClient` with the SDK's default sync transport, and
`software.amazon.awssdk:apache-client` is on the classpath, so that transport is `ApacheHttpClient` —
whose default `maxConnections` is **50**. No service overrides it; there is no `httpClientBuilder` call
anywhere in `services/`. At 58 TPS each send makes **~7 DynamoDB calls** (§6: 5.04 `UpdateItem` + 1.09
`PutItem` + 1.00 `TransactWriteItems`) and each call is slow on this host, so demand for connections
exceeds 50 and callers queue until the acquisition timeout. The pool — not DynamoDB, not the host — is
what the send path hits first.

It is also **shared with the background outbox publishers**, which the Black Friday numbers make plain:
**16,492 transactions written, 2,288 events published to SNS**. A drain sized at 1 s × batch 100 × 4
in flight — nominally hundreds per second — moved an eighth of what it was fed, because it was queueing
for the same 50 connections as the payments. §5.1 is what that debt costs afterwards. Sizing that pool (and deciding whether the drain deserves its own client) is a real
change with real trade-offs and belongs in its own step, not smuggled into a measurement.

**(b) The error contract.** A pool-acquisition timeout is an **unknown result** — ADR-0015 says so for
the ledger, and it is no less true for a write to `pix_transactions`. It currently surfaces as
`500 INTERNAL_ERROR` with no `Retry-After` and no `code` a client can branch on, because
`SdkClientException` is not mapped and falls through to `GlobalExceptionHandler`'s catch-all. The
platform already has the right answer for this shape (`503 … UNAVAILABLE` + `Retry-After`, retry-safe
because the operation's identity is durable), and the 51 failures in row three are exactly the
crash-after-commit window steps 65–67 were built for. **A client told "500, internal error" has no
reason to retry; a client told "503, retry after 2s" does, and retrying is what makes the design's
recovery actually happen.** Also noted, not changed.

**And the part that did hold.** k6 does not retry, so those 1,710 failures are the *worst* case — every
one abandoned. Afterwards: **Σ balances = 0 across 208 accounts, zero negative balances**. The 51 sends
that committed a ledger posting and lost their transaction record moved money correctly between payer
and payee; what they lack is the `pix_transactions` row a real client's retry would have written. The
invariant that cannot be allowed to break did not break, under 1,710 real ambiguous outcomes that no
test wrote on purpose.

---

## 5.1 After the peak, the drain becomes the load

The Black Friday profile ends with a two-minute recovery segment at 58 TPS, on the theory that "does
latency come back down?" is a question a peak profile has to ask. Two minutes turned out to be three
orders of magnitude too short.

The `low` profile — **5 TPS**, the one that passed at the start of this session with a 98 ms p99 and zero
errors — was re-run **after** everything else, as a control for how much of the degradation was table
growth rather than load. It did not measure that. It measured this:

| `low`, 5 TPS | send p99 | median send | 5xx on send | accepted |
|---|---:|---:|---:|---:|
| **early** (clean tables, first run of the session) | **98 ms** | 33 ms | **0%** | 630 / 630 |
| **late** (after `standard`, the drill and `black-friday`) | **67,782 ms** | 21,754 ms | **84%** | 51 / 321 |

Three and a half sends per second cannot exhaust a 50-connection pool. Something else was using it:

```
pix_outbox_lag_seconds{lane="notification"}  3043.112      # 50 minutes behind
gsi3 OUTBOX#UNPUBLISHED#NOTIFICATION          21,291       # events still to publish
gsi3 OUTBOX#UNPUBLISHED#SETTLEMENT                 0
gsi3 OUTBOX#UNPUBLISHED#AUDIT                      0
```

**The peak was over; its consequences were not.** 21,291 `PixSettled` events were still queued on the
notification lane, and the publisher draining them — reading gsi3, publishing to SNS, marking each item
published — was doing so through the **same AWS SDK connection pool as the money path**. A user sending
one Pix per second was competing with a backlog nobody was waiting for, and losing 84% of the time.

**What this does and does not say about ADR-0019.** Lanes worked exactly as designed: `SETTLEMENT` — the
lane where money is blocked — is at **0 and zero seconds of lag**, while the notification lane is fifty
minutes behind. The incident of `docs/load/RESULTS.md` Context 2, where an external payment queued behind
55,538 irrelevant events and was reversed, cannot happen here and did not. **The partition ADR-0019
introduced is between lanes; it is not between the drain and the request path.** They still share one
connection pool per process, so a lane that is deprioritised in *scheduling* is not deprioritised in
*resource contention* — it can still take the connections a payment needs. That is the next thing this
platform would fix, and it is a different fix from the one ADR-0019 made: a separate SDK client with its
own pool for background work, or a bulkhead. Recorded here, not built.

> **Read the late `low` row as a recovery finding, not a floor.** It is not "the application got slower
> as tables grew" — the early/late comparison cannot say that, because the two runs differ in more than
> table size. It is "for roughly an hour after a peak this platform's asynchronous debt is the dominant
> load on its synchronous path", which is a statement about the shape of the recovery, and a much more
> useful one.

---

## 6. Capacity: WCU/RCU and what 500 TPS costs

Task 6 — the point being that "500+ TPS" stops being a latency claim and becomes a capacity claim with a
bill attached. Two measured inputs, no estimates:

**Item sizes**, read from the live tables:

| Item | Size |
|---|---:|
| `pix_ledger` BALANCE | 89 B |
| `pix_ledger` ENTRY | 311 B |
| `pix_ledger` `TX#/POSTING` (the idempotency guard) | 219 B |
| `pix_transactions` META | 529 B |
| `pix_transactions` OUTBOX | 685 B |
| `pix_transactions` `LIMIT#` | 62 B |
| `pix_idempotency` claim | 422 B |
| `pix_accounts` / `pix_keys` | 155 B / 138 B |

Every item is under 1 KB, so **each item is 1 write unit** (2 in a transaction) and the arithmetic below
is item counts, not kilobytes.

**Operation counts per accepted internal send**, measured from `pix_dependency_seconds_count` diffed
across the `standard` run (`load/k6/capacity-delta.js`, 18,692 accepted sends):

| Service | Operation | Per send |
|---|---|---:|
| payment | DynamoDB `UpdateItem` | 5.04 |
| payment | DynamoDB `PutItem` | 1.09 |
| payment | DynamoDB `TransactWriteItems` | 1.00 |
| payment | SNS `Publish` | 1.00 |
| ledger | DynamoDB `TransactWriteItems` | 1.00 |
| ledger | Redis `DEL` (cache invalidation) | 1.00 |
| account | DynamoDB `GetItem` + `Query` | 1.03 + 1.03 |
| fraud | Redis `INCR` + `INCRBY` + `SADD` | 3.00 |

The five `UpdateItem`s are exactly what the code prescribes: three idempotency phase advances
(`CLAIMED → POSTED → RECORDED → COMPLETED`), one daily-limit reservation, one outbox mark-published.

### Write units per internal send

| Table | Writes | Base WCU | GSI replication | Total |
|---|---|---:|---:|---:|
| `pix_idempotency` (no GSI) | 1 `PutItem` + 3 `UpdateItem` | 4 | — | **4** |
| `pix_transactions` (gsi1 E2E, gsi2 STATUS, gsi3 outbox, all `ALL`) | 1 limit `UpdateItem`; 1 transaction of {META, OUTBOX}; 1 mark-published `UpdateItem` | 1 + 4 + 1 | gsi1+gsi2 on META (4), gsi3 on OUTBOX (2), gsi3 delete (1) | **13** |
| `pix_ledger` (gsi1, sparse on ENTRY) | 1 transaction of 5 items | 10 | gsi1 on both ENTRY legs (4) | **14** |
| | | | | **31 WCU** |

Transactional writes are billed at 2× (base items and their index replication alike); the two
`UpdateItem`s outside a transaction are 1× each.

**A third of that bill is the indexes.** 11 of the 31 units are GSI replication, and `ALL` projection is
why: every index copy carries the whole 529 B or 685 B item. `pix_transactions` alone pays 7 units of
index for 6 units of data. That is the price of the sparse-outbox and reconciliation access patterns,
and it is the first thing to attack (`KEYS_ONLY` on gsi1/gsi2 would cut most of it) if this bill ever
mattered.

Reads on the send path are ~2 RCU (account lookup + key resolution). A balance read is ~1 RCU and a
statement page (20 entries × 311 B ≈ 6.2 KB, strongly consistent) is ~2 RCU.

> **The balance cache saves almost nothing under load, and that is worth knowing before sizing anything.**
> Over the `standard` run: **544 hits, 6,294 misses — a 7.9% hit rate.** ledger-service invalidates both
> parties' keys after every posting, and at 40 sends/s across a 200-account ring every account is written
> every few seconds, so the 5 s TTL backstop never gets a chance to serve anyone. Cache-aside on balance
> is a protection for *idle* accounts; at peak, essentially every balance read reaches DynamoDB. The
> budget above assumes exactly that.

### The bill

At AWS on-demand list price for standard tables in `us-east-1` — **$1.25 per million write request
units, $0.25 per million read request units** (list price at time of writing; the arithmetic is shown so
it can be recomputed against current rates):

| | Writes | Reads | Cost |
|---|---:|---:|---:|
| **Per send** | 31 WCU | ~2 RCU | **$0.0000392** |
| **At 500 TPS sustained (1 hour)**, 70/20/10 mix → 350 sends/s | 10,850 WCU/s → 39.1 M | ~900 RCU/s → 3.24 M | **$49.64/hour** |
| **The brief's 5 M transactions/day** | 155 M WCU | ~12.9 M RCU | **$197/day ≈ $5,900/month** |

Storage adds ~5 KB per transaction once the GSI copies are counted (~2.7 KB base + ~2.4 KB index) —
**25 GB/day at 5 M tx/day**, about $6/month per day-of-history retained at $0.25/GB-month. A year of hot
ledger is ~9 TB and $27k/year, which is the number that makes the cold-archive design of step 43 a cost
decision rather than a tidiness one.

**Per-payment cost of the database is about four hundredths of a cent** (≈ R$0.0002). Stated because it
is the number that tells you where to stop optimizing: DynamoDB request units are not what makes an
instant-payments platform expensive.

---

## 7. Degradation drill — the Black Friday peak with an 8-second rail

Task 8. `bash load/k6/run-degradation.sh 8000 0.2`: the Black Friday shape, mock-bacen's `latencyMs`
set to **8,000** — four times the entire send budget — and **20% of sends routed to an external key**
(`bob@otherbank.com`) so the rail is actually in the flow. fraud-service is the other dependency this
drill would degrade and cannot: it still has no runtime knob (step 64, proposed).

### The question, and the answer

The brief claims `p99 < 2s` on the send acknowledgement, and ARCHITECTURE claims that claim survives a
slow rail, because an external send is answered `202 PROCESSING` **before** BACEN is touched (ADR-0003).
That is an architectural assertion, and an unmeasured assertion is a hope.

Both rails ran **inside the same run**, on the same machine, in the same minute, under the same
saturation — which is what makes the comparison worth anything on a host whose own latency moves between
runs:

| | median | p90 | p99 | max |
|---|---:|---:|---:|---:|
| `rail:internal` (BACEN not involved) | 5,609 ms | 39,108 ms | **51,700 ms** | 71,178 ms |
| `rail:external` (BACEN at 8,000 ms) | 5,926 ms | 39,614 ms | **53,055 ms** | 71,185 ms |
| **difference** | **+317 ms** | +506 ms | **+1,355 ms (2.6%)** | +7 ms |

**The rail was 8,000 ms slow and moved the acknowledgement by 317 ms at the median and 1,355 ms at
p99 — under 3%.** Had BACEN been on the acknowledgement's critical path, external would have been at
least 8 seconds worse than internal. It is not. The asynchronous boundary is exactly where ADR-0003 says
it is, and the residual difference is the extra work an external send does *before* answering (the
clearing posting and the settlement-lane outbox item), not the rail.

> This is also why the absolute numbers here — a 52-second p99 — do not undermine the finding. Both rows
> carry the same connection-pool saturation from §5. The drill is a **differential** measurement, and the
> difference is the only quantity it claims.

### What the platform gave up instead

Everything after the `202`. Of **3,370 external sends accepted** during the drill:

| Outcome | Count | Read from |
|---|---:|---|
| **Settled** at the rail | **870** | `SPI_SETTLED` +870,000¢ |
| **Reversed** by reconciliation, payer refunded | **~2,426** | the remainder; settlement-service logged 400× *"the rail still has no record of it past the safety window… the payer must be made whole"* |
| **Stranded in `SPI_CLEARING`** | **74** | `SPI_CLEARING` 58,000¢ → 132,000¢ |

**Only 26% of external payments settled.** A rail at 8s cannot be drained by five settlement workers
faster than a Black Friday fills the queue, so transactions crossed the 120s stuck threshold and the
reconciliation scanner did exactly what it is built to do: queried the rail, found no record, and
**reversed** — returning each payer's money rather than guessing. That is the trade-off recorded plainly:
under a slow rail at peak, **the platform sacrifices settlement completion, not the acknowledgement SLO
and not correctness**. A payment that could have settled at second 300 is refunded at second 120, and
the customer is told so.

The platform also said so *by itself*. Across these runs its own watchdog fired and resolved without
anyone looking: the **send** and **balance** error-budget burn alerts (step 72), the **settlement outbox
lane lag** alert (*"a money flow is blocked"*, step 71), and the **<5-min reconciliation SLO** alert
(step 35) — each followed by its `ALERT RESOLVED`. The alerting built in Sprints 11/11.5 was not
exercised by a contrived drill here; it was exercised by a load test that broke things for real.

### The 74 that stayed — Σ holds and money is still in the wrong place

This is the sharpest finding of the whole step, and it is not a conservation failure.

74 payments' worth of money (R$740) entered `SPI_CLEARING` and never left, while **every transaction is
in a terminal state** — `DEBITED`, `SENT_TO_SPI`, `FINALIZING_SETTLEMENT` and `FINALIZING_REVERSAL` all
read **0** after the drain. The mechanism is §5's third row: an external send whose ledger posting
committed (`debit payer / credit SPI_CLEARING`) and whose `pix_transactions` write then hit the exhausted
connection pool. 362 requests failed at exactly that point, ~20% of them external — **≈72, against 74
observed.**

The consequence is precise and worth stating carefully:

- **Σ balances is unaffected.** The money moved from a payer to a system account; nothing was created or
  destroyed, and the whole-table check in §8 still reads 0. *Conservation is not a strong enough
  assertion to catch this*, which is exactly the case where Σ holds and money is still misplaced.
- **No platform component will ever find it.** The reconciliation scanner (step 34) reads GSI2 by
  transaction *status* — and there is no transaction item to have a status. The detector for stuck money
  is indexed on the very record that failed to be written.
- **The designed recovery is the client's retry**, and it works: the idempotency claim exists carrying
  the operation's `txId` (ADR-0014), a retry of the same `Idempotency-Key` resumes under that stored
  identity, re-posts the same `txId`, the ledger answers `replayed` (ADR-0015), and the transaction row
  is written. **k6 never retries**, so this residue is the load harness behaving worse than any real
  client — but "a client that gives up leaves money in a system account that nothing sweeps" is a real
  gap, and a real client can also give up.

Naming it precisely: the platform has **at-least-once recovery driven by the caller, and no
caller-independent sweeper for money in clearing with no transaction record.** A production answer would
be a periodic reconciliation of `SPI_CLEARING` ledger entries against `pix_transactions` by `txId` — the
ledger already holds `TX#<txId>/POSTING` for every posting, so the join exists; nothing reads it that
way today. Recorded, not built: it is a new flow, not a load test.

---

## 8. Money safety across the whole session

The assertion of last resort, taken **after** every profile, the degradation drill and the late control
run — i.e. after ~11,000 requests failed mid-flight, thousands of payments were reversed by
reconciliation, and the platform spent an hour draining a backlog while still serving traffic:

```json
{ "accountsRead": 208, "sumBalanceCents": 0, "negativeNonSystemAccounts": [] }
```

**Σ over every account is exactly 0, and no non-system account is negative.** The ledger's two
non-negotiable invariants held under everything this step could throw at them, including failure modes
no test wrote on purpose: 1,710 pool-exhaustion failures in `standard` and ~9,600 in the drill, spread
across every phase of the send — before the claim, after the claim, **after the ledger committed**, and
after everything but the response snapshot.

> **Method.** Read by `batch-get` over the enumerable account set rather than by scanning `pix_ledger`.
> The enumeration is complete because the platform has **no account-creation API** — every account comes
> from `infra/localstack/init` or `tools/k6/seed` — and the count matches the 208 that
> `tools/k6/verify/ledger-snapshot.sh` reported by full scan before the profiles ran. The scan is the
> stronger form and is still the right tool on a small table; here it takes minutes (§3, deviation 2),
> which is itself part of what this step measured.

**And §7 is the reason this section is not the end of the story.** Σ = 0 is necessary and not
sufficient: R$740 sits in `SPI_CLEARING` with no transaction record and no sweeper, and every
conservation check in this repository reads 0 while it does. An invariant that cannot distinguish
"money is where it should be" from "money is somewhere with no owner" needs a second invariant beside
it, not a stronger version of itself.

---

## 9. Reproducing

```bash
docker compose -f infra/docker-compose.yml down -v          # fresh volumes
docker compose -f infra/docker-compose.yml up -d --build
bash tools/k6/seed/seed-load-test-fixtures.sh               # the 200-account ring, ~1 min

bash load/k6/run.sh low                                     # ~4 min incl. restart
bash load/k6/run.sh standard                                # ~11 min
bash load/k6/run.sh black-friday                            # ~12 min
bash load/k6/run-degradation.sh 8000 0.2                    # ~12 min + drain

node load/k6/capacity-delta.js standard <acceptedSends>     # operations per send
bash tools/k6/verify/ledger-snapshot.sh                     # Σ balances (slow once tables are large)
```

Each profile arms its own posture (fraud thresholds, trace ratio) and restores it in an `EXIT` trap;
see [`load/k6/README.md`](k6/README.md).

---

## 10. Caveats

- Everything in §3 — the host, `dynamodb-local`, and the fact that the profiles ran in sequence on
  growing tables.
- **The trace sampling ratio differs per profile**: `low` ran at `1.0` (the sandbox default, and at 5 TPS
  the cost is nothing), `standard` and `black-friday` at `0.05`. A run at `1.0` measures the tracing.
- **`standard` and `black-friday` ran under fraud-service's `loadtest` profile**, which raises only the
  two velocity thresholds (`FraudPropertiesTest` asserts exactly that). At 40 sends/s over 200 accounts
  every account trips a rule calibrated for 5 transfers per human per minute; the defaults would have
  measured that rule. `low` ran at the defaults, which is what makes it the profile that keeps full
  scoring inside the asserted path.
- The mix is **100% internal** sends in the three SLO profiles. That is deliberate — the two budgets are
  on the synchronous acknowledgement, and an external send answers `202` before the rail is touched — and
  §7 is the run that tests the claim rather than assuming it.
- fraud-service still has **no runtime latency/failure knob** ([step 64](../docs/steps/step-64.md),
  proposed and unimplemented), so the degradation drill can degrade the rail and nothing else.
- **`black-friday` was measured twice and the recorded run is the later, worse one.** An earlier Black
  Friday run (122.5 req/s, 15.0% 5xx) was overwritten when the degradation drill, which executes the same
  *profile*, wrote to the same artifact names. That is fixed — `run.sh` now takes an `ARTIFACT_PREFIX` —
  and the surviving numbers are the ones with a committed summary behind them. Both readings appear in
  §3, because the gap between them is the ordering effect, not noise to hide.
- **Portability**: the *shape* — fraud fast, storage slow, a connection pool as the first wall, the
  post-peak drain outweighing live traffic, money conserved through every failure — is a property of
  this codebase. The absolute milliseconds are this machine's.
- **One unit test failed during this step's `mvn verify`, and it was the machine, not the change.**
  `HttpFraudScorerTest#aSlowFraudServiceTimesOutIntoSkippedWithoutBlowingTheBudget` asserts that a
  120 ms read timeout returns in under a second; it measured **11.5 s** while the host was draining the
  post-peak outbox backlog — the same ~11 s stall this document reports everywhere else. It passes on a
  quiet machine. Recorded because a wall-clock assertion in a unit test is a latent flake on any loaded
  CI box, and this step is the first thing to have loaded one.
