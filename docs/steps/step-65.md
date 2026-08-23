# Step 65 — Durable operation identity: the `txId` is minted and persisted in the idempotency claim

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** send Pix, recoverable acceptance · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4 (amended)
>
> **Numbered out of order.** Sprint 11.5 was inserted between Sprints 11 and 12 after the external
> review in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58); its steps take the
> next free numbers (65+) rather than 45-47's positions, exactly as step 64 did, so no existing step
> file, CHANGELOG entry or ARCHITECTURE cross-reference has to be renumbered.
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P0 · dinheiro** —
> *"Persistir identidade da operação antes do ledger."* · **ADR:** [ADR-0014](../adr/0014-durable-operation-identity.md) (amends ADR-0002)

## Objective
Mint `txId` and `endToEndId` **before** the idempotency claim and write them as attributes of the
claim item, so every resume of a crashed or timed-out send reuses the same identity instead of
generating a new one. Split the existing `IdempotencyStatus` into the phases the recovery actually
needs (`CLAIMED → POSTED → RECORDED → COMPLETED`), and stop the TTL from recycling an identity whose
operation never reached `COMPLETED`.

## Why / what you'll learn
**An identity that lives only in a heap is not an identity.** The lesson here is what "idempotent"
actually requires: not one guard, but a *chain* — and a chain whose links are written at different
times, in different places, has a gap exactly where the process can die. You'll see why the fix is to
**persist** the identity rather than **derive** it (ADR-0002 rejected derivation for a reason that
still holds), why the write that establishes the right to execute must be the *same* write that
establishes what the money will be called, and how to reason about a residual window instead of
pretending you closed it: after this step the remaining gap is "crash before the claim commits",
which leaves nothing at all.

## Prerequisites
Steps 18, 19 (send skeleton, idempotency layer). First step of Sprint 11.5.

## Problem
The platform's three idempotency layers (ADR-0002) are not connected. Layer 1 (the claim) stops a
duplicate *request*; layer 2 (the ledger's `attribute_not_exists(txId)`) stops a duplicate *posting*.
Nothing persists the `txId` that would let layer 2 recognise a retry of layer 1's work — so a crash
between them defeats both, and the platform double-debits a payer for one request.

## Evidence in the current code
- `services/payment-service/src/main/java/.../infra/persistence/DynamoIdempotencyRepository.java:63-69`
  — the claim item is `pk`, `sk`, `requestHash`, `status`, `claimedAt`, `expiresAt`. No `txId`.
- `services/payment-service/src/main/java/.../domain/usecase/SendPixUseCase.java:424` and `:499` —
  `String txId = "tx-" + UUID.randomUUID();` inside `settleInternally` / `debitToClearing`, i.e.
  **after** the claim and **immediately before** the ledger call.
- `SendPixUseCase.java:224-229` — the stale re-claim path calls `acceptAndComplete` again, which
  re-enters those two methods and mints a **fresh** `txId`.
- `SendPixUseCase.java:311` and `:562` — `idempotency.complete` and `transactions.create` both run
  *after* the ledger posting, so a crash in between leaves the record `IN_PROGRESS` with no record of
  what identity moved the money.
- `DynamoIdempotencyRepository.java:77` — `attribute_not_exists(pk) OR expiresAt < :now` lets an
  expired record be overwritten regardless of its status, discarding an unresolved operation's
  identity.

**The exact window:** claim won → `txId = tx-A` minted → ledger commits the debit → process dies →
record stuck `IN_PROGRESS` → 60s later (`STALE_SECONDS`, `SendPixUseCase.java:85`) the re-claim wins →
`txId = tx-B` minted → the ledger has never seen `tx-B` → **second debit of the same request**.

## Tasks
1. **Mint before claiming.** `SendPixUseCase#execute` generates `txId` and `endToEndId` once, ahead of
   the claim loop, from the injected `Clock` and the id generators already in `domain/service/`.
2. **The claim carries the identity.** `IdempotencyRepository#claim` takes `txId` and `endToEndId` and
   writes them in the same conditional `PutItem`. One conditional write establishes both the right to
   execute and the identity every monetary effect will carry.
3. **`IdempotencyStatus` becomes the phase — no second field (ADR-0014 §3).** `IN_PROGRESS` splits
   into `CLAIMED → POSTED → RECORDED`; `COMPLETED` stays. A separate `phase` attribute alongside
   `status` is **rejected**: two fields asserting one fact can disagree. "In progress" becomes a
   derived question (`status ≠ COMPLETED`), which is what both the `409 REQUEST_IN_PROGRESS` branch
   and `isStale`/`reclaim` already ask; `reclaim`'s condition changes from `#status = :inProgress` to
   `#status <> :completed`. The finer phases are advanced as the operation progresses and are
   **advisory** — they inform logs and recovery decisions, and correctness never rests on them.
4. **A resume reads the identity, never re-mints it.** `IdempotencyRecord` exposes `txId`/`endToEndId`;
   the stale-reclaim branch passes the **stored** values into `acceptAndComplete`. `reclaim` re-stamps
   `claimedAt` and `expiresAt` and is forbidden from touching `txId`/`endToEndId` — enforced by the
   update expression, not by convention.
5. **`acceptAndComplete` accepts the identity as a parameter.** `settleInternally` and
   `debitToClearing` stop calling `UUID.randomUUID()`. The use case's ordering becomes visibly
   *identity → claim → effect*.
6. **The TTL never recycles a live money identity.** The claim's condition becomes
   `attribute_not_exists(pk) OR (expiresAt < :now AND #status = :completed)`. An expired
   record in a non-terminal status is refused with `409` plus an `ERROR` log naming the stranded
   `txId` — it is an unresolved money operation older than 24h, which the < 5-min reconciliation SLO
   says cannot happen, so it needs a human, not a fresh identity.
7. **A record without `txId` is refused, not resumed.** Pre-migration items get an `ERROR` log and a
   `409`; a sandbox has no meaningful backfill and guessing an identity is the defect being removed.
8. **Docs in the same change:** `docs/data-model.md` §5 (the claim item's new attributes and the
   revised claim condition), ADR-0002's header (a pointer to ADR-0014), and ARCHITECTURE §6.4's
   sequence diagram (the claim now precedes id generation).

## Acceptance criteria
- [ ] No code path mints a `txId` after the idempotency claim is won.
- [ ] `claim` persists `txId` and `endToEndId` atomically with the claim.
- [ ] Every resume path — stale re-claim included — posts under the **stored** `txId`.
- [ ] `reclaim` cannot alter `txId` or `endToEndId`.
- [ ] An expired, non-terminal record is never re-claimed; it answers `409` and logs at `ERROR`.
- [ ] Review acceptance criterion *"0 duplicações"* holds for the crash-after-commit case
      (proven end-to-end in step 69; proven at unit/IT level here).

## Tests (TDD)
**The test that fails today — write it first:**
- `SendPixUseCaseTest#resumeAfterCrashPostsUnderTheSameTxId` — win the claim; the fake ledger records
  the posting and then throws to simulate the crash *after* commit; advance the `Clock` past
  `STALE_SECONDS`; re-execute the same command with the same key. **Assert the ledger saw one and only
  one distinct `txId`.** Against `main` the fake sees two, and the test fails on the assertion that
  matters rather than on an exception.

Then:
- `SendPixUseCaseTest#idsAreMintedBeforeTheClaim` — the fake repository asserts `txId`/`endToEndId`
  are non-blank on the `claim` call itself.
- `SendPixUseCaseTest#reclaimReusesTheStoredIdentityNotTheCommandsFreshOne` — a stored `txId` that
  differs from anything the current invocation would generate is the one that reaches the ledger.
- `SendPixUseCaseTest#expiredNonTerminalRecordIsRefused` — `409`, no ledger call.
- `DynamoIdempotencyRepositoryIT#claimPersistsTheIdentity` — read the item back from LocalStack and
  assert both attributes and `status=CLAIMED`.
- `DynamoIdempotencyRepositoryIT#reclaimPreservesTheIdentity` — after a re-claim, `txId` and
  `endToEndId` are byte-identical and only `claimedAt`/`expiresAt` moved.
- `DynamoIdempotencyRepositoryIT#expiredTerminalRecordIsReclaimable` — the legitimate 24h key-reuse
  case still works, so the TTL rule does not break ADR-0002's replay window semantics.
- `IdempotencyIT` — existing behaviours (replay, `409` on hash mismatch, in-progress) unchanged.

## Verify locally
```bash
mvn -pl services/payment-service -am verify

# the claim now carries the identity, before any money moved
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
KEY=$(uuidgen)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"pixKey":"bob@platinumcoin.com","amount":"1.00","description":"identity"}' | jq

aws --endpoint-url=http://localhost:8000 dynamodb get-item --table-name pix_idempotency \
  --key "{\"pk\":{\"S\":\"IDEM#acc-001#$KEY\"},\"sk\":{\"S\":\"META\"}}" | jq '.Item | {txId, endToEndId, status}'
```

## Definition of Done
- [ ] `txId`/`endToEndId` minted before the claim and persisted by it
- [ ] Every resume reuses the stored identity; `reclaim` cannot change it
- [ ] Expired non-terminal records refused (`409` + `ERROR`), expired terminal ones still re-claimable
- [ ] `docs/data-model.md` §5, ADR-0002 header pointer and ARCHITECTURE §6.4 updated in this change
- [ ] `mvn -pl services/payment-service -am verify` green

## CHANGELOG entry
`### Fixed` → `Durable operation identity: txId and endToEndId are minted before the idempotency claim and persisted by it, so a crash-resume reuses the same identity instead of double-debiting (step 65, ADR-0014)`
