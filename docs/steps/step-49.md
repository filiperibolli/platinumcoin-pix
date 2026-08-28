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

## What the finalize pass actually found (recorded 2026-08-28, on completion)

The spec above predicted the wrong work, in the same way [step 48](step-48.md) did for Postman —
worth keeping, because the gap between the two is the lesson.

- **Task 1 (coverage) was already satisfied and Task 2 (the guided journey) was already delivered.**
  All ten paths of `docs/api/openapi.yaml`, and all 21 controller routes including `/internal/**` and
  mock-bacen, had cards; the `send → status → statement` story had existed since 2026-08-20 as the
  `internal` + `balance` journeys. What was missing was **scenarios**, not endpoints: a fraud `DENY`,
  the `403 INTERNAL_PORT_FORBIDDEN`, a stream with no credential at all, the DICT's `404`.
- **Task 3 (examples) was the only unimplemented task**, and is now 65 captured transcripts.
- **The real work was the test line below, which had never been run.** Driven in a headless Chromium
  from `file://`, clicking every card top to bottom on a freshly reset stack, **12 of 55 cards failed
  and one passed for the wrong reason**. Root causes, none of them about endpoints:
  1. `Login (bob)` captured the session token, so every authenticated card after it silently acted as
     bob — alice's key landed on bob's account and `Send Pix — alice → bob` became a self-transfer.
  2. **Nothing registered `bob@platinum.com`** (the seed creates accounts, not keys), so four cards
     across three services answered `422 KEY_NOT_FOUND`.
  3. `DELETE /v1/pix-keys/alice@platinum.com` removed the key the two Resolve cards below it needed.
  4. The limit card paid an unregistered key: `422`, but `KEY_NOT_FOUND`, never the rule it names.
  5. Both balance cards sat *after* the sends, so they shared one 5-second cache entry and the
     invalidation story they were written to tell could not happen.
  6. All six observability cards failed the browser preflight, because the explorer seeds
     `X-Correlation-Id` on every card and Prometheus — not our service — does not allow that header.
- **Two journey defects** the same run surfaced: the observability step raced Prometheus's 10s scrape
  with a fixed 1.2s sleep, and asserted `SENT_TO_SPI` had not moved — a process-wide counter that any
  in-flight external payment moves, reporting somebody else's payment as this one's defect.

**Noted, not fixed here** (adjacent, outside this step's scope): paying your own Pix key reaches the
ledger's `InvalidPostingException` (`422`, "both legs name the same account") and payment-service maps
it to **`503 LEDGER_UNAVAILABLE` + `Retry-After: 5`** — telling the client to retry something that can
never succeed. Recorded in `PLAN.md`'s backlog.

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
