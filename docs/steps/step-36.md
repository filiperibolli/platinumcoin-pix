# Step 36 — LocalStack init: notification-queue + inbound-pix-queue

> **Sprint 8 — Receive & notify** · **Flow:** inbound Pix → SSE push · **Infra que sobe:** SQS `notification-queue` + `inbound-pix-queue` (+DLQs) · **Diagram:** ARCHITECTURE §6.8

## Objective
Create `notification-queue` + DLQ (subscribed to `pix-events` with a filter policy `eventType IN [PixSettled, PixReceived, PixReversed]`) and `inbound-pix-queue` + DLQ (for BACEN-originated inbound settlement work). Extend the SNS subscriptions accordingly.

## Why / what you'll learn
Fan-out in action: the **same** SNS topic `pix-events` now feeds *another* consumer (notification-service) alongside settlement — each with its own physical queue and filter policy, the SNS+SQS analogue of Kafka consumer groups (see the Kafka appendix). You'll refine filter policies so notifications only wake on user-facing events, not internal ones. The `inbound-pix-queue` decouples BACEN's inbound webhook from the credit-posting work.

## Prerequisites
Step 26 (messaging core).

## Tasks
1. `08-messaging-notify.sh` — create `notification-queue`(+DLQ) subscribed to `pix-events` filtered to user-facing event types; create `inbound-pix-queue`(+DLQ).
2. Update the existing `settlement-queue` filter policy if needed so events route correctly.
3. Mirror in `docs/local-dev.md`.

## Tests (TDD)
Verified by the notification and inbound ITs (steps 37–39) and the runbook.

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 sqs list-queues | jq   # + notification-queue, inbound-pix-queue (+dlqs)
```

## Definition of Done
- [ ] notification-queue(+DLQ, filtered) and inbound-pix-queue(+DLQ) created; scripts idempotent
- [ ] Filter policies route user-facing events to notification-queue only
- [ ] Runbook updated

## CHANGELOG entry
`### Added` → `LocalStack init: notification-queue (filtered) and inbound-pix-queue with DLQs (step 36)`
