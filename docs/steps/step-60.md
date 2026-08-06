# Step 60 — Concept: transactional outbox + polling publisher  ✍️ hand-written zone

> **Sprint 15 — Concept mastery & design defense** · **Deliverable:** `docs/concepts/concept-60-transactional-outbox.md` · **Infra que sobe:** none (docs only — no build, no `mvn`)

> **Hand-written zone:** you write the explanation yourself, in your own words, without AI drafting or autocomplete. Claude's role is limited to reviewing the finished write-up, grading it, and posing one Socratic question. See CLAUDE.md → "Hand-written zones".

## Objective
Explain, in your own words, **how a state change and its event are published without the dual-write problem** — the outbox in the same partition, the sparse-GSI polling publisher, and consumer dedup.

## Prerequisite
Step 29 checked in `PLAN.md` (the outbox polling publisher + `ProcessedEventStore`).

## Sources to consult (then close them and write from memory)
- `docs/adr/0004-transactional-outbox-with-polling-publisher.md`
- `ARCHITECTURE.md` §6.6 (external send) and §7.2 (consistency across services)
- `docs/data-model.md` (transactions table: `OUTBOX#<eventId>`, GSI3 sparse, `processed_events`)
- The code: outbox write (step 28), polling publisher (step 29)

## What your write-up must address
1. The **dual-write problem**: why "write DB, then publish to SNS" loses events or invents states.
2. Why the outbox item lives in the **same table/partition** as the transaction — so one `TransactWriteItems` commits both.
3. The publisher: **publish-then-mark** on a **sparse GSI3** (~1s tick) → **at-least-once** delivery; why the mark is a `REMOVE` of the GSI key.
4. Why **consumers dedupe by `eventId`** (`processed_events`, TTL 7d) — how at-least-once becomes effectively-once. Why DynamoDB Streams was rejected here (complexity vs a 10s SPI SLA).

## Deliverable — `docs/concepts/concept-60-transactional-outbox.md`
Follow the shape in `docs/concepts/README.md`. ~300–600 words.

## Claude's role (after you finish)
Review + grade (1–5) against the sources; flag any misconception (especially at-least-once → effectively-once); close with **one Socratic question** requiring synthesis.

## Definition of Done
- [ ] `concept-60-transactional-outbox.md` written by hand
- [ ] The dual-write problem and the same-partition atomic write are both explained
- [ ] The at-least-once + dedupe = effectively-once chain is stated
- [ ] Claude review done; graded; Socratic question answered (or noted)

## CHANGELOG entry
`### Added` → `Concept doc: transactional outbox + polling publisher — own-words design defense (step 60)`
