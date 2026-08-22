# ADR-0015: A ledger timeout is an unknown result, resolved by resuming the same `txId`

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 66 · **Depends on:** ADR-0014

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P0 · resiliência** — *"Tratar timeout do ledger como resultado desconhecido e resolver
> pelo mesmo `txId`; nunca assumir 'não debitou'."*

## Context

`HttpLedgerClient#post` maps a `ResourceAccessException` — a connect or read timeout, or an
unreachable host — onto `LedgerUnavailableException`, under a comment that states the belief
directly: *"Connect/read timeout or unreachable host — nothing debited, safe to retry the same
txId"* (lines 305-310). Both halves of that sentence are wrong today.

**"Nothing debited" is not knowable from a timeout.** A read timeout means the response did not
arrive within the budget. The `TransactWriteItems` on the other side may have committed a
microsecond before the socket gave up. The only honest reading of a timeout is *unknown*.

**"Retry the same `txId`" is not what the code does.** The exception propagates as a `503`; the
client retries with the same `Idempotency-Key`; the claim is `IN_PROGRESS`, so retries inside 60s get
`409`; past 60s the stale re-claim mints a **new** `txId` (ADR-0014) and posts again. So the one
mechanism that would have made the retry safe is exactly the one the retry discards.

There is a third fact, and it is the good news: **the ledger already answers this correctly.** A
posting whose `txId` is already committed is not an error there — `PostDoubleEntryUseCase` detects
the replay and `PostingResponse` returns `replayed: true` alongside the original `postedAt`, with a
javadoc explaining that answering differently *"would tempt callers to treat a retry as a failure and
mint a new `txId` — which is the one behaviour that actually double-spends."* payment-service then
calls `.toBodilessEntity()` (line 283) and throws that answer away.

So the platform already has the query-before-retry mechanism the review asks for. What is missing is
a caller that keeps its identity and reads the reply.

## Decision

1. **An ambiguous ledger outcome is a distinct outcome, not a failure.** A timeout, a connection
   reset, or any response the adapter cannot classify produces `LedgerOutcome.UNKNOWN` — separate
   from `POSTED`, `INSUFFICIENT_FUNDS` and `REFUSED`. The domain, not the adapter, decides what an
   unknown outcome means for the payment.
2. **Resolution is a re-POST of the same `txId`, never a query endpoint.** The retry *is* the query:
   the ledger's posting API is idempotent by `txId`, so re-sending the identical posting either
   commits it (it had not committed) or returns `replayed: true` (it had). One code path resolves the
   ambiguity and completes the work in the same call. A dedicated `GET /internal/ledger/postings/{txId}`
   would add an endpoint whose only answer is one the POST already gives.
3. **`replayed` is read and acted on.** `.toBodilessEntity()` is replaced by binding the response.
   `replayed: true` on a **first** attempt is not routine — it means an earlier attempt under this
   `txId` committed and its caller never learned so — and is logged at `WARN` with the original
   `postedAt`, then treated as success. The funnel counts the debit exactly once regardless, because
   it is counted from the resolved outcome, not from the number of calls.
4. **An unresolved unknown never becomes an implicit "no".** If the resolving re-POST is itself
   ambiguous within the bounded attempts, the request answers `503` and the claim stays in its
   pre-`POSTED` phase with the same `txId`. The recovery path (a client retry, or reconciliation)
   picks up the same identity and resolves it. Nothing rolls back the daily-limit reservation on an
   unknown outcome — releasing headroom for a debit that may have happened is the same class of
   error, in the other direction.
5. **The rule is stated once and applies to every remote money call.** settlement-service's
   `-rel`/`-rev` postings obey it identically, which they already almost do — they are keyed by a
   deterministic `txId` — and they gain the same `UNKNOWN`/`replayed` handling so the two services do
   not hold two different theories of a timeout.

## Alternatives rejected

- **A `GET /internal/ledger/postings/{txId}` query endpoint.** The shape the review's diagram
  suggests ("consultar antes de repetir"). Rejected because the POST already *is* the idempotent
  query: adding a read endpoint buys a second round trip, a second thing to keep consistent, and a
  new race (query says absent → posting commits → we post again with a new id) that the pure retry
  does not have. Reconsider only if a caller ever needs the posting's state *without* wanting to
  make it happen — which no flow in this platform does.
- **Treat a timeout as a failure and reverse.** Posting a compensating entry for a debit that may
  never have happened creates money whenever the guess is wrong. It also cannot work: the
  compensation needs an identity to compensate, and the whole problem is not knowing whether that
  identity moved anything.
- **Treat a timeout as success and record `DEBITED`.** Symmetrically wrong: a `DEBITED` transaction
  with no ledger entry breaks conservation and would be reversed by reconciliation, refunding money
  that was never taken.
- **Keep "nothing debited" and lean on reconciliation.** Reconciliation resolves *external* sends
  stuck in `DEBITED`/`SENT_TO_SPI`. An internal send double-debited by a stale re-claim never enters
  a stuck state — both postings look perfectly valid — so nothing would ever detect it.

## Consequences

- The send path gains one bounded resolution loop and, in the timeout case, one extra ledger call. It
  runs only on an outcome that today produces a wrong answer, so the cost lands exclusively on the
  path being fixed.
- `LedgerClient` grows a return type instead of returning `void`. That is deliberate: a port whose
  only vocabulary is "returned" or "threw" cannot express *unknown*, and the missing third word is
  what produced the defect.
- The client-visible contract does not change: an unresolved unknown is still `503`, still
  retry-safe, still under the same `Idempotency-Key`. What changes is that the retry now converges on
  one debit instead of two.
- A residual, accepted window remains: a crash *between* the ledger committing and the phase write is
  still an unknown, and stays unknown until the next resume reads the same `txId` and re-POSTs. The
  window is now bounded by recovery rather than by luck — which is the property step 69 exists to
  prove by injecting the crash.
