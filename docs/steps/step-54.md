# Step 54 — Concept: atomic double-entry ledger  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-54-atomic-double-entry.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how the ledger guarantees money is never debited without being credited** — the single-`TransactWriteItems` posting, the no-negative-balance condition, and the append-only rule.

## Prerequisite
Step 15 checked in `PLAN.md` (the invariant suite that proves this concept under a concurrency storm).

## Sources to consult (then close them and write from memory)
- `ARCHITECTURE.md` §6.3 (ledger posting) and §8 (storage choice)
- `docs/data-model.md` §3 (the five writes, the `txId` guard)
- `docs/adr/0001-dynamodb-for-the-ledger.md`
- The code: `services/ledger-service` posting use case (step 14) + the step-15 invariant tests
- CLAUDE.md → "Domain safety rules" 3, 4, 5

## What your write-up must address
1. Why the posting is **one** `TransactWriteItems` and what "all-or-nothing" buys you (rule 4).
2. Why `balanceCents >= :amount` lives **inside** the transaction and never as a read-then-check (rule 3).
3. Why there are **five** writes — specifically why the `TX#<txId>/POSTING` guard is needed even though the entry puts are already `attribute_not_exists` (the timestamp-in-the-key double-debit trap).
4. Why corrections are **compensating postings**, never updates/deletes (rule 5) — and what "conservation of money" (`Σ balances` invariant) means here.

## Deliverable — `docs/concepts/concept-54-atomic-double-entry.md`
Follow the shape in `docs/concepts/README.md`: one-sentence summary, where it lives, in-my-own-words mechanism + why, the trade-off, the failure mode it prevents, optional open question. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception, missing edge case, or drift from the ADRs/data-model; close with **one Socratic question** that requires synthesis, not recall.

## Definition of Done
- [ ] `concept-54-atomic-double-entry.md` written by hand
- [ ] The five-writes / `txId`-guard reasoning is stated correctly (the timestamp trap)
- [ ] Trade-off and prevented-failure-mode both explicit
- [ ] Claude review done; graded; Socratic question answered (or noted for later)

## CHANGELOG entry
`### Added` → `Concept doc: atomic double-entry ledger — own-words design defense (step 54)`
