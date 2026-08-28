# Step 49 — Finalize the single-file HTML API explorer

> **Sprint 13 — API tooling & DX** · **Flow:** developer experience · **Infra que sobe:** none

## Objective
**Finalize** `tools/api-explorer/index.html` — a **single self-contained HTML file** (vanilla JS +
embedded CSS, no build step, no server, no CDN — open from disk). The explorer already exists and has
grown incrementally since auth-service — **one card per endpoint, added in its own step** (CLAUDE.md
convention), grouped by service, each pre-filled with valid seed data and a **Send** button that runs
the call against localhost and pretty-prints the live response. This step does not create it from
scratch; it enriches it: audits coverage (every public endpoint has a working card), polishes the
guided **full journey** (send → poll status → statement) now that the money flows exist, and adds
richer happy/error examples.

## Why this step exists
A zero-dependency, open-from-disk explorer is the friendliest possible front door to the platform — no
Postman install, no npm. The discipline is keeping it truly self-contained and every pre-filled
request **valid** (a reviewer clicks and it *works* against the seed data). The guided journey is the
payoff: it chains the real flows into one click and tells the product story end to end. It's the
portfolio face of the project — someone understands the whole system by clicking through it.

## Prerequisites
The public flows exist (Sprints 1–9) and the explorer has been kept up to date step by step (login
panel + in-memory token + auto-`Authorization`, auto-UUID idempotency helper, per-service cards).

## Tasks
1. Audit coverage: every public endpoint has a card in its service section, pre-filled with valid seed
   data (fill any gaps left by earlier steps).
2. Polish the guided **full journey** — send Pix → poll status → statement — driven by the live token.
3. Add richer happy + error examples (e.g. a 202 replay, an RFC 7807 problem body) so the contract is
   visible without a live call.
4. Confirm it stays a single self-contained file (embedded CSS/JS, no external CDN, fully offline).

## Tests (TDD)
- Manual: open from disk, log in as alice, run the guided journey against a live stack; every
  pre-filled request succeeds. Confirm no network request is made to any CDN/external host.

## Verify locally
```bash
open tools/api-explorer/index.html   # log in as alice, click through the guided journey
```

## Definition of Done
- [ ] Single self-contained file; opens from disk, no server/build/CDN
- [ ] Every public endpoint has a card pre-filled with valid sample data and a working Send
- [ ] Login + idempotency automated; guided journey (send → status → statement) works end to end

## CHANGELOG entry
`### Added` → `Finalized single-file HTML API explorer: full guided journey (send → status → statement) and richer happy/error examples (step 49)`
