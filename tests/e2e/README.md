# tests/e2e — the end-to-end journey (step 46)

Not a service. A **test-only** Maven module with a single `*IT` that drives the entire running compose
stack and asserts the promises no single-module test can reach.

## What it proves

| | Claim | Where |
|---|---|---|
| **KR1.1** | Σ `balanceCents` over **every** account — alice, bob, `SPI_CLEARING`, `SPI_SETTLED`, `SEED` — is identical before and after a run that moved money six ways, one of them failed | ACT 9 + the SDK-side reading in `E2EJourneyIT` |
| **KR3.1** | A transaction stuck by a rail outage reaches a terminal state **inside the shipped 5-minute SLO**, measured from the send | DRILL A |
| **KR3.2** | `settlement-queue-dlq` returns to 0 after the outage and nothing in it was lost | DRILL A |
| **KR4.1** | One `correlationId` reconstructs the external send's path across every service it touched | ACT 8 |

## How to run it

```bash
mvn clean package -DskipTests
docker compose -f infra/docker-compose.yml up -d --build
mvn -Pe2e -pl tests/e2e -am verify
```

Or the same journey, same assertions, without Maven — this is the one a human runs while watching the
logs scroll:

```bash
bash scripts/e2e-journey.sh              # add --verbose to echo every response body
bash scripts/e2e-journey.sh --quick      # happy path only; does NOT prove KR3.1/KR3.2
```

## Three decisions worth knowing before you change anything here

**1. It is not in the default reactor.** `mvn verify` at the repo root does not build or run this
module; `-Pe2e` does. Every other `*IT` in this repo is hermetic — Testcontainers brings up its own
LocalStack/Redis and the suite passes with the compose stack *down* (`docs/local-dev.md` §6). This one
is the deliberate exception: its facts live in eight processes talking over a real queue. Folding it
into the default build would make the command this project runs dozens of times a day depend on a
running stack and take the several minutes the failure drills legitimately need.

**2. The journey has one definition, and it is the shell script.** `E2EJourneyIT` executes
`scripts/e2e-journey.sh` and streams its output. That is a choice, not a shortcut: restating forty
cross-service assertions in a second language would create twin artifacts that drift — the exact defect
`CLAUDE.md` forbids for the Postman collection and the API explorer, and the one
`MoneyConservation`'s javadoc explains for conservation itself. What the Java side *adds* is what a
shell script cannot: a place for `mvn`/CI to hang the journey, and an **independent second reading of
Σ balances** through the AWS SDK, wrapped around the whole run. If the two ever disagree, one of them
is reading the ledger wrong.

**3. A missing stack fails; it never skips.** A skipped end-to-end test looks exactly like a passing one
in a build log, and this project's workflow forbids closing a step with skipped tests. So an unreachable
stack is an explicit failure carrying the command that fixes it.

## Why the drills take minutes

Because the thresholds are the shipped ones. It would be trivial to restart settlement-service with
`RECONCILIATION_STUCK_AFTER_SECONDS=5` and finish in forty seconds — and it would prove nothing, since
the claim under test is "*< 5 min with the thresholds we ship*". The SQS backoff ladder (5, 10, 20, 40,
60s capped) puts the sixth receive at ~135s, which is when the message dead-letters; the drill prints
its progress while it waits.

## ADRs it exercises

ADR-0002 (idempotency), ADR-0003 (async settlement, retries, DLQ, the <5-min reconciliation SLO),
ADR-0004 (transactional outbox), ADR-0008 (cache-aside — and why conservation is *not* read through it),
ADR-0012 (the correlation id in the log pattern), ADR-0016 (finalization fencing).
