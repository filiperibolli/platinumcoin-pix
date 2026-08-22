# ADR-0016: Finalization fencing — CAS before the money, settle XOR reverse

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 67 · **Amends:** ADR-0003

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P0 · concorrência** — *"CAS para `FINALIZING_SETTLEMENT` ou `FINALIZING_REVERSAL` antes do
> lançamento; reconciliador conclui a fase vencedora."*

## Context

Two independent paths can finalize an external send: the settlement consumer draining
`settlement-queue`, and the reconciliation resolver on its 60s scan (ADR-0003, steps 33-35). Both end
in one of two money moves:

- **settle** — `ledger.releaseClearing(<txId>-rel)`, then the guarded transition to `SETTLED`;
- **reverse** — `ledger.reverseToPayer(<txId>-rev)`, then the guarded transition to `REVERSED`.

The guarded transitions are genuine compare-and-swap operations
(`DynamoSettlementTransactionStore`: `settledUpdate` conditions on `status = SENT_TO_SPI`,
`reversedUpdate` on `SENT_TO_SPI OR DEBITED`). **They run after the money has already moved.**
`SettlementFinalizer` posts at line 88 and transitions at line 92; it posts at line 152 and
transitions at line 157. The ordering is deliberate and, for *one* path retrying itself, correct: the
posting is idempotent by its deterministic `txId`, so a crash between posting and transition is
replayed harmlessly.

The ordering does **not** survive two *different* paths. `-rel` and `-rev` are different `txId`s, so
posting idempotency does not relate them at all. A settle and a reverse racing on one transaction both
post: the clearing account is drawn down twice against a single credit, and money is created. Only
one of them then wins the CAS; the loser logs and returns — after its posting has already committed.

`StuckTransactionResolver`'s own javadoc (lines 39-49) describes this scenario exactly and names the
mitigation: the **safety window** on the `UNKNOWN` branch, which waits out any in-flight settlement
before reversing. That window is real and well-argued, but it is a probabilistic barrier — it makes
the collision unlikely, it does not make it impossible — and the same javadoc calls the guarded
transition "the backstop that decides the winner if two paths still collide", which is precisely the
thing it cannot do from *after* the posting.

The review's framing is the right one: exclusivity must be won **before** the money moves, not
recorded after it.

## Decision

1. **Two non-terminal fencing states are added to the transaction lifecycle:**
   `FINALIZING_SETTLEMENT` and `FINALIZING_REVERSAL`. They sit between the stuck states
   (`DEBITED`, `SENT_TO_SPI`) and the terminal ones (`SETTLED`, `REVERSED`).
2. **A finalizer wins a conditional transition into its fencing state before it posts anything.**
   The CAS conditions on the transaction being in a state from which that finalization is legal
   *and not already fenced by the other one*. Losing the CAS means another path owns this
   transaction's ending: the loser returns `NOT_ELIGIBLE` **having moved no money at all**. This is
   the entire decision — everything else follows from it.
3. **Settle and reverse are mutually exclusive by construction.** `FINALIZING_SETTLEMENT` is not a
   legal source state for a reversal fence, and vice versa. "One terminal winner" stops being an
   emergent property of timing and becomes a condition expression the database evaluates.
4. **Re-entering your own fence is allowed; entering the other one is not.** A redelivery, a DLQ
   redrive, or the next reconciliation cycle may re-acquire the *same* fence it already holds (the
   condition accepts the fencing state as a source) and replay its idempotent posting. That is what
   keeps a crash mid-finalization recoverable.
5. **A fence has an owner and an age, and reconciliation completes the winning phase.** The fencing
   write stamps `fencedBy` (consumer or resolver) and `fencedAt`. A transaction sitting in a fencing
   state past the reconciliation threshold is **finished in the direction it was fenced** — never
   flipped to the other one — by replaying that phase's idempotent posting and its terminal
   transition. A stuck fence is a stalled finalization, never a licence to reverse a settlement.
6. **The safety window stays.** It is now a latency optimisation rather than a correctness mechanism:
   it stops reconciliation from fencing a reversal on a transaction whose settlement is legitimately
   still in flight, avoiding a pointless race the fence would resolve anyway. Its justification in
   `StuckTransactionResolver`'s javadoc is rewritten to say so — leaving a comment that claims a
   correctness role the fence has taken over would be exactly the doc/code drift CLAUDE.md forbids.

## Alternatives rejected

- **Keep the post-then-CAS ordering and rely on the safety window.** The status quo. Rejected
  because it is probabilistic: it narrows the race, and the failure it leaves is the worst class the
  platform has — silent money creation, detectable only by a conservation audit after the fact.
- **A single deterministic `txId` for both endings** (so `-rel` and `-rev` would replay as one). It
  would make the two postings collide in the ledger — but they are *different postings*, moving money
  in different directions, and forcing them to share an identity means whichever lands first silently
  swallows the other. The ledger would report success for a reversal that never refunded anyone.
- **A distributed lock in Redis around finalization.** Introduces a second source of truth for a
  money decision, on the one component the platform has deliberately confined to caching (ADR-0008),
  plus lock expiry semantics of its own. The transaction item is already the authority on its own
  state; conditioning on it costs one attribute and no new dependency.
- **Serialize finalization through a single consumer (FIFO queue, one worker).** Would remove the
  race by removing the concurrency — and with it the reconciliation loop's independence, which is the
  thing that bounds recovery to < 5 min (ADR-0003). It also caps settlement throughput at one worker,
  directly against the 500+ TPS target.
- **Detect double-finalization after the fact via a conservation check.** Detection is not
  prevention. It is still worth having (step 69's invariant suite asserts conservation), but as the
  proof that the fence works, not as the mechanism.

## Consequences

- `pix_transactions` gains two status values and two attributes (`fencedBy`, `fencedAt`);
  `docs/data-model.md` §4 and the state diagram in ARCHITECTURE §6.7 are updated in the same change.
- Every consumer of `status` must know that `FINALIZING_*` is **not terminal**: the stuck-transaction
  scan (GSI2) must include the fencing states so a stalled fence is found, and the client-facing
  status mapping must present them as still-processing, never as a new user-visible outcome.
- One extra conditional write per finalization. Against a flow that already performs a ledger
  `TransactWriteItems` and an SPI call, it is not measurable.
- A new failure mode replaces the old one, and it is strictly better: a crash between the fence and
  the posting leaves a transaction fenced with no money moved. Reconciliation completes it in the
  fenced direction. The old failure mode — two postings, money created — is gone.
- This is the mechanism that makes the review's acceptance criterion *"1 estado terminal: teste
  concorrente prova que settle e reverse nunca movimentam dinheiro juntos"* provable. Step 69 owns
  that proof.
