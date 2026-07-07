# Step 42 — LocalStack init: audit-queue + S3 buckets

> **Sprint 10 — Audit** · **Flow:** immutable audit trail · **Infra que sobe:** SQS `audit-queue`, S3 buckets · **Diagram:** ARCHITECTURE §6.10

## Objective
Enable **S3** on LocalStack; create the `audit-queue` (+DLQ) subscribed to `pix-events` with **no filter** (all events), and the buckets `pix-audit-log` (versioning + Object Lock config documented) and `pix-statement-archive`.

## Why / what you'll learn
The audit queue subscribes to **every** event (no filter policy) — the audit trail must be complete, unlike the notification queue. S3 comes up only now, for the flow that needs object storage. You'll configure the immutability posture — **versioning + Object Lock (compliance mode) + 5-year retention** — which LocalStack accepts as configuration even though the hard guarantee is AWS-side; documenting the difference honestly is part of the exercise. `SERVICES` grows to `dynamodb,sns,sqs,s3`.

## Prerequisites
Step 26 (messaging core), Step 06 (LocalStack).

## Tasks
1. Flip compose LocalStack `SERVICES=dynamodb,sns,sqs,s3`.
2. `09-audit.sh` — `audit-queue`(+DLQ) subscribed to `pix-events` with no filter; buckets `pix-audit-log` (versioning on; Object Lock compliance + retention documented) and `pix-statement-archive`.
3. Mirror in `docs/local-dev.md`; note LocalStack vs AWS immutability caveat.

## Tests (TDD)
Verified by the audit writer IT (step 43) and the runbook.

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 s3 ls | jq -R .   # pix-audit-log, pix-statement-archive
aws --endpoint-url=http://localhost:4566 sqs list-queues | jq   # + audit-queue (+dlq)
```

## Definition of Done
- [ ] audit-queue(+DLQ, unfiltered) + both buckets created; scripts idempotent
- [ ] LocalStack now exposes dynamodb, sns, sqs, s3
- [ ] Immutability config present; LocalStack-vs-AWS caveat documented

## CHANGELOG entry
`### Added` → `LocalStack init: audit-queue (all events) + S3 audit-log/statement-archive buckets with immutability config (step 42)`
