# Step 57 — Concept: DynamoDB-for-ledger storage trade-off  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-57-ledger-storage-choice.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **why DynamoDB (not PostgreSQL) stores the ledger here** — the NFRs it wins on, what is given up, and how the loss is compensated (Question 3).

## Prerequisite
Step 14 checked in `PLAN.md` (the atomic posting that this storage choice makes possible). If step 51 (`labs/ledger-pg` findings) is later done, revisit this doc to upgrade the rule of thumb to a measured claim.

## Sources to consult (then close them and write from memory)
- `docs/adr/0001-dynamodb-for-the-ledger.md`
- `ARCHITECTURE.md` §8 (ledger storage choice) and the trade-off table in §9
- `docs/adr/0009-relational-ledger-counterpart-lab.md` (the Postgres counterpart)

## What your write-up must address
1. Why Postgres is **the honest default** for a payments ledger — and the specific NFRs that flip the decision here (99.99% availability + zero manual scaling, predictable single-digit-ms latency, elastic Black Friday peak).
2. Why `TransactWriteItems` is **enough** for this access pattern (fixed, small, key-addressable write set — no hot-path joins).
3. What is **given up** (ad-hoc SQL, 100-item transaction cap, constraints living in application condition expressions) and exactly how each is compensated (GSIs designed up front, S3 exports, invariants concentrated in one service + tests).
4. The decision rule: **when to choose which** — and why this project also builds `labs/ledger-pg` to back the rule of thumb with numbers.

## Deliverable — `docs/concepts/concept-57-ledger-storage-choice.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception or hand-wave; close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-57-ledger-storage-choice.md` written by hand
- [ ] Both the win column and the give-up-and-compensate column are explicit
- [ ] The "when to choose which" rule is stated
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: DynamoDB-for-ledger trade-off — own-words design defense (step 57)`
