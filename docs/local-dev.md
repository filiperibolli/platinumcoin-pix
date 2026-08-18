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

## 3. Environment variables (shared conventions)

Set in `infra/docker-compose.yml`; local defaults in each service's `application.yml`.

| Variable | Default | Purpose |
|---|---|---|
| `AWS_ENDPOINT_URL` | `http://localstack:4566` | Point the SDK at LocalStack (SNS/SQS only — see below) |
| `DYNAMODB_ENDPOINT_URL` | `http://dynamodb-local:8000` | Point the DynamoDB client at the standalone `dynamodb-local` container, not LocalStack (`docs/load/BOTTLENECK.md`). Falls back to `AWS_ENDPOINT_URL` if unset (`aws.dynamodb-endpoint-url: ${DYNAMODB_ENDPOINT_URL:${aws.endpoint-url}}`) — so `LocalStackTestBase` ITs, which only override `aws.endpoint-url`, are unaffected, but a service run **outside compose** (e.g. `spring-boot:run`, §5.x below) must set this explicitly or its DynamoDB calls will hit LocalStack, which no longer serves it (`501 Service 'dynamodb' is not enabled`) |
| `AWS_REGION` | `us-east-1` | — |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds |
| `JWT_SECRET` | dev-only value in compose | HS256 signing/validation |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` | Balance cache |
| `BACEN_BASE_URL` | `http://mock-bacen-spi:9090` | SPI stub. Read by account-service (the DICT lookup for keys not held locally, step 30) and later by settlement-service |
| `BACEN_CONNECT_TIMEOUT_MS` / `BACEN_READ_TIMEOUT_MS` | `500` / `1500` | account-service's budget for the DICT call — it is on the **synchronous** send path, so a gone SPI must surface as a timeout (→ `503 DIRECTORY_UNAVAILABLE`), never a pinned request thread |
| `BACEN_LATENCY_MS` | `2000` | Simulated **settlement** latency (0–10000). Not applied to the DICT lookup, on purpose (see `services/mock-bacen-spi/README.md`) |
| `BACEN_FAILURE_RATE` | `0.0` | Fraction of settlement calls answered `503` with **nothing recorded** — transient, so the same `endToEndId` can still settle on a retry |
| `BACEN_TIMEOUT_RATE` | `0.0` | Fraction of settlement calls that **settle and then hang** — BACEN moved the money, the caller never heard. The query-before-retry case (step 32) |
| `BACEN_TIMEOUT_HANG_MS` | `15000` | How long such a call hangs; must exceed the client's own 12s timeout (step 31) or a "timeout" is just a slow success. Boot-time only — `POST /admin/config` cannot lower it mid-drill |
| `SPI_WEBHOOK_TOKEN` | dev-only value in compose | Authenticates mock-bacen's inbound webhook calls to settlement-service |
| `FRAUD_TIMEOUT_MS` | `200` | Fraud budget in payment-service |
| `SNS_TOPIC_ARN` | `arn:aws:sns:us-east-1:000000000000:pix-events` | Topic the outbox publisher drains into (injected, never looked up by name — ADR-0013) |
| `OUTBOX_PUBLISHER_DELAY_MS` | `1000` | Outbox poll interval (`fixedDelay`, so ticks never overlap) |
| `OUTBOX_PUBLISHER_BATCH_SIZE` | `25` | Max events one tick may publish |
| `PIX_SCHEDULERS_ENABLED` | `true` | Master switch for background jobs; integration tests set it `false` and drive each tick explicitly |

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
for p in 8081 8082 8083 8084 8085; do
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

> **Step 26 (messaging backbone).** LocalStack now runs `SERVICES=dynamodb,sns,sqs` and `up` creates the
> SNS topic `pix-events`, `settlement-queue` + `settlement-queue-dlq` (redrive after 5 receives) and the
> filtered subscription. Nothing publishes or consumes yet — the producer is the outbox publisher (step
> 29) and the consumer is settlement-service (step 31) — so the queues come up **empty on purpose**.
> Verify with `aws --endpoint-url=http://localhost:4566 sns list-topics | jq` and `… sqs list-queues | jq`
> (the full command set is in §4, "Messaging"); the init log line to look for is
> `[init] messaging ready: …`, which is also the readiness marker the test harness waits on.

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
>   -H "Authorization: Bearer $TOKEN" | jq                         # {internal:false, externalBank:"99999999"}
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
> `QueueDoesNotExist`. The probe now also asserts a resource created by the *last* init script
> (`pix_processed_events`) exists — scripts run in lexical order, so that one existing means all of them
> ran. Compose and the Testcontainers harness now agree on what readiness means (`LocalStackTestBase`
> already waited on that script's final log line). **If you add an init script that sorts after `07`, move
> both markers.**
>
> Two caveats worth knowing before §5.5's drill: retries, backoff and DLQ handling are **step 32** — today
> a failed or timed-out settlement simply leaves the message on the queue (which is what makes those
> retries possible, since the dedup claim is released), and a permanent `422` refusal is left for the
> reversal of step 33. And `PixSettled` is *written* here but *published* by payment-service's outbox
> publisher, which drains the whole sparse index of `pix_transactions` — so a stopped payment-service
> means settled payments that nobody is told about yet.

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

# a DynamoDB-touching service (account/ledger/payment/settlement) run this way still needs the
# rest of the compose stack up (`docker compose up -d`) AND both AWS endpoints pointed at its
# host-published ports — DYNAMODB_ENDPOINT_URL does NOT default to the right thing outside compose:
JWT_SECRET=dev-only-hs256-secret-change-me-please-32b \
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

**S3** — buckets `pix-audit-log` (versioning + object-lock config documented) and `pix-statement-archive` (step 42); `pix-statement-exports` (step 53).

**Seed data** — demo accounts alice/bob with daily limits (step 07) and initial ledger balances R$ 10,000.00 each funded from `ACCOUNT#SEED` (with the matching `SEED_FUNDING` entries on both sides), plus system account `SPI_CLEARING` at 0 — so Σ over every account is **zero** (step 12). Pix keys are registered via the API, not seeded.

The LocalStack `SERVICES` env grows across sprints: `dynamodb` (Sprint 2) → `+sns,sqs` (Sprint 6, **already flipped** — step 26) → `+s3` (Sprint 10). The list is **enforced**: calling a service that is not on it answers `501 Service 'sqs' is not enabled`, so enabling the service and creating its resources always land in the same change (and so does the matching `withServices(...)` in `LocalStackTestBase`).

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
(`ConsistentRead=true`) because the ledger must read its own writes; `/internal/**` is not public, so
a token is required:

```bash
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# {"accountId":"acc-001","balance":"10000.00","balanceCents":1000000,"version":0}
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq

# the same Σ = 0 as the raw get-item loop above, now through the service
for a in acc-001 acc-002 SPI_CLEARING SEED; do
  curl -s "localhost:8085/internal/ledger/accounts/$a/balance" \
    -H "Authorization: Bearer $TOKEN" | jq -r .balanceCents
done | paste -sd+ | bc                                          # 0 — conservation baseline

curl -s localhost:8085/internal/ledger/accounts/acc-999/balance \
  -H "Authorization: Bearer $TOKEN" | jq   # 404 LEDGER_ACCOUNT_NOT_FOUND — never a zero balance
curl -si localhost:8085/internal/ledger/accounts/acc-001/balance | head -1   # 401 without a token
```

**Step 14 — moving money: the atomic double-entry posting.** One `TransactWriteItems` of five items
(`docs/data-model.md` §3); every guard is a condition *inside* it, so a refusal writes nothing at all.

```bash
POSTING='{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002",
          "amountCents":12550,"entryType":"PIX_INTERNAL","description":"manual test"}'

# 200 — {"txId":"tx-manual-1",…,"amount":"125.50","amountCents":12550,"replayed":false,…}
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$POSTING" | jq

# alice 10000.00 → 9874.50, bob 10000.00 → 10125.50, and Σ over the four accounts is still 0
for a in acc-001 acc-002 SPI_CLEARING SEED; do
  curl -s "localhost:8085/internal/ledger/accounts/$a/balance" \
    -H "Authorization: Bearer $TOKEN" | jq -r .balanceCents
done | paste -sd+ | bc                                          # 0 — money moved, none created

# IDEMPOTENCY: send the identical request again ⇒ 200 "replayed": true, balance unchanged.
# Note the *postedAt* it returns is the first posting's instant, not now.
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$POSTING" | jq '{replayed, postedAt}'
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance \
  -H "Authorization: Bearer $TOKEN" | jq -r .balance          # 9874.50 — once, not twice

# the guard item behind that: keyed by txId alone, so the clock is not part of a posting's identity
aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_ledger \
  --key '{"pk":{"S":"TX#tx-manual-1"},"sk":{"S":"POSTING"}}'
# …and GSI1 still returns exactly the two legs (the guard carries no gsi1pk)
aws --endpoint-url=http://localhost:8000 dynamodb query --table-name pix_ledger \
  --index-name gsi1 --key-condition-expression 'gsi1pk = :t' \
  --expression-attribute-values '{":t":{"S":"TX#tx-manual-1"}}' | jq '.Count'   # 2

# the refusals — each returns problem+json and writes NOTHING
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-1","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":99,"entryType":"PIX_INTERNAL"}' \
  | jq .code    # POSTING_TXID_MISMATCH (409) — same identity, different money
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"txId":"tx-manual-2","debitAccount":"acc-001","creditAccount":"acc-002","amountCents":99999999,"entryType":"PIX_INTERNAL"}' \
  | jq .code    # INSUFFICIENT_FUNDS (422) — the condition failed inside the transaction
curl -s -X POST localhost:8085/internal/ledger/postings -H "Authorization: Bearer $TOKEN" \
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
curl -s localhost:8084/actuator/metrics/outbox.lag | jq
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
curl -s localhost:8085/internal/ledger/accounts/acc-002/balance -H "Authorization: Bearer $TOKEN" | jq

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
# environment: SPRING_PROFILES_ACTIVE: json-logs
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
# 1. held here (local table first — no network hop for a key we already own)
curl -s "localhost:8082/internal/pix-keys/resolve?key=alice@platinum.com" -H "Authorization: Bearer $TOKEN" | jq
# 2. held at another PSP — delegated to mock-bacen's DICT ⇒ the external send branch
curl -s "localhost:8082/internal/pix-keys/resolve?key=bob@otherbank.com" -H "Authorization: Bearer $TOKEN" | jq
# 3. nowhere at all ⇒ 404 KEY_NOT_FOUND (the only honest not-found)
curl -si "localhost:8082/internal/pix-keys/resolve?key=nobody@nowhere.com" -H "Authorization: Bearer $TOKEN" | head -1
# 4. FAIL CLOSED: with the DICT unreachable the answer is 503 DIRECTORY_UNAVAILABLE + Retry-After —
#    NOT a 404, which would tell the payer their payee's key is invalid because OUR dependency is down.
docker compose -f infra/docker-compose.yml stop mock-bacen-spi
curl -si "localhost:8082/internal/pix-keys/resolve?key=bob@otherbank.com" -H "Authorization: Bearer $TOKEN" | head -3
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
  -H "Authorization: Bearer $TOKEN" | jq   # credited by exactly the amount in flight
```

### 5.4 Status + settlement observation

```bash
TX=<transactionId from above>
watch -n1 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq .status"
# PROCESSING → SETTLED after BACEN_LATENCY_MS

# peek at the settlement queue while a message is in flight
awsl sqs receive-message --queue-url $(awsl sqs get-queue-url --queue-name settlement-queue --output text --query QueueUrl) --visibility-timeout 0 | jq
```

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

# 3) settlement gets 422 SETTLEMENT_REJECTED_BY_ADMIN → step 33 reverses; within ~5 min the status is REVERSED
#    and the payer is refunded (SPI_CLEARING nets back to 0). The scanner (60s) + resolver drive it:
watch -n2 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq .status"
docker compose -f infra/docker-compose.yml logs settlement-service | grep -E 'Reconciliation resolved|ALERT'

# 4) clear the reject list so the key settles normally again:
docker compose -f infra/docker-compose.yml exec mock-bacen-spi \
  curl -s -X POST localhost:9090/admin/config -d '{"rejectKeys":[]}' -H 'Content-Type: application/json'
```

### 5.6 Receive Pix + real-time notification

```bash
# terminal 1: subscribe to bob's notification stream
BOB=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bob"}' | jq -r .accessToken)
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $BOB"

# terminal 2: make BACEN generate an inbound Pix to bob
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"External Payer"}'
# terminal 1 shows the SSE event "PIX_RECEIVED" in real time
```

### 5.7 Balance & statement (cache)

```bash
curl -s localhost:8084/v1/accounts/me/balance -H "Authorization: Bearer $TOKEN" | jq
curl -s "localhost:8084/v1/accounts/me/statement?limit=5" -H "Authorization: Bearer $TOKEN" | jq
# see the cache with redis-cli:
docker compose -f infra/docker-compose.yml exec redis redis-cli GET balance:acc-001
```

### 5.8 Audit trail in S3

```bash
awsl s3 ls s3://pix-audit-log/ --recursive | tail
awsl s3 cp s3://pix-audit-log/<key> - | jq
```

### 5.9 Dashboards, load tests and API tooling (after their steps)

```bash
# Grafana (admin/admin): technical dashboard + business funnel
open http://localhost:3000

# k6 load profiles (step 47) — k6 runs in Docker, no local install needed
docker run --rm -i --network=host grafana/k6 run - < load/k6/low.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/standard.js
docker run --rm -i --network=host grafana/k6 run - < load/k6/black-friday.js

# Postman (living since step 04, finalized step 48): import tools/postman/pix-platform.postman_collection.json + environment

# API explorer (living since auth-service, finalized step 49): open from disk, log in, click any request
open tools/api-explorer/index.html
```

## 6. Running tests

```bash
mvn test                       # unit tests, all modules
mvn verify                     # + integration tests (Testcontainers spins LocalStack/Redis per module)
mvn -pl services/ledger-service verify   # one module only
```

Integration tests do **not** need the compose stack running — Testcontainers manages disposable LocalStack/Redis containers per test run. That separation (compose = manual/E2E playground; Testcontainers = automated tests) keeps tests hermetic and repeatable.

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
| ITs fail with `Could not find a valid Docker environment` (but `docker ps` works) | Docker API version negotiation, **not** the socket. Pinned in the parent POM (`docker.api.version`, default 1.44); on an older engine run `mvn verify -Ddocker.api.version=1.41` — see §6 |
| Service can't reach LocalStack | Use `http://localstack:4566` inside compose network, `http://localhost:4566` from host |
| `ResourceNotFoundException` on a table | Init scripts didn't finish — check `localstack-init` logs; `down -v` and retry |
| Outbox events not flowing | Polling publisher in payment-service — check its logs and the `outbox.lag` metric; query GSI3 for stuck unpublished items |
| 202 but status stuck in DEBITED | settlement-queue consumer down or BACEN failure injection active; reconciliation will resolve within 5 min — that's it working as designed |
| Port already in use | Adjust the host-side port mapping in `infra/docker-compose.yml` |
| RAM pressure | Every JVM is capped (`JAVA_TOOL_OPTIONS=-Xmx512m`); total stack ≈ 6–8GB |
