# Step 06 — docker-compose: LocalStack (DynamoDB) with healthchecks

> **Sprint 2 — Accounts & Pix Keys** · **Flow:** register / resolve a Pix key · **Infra que sobe:** LocalStack (DynamoDB) · **Diagram:** ARCHITECTURE §6.2

## Objective
`infra/docker-compose.yml` gains **LocalStack** with **DynamoDB enabled** (only what this flow needs), healthchecked, on the `pix-net` network. Other AWS services (SNS/SQS/S3) and Redis stay off until the flow that needs them.

## Why / what you'll learn
LocalStack is a local AWS emulator: your code uses the real AWS SDK, pointed at `http://localstack:4566` with dummy credentials — so the code you write locally is the code that runs on AWS, only the endpoint differs. The vertical discipline shows here: we enable **only DynamoDB** now; each later sprint flips on the next service. Compose `healthcheck` + `depends_on: condition: service_healthy` encodes startup ordering — a taste of what k8s solves at scale.

## Prerequisites
Step 01 (repo + compose file from step 03's auth-service entry).

## Tasks
1. Add `localstack` to `infra/docker-compose.yml`: image `localstack/localstack`, port 4566, env `SERVICES=dynamodb`, `DEBUG=1`, volume mounting `infra/localstack/init/` into `/etc/localstack/init/ready.d`, healthcheck on `localhost:4566/_localstack/health`.
2. `infra/localstack/init/` dir with a `README` note (table scripts arrive in step 07).
3. `infra/.env.example` documenting `AWS_*` dummy values, `AWS_ENDPOINT_URL`.

## Tests (TDD)
Infrastructure step — verification is the runbook commands below (automated equivalent lands in step 08 with Testcontainers).

## Verify locally
```bash
docker compose -f infra/docker-compose.yml up -d localstack
curl -s localhost:4566/_localstack/health | jq '.services.dynamodb'   # "available"/"running"
aws --endpoint-url=http://localhost:4566 dynamodb list-tables         # {"TableNames":[]}
docker compose -f infra/docker-compose.yml down -v
```

## Definition of Done
- [ ] LocalStack comes up healthy with DynamoDB enabled (and only that)
- [ ] AWS CLI against 4566 answers for dynamodb
- [ ] `down -v` leaves no state behind

## CHANGELOG entry
`### Added` → `docker-compose LocalStack (DynamoDB) with healthchecks, infra network and env template (step 06)`
