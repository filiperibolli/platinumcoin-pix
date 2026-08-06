# docs/concepts — own-words design defense (Sprint 15)

This folder is the deliverable of **Sprint 15 — Concept mastery & design defense** (steps 54–63 in
[`PLAN.md`](../../PLAN.md)). One file per core concept of the platform. Each file is written **by hand,
in the human's own words** (a marked hand-written zone — see [CLAUDE.md](../../CLAUDE.md) → "Hand-written
zones"): no AI drafting, no autocomplete on the first pass.

The point is not to re-document the system — `ARCHITECTURE.md` and the ADRs already do that. The point is
to **prove the design survives being explained from memory**: for each concept you locate it in the
code/docs, restate the mechanism in your own words, name the **trade-off it accepts** and the **failure
mode it prevents**. This is the interview answer, rehearsed against the real artifact.

**Claude's role here is review-only:** after a concept doc is finished, Claude reviews it, grades it
against the ADRs / ARCHITECTURE / code, flags misconceptions or drift, and closes with **one Socratic
question**. Claude never drafts or autocompletes the explanation.

Each step is **gated on the implementation step that builds its concept** — you validate a decision only
after living the code that proves it. Take them out of order as prerequisites are checked; the sprint is
not sequential.

| Concept | File | Prereq step | Primary sources |
|---|---|---|---|
| Atomic double-entry ledger | `concept-54-atomic-double-entry.md` | 15 | ARCHITECTURE §6.3, data-model §3, ADR-0001 |
| Idempotency — three layers | `concept-55-idempotency.md` | 19 | ADR-0002, ARCHITECTURE §5/§7.1 |
| Debited account from the JWT | `concept-56-source-account-from-token.md` | 18 | ADR-0007, ARCHITECTURE §6.4/§7.6 |
| DynamoDB-for-ledger trade-off | `concept-57-ledger-storage-choice.md` | 14 | ADR-0001, ARCHITECTURE §8 |
| Clean/hex-lite + use cases | `concept-58-clean-architecture-lite.md` | 09 | ADR-0010, ADR-0011, ARCHITECTURE §3 |
| Fraud fail-open (200ms) | `concept-59-fraud-fail-open.md` | 25 | ADR-0005, ARCHITECTURE §6.5/§7.5 |
| Transactional outbox | `concept-60-transactional-outbox.md` | 29 | ADR-0004, ARCHITECTURE §6.6/§7.2 |
| Async settlement + reconciliation | `concept-61-async-settlement-reconciliation.md` | 35 | ADR-0003, ARCHITECTURE §6.6/§6.7 |
| Correlation-id observability | `concept-62-observability-correlation-id.md` | 44 | ADR-0012, ARCHITECTURE §7.7 |
| Availability + ledger-down-30s | `concept-63-availability-ledger-outage.md` | 45 | ARCHITECTURE §7.4 |

## The shape of one concept file

```markdown
# Concept NN — <title>

**In one sentence:** …
**Where it lives:** <service:path>, ADR-XXXX, ARCHITECTURE §…
**In my own words:** <the mechanism and *why* it is built this way>
**The trade-off:** <what it costs / what was rejected and why>
**The failure mode it prevents:** <the concrete bug/incident it rules out>
**Open question I still have:** <optional — seeds Claude's review>
```

Aim for ~300–600 words of prose per file — enough to defend the decision, not a re-run of the ADR.
