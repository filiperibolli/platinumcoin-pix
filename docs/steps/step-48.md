# Step 48 — Unified Postman collection

> **Sprint 13 — API tooling & DX** · **Flow:** developer experience · **Infra que sobe:** none

## Objective
**Finalize** `tools/postman/pix-platform.postman_collection.json` + `pix-platform.local.postman_environment.json`. The collection already exists and has grown incrementally since step 04 — **one folder per service**, every endpoint added in its own step (CLAUDE.md convention). This step does not create it from scratch; it enriches it: covers any still-missing public/internal-admin endpoints (mock-bacen chaos config, inbound-pix simulator, internal balance), makes auth and idempotency automatic, and adds happy + error example responses.

## Why / what you'll learn
DX as a first-class deliverable: a collection where **auth is a pre-request script** (login once, token auto-attached) and **idempotency keys auto-generate** removes the friction that makes people mis-test money APIs. The primary structure stays **per service** (how you test a service right after building it); a thin set of **flow-oriented example runs** (Send Pix → settle → receive) is layered on top as living documentation. Example responses for error paths make the RFC 7807 contract visible to anyone poking the API.

## Prerequisites
The public flows exist (Sprints 1–9) and mock-bacen admin (step 30). The per-service collection has been kept up to date step by step.

## Tasks
1. Audit coverage: every public endpoint + useful internal/admin one has a request in its service folder (fill gaps, incl. Admin/Chaos).
2. Promote auth to a collection **pre-request** script (login once → token auto-attached); auto-UUID idempotency key on money-moving POSTs.
3. Happy + error example responses per request.
4. Add a small set of flow-oriented example runs (Send Pix, Receive) on top of the per-service folders.
5. Keep `pix-platform.local.postman_environment.json` base URLs/ports in sync with `docs/local-dev.md`.

## Tests (TDD)
- Manual/Newman smoke: run the collection against a live stack; the send folder produces a 202 and a replayed retry; an error request shows problem+json.

## Verify locally
```bash
# import into Postman, select the local environment, or:
newman run tools/postman/pix-platform.postman_collection.json -e tools/postman/pix-platform.local.postman_environment.json
```

## Definition of Done
- [ ] All public + useful internal/admin APIs covered, organized by flow
- [ ] Auth + idempotency automated; happy/error examples present
- [ ] Runs clean against a live local stack

## CHANGELOG entry
`### Added` → `Unified Postman collection (all APIs by flow) with automated auth/idempotency and happy/error examples (step 48)`
