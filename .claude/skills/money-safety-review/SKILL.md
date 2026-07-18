---
name: money-safety-review
description: Review the current working diff for the PlatinumCoin Pix platform against the six domain safety rules, the ledger invariants, the ADRs, and doc/code drift — before committing or opening a PR. Use when the human says "review my changes", "safety review", "check this before I commit", "/money-safety-review", or after finishing a money-touching step. Read-only: it reports findings and required fixes, it does not change code.
---

# money-safety-review — protect the one non-negotiable: money correctness

Money correctness is the project's non-negotiable; everything else is a budget. This skill is an **adversarial, read-only** pass over the working diff. **Report findings — do not edit code.** Rank findings by severity (a domain-safety violation outranks a style nit).

## Scope

Review the uncommitted diff (or the branch vs. `main` if asked). Establish scope first:

```bash
git status --short
git diff            # working tree
git diff --staged   # staged
```

Read `CLAUDE.md` (§ Domain safety rules, § Conventions), and any ADR / `docs/data-model.md` / `docs/api/openapi.yaml` section the diff touches.

## The six domain safety rules — check each explicitly

For every rule, state **PASS** or **FAIL** with `file:line` evidence; on FAIL, give the concrete failure scenario and the required fix.

1. **Source account from the JWT, never the payload.** No request DTO for a money-moving endpoint may carry a source/debtor account field. The debited account must come from the `accountId` claim. Grep the send/transfer request records for any `debtorAccount`/`sourceAccount`/`fromAccount` field.
2. **Idempotency always.** Every money-moving `POST` requires an `Idempotency-Key`; the ledger posting is conditionally keyed by `txId`; every event consumer dedupes by `eventId` (via `ProcessedEventStore`) **before** side effects. Flag any new consumer or money POST missing a layer.
3. **Never a negative balance.** The `balanceCents >= :amount` check must live **inside** the `TransactWriteItems` `ConditionExpression` — never as a separate read-then-check in application code. Flag any balance decision made off a prior read (including off the Redis cache).
4. **Debit and credit are one atomic transaction.** No code path may write one leg without the other. A debit `UpdateItem` and its credit must be in the same `TransactWriteItems`. Flag any single-leg write.
5. **Ledger is append-only.** Corrections are compensating postings (new `txId`, e.g. `-rev`), never `UpdateItem`/`DeleteItem` on existing `ENTRY#` items. Flag any mutation/deletion of ledger entries.
6. **Money is integer cents (`long`) end to end.** No `double`/`float`/`BigDecimal` arithmetic for money internally; decimal string only at the API edge. Grep for float/double on money paths and for decimal parsing outside the edge.

## Secondary checks (still block a commit if violated)

- **Cache correctness:** the Redis balance cache serves *display* reads only; no money decision reads the cache (ADR-0008).
- **Guarded transitions:** status changes on `pix_transactions` use guarded `ConditionExpression` (`status = :expectedFrom`), not blind writes (ADR-0003/0006).
- **Error contract:** every non-2xx is RFC 7807 `problem+json` with `code` + `correlationId`; no stack traces leaked.
- **Logging:** SLF4J only (never `System.out`, never a concrete logger); `correlationId` (+ `txId`) in MDC on each meaningful stage; payloads only at DEBUG and masked.
- **Hexagonal-lite:** domain code never imports AWS SDK types.

## ADR & doc-drift check

- Does the diff **contradict an ADR**? ADRs must not be silently violated — if the change genuinely needs to, the correct move is to **propose a new ADR**, not quietly diverge. Name the ADR and the conflict.
- Does the code **drift from `docs/api/openapi.yaml`, `docs/data-model.md`, or the step spec**? If code and doc disagree, that is a defect: flag it and require the doc be updated **in the same change** (CLAUDE.md rule).
- If the diff touches a **✍️ hand-written zone** (step-15 invariant suite, step-19 tests, step-51 findings/psql) with AI-generated code, flag it — those are review-only for the AI.

## Output format

Produce a short report:

- **Verdict:** SAFE TO COMMIT / CHANGES REQUIRED.
- **Findings**, most severe first: each with the rule/area, `file:line`, the failure scenario, and the required fix.
- **Missing tests:** any money invariant introduced without an explicit test (the tests are the guardrail).
- End with the single highest-priority fix to make first.

Do not modify files. If the human then asks you to fix, address findings in severity order.
