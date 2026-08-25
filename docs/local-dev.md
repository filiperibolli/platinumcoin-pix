# Local Development Runbook

Everything runs on one machine via docker-compose. Reference hardware: Ryzen 5 8600G (6c/12t), 32GB RAM — the stack is sized to fit with room to spare (each JVM capped at 512MB heap).

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| Docker + Compose v2 | recent | `docker compose version` |
| Java | 21 LTS | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| AWS CLI (talks to LocalStack) | v2 | `aws --version` |
| jq, curl, uuidgen | any | — |

Optional quality-of-life: `awslocal` (`pip install awscli-local`) — an AWS CLI wrapper pre-pointed at LocalStack so you can drop `--endpoint-url`.

One-time AWS CLI setup for LocalStack (credentials are dummies, but must exist):

```bash
aws configure set aws_access_key_id test
aws configure set aws_secret_access_key test
aws configure set region us-east-1
alias awsl='aws --endpoint-url=http://localhost:4566'
# DynamoDB itself lives in its own standalone container, not LocalStack (docs/load/BOTTLENECK.md) —
# `awsl` above (4566) now answers `dynamodb` calls with `501 Service 'dynamodb' is not enabled`.
alias awsd='aws --endpoint-url=http://localhost:8000'
```

## 2. Ports

| Component | Port |
|---|---|
| LocalStack (SNS/SQS — DynamoDB moved out, see below) | 4566 |
| dynamodb-local (standalone, `docs/load/BOTTLENECK.md`) | 8000 |
| Redis | 6379 |
| auth-service | 8081 |
| account-service | 8082 |
| fraud-service | 8083 |
| payment-service | 8084 |
| ledger-service | 8085 |
| settlement-service | 8086 |
| notification-service | 8087 |
| mock-bacen-spi | 9090 |
| Prometheus (step 44) | 9091 (host) → 9090 (container) |
| Grafana (step 44) | 3000 |
| Jaeger UI (step 72) | 16686 |
| OTLP collector — HTTP / gRPC (step 72) | 4318 / 4317 |

## 3. Environment variables (shared conventions)

Set in `infra/docker-compose.yml`; local defaults in each service's `application.yml`.

| Variable | Default | Purpose |
|---|---|---|
| `AWS_ENDPOINT_URL` | `http://localstack:4566` | Point the SDK at LocalStack (SNS/SQS **and S3** — not DynamoDB, see below) |
| `DYNAMODB_ENDPOINT_URL` | `http://dynamodb-local:8000` | Point the DynamoDB client at the standalone `dynamodb-local` container, not LocalStack (`docs/load/BOTTLENECK.md`). Falls back to `AWS_ENDPOINT_URL` if unset (`aws.dynamodb-endpoint-url: ${DYNAMODB_ENDPOINT_URL:${aws.endpoint-url}}`) — so `LocalStackTestBase` ITs, which only override `aws.endpoint-url`, are unaffected, but a service run **outside compose** (e.g. `spring-boot:run`, §5.x below) must set this explicitly or its DynamoDB calls will hit LocalStack, which no longer serves it (`501 Service 'dynamodb' is not enabled`) |
| `AWS_REGION` | `us-east-1` | — |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Placeholder creds, read **only under the `local` profile** (ADR-0013). LocalStack validates no signature and reads the key only to derive the account id `000000000000` — a signing formality, not authentication |
| `SPRING_PROFILES_ACTIVE` | `local` (compose default) | **Since step 45 this is load-bearing.** The `local` profile is the only thing that hands an AWS client an endpoint override and those placeholder credentials; without it the SDK's `DefaultCredentialsProvider` chain looks for an ambient role, finds none, and the service **fails loudly at startup** instead of quietly reaching LocalStack while looking configured for production (ADR-0013). If you export this variable yourself, **include `local`** — e.g. `SPRING_PROFILES_ACTIVE=json-logs,local`. The other profiles: `json-logs` (structured logging, ADR-0012) and `loadtest` (fraud-service velocity thresholds, `docs/load/RESULTS.md`) |
| `JWT_SECRET` | dev-only value in compose | HS256 signing/validation |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` | Redis (ElastiCache stand-in, ADR-0008). Read by **fraud-service** (velocity counters, step 24), **payment-service** (balance cache-aside, step 40) and **ledger-service** (which only *deletes* `balance:` keys after a posting — it never reads one) |
| `BALANCE_CACHE_TTL` | `5s` | How long a cached balance may be served (payment-service). Doubles as the backstop when the ledger's best-effort eviction is lost: it bounds staleness to this window. Raising it trades hit rate for how long a customer can see a number that already moved |
| `BACEN_BASE_URL` | `http://mock-bacen-spi:9090` | SPI stub. Read by account-service (the DICT lookup for keys not held locally, step 30) and later by settlement-service |
| `BACEN_CONNECT_TIMEOUT_MS` / `BACEN_READ_TIMEOUT_MS` | `500` / `1500` | account-service's budget for the DICT call — it is on the **synchronous** send path, so a gone SPI must surface as a timeout (→ `503 DIRECTORY_UNAVAILABLE`), never a pinned request thread |
| `BACEN_LATENCY_MS` | `2000` | Simulated **settlement** latency (0–10000). Not applied to the DICT lookup, on purpose (see `services/mock-bacen-spi/README.md`) |
| `BACEN_FAILURE_RATE` | `0.0` | Fraction of settlement calls answered `503` with **nothing recorded** — transient, so the same `endToEndId` can still settle on a retry |
| `BACEN_TIMEOUT_RATE` | `0.0` | Fraction of settlement calls that **settle and then hang** — BACEN moved the money, the caller never heard. The query-before-retry case (step 32) |
| `BACEN_TIMEOUT_HANG_MS` | `15000` | How long such a call hangs; must exceed the client's own 12s timeout (step 31) or a "timeout" is just a slow success. Boot-time only — `POST /admin/config` cannot lower it mid-drill |
| `SPI_WEBHOOK_TOKEN` | dev-only value in compose | Authenticates mock-bacen's inbound webhook calls to settlement-service (step 37). Set on **both** services and they must match — a mismatch surfaces as `422 INBOUND_REFUSED` (the participant answered `401`), which is the threat-model boundary working, not a bug. settlement-service defaults it to **empty**, and an empty token refuses every delivery: a misconfiguration on a money-crediting route fails closed |
| `SETTLEMENT_BASE_URL` | `http://settlement-service:8086` | Where mock-bacen presents an inbound Pix. Resolved per call, never at boot — mock-bacen must not depend on settlement-service to start, or compose would have a dependency cycle (settlement already gates on the stub) |
| `INBOUND_MAX_ATTEMPTS` / `INBOUND_RETRY_DELAY_MS` | `3` / `500` | How often the rail re-presents a payment whose outcome it does not know (`5xx`/no answer). A `4xx` is **never** retried — it is a decision, and a real rail bounces it back to the payer's PSP |
| `PIX_CLEARING_ACCOUNT_ID` | `SPI_CLEARING` | Where an external send parks money (step 27) and where an inbound Pix draws it from (step 37). Must be the **same** id in payment-service and settlement-service, or the two directions stop netting against one balance |
| `FRAUD_TIMEOUT_MS` | `200` | Fraud budget in payment-service |
| `SNS_TOPIC_ARN` | `arn:aws:sns:us-east-1:000000000000:pix-events` | Topic the outbox publisher drains into (injected, never looked up by name — ADR-0013) |
| `OUTBOX_PUBLISHER_DELAY_MS` | `1000` | Outbox poll interval (`fixedDelay`, so ticks never overlap) |
| `OUTBOX_PUBLISHER_BATCH_SIZE` | `25` | Max events one tick may publish |
| `PIX_SCHEDULERS_ENABLED` | `true` | Master switch for background jobs; integration tests set it `false` and drive each tick explicitly |
| `NOTIFICATION_QUEUE_NAME` | `notification-queue` | The queue notification-service consumes (step 38). Resolved to its URL at **startup**, like settlement's — a push service that boots healthy while consuming nothing is the worst failure mode it has |
| `NOTIFICATION_STREAM_TIMEOUT_MS` | `1800000` (30 min) | How long one SSE connection may live before the server closes it. A closing stream is a non-event: `EventSource` reconnects on its own, which is much of why SSE was chosen over WebSocket |
| `NOTIFICATION_HEARTBEAT_DELAY_MS` | `25000` | Heartbeat sweep interval. Under the ~30s idle timeout common in proxies/load balancers, so a silent stream is never reclaimed underneath us — and it doubles as the registry's garbage collector, since a vanished client is only discovered by a failed write |
| `NOTIFICATION_TOKEN_PARAM` | `access_token` | Query parameter the SSE handshake accepts a token in, because a browser's `EventSource` cannot set headers. **Blank it to accept only the `Authorization` header.** The route is *not* on the JWT allow-list: the parameter is rewritten into a header before common-lib's filter runs, so there is still exactly one JWT verifier in the platform |
| `AUDIT_QUEUE_NAME` | `audit-queue` | The **unfiltered** queue settlement-service's audit writer consumes (step 43). Resolved to its URL at startup — an audit writer that boots healthy while consuming nothing leaves a five-year compliance obligation silently unmet |
| `AUDIT_BUCKET` | `pix-audit-log` | The immutable trail. Object Lock COMPLIANCE + 5-year retention are **bucket defaults**, so the writer never asks for retention and can never forget to |
| `AUDIT_WRITER_NAME` | `settlement-service` | The `<service>` segment of `yyyy/MM/dd/HH/<service>-<uuid>.jsonl` — who wrote the object |
| `AUDIT_BATCH_MAX_EVENTS` / `AUDIT_BATCH_MAX_AGE_SECONDS` | `100` / `30` | The cost/latency dial: an object is written when it holds 100 events **or** when its *oldest* buffered event has waited 30s. Raising the count means fewer, larger objects (cheaper, faster to scan) at the price of holding an event in memory longer — durably held by SQS the whole time |
| `AUDIT_LEASE_SECONDS` | `120` | How long the writer owns a buffered message. **Must exceed** `AUDIT_BATCH_MAX_AGE_SECONDS` plus the write: the batch holds messages past the queue's own 30s visibility timeout, so whoever holds a message extends its lease or SQS hands it to another receiver and the line is written twice for nothing |
| `AUDIT_BATCH_SIZE` / `AUDIT_WAIT_TIME_SECONDS` / `AUDIT_CONSUMER_DELAY_MS` | `10` / `20` / `500` | audit-queue long-poll tuning. The wait is capped at runtime by the time left before the flush deadline — a static 20s poll against a 30s promise would let a batch age up to 50s |
| `STATEMENT_ARCHIVE_BUCKET` | `pix-statement-archive` | The cold statement archive (step 43), written by **ledger-service** — the owner of `pix_ledger` (ADR-0006). A deliberately *plain* bucket: derived, rebuildable data whose monthly object each run rewrites whole |
| `STATEMENT_ARCHIVE_HOT_WINDOW_DAYS` | `90` (**`0` in compose**) | Where the online statement ends and the archive begins. Compose overrides it to `0` on purpose: a freshly seeded sandbox has no entry older than 90 days, so the demo job would archive nothing and look broken. With `0` the cutoff is "now" and every posting is archived on the next run |
| `STATEMENT_ARCHIVE_DELAY_MS` | `3600000` (**`60000` in compose**) | How often the archive job runs — hourly in production (batch work over cold data), every minute in the sandbox so a demo does not wait an hour |
| `STATEMENT_ARCHIVE_MAX_ACCOUNTS` | `500` | Per-run bound on the account **scan**. A larger ledger degrades into more runs rather than one enormous one |
| `NOTIFICATION_BATCH_SIZE` / `NOTIFICATION_WAIT_TIME_SECONDS` / `NOTIFICATION_CONSUMER_DELAY_MS` | `10` / `20` / `500` | notification-queue long-poll tuning. Batch is larger than settlement's 5 because handling one message is a map lookup and a socket write, not a 12s call to BACEN |

### 3.1 Calling an internal port by hand (service tokens — step 68, ADR-0017)

`/internal/**` is where services talk to each other, and since step 68 it accepts **only a service
token**. A token from `POST /v1/auth/login` gets `403 INTERNAL_PORT_FORBIDDEN` there, and a service
token gets `403 PUBLIC_ROUTE_FORBIDDEN` on `/v1/**` — the two surfaces are disjoint in both directions.

`scripts/service-token.sh <audience> <scope>` mints what the services themselves mint:

```bash
scripts/service-token.sh ledger-service ledger:read
# eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsb2NhbC1jbGkiLCJ0eXAiOiJzZXJ2aWNlIiwi…
```

| Audience (`aud`) | Scope | Opens |
|---|---|---|
| `ledger-service` | `ledger:post` | `POST /internal/ledger/postings` |
| `ledger-service` | `ledger:read` | `GET /internal/ledger/accounts/{id}/balance` · `…/entries` |
| `account-service` | `accounts:read` | `GET /internal/accounts/{id}` |
| `account-service` | `keys:resolve` | `GET /internal/pix-keys/resolve` |
| `fraud-service` | `fraud:score` | `POST /internal/fraud/score` |

The pairs are not decoration: `aud` and `scope` are both checked, so a `ledger:read` token cannot post
and a `ledger-service` token is refused by fraud-service. If a call 403s, the service log says which of
the three checks (`typ`, `aud`, `scope`) refused it and with what claims — that WARN line is the fastest
way to the answer, and it never prints the token.

> **This script cannot exist in a real deployment, and that is the point.** It works only because the
> local build signs every token with one shared HS256 secret (ADR-0007), so a human at a terminal can
> impersonate any service. In production the identity is the workload's own credential — RS256 + JWKS
> or mTLS, per ADR-0017's rejected alternatives — and there is no equivalent of this file. The claim
> shape (`typ`/`iss`/`aud`/`scope`) is the part chosen to survive that swap.


## 4. Bring it up

```bash
# from repo root
mvn clean package -DskipTests                # build all service jars
docker compose -f infra/docker-compose.yml up -d --build

# watch LocalStack init (creates tables/queues/topics/buckets + seed data)
# NOTE: there is no separate `localstack-init` container — the ready.d scripts run
# *inside* the `localstack` service, so their output is in that service's logs.
docker compose -f infra/docker-compose.yml logs -f localstack

# just the init lines (every script prefixes its output with `[init]`):
docker compose -f infra/docker-compose.yml logs localstack | grep '\[init\]'

# health of everything that exists *so far* (vertical delivery — the list grows per
# sprint; querying a port whose service hasn't been built yet just fails to connect)
for p in 8081 8082 8083 8084 8085 8086 8087; do
  echo -n "$p: "; curl -s localhost:$p/actuator/health | jq -r .status; done
# full set, once the whole platform is built:
#   8081 8082 8083 8084 8085 8086 8087 9090

# Redis is up (Sprint 5, ADR-0008 — its own container, the ElastiCache stand-in):
docker compose -f infra/docker-compose.yml exec redis redis-cli ping   # PONG
```

Tear down: `docker compose -f infra/docker-compose.yml down -v` (`-v` wipes LocalStack/Redis data → next `up` reseeds a clean world).

> **Step 23 (fraud-service skeleton + Redis).** fraud-service (8083) and `redis` (6379) now come up
> with the stack. The service is a skeleton — only `GET /actuator/health` is reachable (the scoring
> `POST /internal/fraud/score` is step 24). Verify: `curl -s localhost:8083/actuator/health | jq` ⇒
> `{"status":"UP"}` and `docker compose ... exec redis redis-cli ping` ⇒ `PONG`. fraud-service gates
> its own startup on `redis` being healthy (`depends_on: service_healthy`), so a `redis` that fails to
> boot keeps fraud-service out of the healthy set rather than letting it come up half-wired.

> **Step 26 (messaging backbone).** LocalStack now runs `SERVICES=sns,sqs` (DynamoDB was later split out
> into the standalone `dynamodb-local` container — see §2 above) and `up` creates the
> SNS topic `pix-events`, `settlement-queue` + `settlement-queue-dlq` (redrive after 5 receives) and the
> filtered subscription. Nothing publishes or consumes yet — the producer is the outbox publisher (step
> 29) and the consumer is settlement-service (step 31) — so the queues come up **empty on purpose**.
> Verify with `aws --endpoint-url=http://localhost:4566 sns list-topics | jq` and `… sqs list-queues | jq`
> (the full command set is in §4, "Messaging"); this script's own init log line is `[init] messaging
> ready: …`. (The readiness marker the test harness waits on has since moved to the last init script —
> `07` in step 29, then `08` in step 36 — see the step-31 callout below.)

> **Step 30 (mock-bacen-spi).** `mock-bacen-spi` (9090) now comes up with the stack — the SPI rail
> (`POST /spi/settlements`, idempotent by `endToEndId`, with runtime-armable latency/failure/timeout) plus
> BACEN's DICT (`GET /spi/dict/{key}`). It depends on nothing: no AWS, no Redis, no token (BACEN is outside
> PlatinumCoin's trust domain — a real participant would present mTLS + an ICP-Brasil certificate). Its
> settlements live **in memory**, so restarting it wipes BACEN's memory — a drill, not a defect.
>
> Two things become true here. First, an **external key finally resolves**: account-service delegates any key
> it does not hold to the DICT, so `bob@otherbank.com` answers `{internal:false, externalBank:"99999999"}`
> and the external send path is live end to end (it had been the one gap left open by steps 27–29). Second,
> the SPI is **armable at runtime** (`POST /admin/config`), which is what makes §5.5's drill expressible at
> all — a boot-time-only configuration cannot say "fail the next five attempts".
>
> ```bash
> curl -s localhost:9090/actuator/health | jq                      # {"status":"UP"}
> curl -s localhost:9090/spi/dict/bob@otherbank.com | jq           # ispb 99999999, Banco OtherBank S.A.
> curl -s localhost:9090/admin/config | jq                         # what is armed right now
> curl -s "localhost:8082/internal/pix-keys/resolve?key=bob@otherbank.com" \
>   -H "Authorization: Bearer $(scripts/service-token.sh account-service keys:resolve)" \
>   | jq                                                           # {internal:false, externalBank:"99999999"}
> ```
>
> Nothing consumes `POST /spi/settlements` yet — settlement-service is step 31 — so the rail comes up idle
> **on purpose**; it is exercised by hand (`services/mock-bacen-spi/README.md`) until then.

> **Step 31 (settlement-service).** `settlement-service` (8086) now comes up with the stack and the
> external send **completes end to end**: it long-polls `settlement-queue`, dedupes by `eventId`, drives
> `DEBITED → SENT_TO_SPI` (guarded, *before* the call), settles against the rail with a 12s timeout, then
> `SENT_TO_SPI → SETTLED` together with a `PixSettled` outbox event in one atomic write. Gates its startup
> on `localstack` (it resolves the queue URL at boot) and on `mock-bacen-spi`.
>
> It exposes **no business endpoint** — the only HTTP surface is the actuator one, so there is nothing in
> Postman or the API explorer for it. Watch it work instead:
>
> ```bash
> curl -s localhost:8086/actuator/health | jq                       # {"status":"UP"}
> # send an external Pix (§5.3 with bob@otherbank.com), then:
> watch -n1 "curl -s localhost:8084/v1/payments/\$TX -H 'Authorization: Bearer $TOKEN' | jq .status"
> #   PROCESSING → SETTLED, after BACEN_LATENCY_MS (2s in compose)
> docker compose -f infra/docker-compose.yml logs -f settlement-service   # one line per stage (ADR-0012)
> ```
>
> **One infra change came with it:** LocalStack's compose healthcheck now means *"seeded"*, not merely
> *"the emulator answers"*. The emulator opens port 4566 and reports UP **before** its `ready.d` scripts
> finish, and nothing noticed until now because every earlier service touches AWS lazily, on the first
> request. A consumer resolves its queue at **startup**, so settlement-service died on first boot with
> `QueueDoesNotExist`. The probe now also asserts a resource created by the *last* init script exists —
> scripts run in lexical order, so that one existing means all of them ran. Since step 42 the last script
> is `09-audit.sh`, so the probe checks the `pix-statement-archive` bucket (via `aws s3api head-bucket`
> against LocalStack itself at `localhost:4566`, since S3 and SQS live there — unlike DynamoDB, which
> moved to `dynamodb-local`). Compose and the Testcontainers harness now agree on what readiness means
> (`LocalStackTestBase` waits on that same script's final log line, `[init] audit storage ready: …`).
> **If you add an init script that sorts after `09`, move both markers.**
>
> Two caveats worth knowing before §5.5's drill: retries, backoff and DLQ handling are **step 32** — today
> a failed or timed-out settlement simply leaves the message on the queue (which is what makes those
> retries possible, since the dedup claim is released), and a permanent `422` refusal is left for the
> reversal of step 33. And `PixSettled` is *written* here but *published* by payment-service's outbox
> publisher, which drains the whole sparse index of `pix_transactions` — so a stopped payment-service
> means settled payments that nobody is told about yet.

> **Step 38 (notification-service).** `notification-service` (8087) now comes up with the stack, and the
> receive flow finally ends where it should — on the customer's screen. It long-polls `notification-queue`
> (filtered to `PixSettled`, `PixReceived`, `PixReversed` since step 36), dedupes by `eventId` against the
> shared `pix_processed_events` table, and pushes each event onto the **affected account's** live SSE
> stream: an outcome of a send goes to the payer, an arrival goes to the payee. Gates its startup on
> `localstack` (it resolves the queue URL at boot) and `dynamodb-local`.
>
> It is the platform's first **long-lived-connection** service, and that changes what to watch for. Try it
> with two terminals:
>
> ```bash
> # terminal 1 — bob holds a stream open
> BOB=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
>   -d '{"username":"bob","password":"bob"}' | jq -r .accessToken)
> curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $BOB"
> #   :connected sub-…                    ← the handshake frame (an SSE comment)
> #   :ping                               ← every 25s, so proxies never reclaim an idle stream
>
> # terminal 2 — make money arrive (register bob@platinum.com first, account-service)
> curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
>   -d '{"pixKey":"bob@platinum.com","amount":"12.34","payerName":"Carol"}'
> # terminal 1 now shows:  event:PixReceived / id:evt-… / data:{…"amount":"12.34"…}   (step 39 shape)
>
> # the browser-shaped handshake: EventSource cannot set headers, so the token goes in the query string
> curl -N "localhost:8087/v1/notifications/stream?access_token=$BOB"
> curl -si localhost:8087/v1/notifications/stream | head -1     # 401 — the route is NOT public
> ```
>
> **Two things that surprise people here.** A customer closing the app sends this server *nothing it will
> notice* — an async response that is not being written to never learns its socket is gone — so the
> heartbeat is what discovers dead connections, not a callback; a push service without one leaks a
> registration per customer who ever connected. And the registry is **per-instance**: a second replica
> behind a load balancer would only reach the customers connected to *it* (single-instance locally; the
> production shape is a shared pub/sub fan-out).
>
> **Step 39** completed the payload: the `data:` line is now one standardized shape for all three events,
> on the same external status vocabulary `GET /payments/{transactionId}` answers — see §5.6.1. Still open,
> and by design: the payee of an *internal* send is not notified (an internal Pix emits one `PixSettled`,
> which means "your send completed" and belongs to the payer; the arrival event is the producer's to emit).
> Also note nothing depends on this service — a missed push degrades UX, never correctness, because every
> outcome stays queryable on `GET /payments/{transactionId}`.

### How the build works (there is no service image in git)

The repo versions the **recipe** (each `services/<name>/Dockerfile`), never the built image. Service images
like `platinumcoin/auth-service` don't exist until *you* build them locally, and this project is **100% local
by design** (ADR/CLAUDE.md: no Kubernetes, no registry) — **images are never pushed to a registry, not even at
the end**. So the first `docker compose up` on a fresh clone has nothing to run until it builds.

There is a hard **ordering** you must respect, and it's the usual first stumble:

1. `mvn clean package` produces the fat jars under each `services/<name>/target/`.
2. `docker compose ... up -d --build` builds each image — its Dockerfile does `COPY services/<name>/target/*.jar`.

If you skip step 1 (or change code and don't rebuild the jar), the image build fails or ships a **stale** jar —
the container runs old code. Rule of thumb: **touched Java → re-run `mvn package` → `up --build`**.

You do **not** `cd` into a service and run `docker build` by hand: `docker compose --build` reads each service's
`build.context`/`build.dockerfile` and builds all of them in one shot. The layered Dockerfile keeps it fast — a
code-only change re-ships just the tiny `application/` layer; dependencies stay cached.

### Iterating on a single service (skip Docker)

Docker Compose is for the **integrated stack** (services + LocalStack + Redis talking to each other). When you're
hacking on **one** service, running it straight from Maven is a much faster inner loop — no image rebuild:

```bash
# from repo root — auth-service needs no AWS/Redis, so it runs standalone
JWT_SECRET=dev-only-hs256-secret-change-me-please-32b \
  mvn -pl services/auth-service spring-boot:run
# or, after `mvn package`:  java -jar services/auth-service/target/*.jar

# an AWS-touching service (account/ledger/payment/settlement/notification) run this way needs THREE
# things, and forgetting any one of them fails differently:
#   1. the rest of the compose stack up (`docker compose up -d`)
#   2. SPRING_PROFILES_ACTIVE=local — since step 45 (ADR-0013) it is the ONLY thing that hands the SDK
#      an endpoint override and credentials. Without it you get a startup failure from the credential
#      chain ("Unable to load credentials from any of the providers"), NOT a connection error — which
#      is the point: a service that looks configured for production must not quietly reach an emulator.
#   3. both AWS endpoints pointed at the host-published ports — DYNAMODB_ENDPOINT_URL does NOT default
#      to the right thing outside compose
JWT_SECRET=dev-only-hs256-secret-change-me-please-32b \
  SPRING_PROFILES_ACTIVE=local \
  AWS_ENDPOINT_URL=http://localhost:4566 DYNAMODB_ENDPOINT_URL=http://localhost:8000 \
  mvn -pl services/ledger-service spring-boot:run
```

Pick the loop by what you're doing: **`spring-boot:run` / `java -jar`** for one service, **`mvn verify`** for tests
(Testcontainers boots its own LocalStack/Redis — the compose stack does *not* need to be up), **`docker compose
up`** only when you want several services wired together.

### What the init scripts do (`infra/localstack/init/*.sh`)

LocalStack executes scripts in `/etc/localstack/init/ready.d/` once the emulator is ready — this is the standard "infrastructure as init script" pattern for local AWS. The scripts are **added incrementally, one flow at a time** (vertical delivery — see `PLAN.md`): each sprint enables the LocalStack `SERVICES` and creates the resources its flow needs, so a partial checkout only stands up what the built flows use. Once the whole platform is built, the full set below runs on every `up`:

**DynamoDB tables** — accounts/keys (`pix_accounts`, `pix_keys`; step 07), ledger (`pix_ledger`, GSI1; step 12), transactions (`pix_transactions` with GSI1/GSI2 and the sparse GSI3 outbox index) + idempotency (`pix_idempotency`, TTL; step 17), and consumer dedup (`pix_processed_events`, TTL; step 29).

**Messaging** — SNS topic `pix-events` + `settlement-queue`(+DLQ) with a filtered subscription (step 26); `notification-queue`(+DLQ, filtered) (step 36); `audit-queue`(+DLQ, unfiltered — all events) (step 42); `statement-export-queue`(+DLQ) (step 53). Filter policies route by `eventType`.

**S3** — buckets `pix-audit-log` (versioning + Object Lock COMPLIANCE, 5-year retention) and `pix-statement-archive` (plain, rewritable) (step 42); `pix-statement-exports` (step 53).

**Seed data** — demo accounts alice/bob with daily limits (step 07) and initial ledger balances R$ 10,000.00 each funded from `ACCOUNT#SEED` (with the matching `SEED_FUNDING` entries on both sides), plus system account `SPI_CLEARING` at 0 — so Σ over every account is **zero** (step 12). Pix keys are registered via the API, not seeded.

The LocalStack `SERVICES` env grows across sprints: `dynamodb` (Sprint 2) → `+sns,sqs` (Sprint 6, step 26) → `+s3` (Sprint 10, **already flipped** — step 42). The list is **enforced**: calling a service that is not on it answers `501 Service 'sqs' is not enabled`, so enabling the service and creating its resources always land in the same change (and so does the matching `withServices(...)` in `LocalStackTestBase`).

**Docker-compose only, as of `docs/load/BOTTLENECK.md`:** `dynamodb` has been pulled back out of the compose stack's `SERVICES` list into the standalone `dynamodb-local` container above — a load-test-driven fix for LocalStack's own DynamoDB-proxy throughput ceiling, not an architecture change. `LocalStackTestBase` (Testcontainers, `mvn verify`) is **unaffected**: its LocalStack container still serves `dynamodb` exactly as before, so the sprint-by-sprint `SERVICES` growth described above remains accurate for the IT harness — only the docker-compose stack's DynamoDB moved.

#### Table DDL (mirror of `infra/localstack/init/*.sh`)

The exact `create-table` commands the init scripts run — kept here verbatim so the schema is reviewable without reading the scripts, and runnable by hand against a running LocalStack (`aws --endpoint-url=http://localhost:4566 ...`). Added incrementally, one sprint at a time.

**Step 07 — `pix_accounts` + `pix_keys`** (both PAY_PER_REQUEST, one `gsi1` on `gsi1pk`, no TTL):

```bash
aws --endpoint-url=http://localhost:8000 dynamodb create-table \
  --table-name pix_accounts \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes \
      '[{"IndexName":"gsi1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST

aws --endpoint-url=http://localhost:8000 dynamodb create-table \
  --table-name pix_keys \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes \
      '[{"IndexName":"gsi1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST
```

**Step 12 — `pix_ledger`** (PAY_PER_REQUEST; `gsi1` on `gsi1pk` = `TX#<txId>`, sparse because only
`ENTRY#…` items carry it — it answers "both legs of transaction T", which the base table can't since
the legs sit in two different account partitions):

```bash
aws --endpoint-url=http://localhost:8000 dynamodb create-table \
  --table-name pix_ledger \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes \
      '[{"IndexName":"gsi1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST
```

Reading the seeded money supply (`05-seed-ledger.sh`) — alice at R$ 10,000.00, and the four balances
that must sum to **zero**:

```bash
aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_ledger \
  --key '{"pk":{"S":"ACCOUNT#acc-001"},"sk":{"S":"BALANCE"}}'   # balanceCents 1000000, version 0

for a in acc-001 acc-002 SPI_CLEARING SEED; do
  aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_ledger \
    --key "{\"pk\":{\"S\":\"ACCOUNT#$a\"},\"sk\":{\"S\":\"BALANCE\"}}" \
    --query 'Item.balanceCents.N' --output text
done | paste -sd+ | bc                                          # 0 — conservation baseline

# both legs of a seed funding transaction, via GSI1
aws --endpoint-url=http://localhost:8000 dynamodb query --table-name pix_ledger \
  --index-name gsi1 --key-condition-expression 'gsi1pk = :t' \
  --expression-attribute-values '{":t":{"S":"TX#tx-seed-alice"}}'
```

**Step 13 — the same money supply through ledger-service** (`:8085`), which is how every other
service is allowed to read it (ADR-0006: the ledger owns the table). The read is strongly consistent
(`ConsistentRead=true`) because the ledger must read its own writes.

> **Since step 68 (ADR-0017), `/internal/**` refuses a user's login token.** Internal ports accept
> only a **service** token: `typ=service`, addressed to the target service (`aud`) and scoped to one
> operation (`scope`). A token from `/v1/auth/login` now gets **`403 INTERNAL_PORT_FORBIDDEN`** here —
> that is the control working, not a broken runbook. `scripts/service-token.sh <aud> <scope>` mints
> exactly what payment-service mints; see §3.1 for the pairs and for why this script cannot exist in a
> real deployment.

```bash
# ledger:read opens the balance and statement reads — and nothing else. Posting needs ledger:post.
LEDGER_READ=$(scripts/service-token.sh ledger-service ledger:read)
LEDGER_POST=$(scripts/service-token.sh ledger-service ledger:post)

# {"accountId":"acc-001","balance":"10000.00","balanceCents":1000000,"version":0}
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $LEDGER_READ" | jq

# the same Σ = 0 as the raw get-item loop above, now through the service
for a in acc-001 acc-002 SPI_CLEARING SEED; do
  curl -s "localhost:8085/internal/ledger/accounts/$a/balance" \
    -H "Authorization: Bearer $LEDGER_READ" | jq -r .balanceCents
done | paste -sd+ | bc                                          # 0 — conservation baseline

curl -s localhost:8085/internal/ledger/accounts/acc-999/balance \
  -H "Authorization: Bearer $LEDGER_READ" | jq   # 404 LEDGER_ACCOUNT_NOT_FOUND — never a zero balance
curl -si localhost:8085/internal/ledger/accounts/acc-001/balance | head -1   # 401 without a token

# The step-68 control, from the outside. A REAL user token — alice's own login — on the ledger's
# money-moving endpoint. Before step 68 this returned 200 and moved bob's money.
USER_TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-probe","debitAccount":"acc-002","creditAccount":"acc-001","amountCents":100,"entryType":"PIX_INTERNAL","description":"probe"}' \
  | jq .code    # INTERNAL_PORT_FORBIDDEN (403)
# …and the reverse direction: a service token on a customer-facing route.
curl -s localhost:8082/v1/accounts/me -H "Authorization: Bearer $(scripts/service-token.sh account-service accounts:read)" \
  | jq .code    # PUBLIC_ROUTE_FORBIDDEN (403)
# …and a token scoped for the wrong operation: ledger:read cannot post.
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_READ" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-probe-2","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":100,"entryType":"PIX_INTERNAL"}' \
  | jq .code    # INTERNAL_PORT_FORBIDDEN (403) — same service, wrong scope
```

**Step 14 — moving money: the atomic double-entry posting.** One `TransactWriteItems` of five items
(`docs/data-model.md` §3); every guard is a condition *inside* it, so a refusal writes nothing at all.

```bash
POSTING='{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002",
          "amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}'

# 200 — {"txId":"tx-manual-1",…,"amount":"125.50","amountCents":12550,"replayed":false,…}
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_POST" \
  -H 'Content-Type: application/json' -d "$POSTING" | jq

# alice 10000.00 → 9874.50, bob 10000.00 → 10125.50, and Σ over the four accounts is still 0
for a in acc-001 acc-002 SPI_CLEARING SEED; do
  curl -s "localhost:8085/internal/ledger/accounts/$a/balance" \
    -H "Authorization: Bearer $LEDGER_READ" | jq -r .balanceCents
done | paste -sd+ | bc                                          # 0 — money moved, none created

# IDEMPOTENCY: send the identical request again ⇒ 200 "replayed": true, balance unchanged.
# Note the *postedAt* it returns is the first posting's instant, not now.
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_POST" \
  -H 'Content-Type: application/json' -d "$POSTING" | jq '{replayed, postedAt}'
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance \
  -H "Authorization: Bearer $LEDGER_READ" | jq -r .balance     # 9874.50 — once, not twice

# the guard item behind that: keyed by txId alone, so the clock is not part of a posting's identity
aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_ledger \
  --key '{"pk":{"S":"TX#tx-manual-1"},"sk":{"S":"POSTING"}}'
# …and GSI1 still returns exactly the two legs (the guard carries no gsi1pk)
aws --endpoint-url=http://localhost:8000 dynamodb query --table-name pix_ledger \
  --index-name gsi1 --key-condition-expression 'gsi1pk = :t' \
  --expression-attribute-values '{":t":{"S":"TX#tx-manual-1"}}' | jq '.Count'   # 2

# the refusals — each returns problem+json and writes NOTHING
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_POST" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":99,"entryType":"PIX_INTERNAL"}' \
  | jq .code    # POSTING_TXID_MISMATCH (409) — same identity, different money
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_POST" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-2","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":99999999,"entryType":"PIX_INTERNAL"}' \
  | jq .code    # INSUFFICIENT_FUNDS (422) — the condition failed inside the transaction
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_POST" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-3","debitAccount":"acc-001","creditAccount":"acc-001","amountCents":100,"entryType":"PIX_INTERNAL"}' \
  | jq .code    # INVALID_POSTING (422) — both legs on one account moves no money
```

> A posting changes the seeded money supply, so re-running the §4 "Σ = 0" checks after this section
> gives the *moved* balances (the sum stays 0). `docker compose -f infra/docker-compose.yml down -v`
> resets everything and reseeds deterministically.

**Step 17 — `pix_transactions` + `pix_idempotency`** (payment-service; on-demand, no seed rows). All
three GSIs are created up front (unlike LSIs, GSIs *can* be added later, but backfilling a fat table
is slow): `gsi1` on `E2E#<endToEndId>` (reconciliation / inbound dedup), `gsi2` on
`STATUS#<status>`+`updatedAt` (stuck-transaction scan), and the **sparse** `gsi3` on
`OUTBOX#UNPUBLISHED`+`occurredAt` (the outbox publisher's work queue — only unpublished items carry
`gsi3pk`, so the index stays O(in-flight)).

```bash
aws --endpoint-url=http://localhost:8000 dynamodb create-table \
  --table-name pix_transactions \
  --attribute-definitions \
      AttributeName=pk,AttributeType=S \
      AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S \
      AttributeName=gsi2pk,AttributeType=S AttributeName=gsi2sk,AttributeType=S \
      AttributeName=gsi3pk,AttributeType=S AttributeName=gsi3sk,AttributeType=S \
  --key-schema \
      AttributeName=pk,KeyType=HASH \
      AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes '[
      {"IndexName":"gsi1","KeySchema":[{"AttributeName":"gsi1pk","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}},
      {"IndexName":"gsi2","KeySchema":[{"AttributeName":"gsi2pk","KeyType":"HASH"},{"AttributeName":"gsi2sk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}},
      {"IndexName":"gsi3","KeySchema":[{"AttributeName":"gsi3pk","KeyType":"HASH"},{"AttributeName":"gsi3sk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}
  ]' \
  --billing-mode PAY_PER_REQUEST

aws --endpoint-url=http://localhost:8000 dynamodb create-table \
  --table-name pix_idempotency \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

# TTL is a separate call (not part of create-table); DynamoDB deletes expired items lazily
aws --endpoint-url=http://localhost:8000 dynamodb update-time-to-live \
  --table-name pix_idempotency \
  --time-to-live-specification 'Enabled=true,AttributeName=expiresAt'
```

Verify the tables and their indexes (the init script `03-dynamodb-payment.sh` runs the above on `up`):

```bash
# gsi1, gsi2, gsi3 — all three present
aws --endpoint-url=http://localhost:8000 dynamodb describe-table --table-name pix_transactions \
  | jq '.Table.GlobalSecondaryIndexes[].IndexName'
# {"AttributeName":"expiresAt","TimeToLiveStatus":"ENABLED"}
aws --endpoint-url=http://localhost:8000 dynamodb describe-time-to-live --table-name pix_idempotency \
  | jq '.TimeToLiveDescription'
```

#### Messaging (mirror of `infra/localstack/init/06-messaging-core.sh`, step 26)

**Naming convention** — one SNS topic for the whole platform, `pix-events`; fan-out happens at the
*subscription*, never by adding topics. One SQS queue **per consuming service**, `<purpose>-queue`,
whose dead-letter queue is the same name plus `-dlq`. Every queue has exactly one DLQ and a filter
policy on the `eventType` message attribute, so a consumer only receives the event types it handles.

```bash
# the topic — create-topic is idempotent: same name ⇒ same ARN
aws --endpoint-url=http://localhost:4566 sns create-topic --name pix-events

# the DLQ first (the main queue needs its ARN), retention at the SQS maximum of 14 days
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name settlement-queue-dlq \
  --attributes '{"MessageRetentionPeriod":"1209600"}'

# the consumer queue: redrive to the DLQ after 5 receives; visibility 30s (must exceed the 12s SPI
# call of step 31 — it is also the retry backoff of step 32); 20s long polling
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name settlement-queue \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:settlement-queue-dlq\",\"maxReceiveCount\":\"5\"}","VisibilityTimeout":"30","ReceiveMessageWaitTimeSeconds":"20"}'
# …plus a queue Policy allowing ONLY pix-events to sqs:SendMessage — the console adds this for you,
# the API does not, and a missing policy fails SILENTLY (publish accepted, delivery denied).

# subscribe, then scope it: only PixDebited reaches settlement, and the body is the event itself
aws --endpoint-url=http://localhost:4566 sns subscribe --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --protocol sqs --notification-endpoint arn:aws:sqs:us-east-1:000000000000:settlement-queue
aws --endpoint-url=http://localhost:4566 sns set-subscription-attributes --subscription-arn <arn> \
  --attribute-name FilterPolicy --attribute-value '{"eventType":["PixDebited"]}'
aws --endpoint-url=http://localhost:4566 sns set-subscription-attributes --subscription-arn <arn> \
  --attribute-name RawMessageDelivery --attribute-value true
```

Verify what the init script created on `up`:

```bash
# the topic
aws --endpoint-url=http://localhost:4566 sns list-topics | jq
# both queues: settlement-queue + settlement-queue-dlq
aws --endpoint-url=http://localhost:4566 sqs list-queues | jq
# the redrive policy + timings on the consumer queue
aws --endpoint-url=http://localhost:4566 sqs get-queue-attributes --attribute-names All \
  --queue-url $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url \
      --queue-name settlement-queue --query QueueUrl --output text) \
  | jq '.Attributes | {RedrivePolicy, VisibilityTimeout, ReceiveMessageWaitTimeSeconds}'
# exactly ONE subscription, and its filter policy
SUB=$(aws --endpoint-url=http://localhost:4566 sns list-subscriptions-by-topic \
  --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events --query 'Subscriptions[0].SubscriptionArn' --output text)
aws --endpoint-url=http://localhost:4566 sns get-subscription-attributes --subscription-arn $SUB \
  | jq '.Attributes | {FilterPolicy, RawMessageDelivery}'

# end-to-end by hand: a PixDebited arrives, a PixSettled is filtered out (no consumer yet — step 31)
aws --endpoint-url=http://localhost:4566 sns publish --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --message '{"eventId":"ev-manual-1","eventType":"PixDebited","txId":"tx-manual-1"}' \
  --message-attributes '{"eventType":{"DataType":"String","StringValue":"PixDebited"}}'
aws --endpoint-url=http://localhost:4566 sqs receive-message --wait-time-seconds 5 \
  --queue-url $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url \
      --queue-name settlement-queue --query QueueUrl --output text) | jq '.Messages[0].Body'
# ⇒ the raw event JSON, NOT an SNS {"Type":"Notification",...} envelope.
# Re-run with eventType=PixSettled ⇒ the queue stays empty: the filter policy dropped it at SNS.
```

#### Notification fan-out (mirror of `infra/localstack/init/08-messaging-notify.sh`, step 36)

The **second** consumer off the same topic — the SNS+SQS analogue of a second Kafka consumer group.
Same shape as settlement-queue (DLQ first, redrive after 5 receives, visibility 30s, long-poll 20s,
narrow `sqs:SendMessage` policy), but its filter policy routes the **user-facing** outcomes only —
disjoint from settlement's `PixDebited`, so an internal event never wakes a notification and vice versa.

```bash
# the DLQ first, then the queue (same attributes as settlement-queue — see the block above)
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name notification-queue-dlq \
  --attributes '{"MessageRetentionPeriod":"1209600"}'
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name notification-queue \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:notification-queue-dlq\",\"maxReceiveCount\":\"5\"}","VisibilityTimeout":"30","ReceiveMessageWaitTimeSeconds":"20"}'
# …plus the same queue Policy allowing ONLY pix-events to sqs:SendMessage.

# subscribe, then scope it to the user-facing outcomes; deliver the event raw
aws --endpoint-url=http://localhost:4566 sns subscribe --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --protocol sqs --notification-endpoint arn:aws:sqs:us-east-1:000000000000:notification-queue
aws --endpoint-url=http://localhost:4566 sns set-subscription-attributes --subscription-arn <arn> \
  --attribute-name FilterPolicy --attribute-value '{"eventType":["PixSettled","PixReceived","PixReversed"]}'
aws --endpoint-url=http://localhost:4566 sns set-subscription-attributes --subscription-arn <arn> \
  --attribute-name RawMessageDelivery --attribute-value true
```

Verify what the init script created on `up` (both queues present, the policy routing user-facing events):

```bash
# notification-queue + notification-queue-dlq show up alongside the settlement pair
aws --endpoint-url=http://localhost:4566 sqs list-queues | jq
# its subscription's filter policy — PixSettled/PixReceived/PixReversed, never PixDebited
NSUB=$(aws --endpoint-url=http://localhost:4566 sns list-subscriptions-by-topic \
  --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --query "Subscriptions[?ends_with(Endpoint, ':notification-queue')].SubscriptionArn | [0]" --output text)
aws --endpoint-url=http://localhost:4566 sns get-subscription-attributes --subscription-arn $NSUB \
  | jq '.Attributes | {FilterPolicy, RawMessageDelivery}'

# end-to-end by hand: a PixSettled arrives on notification-queue, a PixDebited is filtered out
aws --endpoint-url=http://localhost:4566 sns publish --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --message '{"eventId":"ev-notify-1","eventType":"PixSettled","txId":"tx-notify-1"}' \
  --message-attributes '{"eventType":{"DataType":"String","StringValue":"PixSettled"}}'
aws --endpoint-url=http://localhost:4566 sqs receive-message --wait-time-seconds 5 \
  --queue-url $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url \
      --queue-name notification-queue --query QueueUrl --output text) | jq '.Messages[0].Body'
# ⇒ the raw event JSON. Re-run with eventType=PixDebited ⇒ notification-queue stays empty.
```

#### Audit fan-out + S3 buckets (mirror of `infra/localstack/init/09-audit.sh`, step 42)

The **third** consumer off the same topic, and the only **unfiltered** one. settlement and notification
each name the event types they act on; audit does not act on events at all — it records that they
happened — so a filter policy here would be a list somebody has to remember to extend, and the first
unlisted event type would be missing from the trail silently, forever. The script therefore also
*removes* a filter policy if one ever drifted in: converging to "none" has to be an action, not an
omission.

```bash
# the DLQ first, then the queue (same attributes as the other two consumers — see the blocks above)
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name audit-queue-dlq \
  --attributes '{"MessageRetentionPeriod":"1209600"}'
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name audit-queue \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:audit-queue-dlq\",\"maxReceiveCount\":\"5\"}","VisibilityTimeout":"30","ReceiveMessageWaitTimeSeconds":"20"}'
# …plus the same queue Policy allowing ONLY pix-events to sqs:SendMessage.

# subscribe — and set NO filter policy (the empty value also repairs drift); still raw delivery, so
# the archived line is the envelope the publisher wrote, not an SNS wrapper
aws --endpoint-url=http://localhost:4566 sns subscribe --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --protocol sqs --notification-endpoint arn:aws:sqs:us-east-1:000000000000:audit-queue
aws --endpoint-url=http://localhost:4566 sns set-subscription-attributes --subscription-arn <arn> \
  --attribute-name FilterPolicy --attribute-value ''
aws --endpoint-url=http://localhost:4566 sns set-subscription-attributes --subscription-arn <arn> \
  --attribute-name RawMessageDelivery --attribute-value true
```

The buckets. `--object-lock-enabled-for-bucket` is **create-time only** in real AWS (there is no API to
turn Object Lock on afterwards, which is why the script cannot repair a bucket created without it). It
implies versioning **and freezes it**: a later `put-bucket-versioning --versioning-configuration
Status=Enabled` is rejected with `InvalidBucketState` even though it asks for the state the bucket
already has — so on a locked bucket versioning is not configuration you converge, it is a property you
inherit and can never suspend (which is the point: suspending versioning would be the first move of
anyone trying to erase the trail). The default retention is **COMPLIANCE / 1825 days** — 5 years, the BACEN window —
applied at bucket level, so every `PutObject` inherits it and the audit writer (step 43) cannot forget
to retain a line. COMPLIANCE rather than GOVERNANCE on purpose: GOVERNANCE is bypassable by any
principal holding `s3:BypassGovernanceRetention`, i.e. exactly the privileged operator an audit trail
exists to keep honest.

```bash
aws --endpoint-url=http://localhost:4566 s3api create-bucket --bucket pix-audit-log \
  --object-lock-enabled-for-bucket        # versioning comes with it — and can never be turned off
aws --endpoint-url=http://localhost:4566 s3api put-object-lock-configuration --bucket pix-audit-log \
  --object-lock-configuration '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"COMPLIANCE","Days":1825}}}'

# the cold archive is a PLAIN bucket on purpose: derived, rebuildable data (the ledger stays the
# source of truth) whose monthly account=<id>/yyyy-MM.jsonl object step 43 rewrites as the window rolls
aws --endpoint-url=http://localhost:4566 s3api create-bucket --bucket pix-statement-archive
```

Verify what the init script created on `up`:

```bash
# audit-queue + audit-queue-dlq alongside the settlement/notification pairs
aws --endpoint-url=http://localhost:4566 sqs list-queues | jq
# its subscription has NO FilterPolicy key at all — that absence IS the configuration
ASUB=$(aws --endpoint-url=http://localhost:4566 sns list-subscriptions-by-topic \
  --topic-arn arn:aws:sns:us-east-1:000000000000:pix-events \
  --query "Subscriptions[?ends_with(Endpoint, ':audit-queue')].SubscriptionArn | [0]" --output text)
aws --endpoint-url=http://localhost:4566 sns get-subscription-attributes --subscription-arn $ASUB \
  | jq '.Attributes | {FilterPolicy, RawMessageDelivery}'   # ⇒ FilterPolicy: null

# both buckets, and the immutability posture on the audit one
aws --endpoint-url=http://localhost:4566 s3 ls        # pix-audit-log, pix-statement-archive
aws --endpoint-url=http://localhost:4566 s3api get-bucket-versioning --bucket pix-audit-log
aws --endpoint-url=http://localhost:4566 s3api get-object-lock-configuration --bucket pix-audit-log

# prove it by hand: write a line, read its retention date, then try to erase that version
echo '{"eventId":"ev-audit-1","eventType":"PixSettled"}' > /tmp/audit-probe.jsonl
aws --endpoint-url=http://localhost:4566 s3api put-object --bucket pix-audit-log \
  --key 2026/01/01/00/manual-probe.jsonl --body /tmp/audit-probe.jsonl
aws --endpoint-url=http://localhost:4566 s3api get-object-retention --bucket pix-audit-log \
  --key 2026/01/01/00/manual-probe.jsonl        # ⇒ COMPLIANCE, RetainUntilDate ≈ today + 5y
VID=$(aws --endpoint-url=http://localhost:4566 s3api list-object-versions --bucket pix-audit-log \
  --prefix 2026/01/01/00/manual-probe.jsonl --query 'Versions[0].VersionId' --output text)
aws --endpoint-url=http://localhost:4566 s3api delete-object --bucket pix-audit-log \
  --key 2026/01/01/00/manual-probe.jsonl --version-id $VID
# ⇒ An error occurred (AccessDenied) — the retained version cannot be erased.
```

> **LocalStack vs AWS — the honest caveat.** LocalStack 3 does more than *accept* the Object Lock
> configuration: it **enforces** it at the API (the delete above really is refused, and `S3InitIT`
> asserts exactly that). What remains AWS-only is everything *below* the API — WORM at the storage
> layer, surviving `docker compose down -v` (the emulator's state is ephemeral by design, so the local
> "5-year retention" lasts precisely as long as the container), cross-region replication of the trail,
> and IAM actually denying anything (ADR-0013: LocalStack emulates the IAM APIs but enforces nothing).
> Locally we prove the posture is *configured and refused*; we never prove the bytes are immutable.

#### Consumer dedup table (mirror of `infra/localstack/init/07-processed-events.sh`, step 29)

One tiny table shared by **every** consumer, with the consumer name in the key — the deliberate
exception to one-table-per-service (ADR-0006). Delivery is at-least-once by design (the publisher
publishes *then* marks, so a crash in between republishes), so each consumer conditionally puts
`CONSUMER#<name>#EVT#<eventId>` **before** its side effect: first delivery wins, redeliveries are
skipped. TTL 7 days — comfortably past any redelivery window, and DynamoDB's lazy deletion errs in the
safe direction here (an expired-but-present record still reads as "duplicate", i.e. skip rather than
repeat — the opposite of `pix_idempotency`, where an expired record must read as absent).

```bash
aws --endpoint-url=http://localhost:8000 dynamodb create-table \
  --table-name pix_processed_events \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

aws --endpoint-url=http://localhost:8000 dynamodb update-time-to-live \
  --table-name pix_processed_events \
  --time-to-live-specification 'Enabled=true,AttributeName=expiresAt'
```

#### The outbox publisher (step 29)

payment-service polls the sparse `gsi3` every second, publishes each waiting event to `pix-events`
(message attributes `eventType`/`eventId`/`correlationId`), and only then removes `gsi3pk` so the item
leaves the index. Watch one payment's event make the whole trip:

```bash
# 1. the publisher's own log line (one per event that goes out)
docker compose -f infra/docker-compose.yml logs -f payment-service | grep 'Outbox item published'

# 2. the sparse index drains: briefly 1 after a send, then 0 — "published" IS "no longer indexed"
aws --endpoint-url=http://localhost:8000 dynamodb query --table-name pix_transactions \
  --index-name gsi3 --key-condition-expression 'gsi3pk = :p' \
  --expression-attribute-values '{":p":{"S":"OUTBOX#UNPUBLISHED"}}' | jq '.Count'

# 3. the event actually landed on the subscribed queue (external sends only — the filter policy
#    passes PixDebited and drops everything else)
aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url \
  $(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name settlement-queue --query QueueUrl --output text) | jq

# 4. publisher liveness: age of the oldest unpublished event, in seconds (0.0 on a drained outbox).
#    A climbing value = falling behind or stuck; no value at all = the publisher is dead (step 44
#    alerts on the silence, not only on the threshold).
curl -s localhost:8084/actuator/metrics/pix.outbox.lag | jq
```

**Step 18 — sending a Pix (walking skeleton) through payment-service** (`:8084`). The endpoint
validates, mints `txId` + a Pix-standard `endToEndId`, persists the transaction as `RECEIVED`, and
returns `202` + `Location`. No money moves yet (ledger debit is step 21); the `Idempotency-Key` is now
enforced (step 19, below). The debtor is the token's `accountId`, never the body:

```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# 202 + Location + {"transactionId":"tx-…","endToEndId":"E12345678…","status":"PROCESSING"}
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}' | head -8

# the persisted item: debtor from the JWT (acc-001), amountCents=12550 (status is SETTLED since step 21,
# below — in the step-18 skeleton it was RECEIVED and no money moved)
TXID=$(curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50"}' | jq -r .transactionId)
aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_transactions \
  --key "{\"pk\":{\"S\":\"TX#$TXID\"},\"sk\":{\"S\":\"META\"}}" \
  | jq '.Item | {status:.status.S, debtorAccountId:.debtorAccountId.S, amountCents:.amountCents.N}'

# the refusals
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"0.00"}' | jq .code   # INVALID_AMOUNT (400) — not money
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"12.5"}' | jq .code   # VALIDATION_ERROR (400) — bad shape
curl -si -X POST localhost:8084/v1/payments/pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"1.00"}' | head -1    # 401 without a token
```

**Step 19 — idempotency on send** (ADR-0002). The `Idempotency-Key` header is now required and the
request is de-duplicated per `(accountId, key)`: a retry with the same body **replays** the original
response (same `transactionId`), the same key with a **different** body is `409`, and a missing header
is `400`. The record lives in `pix_idempotency` with a 24h TTL.

```bash
IDEM=$(uuidgen); BODY='{"pixKey":"bob@platinum.com","amount":"10.00"}'

# first call → a new transactionId
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY" | jq -r .transactionId

# identical retry (same key + body) → the SAME transactionId (replay, no second transaction)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' -d "$BODY" | jq -r .transactionId

# same key, different amount → 409 IDEMPOTENCY_KEY_REUSED
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"99.00"}' | jq -r .code

# missing header → 400 IDEMPOTENCY_KEY_REQUIRED
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$BODY" | jq -r .code

# the stored record (IN_PROGRESS→COMPLETED, 24h TTL on expiresAt)
aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_idempotency \
  --key "{\"pk\":{\"S\":\"IDEM#acc-001#$IDEM\"},\"sk\":{\"S\":\"META\"}}" \
  | jq '.Item | {status:.status.S, httpStatus:.httpStatus.N, expiresAt:.expiresAt.N}'
```

**Step 21 — internal orchestration: the send now moves real money.** payment-service resolves the
destination key against account-service's DICT (`acc-002` for `bob@platinum.com`), commands the atomic
debit/credit in ledger-service, and persists the transaction as **`SETTLED`** (an internal transfer has
no SPI leg — it *is* settled the moment the posting commits). Needs the full stack up (`payment` +
`account` + `ledger` + LocalStack).

```bash
# a happy internal send → 202; then the item is SETTLED with settledAt + the resolved creditorAccountId
TXID=$(curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}' | jq -r .transactionId)
aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_transactions \
  --key "{\"pk\":{\"S\":\"TX#$TXID\"},\"sk\":{\"S\":\"META\"}}" \
  | jq '.Item | {status:.status.S, settledAt:.settledAt.S, creditorAccountId:.creditorAccountId.S}'

# bob (acc-002) was credited — read it through the ledger
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance \
  -H "Authorization: Bearer $(scripts/service-token.sh ledger-service ledger:read)" | jq

# the failure mappings
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"ghost@platinum.com","amount":"1.00"}' | jq -r .code   # KEY_NOT_FOUND (422)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"9999999.00"}' | jq -r .code # INSUFFICIENT_FUNDS (422), limit released
```

**Step 22 — status query: `GET /payments/{id}`.** Owner-only; maps the internal state onto the
external vocabulary (`PROCESSING/SETTLED/FAILED/REVERSED/REJECTED`). Reuse the `$TXID` from the step-21
happy send above:

```bash
# 200 with the Payment schema — an internal send already reads back SETTLED + settledAt
curl -s localhost:8084/v1/payments/$TXID -H "Authorization: Bearer $TOKEN" | jq

# an unknown id → 404 PAYMENT_NOT_FOUND (and a token for another account gives the SAME 404 for a real
# id — the two are indistinguishable on purpose, so existence never leaks)
curl -s localhost:8084/v1/payments/tx-does-not-exist -H "Authorization: Bearer $TOKEN" | jq -r .code

# bob's token reading alice's transaction → 404 too (owner-only, from the JWT)
BOB=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bob"}' | jq -r .accessToken)
curl -s localhost:8084/v1/payments/$TXID -H "Authorization: Bearer $BOB" | jq -r .code  # PAYMENT_NOT_FOUND
```

## 4.1 Reading the logs (ADR-0012)

Every service inherits one logging config from `common-lib` — there is nothing to enable per service.

**The shape of a line.** The correlation id is in the log *pattern*, so **every** record carries it
(ours, Spring's, the AWS SDK's):

```
2026-08-01T10:39:28.962-03:00  INFO 1 --- [auth-service] [nio-8081-exec-1] \
  [cid=abbb4c1c-81aa-4aaa-808c-b508ba11fec2 tx=n/a] c.p.p.auth.domain.usecase.LoginUseCase \
  : Login succeeded, access token issued | username=alice userId=u-alice accountId=acc-001 expiresInSeconds=900
```

Message = an English sentence saying what happened, then ` | key=value` pairs. `tx=n/a` until a
transaction id exists (money flows, Sprint 4+); `cid=n/a` means "no request" (startup, schedulers).

**Follow one request across every service** — send a correlation id in and grep it back out:

```bash
CID=$(uuidgen)
curl -s -X POST localhost:8081/v1/auth/login -H "X-Correlation-Id: $CID" \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' > /dev/null
docker compose -f infra/docker-compose.yml logs | grep "cid=$CID"
# (the id is also echoed back on the X-Correlation-Id response header, and generated if you omit it)
```

**Levels.** `com.platinumcoin.pix` runs at **DEBUG by default** in this sandbox — you see the
DynamoDB calls and their keys, not only the business stages. To quiet a service down (or to run load
tests without log I/O in the way), override per service without touching the shared config:

```bash
# in infra/docker-compose.yml, under the service's `environment:`
LOGGING_LEVEL_COM_PLATINUMCOIN_PIX: INFO
```

**JSON instead of text.** The logstash encoder is one profile away — this is the shape a real
deployment ships to a log platform:

```bash
# environment: SPRING_PROFILES_ACTIVE: json-logs,local
#   (keep `local` — it is what points the AWS clients at the emulator since step 45, ADR-0013)
docker compose -f infra/docker-compose.yml logs auth-service | jq -c 'select(.correlationId=="'$CID'")'
```

**Values are printed in the clear** — Pix keys, CPFs, e-mails, account ids, amounts — because this is
a sandbox with seeded fixtures; secrets (passwords, hashes, JWTs, credentials) never are. Read
[ADR-0012](adr/0012-verbose-logs-with-real-values.md) before pointing this stack at anything real.

## 5. Testing each flow by hand

> This section documents the **target** state — the platform is built vertically (`PLAN.md`),
> so a subsection only works once its sprint is checked off. Today: **5.1** (Sprint 1) and
> **5.2** (Sprint 2) run; everything from 5.3 on is written ahead of the code.

### 5.1 Login

```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
```

### 5.2 Pix keys

```bash
curl -s -X POST localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"keyType":"EVP"}' | jq
curl -s localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" | jq
```

**Resolution — the DICT role, and its four answers (steps 11 + 30).** The interesting one is the last:

```bash
# Resolution is a SERVICE operation (payment-service asking the DICT), so it needs a keys:resolve
# service token — registration and listing above are CUSTOMER operations and keep the user token.
RESOLVE=$(scripts/service-token.sh account-service keys:resolve)

# 1. held here (local table first — no network hop for a key we already own)
curl -s "localhost:8082/internal/pix-keys/resolve?key=alice@platinum.com" -H "Authorization: Bearer $RESOLVE" | jq
# 2. held at another PSP — delegated to mock-bacen's DICT ⇒ the external send branch
curl -s "localhost:8082/internal/pix-keys/resolve?key=bob@otherbank.com" -H "Authorization: Bearer $RESOLVE" | jq
# 3. nowhere at all ⇒ 404 KEY_NOT_FOUND (the only honest not-found)
curl -si "localhost:8082/internal/pix-keys/resolve?key=nobody@nowhere.com" -H "Authorization: Bearer $RESOLVE" | head -1
# 4. FAIL CLOSED: with the DICT unreachable the answer is 503 DIRECTORY_UNAVAILABLE + Retry-After —
#    NOT a 404, which would tell the payer their payee's key is invalid because OUR dependency is down.
docker compose -f infra/docker-compose.yml stop mock-bacen-spi
curl -si "localhost:8082/internal/pix-keys/resolve?key=bob@otherbank.com" -H "Authorization: Bearer $RESOLVE" | head -3
docker compose -f infra/docker-compose.yml start mock-bacen-spi
```

### 5.3 Send Pix (internal: alice → bob's key)

```bash
IDEM=$(uuidgen)
curl -si -X POST localhost:8084/v1/payments/pix \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM" \
  -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}'
# expect: HTTP/1.1 202 Accepted + transactionId

# idempotency replay: run the SAME command again → same 202, SAME transactionId
# tamper test: same $IDEM, different amount → 409 Conflict
```

**External send (live since step 30).** Same endpoint, a key held at another PSP: the destination now
resolves through mock-bacen's DICT, so the send debits to `SPI_CLEARING` and answers `202 PROCESSING`
without waiting for BACEN. It stays `DEBITED` until settlement-service consumes the event (step 31).

```bash
curl -si -X POST localhost:8084/v1/payments/pix \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"200.00"}' | head -1     # 202
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance \
  -H "Authorization: Bearer $(scripts/service-token.sh ledger-service ledger:read)" \
  | jq   # credited by exactly the amount in flight
```

### 5.4 Status + settlement observation

```bash
TX=<transactionId from above>
watch -n1 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq .status"
# PROCESSING → SETTLED after BACEN_LATENCY_MS

# peek at the settlement queue while a message is in flight
awsl sqs receive-message --queue-url $(awsl sqs get-queue-url --queue-name settlement-queue --output text --query QueueUrl) --visibility-timeout 0 | jq
```

**Outbox lanes & publisher (step 71, ADR-0019)** — the target of the `outbox_publisher_lag_<lane>`
alerts. Since step 71 there are **three** drains, each with its own tick, batch, in-flight ceiling and
lag budget, so the first question during an incident is *which lane*:

```bash
# one gauge series per lane — settlement / notification / audit
curl -s localhost:8084/actuator/prometheus | grep pix_outbox_lag_seconds

# what each lane is doing, in the INFO layer (ADR-0012: the sentence, then the values)
docker compose -f infra/docker-compose.yml logs payment-service | grep -i 'lane='
# a lane at its in-flight ceiling says so BEFORE its SLO is breached:
docker compose -f infra/docker-compose.yml logs payment-service | grep 'in-flight ceiling'

# what is still waiting, per lane, straight off the sparse index
for LANE in SETTLEMENT NOTIFICATION AUDIT; do
  printf '%s: ' "$LANE"
  awsl dynamodb query --table-name pix_transactions --index-name gsi3 \
    --key-condition-expression 'gsi3pk = :p' \
    --expression-attribute-values "{\":p\":{\"S\":\"OUTBOX#UNPUBLISHED#$LANE\"}}" \
    --select COUNT --output text --query Count
done
```

> **Budgets, and why the settlement one is 12s.** `pix.settlement.reconciliation.stuck-after-seconds`
> is 120s: a `PixDebited` still unpublished by then is a payment heading for a `REVERSED` from
> reconciliation instead of a settlement (this happened — `docs/load/RESULTS.md` Context 2). The
> settlement lane's budget therefore sits an order of magnitude under it, so the alert leaves ~108s to
> act. `notification` is 60s (a person waiting, not a wrong balance) and `audit` is 300s.
>
> **A saturated lane never touches acceptance.** If `POST /v1/payments/pix` slows while a lane is
> backed up, the publisher is *not* the cause — the outbox write is inside the payment's atomic
> transaction and shares nothing with the drain (ADR-0019 decision 4, pinned by
> `SendPixUseCaseTest#outboxSaturationDoesNotSlowAcceptance`). Look at the ledger or DynamoDB instead.

### 5.5 Failure & DLQ drill

```bash
# make BACEN fail 100% of calls, then send a Pix
docker compose -f infra/docker-compose.yml exec mock-bacen-spi \
  curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# after 5 attempts the message lands in the DLQ:
awsl sqs get-queue-attributes --queue-url $(awsl sqs get-queue-url --queue-name settlement-queue-dlq --output text --query QueueUrl) \
  --attribute-names ApproximateNumberOfMessages | jq
# restore, and watch reconciliation (<5 min) resolve/reverse the stuck tx:
docker compose -f infra/docker-compose.yml exec mock-bacen-spi \
  curl -s -X POST localhost:9090/admin/config -d '{"failureRate":0.0}' -H 'Content-Type: application/json'
```

**Reversal drill (step 35) — the send-reachable path to a compensating reversal.** `failureRate` is a
*transient* 503 (nothing recorded, retries, DLQ); it does **not** produce a permanent refusal. The
send-reachable trigger for step 33's reversal is the **reject-key knob**: it refuses a **DICT-known** key
(one that resolves fine at send time) at *settlement*, so a real external Pix can be driven all the way to
a `REVERSED` with the payer refunded.

```bash
# 1) arm a settlement rejection for a key the DICT DOES know (so the send is accepted):
docker compose -f infra/docker-compose.yml exec mock-bacen-spi \
  curl -s -X POST localhost:9090/admin/config -d '{"rejectKeys":["bob@otherbank.com"]}' -H 'Content-Type: application/json'

# 2) send an external Pix to that key — it debits to clearing and returns 202:
TX=$(curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"reversal drill"}' | jq -r .transactionId)

# 3) settlement gets 422 SETTLEMENT_REJECTED_BY_ADMIN → step 33 reverses ON THAT SAME DELIVERY (seconds,
#    not minutes): the consumer holds a DEFINITIVE answer, so it does not wait for the 60s scanner and the
#    message does not redrive to the DLQ — `SettleOutcome.REVERSED` is acked. The payer is refunded and
#    SPI_CLEARING nets back to 0. (Reconciliation is the *fallback* path for this ending, not the normal
#    one; it owns the transactions nobody ever came back with an answer for.)
watch -n2 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq .status"
docker compose -f infra/docker-compose.yml logs settlement-service | grep -E 'Reconciliation resolved|ALERT'

# 4) clear the reject list so the key settles normally again:
docker compose -f infra/docker-compose.yml exec mock-bacen-spi \
  curl -s -X POST localhost:9090/admin/config -d '{"rejectKeys":[]}' -H 'Content-Type: application/json'
```

### 5.6 Receive Pix (step 37)

Bob must have registered `bob@platinum.com` first (§5.2) — the payee is whatever *our* directory says the
key belongs to, never something the caller names.

```bash
# make BACEN generate an inbound Pix to bob and deliver it to settlement-service's webhook
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"External Payer"}' | jq
# → {"endToEndId":"E99999999...","amountCents":30000,"participantTxId":"in-E99999999...",
#    "outcome":"CREDITED","deliveryAttempts":1}

# bob was credited: debit SPI_CLEARING / credit acc-002 (entryType=PIX_IN) — the MIRROR of an
# outbound send, which debits the payer and credits clearing
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance \
  -H "Authorization: Bearer $BOB" | jq
```

**Prove the dedupe.** Re-present the same payment straight at the webhook with the id the call above
returned — this is what BACEN does when it never got an answer:

```bash
E2E=E99999999202608201030abcdef012   # paste the endToEndId from the response above
curl -s -X POST localhost:8086/v1/inbound/pix \
  -H 'Content-Type: application/json' \
  -H 'X-Webhook-Token: dev-only-inbound-webhook-token-change-me' \
  -d "{\"endToEndId\":\"$E2E\",\"pixKey\":\"bob@platinum.com\",\"amountCents\":30000,
       \"payerName\":\"External Payer\",\"payerIspb\":\"99999999\"}" | jq
# → {"outcome":"ALREADY_PROCESSED"} — 200, and bob's balance is UNCHANGED. The transaction id is
#   in-<endToEndId>, so the conditional write on that item IS the endToEndId dedupe.
```

**Prove the guard.** The webhook credits money, so it is never anonymous even though it is JWT-exempt
(threat model, boundary B4):

```bash
curl -s -i -X POST localhost:8086/v1/inbound/pix -H 'Content-Type: application/json' \
  -d '{"endToEndId":"E99999999202608201030forged0000","pixKey":"bob@platinum.com","amountCents":999999}'
# → 401 WEBHOOK_UNAUTHORIZED, nothing resolved, nothing posted, no transaction

# and a key nobody here answers for is bounced permanently — the rail does NOT retry a 4xx
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"nobody@nowhere.com","amount":"1.00"}' | jq
# → 422 INBOUND_REFUSED (the participant answered 422 KEY_NOT_FOUND)
```

### 5.6.1 Real-time notification (steps 38–39)

```bash
# terminal 1: subscribe to bob's notification stream
BOB=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bob"}' | jq -r .accessToken)
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $BOB"
# → ":connected sub-…" immediately (an SSE comment; it COMMITS the response, so you can tell
#   "connected and quiet" from "hanging"), then ":ping" every 25s

# terminal 2: trigger the inbound Pix of §5.6 (bob@platinum.com must be registered — §5.2)
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"77.77","payerName":"Carol Mendes"}' | jq -c

# terminal 1, within seconds:
#   event:PixReceived
#   id:evt-…
#   data:{"transactionId":"in-E99999999…","type":"PixReceived","status":"SETTLED","amount":"77.77",
#         "counterpart":"Carol Mendes","timestamp":"2026-08-20T17:35:54.335Z","failureReason":null}
```

**The sender's side (step 39).** The same three lines, on the payer's stream, for both endings of an
external send — this is the honest ending to the `202 PROCESSING` they saw:

```bash
# terminal 1: alice's stream
ALICE=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $ALICE"

# terminal 2 — the happy ending: an EXTERNAL send (the payee banks elsewhere, so it settles via BACEN)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.34","description":"journey"}' | jq -c
# terminal 1 → event:PixSettled  data:{…"status":"SETTLED","amount":"12.34",
#                                      "counterpart":"bob@otherbank.com","failureReason":null}

# terminal 2 — the failure ending: arm the reject-key knob (§5.5) and send again
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' \
  -d '{"rejectKeys":["bob@otherbank.com"]}' | jq -c
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"55.10"}' | jq -c
# terminal 1 → event:PixReversed data:{…"status":"REVERSED","failureReason":"…"} — the compensating
#              posting already returned the money (step 33); remember to clear rejectKeys afterwards
```

**One shape for all three**, and it is a strict subset of what `GET /v1/payments/{transactionId}`
answers: `{transactionId, type, status, amount, counterpart, timestamp, failureReason}` — same status
vocabulary, `amount` already a decimal string, `counterpart` a display value and never an account id.
Note what routing means in practice: alice's send never appears on bob's stream and vice versa, because
the addressee is read off the event (payer for an outcome, payee for an arrival) and the stream itself is
bound to the JWT's `accountId`.

> **See it as a customer would:** open `tools/api-explorer/index.html` → the **Phone** tab, log in, press
> **Connect**, then trigger any of the commands above. Same endpoint, same bytes, rendered as a lock
> screen; click a notification for the raw `data:` JSON that produced it.

### 5.7 Balance & statement (cache)

```bash
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
curl -s "localhost:8084/v1/accounts/me/statement?limit=5" -H "Authorization: Bearer $TOKEN" | jq
# see the cache with redis-cli:
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001
```

**Seeing cache-aside actually work (step 40, ADR-0008).** Four commands, four properties:

```bash
RC="docker compose -f infra/docker-compose.yml exec -T redis redis-cli"

# (a) miss → ledger → populate. The key did not exist; now it does, with a TTL.
$RC DEL balance:acc-001
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
$RC GET balance:acc-001          # {"balanceCents":1000000,"asOf":"…"}
$RC TTL  balance:acc-001         # <= 5  → the staleness bound, in seconds

# (b) hit. Same answer, no ledger call — confirm in the logs of BOTH services:
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
docker compose -f infra/docker-compose.yml logs --tail=20 payment-service | grep -i "served from the cache"
# ledger-service logs NOTHING for this request: that silence is the point of the cache.

# (c) invalidation on write. Send a Pix, then look: the key is gone, post-commit.
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"7.00"}' >/dev/null
$RC GET balance:acc-001          # (nil) → evicted by ledger-service
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq   # fresh

# (d) hit/miss metrics — the KPI the 300ms budget rests on (graphed on the Technical dashboard).
#     Since step 44 every service also exposes /actuator/prometheus, which is what Prometheus scrapes:
curl -s localhost:8084/actuator/metrics/pix.cache.hit  | jq '.measurements[0].value'
curl -s localhost:8084/actuator/prometheus | grep -E '^pix_cache_(hit|miss)_total'
```

Measured on this stack (200 serial reads, warm cache): **p50 3.9ms · p95 5.2ms · p99 9.8ms** — against a
300ms budget. That is a single-client sanity check, not a load test; the real number under 500+ TPS is
step 47's job.

> **The drill that proves the design, not just the feature — and it found a real bug.** Stop Redis
> (`docker compose -f infra/docker-compose.yml stop redis`) and watch what does *not* break: a balance
> read still answers `200` in ~13ms (from the ledger, with a WARN naming the unreachable cache) and a
> send still answers `202` in ~200ms. `start redis` puts it back, and the client reconnects on its own.
>
> **What it looked like before the fix, because this is the lesson:** the first version wrapped every
> Redis call in a `try/catch` and called that "best-effort". With Redis *stopped* the balance read took
> **114 seconds** and — far worse — a send returned **`503 LEDGER_UNAVAILABLE` for a debit that had
> already committed**, because ledger-service's eviction blocked past payment-service's 3s read timeout.
> A cache that **hangs** is worse than a cache that fails, and catching an exception does nothing about
> it. Three changes fixed it, in increasing order of importance: bounded Redis timeouts
> (`spring.data.redis.timeout` / `connect-timeout`), a fail-fast client that rejects commands while
> disconnected instead of queueing them (`RedisFailFastConfig` — Lettuce queues by default, and queued
> commands ignore the command timeout), and finally moving the eviction **off the posting's thread**
> entirely (`RedisBalanceCacheInvalidator`), because an optional side effect must not share the latency
> of the transaction that triggered it.
>
> **And the inverse, which is the actual safety rule:** a *stale* cache cannot authorize an overdraft.
> Hand-write a fat lie into the cache and then ask the ledger for more than alice really has:
>
> ```bash
> $RC SET balance:acc-001 '{"balanceCents":99999999,"asOf":"2026-01-01T00:00:00Z"}'
> curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq -r .balance
> #   999999.99   ← the lie, served to the screen
> curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $LEDGER_POST" \
>   -H 'Content-Type: application/json' -d '{"txId":"tx-stale-drill-1","debitAccount":"acc-001",
>   "creditAccount":"acc-002","amountCents":5000000,"entryType":"PIX_INTERNAL","description":"drill"}' | jq
> #   422 INSUFFICIENT_FUNDS — "Account acc-001 has 999300 cents, which is short of the 5000000 requested."
> $RC DEL balance:acc-001
> ```
>
> The guard is the `balanceCents >= :amount` condition **inside** the ledger's `TransactWriteItems`, and
> it reads DynamoDB — Redis has no vote. Note the drill goes straight at **ledger-service**, not at
> `POST /v1/payments/pix`: through payment-service the daily limit (R$ 5,000) refuses a send this large
> first, so it would prove `LIMIT_EXCEEDED` and never reach the guard under test. Same property, asserted
> in CI by `BalanceCacheInvalidationIT#aStaleCacheDoesNotAuthorizeAnOverdraft`.

### 5.8 Audit trail in S3, and the cold statement archive (step 43)

Two independent jobs write to S3, and they are worth watching separately.

**(a) The immutable audit trail** — settlement-service consumes `audit-queue` (the one subscription with
*no* filter policy) and appends every event as a JSON line, batched at ~100 events **or** 30s. So do
something first — a login and a Pix (§5.3) — then wait up to 30 seconds:

```bash
# One object per flush, partitioned by the INGESTION hour, in UTC.
awsl s3 ls s3://pix-audit-log/ --recursive | tail
# 2026/08/21/14/settlement-service-3f2b….jsonl

# The lines: one event envelope per line, verbatim as published.
awsl s3 cp s3://pix-audit-log/<key> - | jq -c .
awsl s3 cp s3://pix-audit-log/<key> - | jq -r '.eventType' | sort | uniq -c

# Prove the retention is real: it was stamped by the BUCKET, not asked for by the writer.
awsl s3api head-object --bucket pix-audit-log --key <key> \
  --query '[ObjectLockMode,ObjectLockRetainUntilDate]'
# [ "COMPLIANCE", "2031-08-21T…" ]  ← ~5 years out

# And that it is enforced: this is REFUSED (AccessDenied), even for the account owner.
VERSION=$(awsl s3api list-object-versions --bucket pix-audit-log --prefix <key> \
  --query 'Versions[0].VersionId' --output text)
awsl s3api delete-object --bucket pix-audit-log --key <key> --version-id "$VERSION"
```

**(b) The cold statement archive** — ledger-service copies entries older than the hot window to one
object per account and month. Compose sets the window to **0 days** and the job to run **every minute**
(§3), precisely so this is demonstrable in a fresh sandbox; with the production default of 90 days a new
stack would archive nothing:

```bash
awsl s3 ls s3://pix-statement-archive/ --recursive
# account=acc-001/2026-08.jsonl

awsl s3 cp s3://pix-statement-archive/account=acc-001/2026-08.jsonl - | jq -c .
# {"accountId":"acc-001","txId":"tx-…","direction":"DEBIT","amountCents":-12550,…}

# The point of the whole feature: NOTHING left the ledger. The statement still answers in full.
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8084/v1/accounts/me/statement?limit=50" | jq '.entries | length'
```

> **Money is integer cents in both files.** They are internal artefacts, not API edges — decimal
> formatting is exactly the lossy convenience a five-year record must not carry.

> **Deleting hot data after archiving is deliberately not implemented** — see `docs/data-model.md` §8.2
> for what production does instead and why the local platform stops one step short.

### 5.9 Observability — dashboards, alerts & path tracing (step 44)

Prometheus and Grafana come up with everything else — **always on, not an optional profile**: a dashboard
you have to remember to start is a dashboard that is down exactly when something breaks. Full metric
catalog, alert-rule table and the reasoning behind both: **`docs/observability.md`**.

```bash
# (a) every service exposes the scrape surface
curl -s localhost:8084/actuator/prometheus | grep '^pix_payments_stage_total'

# (b) Prometheus — all nine targets should be `up` (eight services + itself)
open http://localhost:9091/targets
curl -s 'localhost:9091/api/v1/query?query=up{job="pix-services"}' | jq -r \
  '.data.result[] | "\(.metric.service)\t\(.value[1])"'

# (c) Grafana — anonymous Viewer, so there is no login to get past (admin/admin to edit).
#     Home is the funnel; both dashboards are provisioned from infra/observability/, never click-ops.
open http://localhost:3000

# (d) the funnel, straight from Prometheus — send a Pix first, then watch it move
curl -s 'localhost:9091/api/v1/query?query=sum by (stage) (pix_payments_stage_total{outcome="ok"})' \
  | jq -r '.data.result[] | "\(.metric.stage)\t\(.value[1])"'
```

**The silence-alert drill** — the one that proves the platform notices when *nothing* happens (the way
async systems actually fail). Full walk-through in `docs/observability.md` §4:

```bash
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' -d '{"failureRate":1.0}'
# ...keep sending external Pix; within ~2 min:
docker compose -f infra/docker-compose.yml logs settlement-service | grep "ALERT FIRING"
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' -d '{"failureRate":0.0}'
docker compose -f infra/docker-compose.yml logs settlement-service | grep "ALERT RESOLVED"
```

**Tracing one request across every service** — `X-Correlation-Id` comes back on every response and is in
every problem+json body, so you always have the handle:

```bash
./scripts/trace.sh <correlationId>        # what one REQUEST caused, in chronological order
./scripts/trace.sh <txId>                 # one PAYMENT's whole life, reconciliation included
./scripts/trace.sh <id> --all --since 6h  # plus DEBUG adapter detail (the DynamoDB keys, the payloads)
```

### 5.9.1 Distributed tracing & error budgets (step 72)

Two containers came up with everything else: the **OTLP collector** and **Jaeger**. Neither is on any
service's `depends_on` — a payment must never wait for the trace pipeline (ADR-0021).

```bash
# One external send, with a correlation id you choose so both tools can be compared side by side.
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
CID=$(uuidgen)
curl -s -X POST localhost:8084/v1/payments/pix \
  -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: $CID" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"trace"}' | jq
```

**The logs now carry both ids** — the trace id is in the pattern, so every line has it:

```bash
docker compose -f infra/docker-compose.yml logs payment-service | grep "$CID" | head -3
# 2026-08-24T…  INFO … [cid=<CID> tx=tx-9f1c trace=4bf92f3577b34da6a3ce929d0e0e4736] …
```

**Open the trace:** <http://localhost:16686> → service `payment-service` → Find Traces, or paste the
`trace=` value straight into the search box. What you should see, as **one** trace:

```
POST /v1/payments/pix
├─ pix.fraud.budget          ← the 200ms budget, not one socket
├─ pix.ledger.post           ← the atomic double-entry posting
└─ …202 returned here…
pix.outbox.drain (lane=SETTLEMENT)
└─ pix.outbox.publish        ← child of the ACCEPTING REQUEST, seconds later
   └─ pix.settlement.consume ← other service, same trace
      └─ the SPI call, the fence, the clearing release
```

Click any span → its `pix.correlation_id` tag is the id you passed. That is the join in the other
direction.

**And `trace.sh` is unchanged, collector or no collector:**

```bash
bash scripts/trace.sh "$CID"
docker compose -f infra/docker-compose.yml stop otel-collector
bash scripts/trace.sh "$CID"     # identical output — the log path is not sampled and not dependent
docker compose -f infra/docker-compose.yml start otel-collector
```

**Error budgets:** <http://localhost:3000> → *PlatinumCoin Pix — Technical*, the last two rows.
`p99 per dependency` answers *which dependency spent the p99*; the two burn-rate panels answer *how fast
this SLO is spending its allowance*.

**Making one fire is a SYNTHETIC drill, and here is the honest reason why.** The obvious move — turn the
mock-bacen latency dial up — does not work: `latencyMs` slows the **settlement**, which happens after the
`202` has already been returned, so it cannot move the send-acknowledgement p99 by construction. And the
sandbox has no runtime latency dial on the synchronous send path at all; that gap is exactly what
[step 64](steps/step-64.md) (still PROPOSED) would close for fraud-service. So the drill moves **the
budget**, not the latency — which tests the rule, its two windows and its FIRING/RESOLVED lifecycle, and
is honest about testing nothing else:

The four burn-rate tunables are surfaced in `docker-compose.yml` with the `application.yml` values as
their fallback, precisely so a drill can reach them (`ALERTS_LATENCY_OBJECTIVE`, `ALERTS_FAST_BURN_FACTOR`,
`ALERTS_SLOW_BURN_FACTOR`, `ALERTS_BURN_MINIMUM_REQUESTS`). A knob that only lives inside the jar is a knob
nobody can turn in a container.

```bash
# Demand 99.999% inside the SLO and declare anything above 0.1x of that budget a breach. A healthy
# platform then breaches a synthetic budget, which is the point: the RULE is what is under test.
ALERTS_LATENCY_OBJECTIVE=0.99999 ALERTS_FAST_BURN_FACTOR=0.1 ALERTS_SLOW_BURN_FACTOR=0.1 \
  ALERTS_BURN_MINIMUM_REQUESTS=1 \
  docker compose -f infra/docker-compose.yml up -d --force-recreate settlement-service

# SUSTAINED traffic, not a burst: the rule needs a population in BOTH windows, and the 5-minute
# confirming window empties as soon as you stop. A burst makes the fast rule report SKIPPED — correct
# behaviour that looks like a broken alert if you did not expect it.
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
for i in $(seq 1 40); do
  curl -s -o /dev/null localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN"
  curl -s -o /dev/null -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
    -d '{"pixKey":"bob@otherbank.com","amount":"1.00"}'
  sleep 2
done

docker compose -f infra/docker-compose.yml logs settlement-service | grep "ALERT FIRING.*error_budget"

# Put the real objective back. The next tick announces the TRANSITION, not the condition.
docker compose -f infra/docker-compose.yml up -d --force-recreate settlement-service
docker compose -f infra/docker-compose.yml logs settlement-service | grep "ALERT RESOLVED.*error_budget"
```

Observed on this stack (step 72):

```
16:04:23Z  ALERT FIRING    rule=send_error_budget_slow_burn     observed=0.4416
16:04:23Z  ALERT FIRING    rule=balance_error_budget_slow_burn  observed=0.5439
16:06:02Z  ALERT RESOLVED  rule=send_error_budget_slow_burn     observed=0.000386
16:06:02Z  ALERT RESOLVED  rule=balance_error_budget_fast_burn  observed=0.000461
```

The `observed` values are the honest burn rates of a healthy platform against its **real** budget: about
0.0004× — four ten-thousandths of the allowance. That is what "we are nowhere near the SLO" looks like as
a number, which is the whole reason a budget is more useful at 03:00 than a threshold.

> A `SKIPPED` verdict on a quiet sandbox is correct, not broken: under `burn-minimum-requests` (20) the
> rule refuses to compute a burn rate at all, because two slow requests out of three is a burn rate of 66
> and is not news (`docs/observability.md` §4.1).

### 5.10 Load tests and API tooling (after their steps)

```bash

# k6 load profiles (step 47) — k6 runs in Docker, no local install needed
docker run --rm -i --network=host grafana/k6 run - < load/k6/low.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/standard.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/black-friday.js

# Postman (living since step 04, finalized step 48): import tools/postman/pix-platform.postman_collection.json + environment

# API explorer (living since auth-service, finalized step 49): open from disk, log in, click any request
open tools/api-explorer/index.html
```

### 5.11 The whole journey in one run (step 46)

Everything in §5.1–§5.9 is one flow at a time. This is all of them at once, with assertions — the single
artifact that proves the vertical slices **compose** into a system, and then breaks BACEN on purpose to
prove the system recovers.

```bash
bash scripts/e2e-journey.sh              # ~6 min: the journey + both failure drills
bash scripts/e2e-journey.sh --quick      # ~40s: happy path only — does NOT prove KR3.1/KR3.2
bash scripts/e2e-journey.sh --verbose    # echo every response body as it arrives
```

Nine acts and two drills, each line of output one claim:

| | What it asserts |
|---|---|
| ACT 0 | Σ `balanceCents` over every account is the seeded supply, `0` — the baseline (KR1.1) |
| ACT 1–3 | the JWT decides which account pays; bob owns the destination key; both SSE streams are open **before** money moves |
| ACT 4 | an internal Pix debits once, and the retry of the same `Idempotency-Key` returns the same `transactionId` **and leaves exactly two ledger entries** |
| ACT 5 | an external Pix answers `202 PROCESSING`, parks the money in `SPI_CLEARING`, settles, releases clearing to `SPI_SETTLED`, and pushes `PixSettled` to the payer |
| ACT 6 | an inbound Pix credits bob and pushes `PixReceived`; re-presenting the same `endToEndId` answers `ALREADY_PROCESSED` and moves nothing |
| ACT 7 | the API's decimal balance renders the ledger's cents exactly; the statement shows the retried payment **once** |
| ACT 8 | one `correlationId` reconstructs the path across ≥3 services (KR4.1, via `scripts/trace.sh`) |
| DRILL A | rail 5xxs → accepted anyway → DLQ after five deliveries → **nothing reversed on a guess** → redrive → terminal state inside 300s (KR3.1) → DLQ back to 0 (KR3.2) → `ALERT FIRING` then `ALERT RESOLVED` |
| DRILL B | rail refuses the key permanently → reversed **on the same delivery**, payer refunded, a new `-rev` entry pair rather than an edit |
| ACT 9 | Σ balances is unchanged, still `0`, and nothing is stranded in clearing |

> **The drills use the shipped timers on purpose.** Restarting settlement-service with
> `RECONCILIATION_STUCK_AFTER_SECONDS=5` would finish the run in forty seconds and prove nothing: the
> claim is "*< 5 min with the thresholds we ship*". The SQS backoff ladder (5, 10, 20, 40, 60s capped)
> puts the sixth receive at ~135s, which is when the message dead-letters.

> **Safe to Ctrl-C.** An `EXIT` trap always restores mock-bacen's knobs (`failureRate`, `rejectKeys`), so
> an aborted drill never leaves a sandbox that refuses every payment afterwards.

The same journey runs from Maven — `mvn -Pe2e -pl tests/e2e -am verify` — which drives this exact script
and adds an independent SDK-side reading of Σ balances around it. See `tests/e2e/README.md`.


## 6. Running tests

```bash
mvn test                          # unit tests, all modules
mvn verify                        # + integration tests (Testcontainers spins LocalStack/Redis per module)
mvn -pl services/ledger-service -am verify   # one module only — note the -am
```

**Three checks that are not `mvn verify`, and cannot be** (steps 45–46):

```bash
# The error-contract audit: every documented non-2xx across the RUNNING stack is problem+json with
# code + correlationId and no stack trace. It reaches the six services' own domain codes, which live in
# six different processes and which no single-module test can produce. Needs the stack up, and jq.
bash scripts/error-contract-audit.sh            # add --verbose to print each body
```

```bash
# The end-to-end journey (step 46): the whole platform in one run — every flow, both failure drills, and
# Σ balances asserted across every account. Eight processes and a real queue, so no single-module IT can
# reach it. Needs the stack up, jq and the AWS CLI. See §5.11.
bash scripts/e2e-journey.sh                     # or: mvn -Pe2e -pl tests/e2e -am verify
```

```bash
# The security checklist (docs/security-checklist.md) is executed, not generated: its rows cite the test
# or command that proved each one, and the rows it CANNOT prove — the IAM policies under infra/iam/,
# which LocalStack accepts and never enforces — say so instead of showing a green tick.
```

> **Always pass `-am` when running a single module** (or run `mvn install -DskipTests` once first).
> The Testcontainers harness — `LocalStackTestBase`, which decides *which* LocalStack services are
> enabled and *which* init-script log line means "ready" — ships inside **common-lib's test-jar**.
> Without `-am`, Maven resolves that jar from `~/.m2`, and a stale copy fails in ways that look like
> anything but a stale jar: an IT hangs on the readiness wait, or the container comes up missing the
> newest resources — `501 Service 's3' is not enabled`, `QueueDoesNotExist: audit-queue` — because the
> old harness released the tests while the newest init script was still running. Cost this project real
> time in step 43. `mvn verify` from the repo root is always safe: it uses the reactor.

Integration tests do **not** need the compose stack running — Testcontainers manages disposable LocalStack/Redis containers per test run. That separation (compose = manual/E2E playground; Testcontainers = automated tests) keeps tests hermetic and repeatable.

> **Expected noise (step 40): `Cached balances could not be evicted…` in ledger-service's ITs.** Only the ITs that are *about* the cache (`BalanceCacheInvalidationIT`, and payment's `BalanceCacheIT`/`RedisBalanceCacheIT`) start a Redis container; the other posting ITs run without one, so each committed posting logs that WARN and carries on. That is not a broken test — it is ADR-0008's best-effort eviction being exercised on every run, and the assertion that matters (the money moved, atomically) is unaffected. The keys those tests would delete are unique per-run fixture accounts, so a developer with the compose stack up loses nothing either.

> ### Docker Engine API version — pinned in the build, nothing to pass (step 12)
>
> On a recent Docker engine (Desktop 25+/29.x, API 1.54, `MinAPIVersion` 1.40), Testcontainers/
> docker-java negotiates its **default API v1.32**, which the engine rejects — surfacing as `HTTP 400`
> or the misleading **`Could not find a valid Docker environment`**, even though `docker ps` works
> fine. It points at the socket and it is never the socket.
>
> This used to require `-DargLine="-Dapi.version=1.44"` on every command, which cost debugging time
> three separate times because nobody remembers a flag. It is now **pinned in the build**: the parent
> POM's `<docker.api.version>` property (default `1.44`) is passed to the failsafe-forked JVM as the
> `api.version` system property, so a plain `mvn verify` just works. On an engine older than API
> 1.44, override it: `mvn verify -Ddocker.api.version=1.41`.

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| A single-module IT run fails with `501 Service 's3' is not enabled`, `QueueDoesNotExist`, or hangs on the readiness wait | A **stale `common-lib` test-jar** in `~/.m2`: `LocalStackTestBase` came from there instead of the reactor. Re-run with `-am` (or `mvn install -DskipTests` once) — see §6. Never debug the init scripts first; they are mounted from disk and are fine |
| ITs fail with `Could not find a valid Docker environment` (but `docker ps` works) | Docker API version negotiation, **not** the socket. Pinned in the parent POM (`docker.api.version`, default 1.44); on an older engine run `mvn verify -Ddocker.api.version=1.41` — see §6 |
| `403 INTERNAL_PORT_FORBIDDEN` on an `/internal/**` curl that used to work | You are presenting a **user** token to a service port (step 68, ADR-0017). Mint the right one: `scripts/service-token.sh <aud> <scope>` — see §3.1. The service's WARN line names which of `typ`/`aud`/`scope` refused it |
| `403 PUBLIC_ROUTE_FORBIDDEN` on a `/v1/**` call | The reverse: a **service** token on a customer-facing route. Log in and use the user token — the two surfaces are deliberately disjoint |
| A service exits at startup with `Unable to load credentials from any of the providers in the chain` | The **`local` profile is not active** (step 45, ADR-0013). Since the credential sweep, that profile is the only thing that supplies an endpoint override and the placeholder keys; without it the SDK correctly looks for an ambient IAM role and there is none on your machine. Compose sets it by default — this bites when you export `SPRING_PROFILES_ACTIVE` yourself (use `json-logs,local`) or run one service with `spring-boot:run` (§4 "Iterating on a single service"). **The loud failure is the design**: the alternative is a service that looks production-configured and silently talks to an emulator |
| A service starts but every AWS call goes to the wrong place / times out | The profile is on but the endpoints are not — outside compose, `DYNAMODB_ENDPOINT_URL` does not default to the standalone container. Set both `AWS_ENDPOINT_URL=http://localhost:4566` and `DYNAMODB_ENDPOINT_URL=http://localhost:8000` |
| `bash scripts/error-contract-audit.sh` says `Could not log in` | The stack is down, or auth-service is still starting. `docker compose -f infra/docker-compose.yml ps` — every service must read `(healthy)` |
| Service can't reach LocalStack | Use `http://localstack:4566` inside compose network, `http://localhost:4566` from host |
| `ResourceNotFoundException` on a table | Init scripts didn't finish — check `localstack-init` logs; `down -v` and retry |
| Outbox events not flowing | Polling publisher in payment-service — check its logs and the `pix.outbox.lag` metric; query GSI3 for stuck unpublished items |
| 202 but status stuck in DEBITED | settlement-queue consumer down or BACEN failure injection active; reconciliation will resolve within 5 min — that's it working as designed |
| Port already in use | Adjust the host-side port mapping in `infra/docker-compose.yml` |
| RAM pressure | Every JVM is capped (`JAVA_TOOL_OPTIONS=-Xmx512m`); total stack ≈ 6–8GB |
