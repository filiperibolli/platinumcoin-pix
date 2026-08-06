# Step 59 — Concept: fraud fail-open under a 200ms budget  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-59-fraud-fail-open.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how real-time fraud scoring is added without breaking the payment flow** — the 200ms hard timeout, and the fail-open trade-off (Question 5).

## Prerequisite
Step 25 checked in `PLAN.md` (payment-service ↔ fraud integration with the timeout + `FRAUD_SKIPPED`).

## Sources to consult (then close them and write from memory)
- `docs/adr/0005-fraud-latency-budget-fail-open.md`
- `ARCHITECTURE.md` §6.5 (fraud in the path) and §7.5 (fraud under 200ms)
- The code: the fraud client + timeout/fail-open handling in payment-service (step 25); fraud-service `/score` (step 24)

## What your write-up must address
1. Where fraud sits in the flow (between limit-check and ledger debit) and the **hard 200ms client timeout** vs the service's own p99 < 150ms target — why the margin.
2. **Fail-open vs fail-closed:** state the trade-off precisely. Why availability wins *at this layer* for a money-movement product; why fail-closed would let a fraud blip reject 100% of legitimate payments.
3. What happens on timeout/error: proceed, flag `FRAUD_SKIPPED`, emit `FraudCheckSkipped` → async re-scoring; why the risk is **bounded** (daily limits still apply).
4. The documented production evolution (hybrid: fail-closed above a value threshold).

## Deliverable — `docs/concepts/concept-59-fraud-fail-open.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception; close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-59-fraud-fail-open.md` written by hand
- [ ] The fail-open trade-off is stated as a trade-off (both directions), not a slogan
- [ ] The bounded-risk argument and the production hybrid are named
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: fraud fail-open under 200ms — own-words design defense (step 59)`
