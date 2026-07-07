# Step 48 — Unified Postman collection

> **Sprint 13 — API tooling & DX** · **Flow:** developer experience · **Infra que sobe:** none

## Objective
`tools/postman/pix-platform.postman_collection.json` + `local.postman_environment.json`: one collection covering **all** public APIs (auth, payments, accounts, pix-keys, notifications) and the useful internal/admin ones (mock-bacen chaos config, inbound-pix simulator, internal balance), organized by flow folders, with auth and idempotency automated and example responses for happy + error paths.

## Why / what you'll learn
DX as a first-class deliverable: a collection where **auth is a pre-request script** (login once, token auto-attached) and **idempotency keys auto-generate** removes the friction that makes people mis-test money APIs. Organizing by *flow folder* (not by service) mirrors the vertical structure and doubles as living documentation. Example responses for error paths make the RFC 7807 contract visible to anyone poking the API.

## Prerequisites
The public flows exist (Sprints 1–9) and mock-bacen admin (step 30).

## Tasks
1. Collection with folders per flow (Auth, Send Pix, Receive, Balance & Statement, Pix Keys, Admin/Chaos).
2. Pre-request auth script (login → env token); auto-UUID idempotency key on money-moving POSTs.
3. Happy + error example responses per request.
4. `local.postman_environment.json` with base URLs/ports from `docs/local-dev.md`.

## Tests (TDD)
- Manual/Newman smoke: run the collection against a live stack; the send folder produces a 202 and a replayed retry; an error request shows problem+json.

## Verify locally
```bash
# import into Postman, select the local environment, or:
newman run tools/postman/pix-platform.postman_collection.json -e tools/postman/local.postman_environment.json
```

## Definition of Done
- [ ] All public + useful internal/admin APIs covered, organized by flow
- [ ] Auth + idempotency automated; happy/error examples present
- [ ] Runs clean against a live local stack

## CHANGELOG entry
`### Added` → `Unified Postman collection (all APIs by flow) with automated auth/idempotency and happy/error examples (step 48)`
