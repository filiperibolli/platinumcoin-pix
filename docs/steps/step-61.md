# Step 61 — Concept: async settlement + bounded reconciliation  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-61-async-settlement-reconciliation.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **why the user does not wait for the 10s SPI, and how a stuck transaction is always resolved in < 5 minutes** (Questions 4 & 7).

## Prerequisite
Step 35 checked in `PLAN.md` (reconciliation resolution + < 5-min SLO metric/alert).

## Sources to consult (then close them and write from memory)
- `docs/adr/0003-async-settlement-and-reconciliation.md`
- `ARCHITECTURE.md` §6.6 (async external send), §6.7 (resilience), §5 (why `202` not `200`)
- The transaction state machine in `ARCHITECTURE.md` §4
- The code: settlement retries (step 32), finalization/reversal (step 33), stuck-tx scanner (step 34), reconciliation (step 35)

## What your write-up must address
1. Why `202 Accepted` (not `200`) is the **honest** contract for an operation whose money hasn't settled; where the synchronous part ends.
2. The subtle rule: **query-before-retry** — a timeout is not a failure; BACEN may have settled; retrying blind would double-pay.
3. Retries via visibility backoff → DLQ redrive → alert; and the **reconciliation** job (60s scan of GSI2 `status`+age) that finalizes (SETTLED) or **compensates** (`debit clearing / credit payer`, `REVERSED`).
4. How "eventual consistency" is made **bounded** (< 5 min SLO + alert) — and why compensation is a **new posting**, never an update/delete.

## Deliverable — `docs/concepts/concept-61-async-settlement-reconciliation.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception (especially query-before-retry and bounded eventual consistency); close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-61-async-settlement-reconciliation.md` written by hand
- [ ] The `202`-vs-`200` and query-before-retry points are both correct
- [ ] "Eventual made bounded (< 5 min)" is explained with the mechanism
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: async settlement + bounded reconciliation — own-words design defense (step 61)`
