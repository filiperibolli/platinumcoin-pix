# Step 42 — Unified Postman collection

## Objective
`tools/postman/pix-platform.postman_collection.json` + `local.postman_environment.json`: one collection covering **all** public APIs (auth, payments, accounts, pix-keys, notifications) and the useful internal/admin ones (mock-bacen chaos config, inbound-pix simulator, internal balance), organized by flow folders, with auth and idempotency automated and example responses for happy + error paths.

## Why / what you'll learn
Postman-as-documentation done properly: **pre-request scripts** (folder-level: auto-login when the token env var is empty/expired, fresh UUID `Idempotency-Key` per send), **test scripts** capturing chained state (`transactionId` from the 202 into the environment so "Get status" just works), environment variables for every port, and saved **examples** for each documented error (409 idempotency reuse, 422 LIMIT_EXCEEDED/INSUFFICIENT_FUNDS/FRAUD_DENIED, 503 ledger-down) so the collection teaches the API's failure modes, not just its happy path. Folders tell the story in runnable order: "1. Setup", "2. Send Pix journey", "3. Receive & notify", "4. Failure drills (chaos)".

## Prerequisites
Steps 23, 32 (endpoints exist); OpenAPI (docs/api/openapi.yaml) is the contract to mirror.

## Tasks
1. Environment: `baseAuth/baseAccount/basePayment/baseNotify/baseBacen`, `tokenAlice/tokenBob`, `lastTransactionId`, seeded creds.
2. Collection folders per flow; every request named imperatively ("Send Pix — external key"); descriptions link the relevant docs/steps.
3. Scripts: folder pre-request auto-auth (login if `tokenAlice` empty), per-send UUID idempotency header, tests asserting status code + capturing ids; a ready-made "replay same key" request pair demonstrating idempotency.
4. Saved example responses for happy + each error path (copy real responses from the running stack).
5. Chaos folder: BACEN latency/failure config, inbound-pix generator, DLQ drill sequence with comments.
6. Export both files; README-in-folder (`tools/postman/README.md`) with import instructions; note the collection is also runnable headless: `npx newman run ...` (smoke wiring optional).

## Tests (TDD)
`newman` smoke run of the "Send Pix journey" folder against the running stack in `scripts/postman-smoke.sh` — keeps the collection honest as the API evolves.

## Verify locally
```bash
# Import both JSONs in Postman → run folder "2. Send Pix journey" → all green, no manual token copying
npx newman run tools/postman/pix-platform.postman_collection.json \
  -e tools/postman/local.postman_environment.json --folder "2. Send Pix journey"
```

## Definition of Done
- [ ] Every public endpoint + chaos/admin utilities present, organized by runnable flows
- [ ] Zero manual steps: auth, idempotency keys and id chaining fully scripted
- [ ] Error examples saved for all documented failure codes; newman smoke green

## CHANGELOG entry
`### Added` → `Unified Postman collection + environment with auto-auth, idempotency scripting, chained flows and error examples (step 42)`
