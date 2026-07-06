# Step 16 — Statement query (paginated) on the ledger

## Objective
`GET /internal/ledger/accounts/{id}/entries?cursor=&limit=` returns ledger entries newest-first with DynamoDB-native pagination (`LastEvaluatedKey` as an opaque base64 cursor).

## Why / what you'll learn
Cursor pagination the DynamoDB way: `Query pk=ACCOUNT#id AND begins_with(sk,"ENTRY#")`, `ScanIndexForward=false`, `Limit=n`; the engine hands back `LastEvaluatedKey`, which you serialize (base64 JSON) into an **opaque cursor** — clients can't fabricate offsets, and the query cost is O(page), not O(offset) like SQL `OFFSET`. Timestamped sort keys give reverse-chronological order for free — the payoff of the key design from step 13.

## Prerequisites
Steps 13–15.

## Tasks
1. `LedgerRepository.listEntries(accountId, limit, exclusiveStartKey)` → page + nextKey.
2. Cursor codec in common-lib (`base64(json(LastEvaluatedKey))` + tamper-safe decode: invalid cursor ⇒ 400 INVALID_CURSOR).
3. Internal endpoint per contract; `limit` clamp 1..100 default 20.

## Tests (TDD)
- `LedgerStatementIT` — seed 25 entries; page1 (20, newest first, nextCursor present) → page2 (5, nextCursor null); ordering strictly descending by timestamp; garbage cursor ⇒ 400.
- Boundary: empty account ⇒ empty page, null cursor.

## Verify locally
```bash
curl -s 'localhost:8085/internal/ledger/accounts/acc-001/entries?limit=3' | jq
CUR=$(curl -s 'localhost:8085/internal/ledger/accounts/acc-001/entries?limit=3' | jq -r .nextCursor)
curl -s "localhost:8085/internal/ledger/accounts/acc-001/entries?limit=3&cursor=$CUR" | jq
```

## Definition of Done
- [ ] Newest-first pagination with opaque cursor; no offset scans
- [ ] Cursor tamper-safe (400 on invalid)
- [ ] Entries expose txId, direction, signed amount, counterpart, timestamp

## CHANGELOG entry
`### Added` → `Paginated newest-first ledger statement query with opaque cursor (step 16)`
