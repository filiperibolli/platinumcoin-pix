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
```

## 2. Ports

| Component | Port |
|---|---|
| LocalStack (all AWS APIs) | 4566 |
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
| `AWS_ENDPOINT_URL` | `http://localstack:4566` | Point SDK at LocalStack |
| `AWS_REGION` | `us-east-1` | — |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds |
| `JWT_SECRET` | dev-only value in compose | HS256 signing/validation |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` | Balance cache |
| `BACEN_BASE_URL` | `http://mock-bacen-spi:9090` | SPI stub |
| `BACEN_LATENCY_MS` | `2000` | Simulated SPI latency (0–10000) |
| `BACEN_FAILURE_RATE` | `0.0` | Fraction of SPI calls that 500 |
| `BACEN_TIMEOUT_RATE` | `0.0` | Fraction of SPI calls that hang |
| `FRAUD_TIMEOUT_MS` | `200` | Fraud budget in payment-service |

## 4. Bring it up

```bash
# from repo root
mvn clean package -DskipTests                # build all service jars
docker compose -f infra/docker-compose.yml up -d --build

# watch LocalStack init (creates tables/queues/topics/buckets + seed data)
docker compose -f infra/docker-compose.yml logs -f localstack-init

# health of everything
for p in 8081 8082 8083 8084 8085 8086 8087 9090; do
  echo -n "$p: "; curl -s localhost:$p/actuator/health | jq -r .status; done
```

Tear down: `docker compose -f infra/docker-compose.yml down -v` (`-v` wipes LocalStack/Redis data → next `up` reseeds a clean world).

### What the init scripts do (`infra/localstack/init/*.sh`)

LocalStack executes scripts in `/etc/localstack/init/ready.d/` once the emulator is ready — this is the standard "infrastructure as init script" pattern for local AWS. The scripts are **added incrementally, one flow at a time** (vertical delivery — see `PLAN.md`): each sprint enables the LocalStack `SERVICES` and creates the resources its flow needs, so a partial checkout only stands up what the built flows use. Once the whole platform is built, the full set below runs on every `up`:

**DynamoDB tables** — accounts/keys (`pix_accounts`, `pix_keys`; step 07), ledger (`pix_ledger`, GSI1; step 12), transactions (`pix_transactions` with GSI1/GSI2 and the sparse GSI3 outbox index) + idempotency (`pix_idempotency`, TTL; step 17), and consumer dedup (`pix_processed_events`, TTL; step 29).

**Messaging** — SNS topic `pix-events` + `settlement-queue`(+DLQ) with a filtered subscription (step 26); `notification-queue`(+DLQ, filtered) and `inbound-pix-queue`(+DLQ) (step 36); `audit-queue`(+DLQ, unfiltered — all events) (step 42). Filter policies route by `eventType`.

**S3** — buckets `pix-audit-log` (versioning + object-lock config documented) and `pix-statement-archive` (step 42).

**Seed data** — demo accounts alice/bob with daily limits (step 07) and initial ledger balances R$ 10,000.00 each funded from `ACCOUNT#SEED`, plus system account `SPI_CLEARING` (step 12). Pix keys are registered via the API, not seeded.

The LocalStack `SERVICES` env grows across sprints: `dynamodb` (Sprint 2) → `+sns,sqs` (Sprint 6) → `+s3` (Sprint 10).

## 5. Testing each flow by hand

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

# Postman (step 48): import tools/postman/pix-platform.postman_collection.json + environment

# API explorer (step 49): open in a browser, click any request
open tools/api-explorer/index.html
```

## 6. Running tests

```bash
mvn test                       # unit tests, all modules
mvn verify                     # + integration tests (Testcontainers spins LocalStack/Redis per module)
mvn -pl services/ledger-service verify   # one module only
```

Integration tests do **not** need the compose stack running — Testcontainers manages disposable LocalStack/Redis containers per test run. That separation (compose = manual/E2E playground; Testcontainers = automated tests) keeps tests hermetic and repeatable.

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Service can't reach LocalStack | Use `http://localstack:4566` inside compose network, `http://localhost:4566` from host |
| `ResourceNotFoundException` on a table | Init scripts didn't finish — check `localstack-init` logs; `down -v` and retry |
| Outbox events not flowing | Polling publisher in payment-service — check its logs and the `outbox.lag` metric; query GSI3 for stuck unpublished items |
| 202 but status stuck in DEBITED | settlement-queue consumer down or BACEN failure injection active; reconciliation will resolve within 5 min — that's it working as designed |
| Port already in use | Adjust the host-side port mapping in `infra/docker-compose.yml` |
| RAM pressure | Every JVM is capped (`JAVA_TOOL_OPTIONS=-Xmx512m`); total stack ≈ 6–8GB |
