# Step 04 — docker-compose: LocalStack + Redis (infra only)

## Objective
`infra/docker-compose.yml` bringing up **LocalStack** (DynamoDB, SQS, SNS, S3 enabled) and **Redis**, with healthchecks, on a named network. No app services yet.

## Why / what you'll learn
LocalStack is a local AWS emulator: your code uses the real AWS SDK, pointed at `http://localstack:4566` (one edge port for all services) with dummy credentials — so the code you write locally is the code that runs on AWS, only the endpoint differs. Redis rides alongside **because LocalStack does not emulate ElastiCache** (we document this explicitly). Compose `healthcheck` + `depends_on: condition: service_healthy` is how you encode startup ordering — a small taste of the orchestration problems k8s solves at scale.

## Prerequisites
Step 01 (repo exists). Independent of steps 02–03.

## Tasks
1. `infra/docker-compose.yml`: services `localstack` (image `localstack/localstack`, port 4566, env `SERVICES=dynamodb,sqs,sns,s3`, `DEBUG=1`, volume for `/etc/localstack/init/ready.d` mounted from `infra/localstack/init/`, healthcheck on `localhost:4566/_localstack/health`) and `redis` (image `redis:7-alpine`, port 6379, healthcheck `redis-cli ping`). Network `pix-net`.
2. `infra/localstack/init/` empty dir with a `README` note (scripts arrive in step 05).
3. `infra/.env.example` documenting `AWS_*` dummy values.

## Tests (TDD)
Infrastructure step — verification is the runbook commands below (automated equivalent lands in step 06 with Testcontainers).

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d
curl -s localhost:4566/_localstack/health | jq '.services | {dynamodb,sqs,sns,s3}'   # all "available"/"running"
docker compose -f infra/docker-compose.yml exec redis redis-cli ping                  # PONG
aws --endpoint-url=http://localhost:4566 dynamodb list-tables                         # {"TableNames":[]}
docker compose -f infra/docker-compose.yml down -v
```

## Definition of Done
- [ ] One command brings both containers up healthy
- [ ] AWS CLI against 4566 answers for dynamodb/sqs/sns/s3
- [ ] `down -v` leaves no state behind

## CHANGELOG entry
`### Added` → `docker-compose infrastructure: LocalStack (DynamoDB/SQS/SNS/S3) and Redis with healthchecks (step 04)`
