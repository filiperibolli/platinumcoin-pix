# Step 69 — Recovery & fencing invariant suite (crash, race, lateral access)  ✍️ hand-written zone

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** the P0 invariants, proven · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4 / §6.6 / §6.7
>
> **Numbered out of order** — see the note in [step 65](step-65.md).
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), section **07 · pronto quando** —
> the three P0 acceptance criteria: *"0 duplicações"*, *"1 estado terminal"*, *"0 acesso lateral"*.

> **Hand-written zone:** this entire suite is written by the human, by hand, without AI code
> generation and without IDE autocomplete on the first pass (AI may review the finished suite).
> Rationale, and why *this* step in particular: steps 65-68 change what the platform claims about
> crash recovery, ambiguous outcomes and exclusivity. A claim of that kind is worth exactly what its
> adversarial test is worth, and writing that test by hand is what turns "the ADR says so" into
> knowing where the window is. It also doubles as deliberate practice of the mechanics these proofs
> need — `CountDownLatch` release, fault injection at a chosen instant, and asserting **system-level**
> invariants (`Σ balances`) rather than per-call outcomes. See CLAUDE.md → "Hand-written zones", and
> step 15, whose ledger storm this suite is the distributed counterpart of.

## Objective
One consolidated suite that attacks the four P0 remediations with crashes, races and forged
credentials, and proves the three review acceptance criteria as executable assertions. Steps 65-68
each ship the tests that drove their mechanism; **this step is the adversarial pass over all four
together**, where the interactions live.

## Prerequisites
Steps 65, 66, 67, 68 — all four merged. This suite is meaningless against any subset, because every
scenario below crosses at least two of them.

## Why / what you'll learn
Ordinary tests prove a mechanism does what it says on a good day. These prove it does the right thing
at the **worst possible instant** — after the commit but before the record; between the fence and the
posting; with two paths released simultaneously. The skills: choosing the injection point (the whole
game is *where* you kill it), asserting conservation instead of outcomes, and reading a failure that
only appears once in twenty runs without dismissing it as flakiness. The suite becomes a permanent
regression guard: every later step runs against it.

## Scenarios (the suite)

**A · Crash after commit, before record (steps 65 + 66).**
Kill the send between the ledger's commit and the phase/transaction write, then let the resume run.
Assert: **exactly one** posting, one `txId`, the payer debited once, `Σ balances` unchanged, the
client's eventual answer consistent with the money. Vary the kill point across the window — before
the phase write, after it, before `idempotency.complete` — and assert the same invariant at each.

**B · Timeout that actually committed (step 66).**
The ledger commits and the response is lost. Assert the resolution re-POSTs the **same** `txId`, the
`replayed` flag comes back, and one debit exists. Then the inverse: the ledger never received the
request. Assert one debit and no double-count in the funnel. *The suite must not be able to tell the
two cases apart from the outside — that is the property.*

**C · Settle × reverse, released together (step 67).**
One `SENT_TO_SPI` transaction; a latch releases the settlement path and the reconciliation resolver at
the same instant, against real DynamoDB. Assert: exactly one of `-rel` / `-rev` exists, the loser
made **zero** ledger calls, the clearing account nets correctly, `Σ balances` unchanged. Repeat N
times — a single green run proves nothing about a race.

**D · Crash inside the fence (step 67).**
Kill between winning the fence and posting; then between posting and the terminal transition. Assert
reconciliation completes the transaction **in the fenced direction** in both cases, and never flips it.

**E · Lateral access matrix (step 68).**
For every `/internal/**` route in every service: user token ⇒ `403`; service token with wrong `aud`
⇒ `403`; wrong `scope` ⇒ `403`; correct ⇒ `2xx`. Plus a service token on a public route ⇒ `403`, and
a forged on-behalf-of header changing no outcome. Assert **no side effect** on every refusal — a
`403` that already wrote a ledger entry is not a `403`.

**F · Idempotency storm across a crash (65 + 66 + 67).**
K concurrent retries of one `Idempotency-Key` while the ledger is intermittently timing out. Assert:
one transaction, one debit, every response either the same `202` body or a `409`/`503`, and `Σ` never
moves by more than the single amount.

**G · Conservation, always.** Every scenario ends with the same assertion: `Σ balanceCents` across all
accounts, before and after, is identical, and `Σ` of all ledger entry amounts is zero. If a scenario
cannot state its conservation assertion, it is not finished.

## Tests (TDD)
The step *is* tests. Suggested shape: `RecoveryInvariantsIT` (A, B, F), `FencingInvariantsIT` (C, D),
`LateralAccessIT` (E), with the conservation assertion (G) in a shared helper every class calls.
Testcontainers throughout — never the compose stack.

## Verify locally
```bash
mvn -q -pl services/payment-service    -Dit.test=RecoveryInvariantsIT verify
mvn -q -pl services/settlement-service -Dit.test=FencingInvariantsIT  verify
mvn -q -Dit.test=LateralAccessIT verify
mvn verify          # the whole suite runs in a normal build, not behind a flag
```

## Definition of Done
- [ ] **0 duplicações** — fault injection after the commit and before the record never produces a
      second posting, at every injection point tested
- [ ] **1 estado terminal** — the concurrent settle/reverse drill, repeated, never moves money twice
- [ ] **0 acesso lateral** — the full matrix is green, and every refusal is side-effect-free
- [ ] Conservation of money asserted at the end of every scenario
- [ ] The suite runs in a plain `mvn verify`, is not skippable, and is not marked flaky
- [ ] A short findings note in the CHANGELOG entry if any scenario revealed a residual window — an
      honest "here is what is still open" beats a silent green

## CHANGELOG entry
`### Added` → `Recovery & fencing invariant suite: crash-after-commit, ambiguous-timeout, concurrent settle×reverse and lateral-access drills proving the three P0 acceptance criteria from the external review (step 69) ✍️`
