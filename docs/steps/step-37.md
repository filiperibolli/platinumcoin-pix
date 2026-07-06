# Step 37 — Immutable audit trail + cold archive

## Objective
An `AuditWriter` in settlement-service consumes `audit-queue` (subscribed to *all* events) and appends JSON lines to S3 `pix-audit-log` partitioned `yyyy/MM/dd/HH/<service>-<uuid>.jsonl` (batched, e.g. 100 events or 30s). A scheduled `StatementArchiver` copies ledger entries older than a configurable window to `pix-statement-archive` (parquet-ish JSON lines; deletion from hot storage deliberately NOT done locally — documented). Immutability posture documented: versioning + Object Lock compliance mode + retention 5y (flags in init script, real enforcement is AWS-side).

## Why / what you'll learn
Audit as an **event-sourced projection**: because every transition already emits an event through the outbox, the audit trail is just one more subscriber — zero coupling to business code, completeness by construction. S3 write patterns (batching small events into objects; time-partitioned keys enabling Athena later), and the honest line between what LocalStack demonstrates vs what AWS enforces (Object Lock) — knowing that difference *is* the learning.

## Prerequisites
Steps 05, 22.

## Tasks
1. Audit consumer: dedup, buffer, flush on size/time, S3 `PutObject`; flush on shutdown hook; metric `audit.buffer.size`, `audit.flush.count`.
2. JSONL record: envelope + full payload + ingest timestamp (never mutate — append-only files).
3. `StatementArchiver` (daily schedule, window `ARCHIVE_AFTER_DAYS=90` local default): query old entries per account page-wise → JSONL objects `account=<id>/yyyy-MM.jsonl` in archive bucket; idempotent (manifest object per completed month).
4. docs: retention/immutability section in local-dev.md (verification commands) + ARCHITECTURE cross-ref check.

## Tests (TDD)
- Event ⇒ appears in a flushed S3 object (poll ListObjects); dedup under duplicate delivery; buffer flush on both triggers.
- Archiver: seeded old entries land in archive bucket exactly once across two runs (manifest idempotency).

## Verify locally
```bash
awsl s3 ls s3://pix-audit-log/ --recursive | tail -3
awsl s3 cp "s3://pix-audit-log/$(awsl s3 ls s3://pix-audit-log/ --recursive | tail -1 | awk '{print $4}')" - | head -2 | jq
```

## Definition of Done
- [ ] Every domain event lands in time-partitioned JSONL audit objects
- [ ] Archiver idempotent; hot/cold boundary configurable
- [ ] Immutability posture (lock/versioning/5y) documented with LocalStack-vs-AWS honesty

## CHANGELOG entry
`### Added` → `Immutable S3 audit trail (event-sourced JSONL) and idempotent statement cold-archiver (step 37)`
