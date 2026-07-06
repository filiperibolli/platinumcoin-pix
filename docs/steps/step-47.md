# Step 47 — Cold statement retrieval: async export with polling status URL

## Objective
Historical statement (beyond the hot window) as an asynchronous export: `POST` an export request → `202 Accepted` with a status URL → a queue-driven worker assembles the artifact from the cold archive (step 37) into `pix-statement-exports` → the status endpoint flips to `READY` with a download URL. The standard fintech pattern for slow reads ("building your statement…").

## Why / what you'll learn
The async-request/polling contract done properly: request resources with ids and lifecycle (`PENDING → READY | FAILED`), idempotent request creation, a worker that is safe to retry, and the UX contract that lets a front-end poll politely. Also the S3 assembly pattern: many monthly JSONL objects → one export artifact (CSV) → time-limited presigned download URL.

## Prerequisites
Steps 36, 37 (hot statement API; cold archive populated).

## Tasks
1. **Contract first** — extend `docs/api/openapi.yaml`:
   - `POST /v1/accounts/me/statement/exports` body `{ "fromMonth": "2025-01", "toMonth": "2025-06" }` → `202` `{ "exportId", "status": "PENDING", "statusUrl" }`. Requires `Idempotency-Key`; same key+range replays the same `exportId`. Range validation: `fromMonth <= toMonth`, max 24 months, only months at/after account creation; ranges inside the hot window are redirected to the hot API via `422 USE_HOT_STATEMENT`.
   - `GET /v1/statement-exports/{exportId}` → `{ "exportId", "status", "requestedRange", "downloadUrl"?, "expiresAt"?, "failureReason"? }`. Ownership enforced from the JWT.
2. Export request item in DynamoDB (`EXPORT#<exportId>`, GSI by account) + message to new `statement-export-queue` (with DLQ) via the outbox.
3. Worker (payment-service or a small dedicated consumer in settlement-service — decide and note in the step commit): read archive objects `account=<id>/yyyy-MM.jsonl` for the range, merge → CSV artifact `exports/<accountId>/<exportId>.csv` in `pix-statement-exports`, presign (LocalStack) with 1h expiry, mark READY. Missing months (no activity) are skipped, not failed. Failures → bounded retries → FAILED with reason; DLQ alarm covers the rest.
4. Idempotent worker: re-delivery of the same exportId must not duplicate artifacts (conditional status transition PENDING→READY).
5. Front-of-house polish: `Retry-After: 5` on `PENDING` status responses.

## Tests (TDD)
- Happy path IT: seed archive for 3 months → request export → poll to READY → download CSV → row count and totals match seeded entries.
- Idempotency: same key+range twice ⇒ same `exportId`, one artifact.
- Redelivery of the queue message ⇒ single artifact, single READY transition.
- Ownership: token for another account polling the exportId ⇒ 404.
- Validation matrix: inverted range, >24 months, hot-window range (`422 USE_HOT_STATEMENT`).

## Verify locally
```bash
IDEM=$(uuidgen)
EXP=$(curl -s -X POST localhost:8084/v1/accounts/me/statement/exports \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $IDEM" -H 'Content-Type: application/json' \
  -d '{"fromMonth":"2025-01","toMonth":"2025-03"}' | jq -r .exportId)
watch -n 2 "curl -s localhost:8084/v1/statement-exports/$EXP -H 'Authorization: Bearer $TOKEN' | jq .status"
curl -s "localhost:8084/v1/statement-exports/$EXP" -H "Authorization: Bearer $TOKEN" | jq -r .downloadUrl | xargs curl -s | head
```

## Definition of Done
- [ ] OpenAPI updated first; implementation conforms to it
- [ ] `202` + polling lifecycle works end to end with a downloadable, correct CSV
- [ ] Worker idempotent under redelivery; DLQ wired and alarmed
- [ ] Hot-window requests are steered to the hot API, not silently duplicated

## CHANGELOG entry
`### Added` → `Async cold statement export: 202 + polling status URL + presigned CSV artifact from the S3 archive (step 47)`
