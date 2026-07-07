# Step 16 — statement query (paginated, newest first)

> **Sprint 3 — Ledger** · **Flow:** atomic double-entry posting · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.3

## Objective
`GET /internal/ledger/accounts/{id}/entries?cursor=&limit=` returns ledger entries newest-first with DynamoDB-native pagination (`LastEvaluatedKey` as an opaque base64 cursor).

## Why / what you'll learn
The statement is *free* from the ledger's key design: `Query pk=ACCOUNT#id AND begins_with(sk,"ENTRY#")`, `ScanIndexForward=false` gives reverse-chronological order because the sort key is timestamp-prefixed. You'll learn **cursor pagination** the DynamoDB way — the cursor is the base64 of `LastEvaluatedKey`, opaque to the client (never offset/limit, which doesn't exist in DynamoDB). This internal endpoint is what the public statement API (step 41) proxies.

## Prerequisites
Step 14.

## Tasks
1. `getEntries(accountId, cursor, limit)` on `LedgerRepository`: query with `begins_with(sk,"ENTRY#")`, `ScanIndexForward=false`, `Limit`.
2. Encode/decode the cursor: base64 of `LastEvaluatedKey`; validate on decode — the decoded key's `pk` **must equal the requested account** (the cursor embeds the partition key, so a forged cursor must never page another account's entries) and malformed cursors ⇒ 400.
3. `GET /internal/ledger/accounts/{id}/entries` → `{entries:[...], nextCursor|null}`.
4. Clamp `limit` (default 20, max 100).

## Tests (TDD)
- `StatementQueryIT` — post N entries; page through with `limit=5`; assert newest-first order, stable pagination, `nextCursor` null on last page, no overlap/gap.
- Tampered or foreign-account cursor ⇒ 400 (never another account's page).

## Verify locally
```bash
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=5" | jq
```

## Definition of Done
- [ ] Entries returned newest-first with opaque cursor pagination
- [ ] Cursor is base64 `LastEvaluatedKey`; tampered or cross-account cursor rejected
- [ ] `limit` clamped

## CHANGELOG entry
`### Added` → `Ledger statement query: newest-first entries with opaque DynamoDB cursor pagination (step 16)`
