---
name: run-step
description: Execute exactly one PLAN.md implementation step end-to-end under the project's mandatory spec-driven, TDD, one-step-per-session workflow. Use when the human says "run the next step", "start step NN", "let's do the next step", "/run-step", or otherwise wants to build the next increment of the PlatinumCoin Pix platform. Enforces plan-before-code, the AI-metrics line, hand-written zones, Definition of Done, and STOP-after-one-step.
---

# run-step — build one PLAN.md step correctly

You are implementing the PlatinumCoin Pix platform. **`CLAUDE.md` overrides any default behavior** — if anything here conflicts with it, follow `CLAUDE.md` and say so.

This skill runs **one** step and then stops. Never chain into the next step.

## Phase 0 — Load context (read before touching anything)

1. Read `CLAUDE.md` fully: conventions, the six **Domain safety rules — NEVER violate**, the mandatory per-step workflow, hand-written zones (✍️), and the per-step AI-metrics rule.
2. Open `PLAN.md`. Take the step the human named; if none, take the **first unchecked step only**.
3. Read that step's `docs/steps/step-XX.md` **completely — it is the spec** (spec-driven). Then read what it references: the relevant `ARCHITECTURE.md` §, the cited ADR(s) in `docs/adr/`, `docs/data-model.md` / `docs/api/openapi.yaml` when the step touches schema or API, and the step's **ADR learning companion** if one exists (`docs/steps/step-XX-adrNNNN.md`).
4. Confirm the step's **prerequisites are checked** in `PLAN.md`. If a prerequisite is unchecked, **STOP** and tell the human — do not proceed.

## Phase 1 — Plan, then wait

Before writing any code, reply with a short plan and **wait for explicit "go"**:

- Restate the step's **objective** in one or two sentences.
- List the **exact files** you intend to add or change.
- Flag whether any part is a **✍️ hand-written zone**. If so, you **review only** — you do **not** generate that code, even if asked casually; remind the human it is a marked zone.
- Record your honest time **estimate now** (the `est` metric — cheap, ~2 minutes).

Do not start coding until the human says go.

## Phase 2 — Build (after "go")

5. **TDD**: write the step's listed tests first (red) → the minimum code to pass (green) → refactor. Colocate unit tests (`*Test`); integration tests (`*IT`) use Testcontainers, never the compose stack. **Every money invariant gets an explicit test.**
6. Implement **only** what the step's tasks describe. Resist scope creep. If something adjacent is broken, **note it — do not fix it silently**. If reality diverges from the docs (API/schema/ADR), **STOP and update the doc in the same change** — docs and code must not drift.
7. Honor the conventions: Java 21, records for DTOs/value objects; **money is integer cents (`long`)**, never float/double, decimal only at the API edge; hexagonal-lite (domain never imports AWS SDK types); RFC 7807 `problem+json` with `code` + `correlationId`; **SLF4J only**, structured JSON, `correlationId` (+ `txId`) in MDC on every meaningful stage.

## Phase 3 — Verify & close out

8. Run the step's **"How to verify locally"** commands and `mvn verify` for touched modules. All tests **green, nothing skipped**. Never mark a step done with failing/skipped tests.
9. Check the step's **Definition of Done** items one by one — quote each and check it off.
10. Update `CHANGELOG.md` with the step's exact entry, immediately followed by the metrics line:
    `  AI: est <Xh> / actual <Yh> / ~<Z>% generated / <N> issues caught in human review`
    Then check the step's box in `PLAN.md`.
11. Commit with **Conventional Commits** (e.g. `feat(ledger): atomic double-entry posting (step 14)`), one step = one commit (or a small clean series). **Only commit/push if the human asked;** if on `main`, branch first.

## Phase 4 — Stop & teach

12. **STOP.** Do not start the next step without explicit instruction.
13. End with **one open-ended conceptual question** that forces synthesis (not recall) about a trade-off or edge case this step introduced — the human's primary objective is to learn.

## Never bend (Domain safety rules)

- Idempotency on every money-moving POST; ledger posting conditional by `txId`; consumers dedupe by `eventId`.
- The debited account comes from the **JWT `accountId` claim, never the payload** (the body has no source-account field).
- **Never a negative balance:** the `balanceCents >= :amount` condition lives **inside** the `TransactWriteItems`, never as a prior read.
- **Debit and credit are one atomic transaction** — no path writes one leg without the other.
- **Ledger is append-only:** corrections are compensating postings, never updates/deletes.
- Money is integer cents end to end; format to decimal only at the API edge.
