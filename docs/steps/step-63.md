# Step 63 — Concept: 99.99% availability & the ledger-down-30s behavior  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-63-availability-ledger-outage.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how the platform targets 99.99% availability, and exactly what happens if the ledger is down for 30 seconds** (Question 7).

## Prerequisite
Step 45 checked in `PLAN.md` (hardening: guarded status transitions, error-contract audit — the pass that makes the fail-fast behavior real and tested).

## Sources to consult (then close them and write from memory)
- `ARCHITECTURE.md` §7.4 (availability + the 30s ledger outage) and §7.3 (performance/scale)
- The threat model / security checklist referenced by step 45
- CLAUDE.md → "Domain safety rules" (why rejecting is safer than guessing)

## What your write-up must address
1. The four-nines posture: **stateless services** (≥3 replicas / 3 AZs), managed multi-AZ AWS on the critical path, **no self-managed stateful component** there (Redis is a cache — losing it degrades latency, not correctness).
2. The 30s ledger outage on `POST /payments/pix`: fail-fast (timeout ~1s, circuit breaker) → **`503` + `Retry-After`**; **nothing was debited** (the ledger write is the *first* money mutation); the idempotency record stored no success, so the client's retry with the same key processes cleanly.
3. Why in-flight settlements/receives survive (messages stay on SQS) and balance reads survive (Redis, ≤5s stale).
4. The **error-budget math**: 30s of send unavailability ≈ 12% of a monthly four-nines budget — acceptable if rare; why "rejecting a payment is always safer than guessing about money."

## Deliverable — `docs/concepts/concept-63-availability-ledger-outage.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception (especially "nothing debited → retry is safe"); close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-63-availability-ledger-outage.md` written by hand
- [ ] The "nothing debited, retry-safe" chain is explained end to end
- [ ] The error-budget number and the "reject > guess" principle are stated
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: 99.99% availability & ledger-down-30s — own-words design defense (step 63)`
