# ADR-0014: Durable operation identity — the `txId` is minted at claim time

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 65 · **Amends:** ADR-0002

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P0 · dinheiro** — *"Persistir identidade da operação antes do ledger. Claim durável com
> fases, `operationId` e `txId` estáveis; TTL nunca recicla uma identidade já usada para dinheiro."*

## Context

ADR-0002 gives the send flow three layers of idempotency: the API claim (`pix_idempotency`), the
ledger's `txId` guard, and the rail's `endToEndId`. Layer 1 stops a **duplicate request**; layer 2
stops a **duplicate posting of the same `txId`**. The review found that nothing connects them: the
`txId` is not part of the claim.

Today `DynamoIdempotencyRepository#claim` writes `pk`, `sk`, `requestHash`, `status`, `claimedAt`,
`expiresAt` — and no identity for the money. The `txId` is minted later, *inside* the money-moving
work, as a fresh `UUID` (`SendPixUseCase` lines 424 and 499). That ordering leaves a window with a
real double-debit:

1. The claim is won; `txId = tx-A` is minted in memory.
2. `ledger.post…` commits — the payer is debited.
3. The process dies **before** `transactions.create` and before `idempotency.complete`.
4. The record stays `IN_PROGRESS` forever. Retries inside 60s get `409`; past
   `STALE_SECONDS = 60` the `reclaim` wins and re-enters `acceptAndComplete`.
5. A **new** `txId = tx-B` is minted. The ledger's `attribute_not_exists(txId)` guard has never seen
   `tx-B`, so it posts. **The payer is debited twice for one request.**

Layer 2 is a guard over an identity that layer 1 never persisted, so a crash in the window between
them defeats both. Every downstream mechanism that keys off `txId` — the `-rel`/`-rev` finalization
postings, the reconciliation scan, the audit trail — inherits the same weakness: it is keyed on an
identity that only ever lived in one JVM's heap.

ADR-0002 already considered and **rejected** deriving `txId = f(accountId, idempotencyKey)`, because a
legitimate reuse of the same key *value* after the 24h window would regenerate a `txId` the ledger
already holds and the new payment would be swallowed as a duplicate. That rejection stands and this
ADR does not disturb it: the fix is not to *derive* the identity, it is to **persist** it.

## Decision

1. **The `txId` is minted before the claim and written by the claim itself.** The conditional
   `PutItem` that wins the idempotency claim carries `txId` and `endToEndId` as attributes of the
   claim item. One conditional write establishes both "this request is mine to execute" and "this is
   the identity every monetary effect of it will carry". There is no instant at which an accepted
   request has a monetary effect without a durable identity, because the identity is written first
   and the effect is keyed on it.
2. **A resume reuses the stored identity; it never mints a new one.** Every path that re-enters the
   money-moving work — the stale re-claim, an operator-driven retry, the recovery of a crashed
   request — reads `txId` from the claim record. `reclaim` re-stamps `claimedAt` and **must not**
   touch `txId`; a re-claim that changed the identity would be the very bug this ADR closes.
3. **The existing `IdempotencyStatus` becomes the phase — no second field.** `IN_PROGRESS` splits
   into `CLAIMED → POSTED → RECORDED`; `COMPLETED` stays. So the lifecycle is
   `CLAIMED → POSTED → RECORDED → COMPLETED` in the one `status` attribute the record already has.
   Adding a separate `phase` alongside `status` was considered and rejected: `phase=COMPLETED` and
   `status=COMPLETED` would be two fields asserting one fact, and two fields that can disagree are a
   bug waiting for the write that lands only one of them. "In progress" stops being a stored value
   and becomes what it always was — a **derived** question, `status ≠ COMPLETED` — which is also what
   the `409 REQUEST_IN_PROGRESS` branch and the stale re-claim both actually ask.
   The finer phase is advisory for logs and recovery decisions; **correctness never depends on it** —
   it rests on the `txId` and the ledger's guard, both of which hold even if a phase advance is lost.
4. **A `txId` that has been used for money is never recycled by the TTL.** The claim's
   `attribute_not_exists(pk) OR expiresAt < :now` condition may only overwrite an expired record
   whose `status` is `COMPLETED`. An expired record
   still in a non-terminal status is **not** re-claimable: it is an unresolved money operation older
   than 24h, which the < 5-min reconciliation SLO says cannot happen — so if it ever does, it is a
   defect that needs a human, not a fresh identity handed to a client. It surfaces as a `409` plus an
   `ERROR` log, never as a silent new payment.

   > **The exact reach of this guarantee (recorded during step 65's implementation).** The rule is
   > enforced by a *condition on an item*, so it holds **for as long as the item exists**. DynamoDB TTL
   > is enabled on `pix_idempotency.expiresAt`, and its background collector does eventually delete an
   > expired record — after which `attribute_not_exists(pk)` is true again and the key is claimable,
   > with no `409` and no `ERROR` log naming the stranded `txId`. Two reasons we accept that rather than
   > drop the TTL for non-terminal records. First, **this was never the detector**: money that stopped
   > moving is found by the reconciliation scan over `pix_transactions` (step 34), a table with no TTL —
   > this rule is a backstop at the intake door, not the alarm. Second, **dropping the TTL here would
   > cause a worse bug today**: a *refused* send (`KEY_NOT_FOUND`, `LIMIT_EXCEEDED`, `FRAUD_DENIED`,
   > `INSUFFICIENT_FUNDS`) also leaves a non-terminal record, so an immortal item would block that key
   > value forever for a payment that never moved a cent. The right order is to make refusals terminal
   > first — that write belongs with the finalization fencing of ADR-0016 — and only then consider
   > suspending the TTL while an operation is genuinely unresolved.
5. **`endToEndId` follows the same rule as `txId`.** It is the rail's idempotency key (layer 3) and
   is minted and persisted at the same moment, for the same reason: a resume that re-generated it
   would present BACEN with a second, unrelated payment.

## Alternatives rejected

- **Derive `txId` from `(accountId, idempotencyKey)`.** Already rejected by ADR-0002 and still
  rejected, for the reason recorded there: key reuse after the 24h window would regenerate an
  identity the ledger holds and the *new* payment would be silently swallowed as a duplicate.
  Persisting a random id gives the same recovery property with none of the collision semantics.
- **Write the transaction item (status `CLAIMED`) before the ledger call, and read the `txId` back
  from it.** Also durable, and it was close. Rejected because it puts the identity in a *second*
  partition from the one the claim already writes conditionally: accepting a request would become
  two writes that can partially fail, re-creating in `pix_transactions` exactly the gap being closed
  in `pix_idempotency`. One conditional write is the whole point.
- **Shorten `STALE_SECONDS` so the window is smaller.** Treats a correctness defect as a tuning
  problem. Any non-zero window still double-debits, and shrinking it makes a *legitimate* slow
  request more likely to be re-claimed while still in flight — trading a rare bug for a common one.
- **Rely on the daily-limit counter to bound the damage.** It bounds the *amount*, never the
  *duplication*, and it is not a money invariant. Two debits of R$10 stay two debits.

## Consequences

- The claim item grows two attributes (`txId`, `endToEndId`) and its `status` gains two intermediate
  values; `docs/data-model.md` §5 is updated in the same change.
  Existing records written before this step have no `txId`; a record read without one is treated as a
  pre-migration record and refused with an `ERROR` log rather than resumed — a sandbox has no
  meaningful backfill, and guessing an identity is precisely the failure mode being removed.
- `SendPixUseCase` stops calling `UUID.randomUUID()` inside the money path. Id generation moves ahead
  of the claim, which makes the use case's ordering visibly *identity → claim → effect* — the
  ordering a reader should be able to see without a diagram.
- A crash anywhere after the claim is now recoverable to the same identity, which is what makes
  ADR-0015 (timeout as unknown result) implementable at all. The two decisions are separate but the
  second is worthless without this one.
- The residual window shrinks to something harmless: a crash between minting the ids and the claim's
  conditional write leaves *nothing* — no claim, no money — and the client's retry is a clean first
  attempt.
- **A refused send also leaves a non-terminal record, and decision 4 now catches it too.** A send that
  dies at `KEY_NOT_FOUND`, `LIMIT_EXCEEDED`, `FRAUD_DENIED` or `INSUFFICIENT_FUNDS` throws out of the
  use case without completing its claim — pre-existing behaviour, unchanged here. What *is* new is the
  consequence past 24h: reusing that exact key value now answers `409 OPERATION_UNRESOLVED` where it
  used to be re-claimable. We accept this deliberately. Storage cannot tell "nothing moved, the payment
  was refused" from "the debit committed and the process died" — both are a non-terminal record — and
  when the two are indistinguishable the safe reading is the expensive one. The blast radius is small
  (a client generates a fresh key per business operation; inside 24h nothing changes) and the failure
  mode is a visible `409`, never a silent second debit. Making refusals terminal would remove the
  false positive, but it means writing a terminal state on the failure path — a write that can itself
  fail, at the exact moment the system is already failing — so it belongs with the finalization
  fencing work (ADR-0016), not here.
