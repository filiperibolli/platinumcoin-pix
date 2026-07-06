# Step 36 — Statement API with opaque cursor

## Objective
`GET /v1/accounts/me/statement?cursor&limit` on payment-service, proxying the ledger statement (step 16), decimal-string amounts, masked counterparts, contract-exact pagination.

## Why / what you'll learn
The BFF-ish edge pattern: the public endpoint owns *presentation* (masking, formatting, external field names) while the ledger owns *truth* — so internal refactors never leak. Also cursor hygiene at the public boundary: the cursor is passed through opaque, never parsed by the client, and invalid cursors 400 without stack traces. Small step by design — a breather that also closes feature F3.

## Prerequisites
Steps 16, 35 (masking helper from 34).

## Tasks
1. Endpoint per OpenAPI: JWT account → ledger internal entries call → map to `StatementEntry` (signed decimal string, masked counterpart, ISO timestamps).
2. Limit clamp mirrors ledger's (1..100/20); pass-through cursor.
3. Cache headers: `Cache-Control: private, max-age=5` (align with balance freshness).

## Tests (TDD)
- Contract IT: shape matches OpenAPI; page walk (seeded entries) with cursors terminates; foreign account impossible (token-derived).
- Mapping: DEBIT ⇒ "-125.50"; masking applied.

## Verify locally
```bash
curl -s "localhost:8084/v1/accounts/me/statement?limit=5" -H "Authorization: Bearer $TOKEN" | jq
```

## Definition of Done
- [ ] Public statement contract-exact with opaque pagination
- [ ] Presentation concerns (mask/format) at the edge only
- [ ] Entries reflect the full journey incl. reversals

## CHANGELOG entry
`### Added` → `Public paginated statement endpoint with masking and opaque cursors (step 36)`
