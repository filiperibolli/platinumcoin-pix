# Step 67 — Finalization fencing: CAS before the money, settle XOR reverse

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** settlement finalization · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.7 (amended)
>
> **Numbered out of order** — see the note in [step 65](step-65.md).
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P0 · concorrência** —
> *"CAS para `FINALIZING_SETTLEMENT` ou `FINALIZING_REVERSAL` antes do lançamento; reconciliador
> conclui a fase vencedora."* · **ADR:** [ADR-0016](../adr/0016-finalization-fencing-settle-xor-reverse.md) (amends ADR-0003)

## Objective
Make a finalizer win exclusivity **before** it moves money. Add the non-terminal states
`FINALIZING_SETTLEMENT` and `FINALIZING_REVERSAL`; a conditional transition into one of them is the
precondition for posting anything. The loser of the CAS returns having moved nothing. Reconciliation
completes a stalled fence **in the direction it was fenced**, never the other one.

## Why this step exists
**Where you put the CAS is the whole design.** The platform already has the right mechanism — a
conditional write that returns "did I win" — and already uses it correctly one state earlier
(`markSentToSpi`). This step is about *ordering*: a guard after the money records who won a race that
already cost money; a guard before it decides who is allowed to spend. You'll also learn to
distinguish a **probabilistic** barrier from a **structural** one. The safety window is good
engineering and it narrows the race; it cannot close it, and the code's own javadoc admits the
failure mode. Replacing "unlikely" with "impossible by condition expression" — and then demoting the
window to the optimisation it always really was — is the move worth internalising.

## Prerequisites
Steps 33, 34, 35 (finalization, stuck scan, reconciliation resolution).

## Problem
Two independent paths finalize an external send — the settlement queue consumer and the
reconciliation resolver — and both post to the ledger *before* their guarded transition. The postings
carry different `txId`s (`-rel` vs `-rev`), so posting idempotency does not relate them: a settle
racing a reverse draws the clearing account down twice against one credit. **Money is created.** Only
one of them then wins the CAS, after the fact.

## Evidence in the current code
- `services/settlement-service/src/main/java/.../domain/service/SettlementFinalizer.java:88` —
  `ledger.releaseClearing(<txId>-rel, …)`; the guarded `transactions.markSettled` follows at `:92`.
- `SettlementFinalizer.java:152` — `ledger.reverseToPayer(<txId>-rev, …)`; the guarded
  `transactions.markReversed` follows at `:157`.
- `services/settlement-service/src/main/java/.../infra/persistence/DynamoSettlementTransactionStore.java:282`
  — `settledUpdate` conditions on `#status = :sentToSpi`; `:242` — `reversedUpdate` conditions on
  `SENT_TO_SPI OR DEBITED`. Both are real CAS operations, **and both run after the posting**.
- `services/settlement-service/src/main/java/.../domain/service/StuckTransactionResolver.java:39-49`
  — the class javadoc describes this exact race and its outcome: *"the `-rev` and `-rel` postings
  (different `txId`s, so posting idempotency does not cover them) would both draw the clearing account
  down — money created."* It names the safety window as the mitigation and calls the guarded
  transition *"the backstop that decides the winner if two paths still collide"* — a role it cannot
  play from after the posting.
- `DynamoSettlementTransactionStore.java:85-128` — `markSentToSpi` is already a CAS returning "did I
  win", with `ReturnValue.ALL_OLD` to distinguish a first claim from a re-stamp. **This is the
  pattern the fence copies**; the mechanism exists, it is simply not applied to finalization.

**The mitigation today is probabilistic.** The safety window makes the collision unlikely; it cannot
make it impossible, and its failure mode is the worst class the platform has — silent money creation,
detectable only by a conservation audit after the fact.

## Tasks
1. **Two non-terminal statuses.** `FINALIZING_SETTLEMENT` and `FINALIZING_REVERSAL` in
   `TransactionStatus` — **in both services, in the same commit, and this is not stylistic**:
   `DynamoTransactionRepository.java:287` (payment-service) does
   `TransactionStatus.valueOf(item.get("status").s())` on an item settlement-service writes. Ship the
   fencing states on one side only and the first `GET /v1/payments/{id}` against a transaction being
   finalized throws `IllegalArgumentException` → `500`. The two enums "agree by contract, not by
   construction" (their own javadocs); this step is where that contract is cashed.
2. **`SettlementTransactionStore` gains two fencing operations**, modelled on `markSentToSpi`:
   - `fenceForSettlement(txId, now)` — conditional on `status ∈ {SENT_TO_SPI, FINALIZING_SETTLEMENT}`;
   - `fenceForReversal(txId, now)` — conditional on `status ∈ {SENT_TO_SPI, DEBITED, FINALIZING_REVERSAL}`.
   Each stamps `fencedBy` (`settlement-consumer` | `reconciliation-resolver`) and `fencedAt`, moves
   the GSI2 keys onto the fencing state, and returns whether this call won it.
   **Neither accepts the other's fencing state as a source** — that single asymmetry is what makes
   settle and reverse mutually exclusive.
3. **`SettlementFinalizer` fences first, posts second.** `finalizeSettled` and `reverse` each begin
   with their fence; losing it returns `NOT_ELIGIBLE` **before any ledger call**. Re-acquiring your
   own fence is allowed, so a redelivery or a crash mid-finalization replays its idempotent posting.
4. **Terminal transitions move from the fencing state.** `settledUpdate` conditions on
   `FINALIZING_SETTLEMENT`, `reversedUpdate` on `FINALIZING_REVERSAL`. The lifecycle becomes
   `SENT_TO_SPI → FINALIZING_* → terminal`, with each arrow a conditional write.
5. **The stuck scan includes the fencing states.** `StuckTransactionScanner`'s GSI2 query adds them,
   so a fence stalled past the threshold is found. `StuckTransactionResolver` **completes the fenced
   direction**: it re-acquires that fence, replays that phase's idempotent posting and its terminal
   transition. A transaction in `FINALIZING_SETTLEMENT` is never reversed, whatever the rail now says
   — a stalled finalization is not a licence to flip the outcome.
6. **The safety window stays, reclassified.** It is now a latency optimisation (don't fence a reversal
   over a settlement legitimately in flight), not a correctness mechanism. `StuckTransactionResolver`'s
   javadoc §"Why the safety window is a correctness mechanism" is **rewritten** — leaving a comment
   asserting a role the fence has taken over is exactly the drift CLAUDE.md forbids.
7. **Client-facing status mapping** presents `FINALIZING_*` as still-processing. No new user-visible
   outcome; `GET /payments/{id}` gains no vocabulary.
8. **Docs in the same change:** `docs/data-model.md` §4 (two statuses, `fencedBy`/`fencedAt`),
   ARCHITECTURE §6.7 (the state diagram), and ADR-0003's status list.

## Acceptance criteria
- [ ] No ledger posting happens in a finalization path without a won fence preceding it.
- [ ] A settle and a reverse racing on one transaction produce **exactly one** posting; the loser
      moves no money and returns `NOT_ELIGIBLE`.
- [ ] Re-acquiring your own fence is permitted; acquiring the other one is impossible by condition.
- [ ] A transaction stalled in a fencing state is completed in that direction by reconciliation.
- [ ] Conservation of money holds across the concurrent drill.
- [ ] Review acceptance criterion *"1 estado terminal"* holds (proven at scale in step 69).

## Tests (TDD)
**The test that fails today — write it first:**
- `FinalizationFencingIT#settleAndReverseRacingOnOneTransactionMoveMoneyOnce` — one `SENT_TO_SPI`
  transaction; a latch releases the settle path and the resolver's reverse path simultaneously
  against real DynamoDB. **Assert: exactly one of `-rel` / `-rev` exists in the ledger, the clearing
  account nets correctly, and `Σ balances` is unchanged.** Against `main` both postings commit and the
  conservation assertion fails — which is the finding, reproduced.

Then:
- `SettlementFinalizerTest#losingTheFenceMakesNoLedgerCall` — the fake ledger records **zero**
  invocations. The strongest statement of the whole step.
- `SettlementFinalizerTest#reAcquiringOwnFenceReplaysThePosting` — crash-recovery within one direction.
- `DynamoSettlementTransactionStoreIT#fenceForReversalRejectsASettlementFence` and its mirror — the
  mutual exclusion, asserted at the condition-expression level where it actually lives.
- `DynamoSettlementTransactionStoreIT#terminalTransitionRequiresTheMatchingFence` — `markSettled`
  from `SENT_TO_SPI` (unfenced) is refused.
- `StuckTransactionResolverTest#completesAStalledFenceInItsOwnDirection` — a transaction in
  `FINALIZING_SETTLEMENT` whose rail now answers `UNKNOWN` past the window is **still settled**, not
  reversed.
- `StuckTransactionScannerIT#fencingStatesAreScanned` — or a stalled fence is invisible forever.
- `StatusQueryIT#aFencedTransactionIsReadableAsProcessing` — payment-service reads back an item
  settlement-service wrote in a fencing state. **Without the enum change this is a `500`**, which is
  why it belongs here and not in a later cleanup.
- Existing `ReversalIT` / `ClearingReleaseIT` / reconciliation ITs stay green through the new
  lifecycle.

## Verify locally
```bash
mvn -pl services/settlement-service -am verify

# watch a finalization pass through its fence
docker compose -f infra/docker-compose.yml logs -f settlement-service | grep -i 'fence'

TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@otherbank.com","amount":"12.50","description":"fencing"}' | jq -r .transactionId
# conservation: clearing nets to 0, SPI_SETTLED takes the amount up, Σ unchanged
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance -H "Authorization: Bearer $TOKEN" | jq
```

## Definition of Done
- [ ] `FINALIZING_SETTLEMENT` / `FINALIZING_REVERSAL` exist in both status enums and in the data model
- [ ] Every finalization fences before posting; the loser moves no money
- [ ] Settle and reverse are mutually exclusive by condition expression, not by timing
- [ ] Stuck scan covers fencing states; the resolver completes the fenced direction
- [ ] `StuckTransactionResolver`'s safety-window javadoc rewritten to its new role
- [ ] `docs/data-model.md` §4, ARCHITECTURE §6.7 and ADR-0003 updated in this change
- [ ] `mvn -pl services/settlement-service -am verify` green

## CHANGELOG entry
`### Fixed` → `Finalization fencing: settle and reverse now win a conditional FINALIZING_* transition before any ledger posting, so a race between the settlement consumer and the reconciliation resolver can no longer move money twice (step 67, ADR-0016)`
