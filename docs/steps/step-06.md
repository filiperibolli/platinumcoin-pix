# Step 06 — Testcontainers integration-test harness

## Objective
A reusable test foundation in `common-lib` (test-jar): `LocalStackTestBase` and `RedisTestBase` that spin disposable LocalStack/Redis containers, run the **same init scripts from step 05**, and inject endpoints into Spring properties.

## Why / what you'll learn
Testcontainers gives **hermetic integration tests**: each `mvn verify` starts real (containerized) DynamoDB/SQS/SNS/S3 and Redis, runs the suite, throws them away — no "works on my machine", no dependency on the compose stack being up, safe in CI. Mounting the same init scripts used by compose means test infra and dev infra can never drift — one source of truth. Also learn `@DynamicPropertySource`, the Spring hook that feeds container-assigned ports into configuration.

## Prerequisites
Steps 03, 05.

## Tasks
1. Add Testcontainers (`localstack`, `junit-jupiter`) + `redis` container deps to common-lib test scope; produce a `test-jar` artifact other modules consume.
2. `LocalStackTestBase`: static `LocalStackContainer` with DYNAMODB,SQS,SNS,S3, classpath mount of `infra/localstack/init` into `/etc/localstack/init/ready.d`, container reuse flag for speed; `@DynamicPropertySource` exporting `aws.endpoint`, region, dummy creds.
3. `RedisTestBase`: `GenericContainer("redis:7-alpine")` exporting host/port.
4. Shared `AwsTestClients` helper: builds SDK v2 clients (Dynamo, SQS, SNS, S3) against the container.
5. Sample IT in common-lib proving the harness: lists tables, asserts `pix_ledger` exists and seed balance = 1_000_000 cents.

## Tests (TDD)
- `LocalStackHarnessIT` — tables exist post-init; seed data readable.
- `RedisHarnessIT` — SET/GET round-trip.

## Verify locally
```bash
mvn -q -pl services/common-lib verify   # containers start, ITs green, containers gone after
docker ps                                # nothing left running
```

## Definition of Done
- [ ] Any module can extend the bases and get full AWS+Redis test infra
- [ ] Init scripts are mounted from `infra/` (not copied)
- [ ] `mvn verify` needs no pre-started environment

## CHANGELOG entry
`### Added` → `Testcontainers harness (LocalStack + Redis) sharing the compose init scripts (step 06)`
