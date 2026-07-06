# Step 15 — Ledger invariant test suite (concurrency storm)  ✍️ hand-written zone

> **Hand-written zone:** this entire suite is written by the human, by hand, without AI code generation and without IDE autocomplete on the first pass (AI may review the finished suite). Rationale: these tests double as deliberate practice of JUnit 5, `ExecutorService`/`CountDownLatch` and collections mechanics — the exact fluency a live pairing session demands. See CLAUDE.md → "Hand-written zones".

## Objective
A dedicated IT class that attacks the ledger with concurrency and proves the financial invariants hold: no negative balance, no double-spend, conservation of money, exact accounting under contention.

## Why / what you'll learn
Sequential tests can't catch race conditions — this step is where you *earn* confidence in the consistency mechanism. Patterns: `ExecutorService` + `CountDownLatch` to release N threads simultaneously; asserting **system-level invariants** (Σ balances constant) rather than per-call outcomes; classifying results (success vs INSUFFICIENT_FUNDS vs retried-conflict) and checking the arithmetic closes. This suite is the executable answer to design question 2 and the permanent guard-rail every later step runs against.

## Prerequisites
Step 14.

## Tasks
1. `LedgerInvariantsIT` with a fresh funded account per test (isolated partitions).
2. **Storm test**: balance 1000_00; 50 parallel postings of 100_00 → exactly 10 succeed, 40 fail INSUFFICIENT_FUNDS, final balance 0, entries = 10 debit+10 credit.
3. **Conservation test**: random transfer storm among 5 accounts + clearing; Σ balanceCents before == after; Σ all entry amounts == 0.
4. **Replay-under-concurrency**: same txId posted from 10 threads → one set of entries.
5. Wire the suite into `mvn verify` for ledger-service (not skippable).

## Tests (TDD)
The step *is* tests. Definition above.

## Verify locally
```bash
mvn -q -pl services/ledger-service verify
mvn -q -pl services/ledger-service -Dit.test=LedgerInvariantsIT verify
```

## Definition of Done
- [ ] Storm: exactly ⌊balance/amount⌋ successes, never one more
- [ ] Conservation of money holds across random concurrent transfers
- [ ] Suite runs in every `verify` of ledger-service

## CHANGELOG entry
`### Added` → `Ledger invariant suite: concurrent storm proving no-negative-balance, no-double-spend and conservation of money (step 15)`
