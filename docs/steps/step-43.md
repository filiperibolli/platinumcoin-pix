# Step 43 — Immutable audit trail → S3 + statement cold-archive

> **Sprint 10 — Audit** · **Flow:** immutable audit trail · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.10

## Objective
An `AuditWriter` in settlement-service consumes `audit-queue` (all events) and appends JSON lines to S3 `pix-audit-log` partitioned `yyyy/MM/dd/HH/<service>-<uuid>.jsonl` (batched: ~100 events or 30s). A scheduled `StatementArchiver` copies ledger entries older than a configurable window to `pix-statement-archive` (JSON lines). Deletion from hot storage is deliberately **not** done locally (documented).

## Why / what you'll learn
The audit trail is the platform's **long-term event store** — the SNS/SQS equivalent of Kafka's replayable log is exactly this S3 archive (see the Kafka appendix). You'll learn batched writes (cost/throughput vs latency of small objects), time-partitioned keys for cheap range retrieval, and the immutability posture for the 5-year BACEN retention. The cold-archive job sets up Sprint 14's async export (step 53): the archive is the source those exports read from.

## Prerequisites
Steps 29 (events flowing), 42 (queue + buckets).

## Tasks
1. `AuditWriter` (settlement-service): consume `audit-queue`, batch, append JSONL to `pix-audit-log` with the partitioned key; dedupe/idempotency acceptable to be at-least-once (audit tolerates dup lines, documented) or dedupe by eventId.
2. `StatementArchiver` (`@Scheduled`): copy ledger entries older than the window to `pix-statement-archive` as `account=<id>/yyyy-MM.jsonl`; do **not** delete hot data locally (note the prod difference).
3. Document immutability (versioning + Object Lock) in `docs/observability.md` or data-model.

## Tests (TDD)
- `AuditWriterIT` — events land as JSONL objects under the right partition; batch flush by count and by time.
- `StatementArchiverIT` — entries older than the window are archived to the monthly object; hot data untouched.

## Verify locally
```bash
aws --endpoint-url=http://localhost:4566 s3 ls s3://pix-audit-log/ --recursive | tail
aws --endpoint-url=http://localhost:4566 s3 cp s3://pix-audit-log/<key> - | jq
```

## Definition of Done
- [ ] All events archived to S3 as partitioned JSONL (batched)
- [ ] Statement cold-archive job copies old entries; hot data preserved locally (prod-delete documented)
- [ ] Immutability posture documented

## CHANGELOG entry
`### Added` → `Immutable audit trail to S3 (partitioned JSONL) + statement cold-archive job (step 43)`
