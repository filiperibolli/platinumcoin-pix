# Step 08 — Testcontainers integration-test harness (LocalStack) in common-lib

> **Sprint 2 — Accounts & Pix Keys** · **Flow:** register / resolve a Pix key · **Infra que sobe:** none (test-only, disposable) · **Diagram:** ARCHITECTURE §6.2

## Objective
A reusable test foundation in `common-lib` (test-jar): `LocalStackTestBase` that spins a disposable LocalStack container, runs the **same init scripts from step 07**, and injects endpoints/credentials into Spring properties — so integration tests never depend on the compose stack being up.

## Why / what you'll learn
The separation that keeps tests hermetic: **compose = manual/E2E playground; Testcontainers = automated tests**. Reusing the *same* init scripts in tests means the schema you test against is the schema you run — no drift between a hand-maintained test fixture and the real init. You'll learn `@DynamicPropertySource` to point the AWS SDK at the container, and how to make the base class fast (container reuse across a module's ITs).

## Prerequisites
Steps 06, 07.

## Tasks
1. `common-lib` test-jar: `LocalStackTestBase` using the Testcontainers LocalStack module (DynamoDB service), running `infra/localstack/init/*.sh` on start.
2. `@DynamicPropertySource` injecting `AWS_ENDPOINT_URL`, region, dummy creds.
3. Publish the test-jar so services can extend the base in their own `*IT` classes.
4. A smoke `LocalStackHarnessIT` proving a table from the init scripts is queryable.

## Tests (TDD)
- `LocalStackHarnessIT` — container starts, init scripts ran, `pix_accounts` exists and the seed item is readable.

## Verify locally
```bash
mvn -q -pl services/common-lib verify   # spins a disposable LocalStack, no compose needed
```

## Definition of Done
- [ ] `LocalStackTestBase` reusable by any module; runs the real init scripts
- [ ] ITs pass with the compose stack DOWN (Testcontainers-managed)
- [ ] Endpoints injected via `@DynamicPropertySource`

## CHANGELOG entry
`### Added` → `Testcontainers LocalStack harness in common-lib running the real init scripts (step 08)`
