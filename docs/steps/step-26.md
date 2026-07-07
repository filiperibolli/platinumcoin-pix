# Step 26 — LocalStack init: SNS pix-events + settlement-queue (+DLQ)

> **Sprint 6 — Send Pix (external)** · **Flow:** external Pix → SETTLED · **Infra que sobe:** SNS `pix-events`, SQS `settlement-queue` + DLQ · **Diagram:** ARCHITECTURE §6.6

## Objective
Enable **SNS + SQS** on LocalStack and create the SNS topic `pix-events`, the `settlement-queue` with its `settlement-queue-dlq` (redrive policy, maxReceiveCount 5), and the SNS→SQS subscription with a filter policy on `eventType`.

## Why / what you'll learn
The messaging backbone comes up **only now**, for the first asynchronous flow — everything before this was synchronous. You'll learn SNS fan-out + SQS per-consumer queues (ADR-0004), **DLQ via redrive policy** (native to SQS — you get it for free, unlike Kafka; see the Kafka appendix), and **subscription filter policies** so a queue only receives the event types it cares about. `SERVICES` in the compose LocalStack config grows to `dynamodb,sns,sqs`.

## Prerequisites
Step 17 (transactions table), Step 06 (LocalStack).

## Tasks
1. Flip compose LocalStack `SERVICES=dynamodb,sns,sqs`.
2. `06-messaging-core.sh` — create SNS topic `pix-events`; create `settlement-queue` + `settlement-queue-dlq` with redrive (maxReceiveCount 5); subscribe the queue to the topic with filter policy `eventType IN [PixDebited]` (extended in later sprints).
3. Mirror in `docs/local-dev.md`; document the queue/DLQ naming convention.

## Tests (TDD)
Verified by the publisher/consumer ITs (steps 29, 31) and the runbook below.

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 sns list-topics | jq
aws --endpoint-url=http://localhost:4566 sqs list-queues | jq   # settlement-queue + dlq
```

## Definition of Done
- [ ] SNS topic + settlement-queue(+DLQ, redrive) + filtered subscription created; scripts idempotent
- [ ] LocalStack now exposes dynamodb, sns, sqs
- [ ] Naming convention documented

## CHANGELOG entry
`### Added` → `LocalStack init: SNS pix-events + settlement-queue with DLQ/redrive and filtered subscription (step 26)`
