# Step 41 — Statement API through payment-service (cursor pagination)

> **Sprint 9 — Balance & statement (cache)** · **Flow:** fast reads · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.9

## Objective
`GET /v1/accounts/me/statement?cursor&limit` on payment-service, proxying the ledger statement (step 16), decimal-string amounts, masked counterparts, contract-exact pagination.

## Why / what you'll learn
The public read model over the internal ledger query: payment-service is the public edge for **money views**, so the statement (like balance) is exposed here rather than directly from ledger-service — auth, accounts/keys and notifications keep their own public edges; there is no gateway in this build (the OpenAPI per-path `servers` document which port serves what). You'll pass the **opaque cursor** straight through (base64 `LastEvaluatedKey`), format money at the edge (cents → decimal string), and mask counterpart display (don't leak internal account ids). This keeps the OpenAPI contract stable while the storage detail (single-table ledger, cold archive later) stays hidden.

## Prerequisites
Step 16 (ledger entries), Step 18 (payment-service edge).

## Tasks
1. `GET /v1/accounts/me/statement` (from JWT): call `GET /internal/ledger/accounts/{id}/entries`; map to `StatementEntry` (decimal amounts, masked counterpart, direction, timestamp).
2. Pass `cursor`/`limit` through; return `{entries, nextCursor|null}` per OpenAPI.
3. Clamp `limit` (default 20, max 100); reject tampered cursors (400) — the decoded cursor's account must match the JWT (step 16 enforces this at the ledger; re-assert at the edge).

## Tests (TDD)
- `StatementApiIT` — page through a seeded history; newest-first, stable cursor, `nextCursor` null on the last page; amounts are decimal strings; counterpart masked.
- Ownership — only the caller's entries (derived from JWT).

## Verify locally
```bash
curl -s "localhost:8084/v1/accounts/me/statement?limit=5" -H "Authorization: Bearer $TOKEN" | jq
```

## Definition of Done
- [ ] Statement served through payment-service with opaque cursor pagination
- [ ] Amounts decimal strings, counterpart masked, ownership from JWT
- [ ] Matches OpenAPI `/accounts/me/statement`

## CHANGELOG entry
`### Added` → `Public statement API through payment-service with opaque cursor pagination and edge money formatting (step 41)`
