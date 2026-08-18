# Bottleneck isolation — where the ~8.5 req/s ceiling actually comes from

**Status: RESOLVED.** RUNGs 0-4 below are the original diagnostic pass (read-only, no application
code changed). The fix that came out of it — DynamoDB split into its own standalone container —
is implemented and verified in the [RESOLUTION](#resolution--the-fix-applied-and-verified) section
at the end: **~8.5 req/s → ~158-201 req/s**. Current numbers: `docs/load/RESULTS.md`.

Triggered by a specific observation: `throughput × unloaded-latency ≈ 1.2` held across all six
S2 stages, and `1/144ms ≈ 6.9 req/s` sat almost exactly on the measured ceiling — a signature of a
**serialized** resource, not application concurrency limits. This document runs the cheapest-first
diagnostic ladder to find exactly where, without touching any application code.

**Bottom line up front**: the ceiling is **DynamoDB Local's own write-serialization**, not WSL2,
not k6, not the JVM services. A single Pix send makes 5 sequential DynamoDB **write** calls
(2 more are fast reads); DynamoDB Local caps raw write throughput at ~45 ops/s regardless of
client concurrency; `45 ÷ 5 ≈ 9 req/s` predicts the observed ~8.5-8.9 req/s ceiling almost exactly,
and `5 writes × ~22ms/write ≈ 110ms` plus ~10ms of fast reads predicts the observed ~144ms
unloaded latency almost exactly. **Separately**, RUNG 4 found that the ~30s stalls documented in
`docs/load/RESULTS.md` as a "WSL2 clock-drift artifact" are **not a measurement artifact** — the
correction is below, in [RUNG 4](#rung-4--settle-the-30s-stall-question).

## RUNG 0 — filesystem and resources

**Filesystem**: the repo is on native ext4, not a Windows drive through `drvfs`.
```
$ pwd -P
/home/ribolli1/projetos/platinumcoin-pix
$ df -T .
Filesystem  Type  ...
/dev/sdd    ext4  ...  1055762868  9699180  992360216  1% /
$ mount | grep -E "drvfs|ext4"
/dev/sdd on / type ext4 (rw,relatime,discard,errors=remount-ro,data=ordered)
```
`C:\` is separately mounted at `/mnt/c` via `drvfs` (9p protocol) but the repo and Docker's own
storage are not on it — filesystem type is ruled out as the cause.

**Docker runtime**: this is **Docker Desktop for Windows** (WSL2 backend), not bare `docker-ce` —
`docker info` reports `Operating System: Docker Desktop`, and there's a second Docker context
(`desktop-linux`) alongside `default`. Containers run inside Docker Desktop's own dedicated WSL2
distro, one virtualization layer removed from this Ubuntu WSL2 distro.

**LocalStack's Docker volumes**: `docker inspect localstack` shows one anonymous named volume
mounted at `/var/lib/localstack` (`ext4`, backed by Docker Desktop's own VM disk) plus the
bind-mounted `infra/localstack/init/` (read-only ready.d scripts). No `PERSISTENCE` env var is set
in `infra/docker-compose.yml`.

**LocalStack's DynamoDB backend — the key finding of this rung**: LocalStack 3 runs the **real AWS
`DynamoDBLocal.jar`** (not an in-house Python emulation), launched as:
```
java -Xmx256m -javaagent:.../ddb-local-loader-0.1.jar \
  -jar DynamoDBLocal.jar -port 34831 -dbPath /tmp/localstack/state/dynamodb
```
No `-inMemory` flag — this is **file-backed mode**, and `/tmp/localstack/state/dynamodb/` contains
`.db` files (SQLite). `-Xmx256m` is a very small heap for a JVM doing continuous JDBC/SQLite
traffic under any sustained load. Image: `localstack/localstack:3`. Env vars:
`SERVICES=dynamodb,sns,sqs`, `DEBUG=1` — no `DYNAMODB_*` or `PROVIDER_OVERRIDE` overrides, so this
is LocalStack 3's default DynamoDB provider behavior, not a local misconfiguration.

**`docker stats` during a 5-VU run**: `localstack` is the only container that shows real CPU
pressure — it spikes to **32-76%** of a core while every other service (payment/ledger/fraud/
account/settlement/auth/redis/mock-bacen-spi) stays under ~10%:
```
NAME                 CPU %
localstack           75.78%
payment-service       7.19%
ledger-service        5.35%
account-service       3.35%
fraud-service          1.99%
... (everything else < 3%)
```
**Conclusion**: one container is doing all the work. RUNG 2 and RUNG 4 pin down what inside it.

## RUNG 1 — is the harness itself the ceiling?

k6 against `payment-service`'s `/actuator/health` (touches nothing downstream), 15s each:

| VUs | throughput | p50 |
|---|---|---|
| 5 | 10,486 req/s | 388 µs |
| 25 | 19,202 req/s | 805 µs |
| 100 | 17,190 req/s | 931 µs |

**Ruled out.** The harness (k6-in-Docker over WSL2 networking) handles four orders of magnitude
more throughput than the observed 8.5 req/s ceiling. k6/Docker-networking is not the bottleneck.

(Side finding, relevant to RUNG 4: even this trivial health-check endpoint occasionally showed
`max=30.7s` and — more tellingly — a **negative** `http_req_duration` sample, `min=-30674087841ns`.
A negative duration is only possible if the wall clock moved backward between k6's start- and
end-of-request reads. This is real evidence of client-side wall-clock instability — but RUNG 4
shows it is not the whole story.)

## RUNG 2 — is DynamoDB Local the serialization point?

A minimal Python/boto3 script (`ThreadPoolExecutor`-free, raw `threading`), `PutItem` directly
against LocalStack (`http://localhost:4566`), no Spring service in the path, on a throwaway table.
Concurrency ramped 1→32, 5s per level, `time.monotonic()` used throughout (immune to the wall-clock
jump that contaminated the first, discarded run — see below):

| concurrency | puts | elapsed | **TPS** |
|---|---|---|---|
| 1 | 223 | 5.02s | 44.4 |
| 2 | 232 | 5.03s | 46.1 |
| 4 | 228 | 5.07s | 45.0 |
| 8 | 234 | 5.16s | 45.4 |
| 16 | 237 | 5.33s | 44.4 |
| 32 | 257 | 5.67s | 45.3 |

**Flat at ~45 TPS from concurrency 1 straight through 32.** Per the hypothesis this rung was
designed to test: this confirms DynamoDB Local's global write-serialization as a real, hard
ceiling — adding client concurrency buys literally nothing.

(A first run using `time.time()` instead of `time.monotonic()` gave a contaminated `concurrency=1`
reading — 34.52s elapsed for a nominal 5s window, because a single call hit the same ~30s stall
RUNG 4 investigates. That's a *client-measurement* symptom of the same underlying WSL2 clock
instability RUNG 4 also found — orthogonal to the concurrency question this rung asks, which is
why the table above uses the monotonic-clock rerun instead.)

## RUNG 3 — decompose the 144ms

One unloaded `POST /v1/payments/pix` (bob → alice), traced end to end via
`X-Correlation-Id: 15136221-...` across `payment-service`/`account-service`/`fraud-service`/
`ledger-service` logs (three more samples taken to rule out a cold-start outlier: 150ms, 146ms,
138ms — all close to the reported baseline; the first sample below is one of these, ~144-150ms
band, not the 281ms first attempt which included a cold-start PutItem):

| step | call | type | cost |
|---|---|---|---|
| 1 | `pix_idempotency` PutItem (conditional claim) | **DynamoDB write** | ~20-140ms (variable — see below) |
| 2 | account-service `GET /internal/pix-keys/resolve` → `pix_keys` GetItem | DynamoDB **read** | ~4ms |
| 3 | account-service `GET /internal/accounts/{id}` → `pix_accounts` GSI Query | DynamoDB **read** | ~4ms |
| 4 | `pix_transactions` UpdateItem (daily-limit reservation, conditional) | **DynamoDB write** | ~7-27ms |
| 5 | fraud-service score (Redis only — no DynamoDB) | Redis | ~10ms |
| 6 | ledger-service → `pix_ledger` **TransactWriteItems**, 5 items (double-entry) | **DynamoDB write** | ~22-23ms |
| 7 | `pix_transactions` **TransactWriteItems** (tx META + outbox event) | **DynamoDB write** | ~23ms |
| 8 | `pix_idempotency` UpdateItem (complete/memoize) | **DynamoDB write** | ~40ms |

**7 DynamoDB round trips, strictly sequential** (each step's log line completes before the next
one starts — verified from the millisecond timestamps, no overlapping windows anywhere in the
trace). **5 of the 7 are writes; 2 are reads that return in ~4ms** (fast — DynamoDB Local's global
lock appears to bind writes far harder than reads, consistent with RUNG 2 measuring pure writes).

**The arithmetic that closes the loop**: RUNG 2 measured DynamoDB Local's raw write ceiling at
~45 ops/s, i.e. ~22ms/write when saturated. `5 sequential writes × 22ms ≈ 110ms`, plus the two
~4ms reads and a few ms of inter-service HTTP overhead, lands at **~120-140ms** — matching the
observed ~138-150ms unloaded latency closely. And for throughput: **`45 writes/s ÷ 5 sequential
writes per request ≈ 9 req/s`** — matching the observed S2 ceiling of 8.5-8.9 req/s almost exactly.

**This is the mechanism.** DynamoDB Local serializes writes globally (RUNG 2); a Pix send performs
5 of them, strictly one after another, with no fan-out or batching (RUNG 3); the two numbers
multiply out to predict both the observed unloaded latency and the observed saturated throughput
within a few percent. Nothing above DynamoDB Local (Spring, Tomcat, the JVMs, k6, WSL2 networking)
needs to be invoked to explain the ceiling — RUNG 1 already ruled the harness out, and `docker
stats` (RUNG 0) already showed only `localstack` under real CPU pressure.

## RUNG 4 — settle the 30s stall question

**This corrects `docs/load/RESULTS.md`.** The original hypothesis (from the earlier
load-measurement session) was: the WSL2 VM's clock is unstable (`timedatectl` reports an
unsynchronized ~-30s offset, `journalctl -k` shows recurring "Time jumped backwards"), and the
~30s stalls seen in ~4-8% of requests were a **client-side measurement artifact** of that
instability, not a real server-side delay. The RESULTS.md decision rule for this rung was
explicit: *"Application ~150ms + k6 ~30s means a measurement artifact. Application ~30s too means
a real stall, and the clock hypothesis is wrong and must be corrected."*

**Application ~30s too. The clock-drift explanation is wrong (or at best, an incomplete
description of a different, correlated symptom) and is corrected below.**

Method: fired requests in a loop against a fresh ring account (locally-minted JWT, same technique
as the k6 scripts) until one stalled (`curl`-measured wall time), then pulled every log line for
that request's `correlationId` across all four services.

Stalled request: `cid=92c7073a-854d-4629-922b-0af1890b0969`, curl-measured **30.84s**.
`payment-service`'s own log for that same request:

```
20:35:48.453  DEBUG  DynamoDB conditional UpdateItem to reserve daily-limit headroom | pk=LIMIT#acc-lt-001
20:36:19.169  DEBUG  Daily-limit headroom reserved | pk=LIMIT#acc-lt-001
```

**A 30.7-second gap between two consecutive log lines inside one synchronous request thread**,
both timestamps written by the same JVM. This is not k6/curl's measurement — it is the
application's own record of its own elapsed time.

That alone doesn't yet distinguish "the JVM thread was really blocked for 30s" from "the
*timestamps* are both individually correct reads of a clock that itself jumped 30s between the two
log calls" — logging timestamps are wall-clock (`System.currentTimeMillis()`-equivalent), exactly
what a clock step would corrupt too. Three further checks separate these:

1. **No kernel clock-jump event coincides with this specific stall.** `journalctl -k --since
   "2026-08-17 20:35:40" --until "2026-08-17 20:36:25"` returns **zero** "Time jumped backwards"
   lines — even though that message fires reliably elsewhere in this same environment. Whatever
   happened during this specific 31-second window, the kernel's own clock-step detector did not
   see a system-wide clock step.
2. **LocalStack's own request log goes silent for the same window, in two chunks, then bursts.**
   `docker compose logs localstack` shows a steady stream of `AWS dynamodb.* => 200` lines (one
   every ~20-100ms) right up to `20:36:04.426`, then **nothing at all until `20:36:14.274`**
   (9.85s of silence), then a burst of 5 completions in 82ms, then **nothing again until
   `20:36:19.168`** (4.8s more), then another burst. This is LocalStack's own process — not a
   client's view of it — going quiet and then catching up all at once. That shape (silence, then a
   burst of everything that was queued) is the signature of a **stop-the-world pause**, not a
   clock adjustment: a clock step would not cause a downstream server process to stop emitting log
   lines and then flush several requests' worth of completions in the same 80ms window.
3. **A sibling container kept its own unrelated schedule through the same window.**
   `settlement-service`'s Docker healthcheck (`GET /actuator/health/readiness`, configured every
   10s) logged at `20:36:04.148` and next at `20:36:14.213` — a completely normal ~10.07s gap,
   not stretched to 30s and not delayed. If the whole VM/host had been frozen or descheduled,
   this healthcheck would have arrived late too. It didn't. The stall is not global; it is
   localized to the LocalStack container specifically.

**Conclusion**: this is a real, multi-second stop-the-world pause **inside LocalStack's
`DynamoDBLocal.jar` process** (`-Xmx256m` — a small heap for continuous JDBC/SQLite traffic; no GC
logging is enabled, so the exact GC algorithm/pause cause isn't directly confirmed, but the
silence-then-burst signature, isolated to one JVM-backed subprocess while every sibling container
keeps its own schedule, is the structural fingerprint of a stop-the-world pause, not a
system clock adjustment). Restart count is 0 and `OOMKilled=false`, so it isn't crashing — it is
pausing and resuming.

**What this means for `docs/load/RESULTS.md`**: the "WSL2 clock-drift" framing should be replaced
(or at minimum heavily caveated) with "periodic multi-second stalls inside the LocalStack/
DynamoDB Local process, plausibly GC pauses given the 256MB heap — confirmed present in the
application's own server-side logs, not just in client-side timing." The two phenomena (kernel
clock-jump events, and these LocalStack-internal stalls) may share a root cause one level up (host
resource pressure on the Docker Desktop VM affecting both JVM scheduling and clock sync
simultaneously) — that is plausible but **not confirmed**, and is exactly the kind of claim RUNG 4
was designed to stop this document from making without evidence. What *is* now directly evidenced:
independent of whatever causes the kernel-level clock jumps, LocalStack's own DynamoDB-serving
process independently stalls for multi-second stretches, and that is real server-side time, not a
measurement artifact.

## What this changes, and what it doesn't

**Unaffected**: S1 (conservation) and S3 (idempotency) — their pass/fail invariants (Σ balances
conserved, zero double postings, exactly-once winner per round) don't depend on latency
interpretation at all; they hold regardless of which rung explains the stalls.

**Needs revision in `docs/load/RESULTS.md`**:
- The "Environment limitation: WSL2 clock drift" section's causal claim is superseded by RUNG 4's
  finding above — the stalls are a real LocalStack-internal pause, not purely a measurement
  artifact of an unstable client/server clock. The 10,000ms trim rule and its `removed_count`/
  `removed_rate` bookkeeping remain valid as a description of *what got excluded*, since that part
  never depended on *why* the excluded samples were large — but the "why" text needs to point here
  instead.
- The capacity-ceiling explanation ("leading suspects... not confirmed") is now confirmed, not
  speculative: RUNG 2 + RUNG 3 together fully explain the ~8.5-8.9 req/s ceiling as DynamoDB
  Local's write-serialization (~45 ops/s) divided by 5 sequential writes/request. This is a
  stronger, evidence-backed claim than the original "leading suspects, unconfirmed" hedge and
  should replace it.
- The **portability caveat stands and is now better justified**: the numbers are still specific to
  this exact environment (LocalStack's DynamoDB Local, this heap size, this machine) — if anything,
  this rung's findings make the non-portability claim *more* defensible (a named, understood
  local-only bottleneck) rather than less.

**Not done in the diagnostic pass itself** (explicitly out of scope — no application code changes
were made during the ladder, per instruction): actually fixing anything. Two follow-ups the ladder
surfaced were later authorized and implemented (below); one remains open.

## RESOLUTION — the fix, applied and verified

Per explicit follow-up instruction, the throughput ceiling was fixed (not just diagnosed) and the
whole S0-S3 suite was rerun to get real numbers. Two things were tried, in order:

1. **LocalStack tuning first** (`DYNAMODB_IN_MEMORY=1`, `DYNAMODB_HEAP_SIZE=1g`, up from the
   256MB-heap file-backed default): raw write throughput went from ~45 ops/s to ~400 ops/s (RUNG 2
   rerun, `time.monotonic()`-based, concurrency 1→32, flat at ~400 throughout). Raising the heap
   further to 3g made no additional difference (~380 ops/s) — the ceiling had stopped being
   heap/GC-bound and become something else.
2. **Reading LocalStack's own source** (`localstack-core/localstack/services/dynamodb/server.py`,
   `rolo/client.py`, inside the running container) found the actual remaining ceiling: LocalStack
   proxies every DynamoDB call through `AwsRequestProxy` → `SimpleRequestsClient`, a plain
   `requests.Session()` with no custom `HTTPAdapter` — meaning `requests`' library-default
   `pool_maxsize=10` caps concurrent backend connections, **not exposed via any LocalStack env
   var**. A raw concurrency sweep (2/6/8/10/12/16/20) already saturated by concurrency ≈ 6-10,
   consistent with a 10-connection pool.

The fix implemented: DynamoDB moved into its own standalone `amazon/dynamodb-local` container
(official AWS image, `-inMemory` mode, no LocalStack proxy in front of it), reachable at
`http://dynamodb-local:8000`. LocalStack keeps SNS/SQS only. This required:
- `infra/docker-compose.yml`: new `dynamodb-local` service; `localstack`'s `SERVICES` dropped
  `dynamodb`; `localstack` now `depends_on: dynamodb-local: condition: service_healthy` (so its
  ready.d scripts, which create the DynamoDB tables, don't race a not-yet-ready backend);
  `localstack`'s own healthcheck now runs `aws dynamodb describe-table` **against
  `dynamodb-local:8000`, from inside the localstack container** (which has the AWS CLI; the
  minimal `amazon/dynamodb-local` image doesn't) — preserving the existing "healthy means seeded"
  contract with a one-line endpoint change, not a redesign.
- `infra/localstack/init/{01,02,03,04,05,07}-*.sh`: `ENDPOINT` changed from `localhost:4566` to
  `dynamodb-local:8000` (script 06, messaging, unchanged).
- Four services (`account-service`, `ledger-service`, `payment-service`, `settlement-service`):
  added `AwsProperties#dynamoDbEndpointUrl` (defaults to `endpointUrl` via
  `${DYNAMODB_ENDPOINT_URL:${aws.endpoint-url}}` in `application.yml`, so `LocalStackTestBase`'s
  Testcontainers ITs — which only override `aws.endpoint-url` — are completely unaffected); each
  service's `DynamoConfig`/`AwsClientsConfig` now builds its `DynamoDbClient` from
  `dynamoDbEndpointUrl` instead of `endpointUrl`, while any `SnsClient`/`SqsClient` in the same
  service keeps using `endpointUrl` (LocalStack).
- `tools/k6/seed/seed-load-test-fixtures.sh` and `tools/k6/verify/{ledger-snapshot,
  check-double-postings}.sh`: endpoint default changed from `localhost:4566` to `localhost:8000`
  (env var renamed `LOCALSTACK_ENDPOINT` → `DYNAMODB_ENDPOINT` for honesty).

**Verified, layer by layer**:
- Raw write throughput (RUNG 2 style, direct `PutItem`, bypassing every Spring service): flat at
  **~800-950 ops/s** across concurrency 4→128 — roughly 2.3x the LocalStack-proxied in-memory
  number, confirming the pool-cap theory.
- `mvn compile` clean across the four touched services before rebuilding the stack.
- End-to-end sanity: a real `POST /v1/payments/pix` returns `202` against the new stack; the
  `localstack` healthcheck passes (proving the cross-container `aws dynamodb describe-table` probe
  works); `aws --endpoint-url=http://localhost:4566 dynamodb list-tables` now correctly answers
  `Service 'dynamodb' is not enabled` (proving the split is real, not just additive).
- Full S0-S3 rerun: application-level Pix-send throughput went from ~8.5 req/s to **~158-201
  req/s** (S2, six stages 5-150 VUs, chosen to stay inside the zero-`req_failed` range on explicit
  instruction — not pushed to failure). Every money invariant (S1 conservation, S3 idempotency)
  still held exactly at the new throughput. Full numbers: `docs/load/RESULTS.md`.
- The WSL2 clock-jump stall (RUNG 4, above) is **unaffected by this fix, as expected** — it is a
  different mechanism (whichever DynamoDB-serving process pauses under load) — but its rate
  dropped from 4.21% to 1.02% at the same S0 baseline (1 VU, no concurrency), consistent with a
  smaller/faster JVM doing less total work pausing less often. Not claimed as confirmed causation.

**Still open** (not pursued — the >=100 req/s target was already cleared): the exact mechanism
behind `dynamodb-local`'s own ~800-950 ops/s ceiling (single-process DynamoDB Local is documented
by AWS as not built for performance testing; no further internal profiling was done since the goal
was reached). If a future goal needs numbers that generalize off this machine, the right move is
pointing S2 at a real (non-Local) DynamoDB target, not further local tuning.
