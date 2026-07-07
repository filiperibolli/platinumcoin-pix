# Step 49 — Single-file HTML API explorer

> **Sprint 13 — API tooling & DX** · **Flow:** developer experience · **Infra que sobe:** none

## Objective
`tools/api-explorer/index.html`: a **single self-contained HTML file** (vanilla JS + embedded CSS, no build step, no server — open from disk) presenting every API in one swagger-style page grouped by service, each endpoint pre-filled with **valid sample data** (seed users, real keys, correct headers) and a **"Send"** button that executes the call against localhost and pretty-prints the live response. Includes a login panel (token in memory, auto-attached), an auto-UUID idempotency-key button, and a guided "full journey" section chaining send → status → statement.

## Why / what you'll learn
A zero-dependency, open-from-disk explorer is the friendliest possible front door to the platform — no Postman install, no npm. You'll practice keeping it truly self-contained (embedded CSS/JS), pre-filling **valid** requests (so a reviewer clicks and it *works* against the seed data), and chaining a guided journey that tells the product story. It's the portfolio face of the project: someone can understand the whole system by clicking through it.

## Prerequisites
The public flows and the Postman work (step 48) as a content reference.

## Tasks
1. `index.html` — grouped endpoint cards; each with method, path, editable headers/body pre-filled with valid seed data, and a Send button (fetch → pretty-print).
2. Login panel storing the token in memory and auto-attaching `Authorization`; auto-UUID idempotency-key button.
3. Guided "full journey" section: send → poll status → statement, using the live token.
4. No build step, no external CDN (fully offline-capable).

## Tests (TDD)
- Manual: open from disk, log in, run the guided journey against a live stack; every pre-filled request succeeds.

## Verify locally
```bash
open tools/api-explorer/index.html   # log in as alice, click through the guided journey
```

## Definition of Done
- [ ] Single self-contained file; opens from disk, no server/build
- [ ] Every endpoint pre-filled with valid sample data and a working Send
- [ ] Login + idempotency automated; guided journey works end to end

## CHANGELOG entry
`### Added` → `Single-file HTML API explorer with valid pre-filled requests, login/idempotency helpers and a guided journey (step 49)`
