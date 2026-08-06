# Step 55 — Concept: idempotency, the three layers  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-55-idempotency.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how a retried or double-tapped send never moves money twice** — the three defense-in-depth layers and the sharp edges of the API layer.

## Prerequisite
Step 19 checked in `PLAN.md` (the API idempotency layer).

## Sources to consult (then close them and write from memory)
- `docs/adr/0002-idempotency-strategy.md`
- `ARCHITECTURE.md` §5 (idempotency mechanism) and §7.1 (three layers)
- `docs/steps/step-19.md` (claim → replay → 409, IN_PROGRESS, stale-claim, lazy TTL)
- The code: the payment-service idempotency repository + controller flow (step 19)

## What your write-up must address
1. The **three layers** and why each exists: API (`Idempotency-Key` claim + stored-response replay), ledger (`txId` conditional guard), SPI (`endToEndId`). Which layer catches which failure.
2. The API lifecycle: conditional claim (`IN_PROGRESS`) → execute → memoize → replay; why the request body is **canonicalized** before hashing.
3. The crash-between-claim-and-response window: `IN_PROGRESS` → `409` + `Retry-After`, **unless `claimedAt` is older than 60s** (stale orphan → re-claim). Why no client is blocked until the 24h TTL.
4. Why DynamoDB's **lazy TTL** means `expiresAt` must be checked on read, and why the record TTL (24h) must exceed any client retry horizon.

## Deliverable — `docs/concepts/concept-55-idempotency.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception (especially the stale-claim and lazy-TTL edges); close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-55-idempotency.md` written by hand
- [ ] All three layers named with the failure each one catches
- [ ] Stale-claim (60s) and lazy-TTL edges stated correctly
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: idempotency three layers — own-words design defense (step 55)`
