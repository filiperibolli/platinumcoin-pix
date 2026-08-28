# The ledger, relationally — findings

> **What this is.** The measured half of [ADR-0009](adr/0009-relational-ledger-counterpart-lab.md):
> the same double-entry posting, on PostgreSQL, in two locking strategies, put through the same
> invariant storm as the DynamoDB ledger and then investigated — query plans, index write-cost, a
> reproduced deadlock, a contention benchmark. Delivered by [step 51](steps/step-51.md); the code is
> [`labs/ledger-pg`](../labs/ledger-pg/README.md) (step 50).
>
> **What it is for.** [ADR-0001](adr/0001-dynamodb-for-the-ledger.md) chose DynamoDB and closed with a
> "when to choose which" rule of thumb that was *citation, not experience*. This document is the
> first-hand data behind that rule. It is **not** a migration proposal —
> [ADR-0020](adr/0020-keep-dynamodb-for-the-ledger.md) §2 already settled that a finding here
> authorizes a new ADR, never a migration.
>
> **The one thing to read if you read nothing else:** §6. The two relational strategies differ almost
> not at all in throughput and enormously in *who pays* — and the third leg of the comparison, the
> DynamoDB one, **could not be measured on this infrastructure**, for a reason this repository had
> already proven before step 51 asked the question.

## How to reproduce every number here

```bash
# 1. Parity — the step-15 invariant storm on both strategies. Runs on a plain verify.
mvn -q -pl labs/ledger-pg verify

# 2. The measurements (off by default: a benchmark on every build slows the loop and
#    turns timing noise into a red build).
mvn -pl labs/ledger-pg verify -Dit.test=LedgerPgStudy
#    → labs/ledger-pg/study/raw/study.txt

# 3. The DynamoDB leg, gated identically, from the deployable's test scope.
mvn -pl services/ledger-service verify -Dit.test=LedgerContentionStudy \
    -Dstudy.out=../../labs/ledger-pg/study/raw/dynamodb-contention.txt
```

Raw captures are committed under [`labs/ledger-pg/study/raw/`](../labs/ledger-pg/study/raw/). The
numbers below are quoted from them; **machine**: WSL2 on Docker Desktop, 12 CPUs, Postgres 16.13 in
Testcontainers, HikariCP pool of 16.

---

## 1. Parity first, because a benchmark of an incorrect implementation compares nothing

ADR-0009 insists the invariant suite passes *before* any number is taken. That is not ceremony. The
cheapest way to win a ledger benchmark is to skip the guarantee being measured: drop the lock and the
pessimistic strategy gets faster, drop the retry and the optimistic one stops paying for contention.
Both would produce a table that looks like this one and means nothing.

`PostgresLedgerInvariantsIT` is the step-15 storm, rerun here: **exactly ⌊balance/amount⌋ successes
and never one more**, conservation across a random transfer storm, the same `txId` from ten threads
moving the money once, and a sampler that never sees a negative balance. One suite, two subclasses,
22 tests green on `mvn verify` (the module total, including §4's deadlock pair).

**What that suite is worth, demonstrated rather than asserted.** Deleting two words —
`FOR UPDATE` — from `PessimisticLedger`:

| suite | against the mutant |
|---|---|
| `PessimisticPostingIT` (the sequential contract, step 50) | **6/6 green** — it cannot tell a lock from no lock |
| `PessimisticInvariantsIT` (the storm, step 51) | **3 of 4 red** |

and the red is specific:

```
ERROR: new row for relation "accounts" violates check constraint "accounts_balance_cents_check"
  Detail: Failing row contains (acc-storm-payer, -10000, 11).
```

An eleventh posting against a balance that could afford ten. Two things follow. First, without the
lock the read-then-check *is* a race, exactly as Domain Safety Rule 3 says — the serialized region is
the whole argument, and removing it removes the correctness, not just the performance. Second, the
`CHECK (balance_cents >= 0)` that step 50 called a backstop **fired in anger**: it was the only thing
standing between the mutant and a negative balance in a committed transaction. A backstop nobody has
ever seen fire is a comment; this one is a constraint.

---

## 2. The read side — what the covering index buys (task 2)

Dataset: 100,000 postings / 200,000 legs over 500 accounts (~400 entries per account), the statement
query mirrored from `DynamoLedgerRepository#queryStatement` — one account's entries, newest first,
20 per page.

| variant | plan | buffers | execution |
|---|---|---|---|
| no index | Seq Scan + top-N heapsort | 2,858 | **8.25 ms** |
| no index, page 2 (keyset) | Parallel Seq Scan + Gather Merge | 2,874 | **10.37 ms** |
| `(account_id, posted_at DESC, tx_id DESC)` | Index Scan | 21 | **0.068 ms** |
| same, page 2 | Index Scan | 22 | **0.072 ms** |
| covering (`INCLUDE` every selected column) | Index **Only** Scan | 22 | **0.111 ms** |
| covering, page 2 | Index Only Scan | 22 | **0.049 ms** |

**~136× less I/O and ~120× less time**, and the size of the win is a property of the *table*, not of
the query: without the index the planner reads all 200,000 legs to return 20, so the cost of one
customer's statement grows with every other customer's traffic. That is the sentence worth carrying
out of this section, because it is exactly the failure mode a ledger meets in year three.

The buffer count is the number to trust here, not the millisecond: 2,858 → 21 is a *count of pages
touched*, deterministic and reproducible, while sub-millisecond timings on a laptop under Docker
Desktop are noise-dominated (§9).

**Three things the plans say that the summary table does not.**

**The covering index cost 9 MB and bought nothing measurable.** It did change the plan from Index
Scan to Index **Only** Scan (11 MB → 20 MB, against a 22 MB table), which is the textbook win — and
across three runs its page-one timing landed at 0.074, 0.074 and 0.111 ms against the plain index's
0.131, 0.070 and 0.068 ms. **The difference is smaller than the run-to-run noise**, in both
directions, and the plan says why: `Heap Fetches: 20`. All twenty rows went to the heap anyway,
because the visibility map was cold after a bulk load with no `VACUUM` — so the "index-only" scan was
not index-only. The plain index had already taken the whole win: its second key column made the
`ORDER BY` free, and *that* was the expensive part, never the column fetch. **A covering index is a
bet on the visibility map**, and it pays only where the table is vacuumed enough for the map to be
current. On an append-only entries table with a steady write rate, that is a bet to re-measure rather
than assume — and 9 MB per 22 MB of table is the stake.

**Keyset pagination is only cheap when the index agrees with it.** `(posted_at, tx_id) < (?, ?)`
appears as a `Filter` in the unindexed plan — the row comparison is evaluated after the scan, so
page 2 costs a full scan just like page 1 — and as an `Index Cond` once the index matches the sort
order, where it becomes a descent to a leaf. Same SQL, different asymptotics. The deployable's opaque
cursor is the same idea; the relational version needs an index to keep the promise.

**Postgres went parallel to answer page two, and that is a warning, not a feature.** The unindexed
page-two plan launched a worker (`Workers Launched: 1`). Parallel query is what an engine does when
it has decided a scan is unavoidable; a statement endpoint that recruits background workers per
request is one whose index is missing.

**The DynamoDB contrast.** There is no plan to inspect, no planner to convince and no index to forget
to create — `pk = ACCOUNT#<id> AND begins_with(sk, "ENTRY#")` with `ScanIndexForward=false` is a
descent into a sorted partition, and it costs the same on day one and in year five. The price for
that is paid in a different currency: the access pattern had to be **designed into the key** before
any data existed (`docs/data-model.md` §3), and a statement sorted by anything other than time
requires a new GSI and a backfill, where Postgres would need one `CREATE INDEX`. The relational side
is more forgiving of a design you have not finished having; the DynamoDB side is more forgiving of a
table that got large while nobody was looking.

---

## 3. The write side — every read index taxes every write (task 3)

40,000 legs inserted per run, single-threaded, batched, each run starting from an empty schema and
adding one more read index than the last.

| extra indexes | legs/s | vs 0 | index MB |
|---:|---:|---:|---:|
| 0 | 30,772 | 1.00× | 2.6 |
| 1 | 28,433 | 1.08× | 5.4 |
| 2 | 27,796 | 1.11× | 5.7 |
| 3 | 27,028 | 1.14× | 8.5 |
| 4 | 26,643 | 1.15× | 9.9 |
| 5 | 25,463 | **1.21×** | 10.2 |

Five read indexes cost **~20% of the insert throughput** and ~4× the index storage. Three full runs
of the study put that ratio at 1.19×, 1.23× and 1.21× — the one number in this document that
reproduced tightly. The curve is monotone but the per-index steps are not evenly sized and some
adjacent pairs differ by less than the noise, so read this as *"each index costs a few percent and
they add up roughly linearly"*, never as a per-index constant.

Two honest limits on this number. It is a **single-threaded batched bulk insert**, which is the
friendliest possible shape for index maintenance — under concurrent single-row commits the tax is
larger, because each index page split contends. And the entries table here is append-only with a
monotonically increasing timestamp, so the `posted_at` indexes append to their right edge instead of
fragmenting; an index on a random key (`counterpart_account_id`) is the one that hurt most, which the
per-index deltas hint at but this experiment was not designed to isolate.

**The DynamoDB contrast, and the sentence ADR-0001 needs.** The DynamoDB ledger sidesteps this tax by
having **no secondary index on the hot write path at all**: `gsi1` exists for `TX#<txId>` lookups, and
the statement is served by the *base table's own sort key* rather than by an index. What DynamoDB
charges instead is that the sort key had to be right the first time — and where a GSI *is* added, the
tax comes back as WCU: every write that touches a projected attribute is billed a second time, on a
resource that can also throttle independently of the base table. The relational tax is latency you
can measure on your own machine; the DynamoDB tax is money on a bill and a second throttling surface.
Same trade, different unit.

---

## 4. The deadlock, reproduced and then fixed (task 4)

`LockOrderDeadlockIT` builds the cycle rather than hoping for it: two transactions, each takes its
first row lock, a `CyclicBarrier` holds both, then each asks for the row the other holds.

```
40P01: ERROR: deadlock detected
  Detail: Process 62 waits for ShareLock on transaction 735; blocked by process 61.
          Process 61 waits for ShareLock on transaction 736; blocked by process 62.
  Where: while locking tuple (0,1) in relation "accounts"
```

Postgres kills **exactly one** of the two after `deadlock_timeout` (1 s by default) and lets the other
through. That "exactly one" is the part worth internalizing: a deadlock is not an outage, it is a
*choice the engine makes for you*, and its price is one aborted transaction plus a second of wall
clock during which both parties are doing nothing. At 500 TPS, a second of nothing is a queue.

The fix is not a retry, and this is where the test earns its keep: the second test runs the *same*
traffic — 40 A→B postings racing 40 B→A postings on the same two rows — through the real
`PessimisticLedger`, whose locks go through `LedgerSql.inLockOrder`. **Zero deadlocks, zero retries,
80 commits, both balances back where they started.**

**The generalization.** Deadlock requires a *cycle* in the waits-for graph. If every transaction
acquires its locks along one total order agreed by all of them, a cycle is impossible — a waiting
transaction always waits on one that is further along the same order, and "further along" cannot
loop. Sorting the account ids is the cheapest possible total order; any deterministic one works. The
rule generalizes past two-leg postings: a three-leg settlement (payer → clearing → payee) that locks
"in the order the legs appear in the request" reintroduces the cycle immediately, because two
requests can name the same three accounts in different orders. **The invariant is about the order of
acquisition, never about the number of locks.**

---

## 5. The bug this study found in the lab's own code

The contention harness deadlocked before it measured anything:

```
SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=16, active=16, idle=0, waiting=11)
```

Sixteen threads replaying one committed `txId` against a sixteen-connection pool. `replayOrConflict`
took the `DataSource` and opened **its own** connection to read the committed legs back — while the
caller was still holding the connection whose transaction had just aborted. Every thread held one and
queued for a second; the pool had no seventeenth to give; thirty seconds later they all failed a call
whose only correct answer was *"yes, that already committed"*.

It is §4's deadlock, one level up the stack: **a cycle formed by acquiring a second resource of a
kind you are already holding.** Rows are fixed by a global acquisition order; connections are fixed by
not needing two — the replay wants a new *transaction*, and a rolled-back connection already is one.
The fix is a signature change (`Connection` instead of `DataSource`) and it is worth noticing what
found it: not the sequential contract, not the invariant storm at ten threads, but a benchmark that
happened to raise the replay fan-in to exactly the pool size. **Concurrency bugs are found at the
ratio, not at the code.**

That is also why the fix is no longer guarded only by a benchmark nobody runs on a normal build:
`PostgresLedgerInvariantsIT` replays from `POOL_SIZE + 4` threads rather than step 15's ten, so the
storm now sits above the threshold on purpose. Reverting the fix turns it red immediately, with the
same signature (`active=16, idle=0, waiting=16`) — verified by doing exactly that before accepting
the test.

Worth naming plainly: no money was ever at risk here. Nothing was written, and the failure was
loud. But under load it converts an idempotent retry — the single most common thing a payment system
does when it is already having a bad day — into a thirty-second stall and a hard error, on every
caller at once.

---

## 6. Contention: pessimistic vs optimistic vs DynamoDB (task 5)

32 threads × 25 postings, all accounts richly funded so the run measures contention rather than
refusals. Three shapes: **HOT CREDIT** (everyone credits one shared account — the clearing-account
shape), **HOT DEBIT** (everyone debits one shared account — the storm shape), **COLD** (each thread
owns its pair; the same work with no contention at all).

| shape | strategy | posts/s | p50 | p95 | p99 | committed | busy |
|---|---|---:|---:|---:|---:|---:|---:|
| HOT CREDIT | pessimistic | 604 | 40.24 ms | 114.56 ms | 198.13 ms | 800 | 0 |
| HOT CREDIT | optimistic | 541 | **1.64 ms** | 191.31 ms | **800.03 ms** | 792 | **8** |
| HOT DEBIT | pessimistic | 667 | 15.99 ms | 149.40 ms | 330.16 ms | 800 | 0 |
| HOT DEBIT | optimistic | 546 | **1.57 ms** | 204.40 ms | **795.02 ms** | 793 | **7** |
| COLD | pessimistic | 4,775 | 3.17 ms | 18.49 ms | 49.85 ms | 800 | 0 |
| COLD | optimistic | 4,653 | 3.07 ms | 21.87 ms | 43.51 ms | 800 | 0 |

**Result 1 — with no contention the two strategies are the same program.** COLD differs by ~2%, which
is noise (across three runs each strategy won at least once). Every argument about locking strategy is an argument about the hot rows only; on the
99.99% of accounts that are cold, this choice buys nothing and should be made on other grounds
(readability, familiarity, what the team can debug at 3am).

**Result 2 — contention costs ~8× throughput, and the strategy barely moves that.** 4,775 → 604
posts/s. Both strategies land in the same band; the serialization is the price of the invariant, not
of the mechanism. **If a hot account is your problem, changing your locking strategy is not your
fix** — sharding the hot key is (which is exactly what [step 52](steps/step-52.md) does for the
clearing account).

**Result 3 — the strategies differ in *who pays*, and this is the whole finding.** The optimistic p50
is **25× better** (1.64 ms vs 40.24 ms) and its p99 is **4× worse** (800 ms vs 198 ms), and 8 of
800 callers got no answer at all — a `LedgerBusyException` after exhausting eight attempts, which the
platform surfaces as a `503` the caller must retry. The pessimistic strategy is a **queue**: everyone
waits their turn, nobody is turned away, and the tail is bounded by the queue length. The optimistic
strategy is a **race**: most callers are served almost instantly, the losers pay repeatedly, and some
are eventually told to come back.

For a payments ledger that is not a performance choice, it is a product one. "Everyone waits 40 ms"
and "99% wait 2 ms, 1% get a 503" are two different promises to make to a payer, and the second one
is *worse than it looks* — the callers who lose are not random, they are the ones contending for the
hot account, i.e. the clearing account on the busiest day of the year. **Optimistic concurrency
distributes its cost onto exactly the traffic you most wanted to serve.**

### The third leg could not be measured, and that is a finding

| shape | posts/s | p50 | p95 | p99 | busy |
|---|---:|---:|---:|---:|---:|
| HOT CREDIT | 39 | 817.11 ms | 876.01 ms | 910.08 ms | 0 |
| HOT DEBIT | 42 | 798.41 ms | 830.45 ms | 867.50 ms | 0 |
| COLD | 40 | 792.79 ms | 833.01 ms | 847.10 ms | 0 |

Flat. Contended and uncontended shapes agree within noise, p50 ≈ p99, and no caller ever exhausted
the adapter's retry budget. That is not a concurrency-control profile — **it is a saturated server**,
and this repository had already proved it before step 51 asked: `docs/load/BOTTLENECK.md` RUNG 2
measured LocalStack's DynamoDB at **~45 write ops/s flat from concurrency 1 through 32**, because
LocalStack 3 runs the real `DynamoDBLocal.jar` file-backed with a 256 MB heap behind a
10-connection proxy pool. My ~40 postings/s lands on that ceiling almost exactly.

So the honest reading is: **these numbers measure the emulator, and they say nothing about DynamoDB.**
Not "DynamoDB is slower than Postgres" — the comparison was never available. Three separate reasons,
any one of which is disqualifying:

1. **The emulator saturates before contention starts.** A ceiling that ignores concurrency cannot
   report a concurrency effect.
2. **The transports are not comparable.** JDBC to a container on a socket versus signed HTTP to a
   Java process. That difference alone is most of the latency gap.
3. **The interesting DynamoDB properties are not local properties.** On-demand capacity, multi-AZ
   durability and per-partition throttling — the three things ADR-0001 actually bought — do not exist
   in an emulator on one laptop.

What *would* make the leg measurable: real DynamoDB, on-demand, driven from in-region compute, with
the same shapes and a WCU budget. That is a cloud account, which this project rules out by
construction (CLAUDE.md: 100% local, no cloud). Recording the gap is the deliverable here; inventing
a number to fill it would have been worse than an empty cell.

### What a replay costs (the question step 50 left open)

16 threads × 20 replays of one committed `txId`, then the same count of new postings as the scale.

| | replay p50 | replay p99 | new posting p50 | new posting p99 |
|---|---:|---:|---:|---:|
| pessimistic | 8.14 ms | 14.64 ms | 12.95 ms | 174.37 ms |
| optimistic | 2.48 ms | 5.18 ms | 1.48 ms | 649.09 ms |
| DynamoDB (emulator) | 53.18 ms | 81.57 ms | 396.80 ms | 434.45 ms |

Step 50 predicted that a replay under the pessimistic strategy pays for a row lock before it
discovers it is a replay, and it does: **3.3× the optimistic replay** (8.14 ms vs 2.48 ms), and it
blocks other posters on those rows for the duration. But the prediction was incomplete in an
interesting way. A pessimistic replay is *cheaper than a pessimistic commit* (8.14 vs 12.95 ms) —
it takes the locks and then does almost nothing — while an optimistic replay is *more expensive than
an optimistic commit at p50* (2.48 vs 1.48 ms) and **125× cheaper at p99** (5.18 vs 649.09 ms).

The reason is the same one in every row of this document: **a replay never retries.** The unique
violation is immediate and terminal, so a replay is the one operation whose latency is
contention-independent. In the strategy that pays for contention with attempts, that makes the replay
the most predictable thing it does; in the strategy that pays with waiting, the replay still waits.
Idempotent retries are the traffic a payment platform generates most of when it is degraded — a
retry storm is a system's response to its own slowness — so "what does a replay cost under load" is
not a footnote question, and the two strategies answer it differently.

---

## 7. So does any of this re-open ADR-0001?

**No, and it is worth being precise about why**, because "Postgres was faster in a benchmark" is the
kind of sentence that starts migrations.

ADR-0001 rests on four pillars. This study speaks to **one and a half** of them:

| pillar | does the study speak to it? |
|---|---|
| 1. Availability & elasticity (multi-AZ managed, on-demand absorbing 8-10× peaks with no failover engineering) | **No.** Nothing here is measurable on one laptop, and it is the pillar that carried the most weight. |
| 2. The access pattern is transaction-friendly (a small, fixed, key-addressable write set) | **Yes** — and it confirms it from the other side: Postgres does this correctly too, in two ways, and §6 shows the cost is contention on the hot key, which is identical in both engines. |
| 3. Predictable single-digit-ms latency at any table size | **Half.** §2 shows the relational read is *faster* here at 200k legs — with the right index, which is the caveat that is the whole point: the DynamoDB number does not depend on remembering it. |
| 4. Retention (TTL + S3 export) | **No.** Untouched. |

And the strongest finding cuts *against* using this data to decide anything at scale: §6 could not
measure the DynamoDB side at all. A benchmark missing its third leg is not a tie-breaker.

What the study *does* change is the honesty of ADR-0001's closing rule of thumb. It is no longer a
citation. Concretely, three of its claims are now measured facts: the relational engine gives the
same guarantees (§1), it gives them via a serialized region the DynamoDB path cannot offer (§1, §4),
and the read convenience it is famous for is paid for on every write (§3). ADR-0020's three
conditions for re-opening the storage choice remain the reopening criteria; none is met.

---

## 8. Step 50's three open questions, answered

| question handed forward | answer |
|---|---|
| **A replay costs a lock under the pessimistic strategy** — how much? | 8.14 ms p50 vs 2.48 ms optimistic, 3.3×. But it is *cheaper than a commit* on the same strategy, and its tail is 125× tighter than the optimistic commit's. §6. |
| **The retry budgets differ on purpose (3 vs 8)** — what is that worth? | At the hot shape, the optimistic budget of 8 still left **7-11 of 800 callers** (across three runs) with a `LedgerBusyException`, while the pessimistic 3 was never exhausted at all — its callers wait instead of failing. The budgets are not comparable quantities: one bounds *attempts*, the other bounds *a failure that barely occurs*. §6. |
| **No `(account_id, posted_at)` index yet** — measure it both ways. | ~136× fewer buffers and ~120× less time (2,858 → 21 buffers; 8.25 ms → 0.068 ms), for ~4-8% of insert throughput and 11 MB. The covering `INCLUDE` variant costs a further 9 MB and its timing difference from the plain index is within the noise, because `Heap Fetches: 20` means the visibility map never delivered the index-only scan. §2, §3. **The index is worth it and the covering variant is not, on this workload.** |

The schema keeps no index on `(account_id, posted_at)` — the lab's `schema.sql` still ships without
it, deliberately, so the experiment above stays reproducible from a clean start.

---

## 9. How precise are these numbers

Every table above is quoted from the committed capture
[`labs/ledger-pg/study/raw/study.txt`](../labs/ledger-pg/study/raw/study.txt), produced by one run.
The study was run three times while writing this document; the spread tells you which conclusions are
load-bearing and which are decoration:

| quantity | run 1 | run 2 | run 3 (quoted) | verdict |
|---|---:|---:|---:|---|
| Seq Scan buffers, statement query | 2,858 | 2,858 | 2,858 | **exact** — a page count, not a clock |
| insert throughput, 5 indexes vs 0 | 1.19× | 1.23× | 1.21× | **solid** |
| HOT CREDIT pessimistic p50 | 42.70 ms | 43.42 ms | 40.24 ms | **solid** (±4%) |
| HOT CREDIT optimistic p50 | 1.64 ms | 1.74 ms | 1.64 ms | **solid** |
| HOT CREDIT optimistic p99 | 796.72 ms | 811.40 ms | 800.03 ms | **solid** |
| optimistic `LedgerBusyException`s per 800 | 7 | 11 | 8 | **directionally solid**, not a rate |
| plain-index page-one execution time | 0.131 ms | 0.070 ms | 0.068 ms | **noise** — do not quote to 3 digits |
| covering-index page-one execution time | 0.074 ms | 0.074 ms | 0.111 ms | **noise**, and §2 leans on that fact |

The rule this suggests, and the one worth carrying to any benchmark: **prefer counters to clocks.**
`Buffers`, `Heap Fetches`, rows removed by filter, commits, `LedgerBusyException`s — these are
deterministic properties of a plan or a run, and they reproduce exactly. Sub-millisecond timings on a
laptop under Docker Desktop on WSL2 do not, and this repository has already documented that host
misbehaving in ways far larger than the effects measured here (`docs/load/BOTTLENECK.md` RUNG 4: real
multi-second stalls, plus kernel-level clock jumps). Every conclusion in this document is built on a
counter or on a ratio that survived three runs; none rests on a single millisecond.
