# ADR-0020: Keep DynamoDB for the ledger; PostgreSQL stays a lab, not a migration

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** none — this ADR records a decision **not** to act · **Confirms:** ADR-0001, ADR-0009

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Decision table row **"PostgreSQL no ledger → Avaliar depois"** — *"Primeiro corrigir identidade e
> fencing no DynamoDB. Migrar apenas com benchmark, plano de dados e operação paralela. Uma reescrita
> não elimina automaticamente falhas entre serviços e introduz risco de migração."* The 90-day plan's
> final item is likewise *"ADR: manter DynamoDB ou iniciar migração controlada"* — this is that ADR.

## Context

The review's target architecture is presented against an attached proposal in which the ledger runs
on PostgreSQL and the services consolidate into a modular monolith. Its verdict on both is the same
and it is not "yes": PostgreSQL is **"avaliar depois"**, the monolith is **"evolução seletiva"**. The
stated reason is the one that matters — *a rewrite does not automatically eliminate cross-service
failures* — and every P0 in the review is a cross-service failure: operation identity, timeout
semantics, finalization fencing, internal-port identity. None of the four is a storage problem, and
all four would exist unchanged on PostgreSQL.

This needs an ADR for a specific reason: "we discussed migrating and decided not to" is invisible in
a repository. Without a record, the next reader finds a review recommending PostgreSQL and a codebase
running DynamoDB, and cannot tell whether that is a considered position or an unfinished task.

The relevant existing decisions:

- **ADR-0001** chose DynamoDB for the ledger, with `TransactWriteItems` giving ACID across the exact
  set of items a Pix posting touches, and a conditional `balanceCents >= :amount` **inside** the
  transaction (Domain Safety Rule #3).
- **ADR-0009** already commissioned the honest counterpart: `labs/ledger-pg`, the same ledger port on
  PostgreSQL with pessimistic (`SELECT FOR UPDATE`) and optimistic (version column) strategies, plus
  invariant parity, an `EXPLAIN`/index/deadlock study and a contention benchmark against DynamoDB
  (steps 50-51). It is explicitly **never wired to the platform**.

So the comparison the review asks for ("migrar apenas com benchmark") is already a planned
deliverable. What did not exist was a decision about what it is *for*.

## Decision

1. **DynamoDB remains the ledger's store.** No migration step is created, and none of the review's
   P0/P1 remediation is contingent on the storage engine.
2. **`labs/ledger-pg` stays a lab, and its purpose is stated: comparison and learning, not
   preparation.** Steps 50-51 produce a measured, written comparison — contention behaviour, index
   and locking study, invariant parity. A lab whose findings favour PostgreSQL does not thereby
   authorize a migration; it authorizes a new ADR that argues for one.
3. **The P0 remediation happens on DynamoDB first, and that ordering is the decision.** ADR-0014
   through ADR-0017 are implemented on the current store. This directly follows the review's own
   sequencing — *"Primeiro corrigir identidade e fencing no DynamoDB"* — and it has a property worth
   naming: fixing the coordination defects *before* any storage discussion means a future migration
   would carry a correct design across, rather than carrying a defect into a new engine and
   attributing the fix to the move.
4. **The conditions under which this decision would be revisited are written down now, so a future
   argument has a bar to clear rather than a preference to assert.** All three would need to hold:
   - steps 50-51 measure a contention or cost profile that materially favours a relational engine at
     the target write rate, on representative infrastructure — not on this WSL2 host;
   - a ledger requirement appears that DynamoDB genuinely cannot serve (multi-row analytical queries
     over the entry history, or a transaction spanning more items than `TransactWriteItems` allows);
   - a data plan and a parallel-operation plan exist — dual-write, reconciliation between the two
     stores, cut-over and rollback — because a ledger cannot be migrated with a maintenance window
     and a `pg_restore`.
5. **The modular-monolith recommendation is answered the same way and in the same place.** The review
   says "evolução seletiva… consolidar deve reduzir uma falha concreta, não apenas implantações."
   The concrete failure it names — the payment/settlement state machine split across two services —
   is precisely what ADR-0016's fencing addresses **without** moving code between modules. If fencing
   turns out to be insufficient, co-locating that state machine is the next candidate; ADR-0006's
   boundaries are otherwise unchanged.

## Alternatives rejected

- **Create a migration step (a "step 73 — move the ledger to PostgreSQL").** Rejected as the direct
  opposite of what the review recommends. It would also be the largest and riskiest change in the
  project, undertaken for a benefit nobody has measured, against a store whose one hard requirement
  (atomic multi-item posting with a balance guard inside the transaction) it demonstrably meets.
- **A dual-write / parallel-run of both ledgers.** The genuinely correct way to migrate a ledger, and
  therefore the right shape *if* a migration were justified. Rejected now because it is enormous
  (two stores, a reconciler between them, a cut-over plan) and everything it costs is spent proving
  something the lab answers more cheaply.
- **Close the review item silently, with no ADR.** The cheapest option and the one that fails the
  project's stated purpose — *"every non-trivial decision is written down with its trade-off"*
  (CLAUDE.md). A recommendation from an external reviewer that the project declines is exactly the
  kind of decision that needs a record.
- **Reclassify `labs/ledger-pg` as migration groundwork.** Would quietly change ADR-0009's intent
  from learning to preparation, and would put pressure on the lab's findings to justify a conclusion
  already chosen. The lab is more useful if it is allowed to say "DynamoDB is fine here".

## Consequences

- Sprint 11.5 contains no storage work, and its steps are free to use DynamoDB-specific mechanisms
  (conditional writes, `TransactWriteItems`, sparse GSIs) without hedging against a future port. The
  fencing CAS of ADR-0016 is a conditional `UpdateItem`; that is a deliberate, recorded commitment.
- Steps 50-51 keep their scope and their `✍️` hand-written findings document, now with an explicit
  question to answer: *does the measured contention profile clear the bar in §4 of this ADR?* A "no"
  is a valid and useful result.
- The trade-offs ADR-0001 accepted stand and are not re-litigated here: no ad-hoc analytical queries
  over ledger history (the S3 audit trail and the cold archive serve that), and clearing-account
  write contention as a real limit (step 52's sharding is its answer).
- A reader arriving from the review now finds the answer next to the question, with the conditions
  for changing it — rather than a silence they would have to interpret.
