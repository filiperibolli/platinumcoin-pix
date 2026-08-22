# Step 66 — A ledger timeout is an unknown result, resolved by resuming the same `txId`

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** send Pix, ambiguous outcome · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.4 / §6.6 (amended)
>
> **Numbered out of order** — see the note in [step 65](step-65.md); Sprint 11.5 takes the next free numbers.
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P0 · resiliência** —
> *"Tratar timeout do ledger como resultado desconhecido e resolver pelo mesmo `txId`; nunca assumir
> 'não debitou'."* · **ADR:** [ADR-0015](../adr/0015-ledger-timeout-is-an-unknown-result.md)

## Objective
Stop treating a ledger timeout as proof that nothing was debited. Introduce an explicit `UNKNOWN`
outcome, resolve it by re-POSTing the **same** `txId` (which the ledger already answers idempotently),
and read the `replayed` flag the ledger already returns and the client currently discards.

## Why / what you'll learn
**A distributed system needs a third word, and most codebases only have two.** A call either returned
or it threw — and *unknown* is neither. You'll see how a missing word in a port's vocabulary becomes a
wrong belief in a comment and then a double debit in production, and why the honest fix is a return
type rather than a cleverer `catch`. The second lesson is cheaper than it looks: the resolution of an
ambiguous outcome is **the same call again**, because an idempotent API turns "did it happen?" and
"make it happen" into one question. Notice what that saves — no query endpoint, no second race
between the read and the write.

## Prerequisites
**Step 65.** Resolving by the same `txId` is only possible once the `txId` is durable; without it,
this step's retry would resolve one ambiguity by creating a second identity.

## Problem
`HttpLedgerClient` maps every `ResourceAccessException` to `LedgerUnavailableException` under the
comment *"nothing debited, safe to retry the same txId"*. Neither half is true today: a read timeout
carries no information about whether the `TransactWriteItems` committed, and the retry does **not**
reuse the `txId`. The ledger, meanwhile, already implements the correct answer and the client throws
it away.

## Evidence in the current code
- `services/payment-service/src/main/java/.../infra/client/HttpLedgerClient.java:305-310` — the
  `ResourceAccessException` catch, and the comment asserting "nothing debited".
- `HttpLedgerClient.java:283` — `.toBodilessEntity()`: the posting response, including `replayed`, is
  discarded.
- `services/ledger-service/src/main/java/.../api/PostingResponse.java:13-30` — `replayed` exists, is
  documented, and its javadoc explains that answering a replay differently *"would tempt callers to
  treat a retry as a failure and mint a new `txId` — which is the one behaviour that actually
  double-spends"*. That is precisely what the caller does.
- `services/ledger-service/src/main/java/.../domain/usecase/PostDoubleEntryUseCase.java:71-84` — the
  replay is detected and the stored posting returned; a duplicate `txId` is **not** an error there.
- `services/payment-service/src/main/java/.../domain/port/LedgerClient.java` — the posting methods
  return `void`, so the port has no vocabulary for "unknown" and the adapter's only way to express
  doubt is to throw.

**The consequence chain:** timeout → `503` → client retries the same `Idempotency-Key` → `409` for
60s → stale re-claim (pre-step-65: a new `txId`) → second posting. The mechanism designed to make the
retry safe is the one the retry bypasses.

## Tasks
1. **`LedgerClient` returns an outcome.** `LedgerOutcome ∈ {POSTED, REPLAYED, INSUFFICIENT_FUNDS,
   REFUSED, UNKNOWN}` replaces `void` on `postInternalTransfer` and `postExternalDebitToClearing`.
   `INSUFFICIENT_FUNDS` keeps throwing its domain exception (it is a business refusal with a distinct
   422 mapping and a limit release); the point of the return type is that *unknown* becomes sayable.
2. **The adapter classifies instead of collapsing.** `ResourceAccessException`, a connection reset,
   and any response it cannot classify produce `UNKNOWN`. A definite `503 LEDGER_CONFLICT` stays what
   it is — a definite refusal, safe to retry — and is `REFUSED`.
3. **Bind the response body.** `.toBodilessEntity()` → `.body(PostingView.class)`; `replayed=true`
   maps to `LedgerOutcome.REPLAYED`.
4. **The use case resolves an `UNKNOWN` by re-POSTing the same `txId`.** A small bounded loop
   (attempts and backoff from configuration, defaults `2` attempts): the re-POST either commits or
   returns `REPLAYED`. Either way the ambiguity is resolved *and the work is done* in the same call.
   No query endpoint is added — the POST **is** the idempotent query (ADR-0015 §2).
5. **`REPLAYED` on a first attempt is a `WARN`, not a shrug.** It means a previous attempt under this
   `txId` committed and its caller never learned so. Logged with the original `postedAt`, then treated
   as success. The funnel counts `DEBITED` exactly once, from the resolved outcome — never from the
   number of HTTP calls.
6. **An unresolved `UNKNOWN` stays unknown.** After the bounded attempts: `503`, the claim stays in
   its pre-`POSTED` phase carrying the same `txId`, **no daily-limit release** (releasing headroom for
   a debit that may have happened is the same error mirrored), and an `ERROR` log naming the `txId`
   so the operator can resolve it. The next resume picks up the same identity.
7. **settlement-service obeys the same rule.** `HttpSettlementLedgerClient`'s `-rel`/`-rev` postings
   get the identical classification and `replayed` handling, so the two services do not hold two
   theories of a timeout. They are already keyed by a deterministic `txId`, so this is classification
   only — no identity work.
8. **Docs in the same change:** ARCHITECTURE §6.4/§6.6 (the resolution loop in the sequence
   diagrams), and the `HttpLedgerClient` class javadoc — whose current "Timeouts" paragraph asserts
   the belief this step removes.

## Acceptance criteria
- [ ] A ledger timeout produces `UNKNOWN`, never a claim that nothing was debited.
- [ ] Resolution is a re-POST of the same `txId`; no `GET /internal/ledger/postings/{txId}` is added.
- [ ] `replayed` is read; a replay is success, counted once, logged at `WARN` when unexpected.
- [ ] An unresolved `UNKNOWN` answers `503`, releases no limit, and preserves the `txId` for the next resume.
- [ ] settlement-service's finalization postings classify timeouts identically.

## Tests (TDD)
**The test that fails today — write it first:**
- `SendPixUseCaseTest#aLedgerTimeoutThatActuallyCommittedDebitsOnlyOnce` — the fake ledger commits the
  posting and *then* signals a timeout; the use case resolves. **Assert exactly one committed posting
  and a successful `202`.** Against `main` the timeout surfaces as `503` and the eventual retry
  produces a second posting.

Then:
- `HttpLedgerClientTest#readTimeoutIsUnknownNotUnavailable` — MockWebServer stalls past the read
  timeout ⇒ `UNKNOWN`.
- `HttpLedgerClientTest#replayedTrueIsReported` — a `200` with `replayed: true` ⇒ `REPLAYED`.
- `HttpLedgerClientTest#definite503IsRefusedNotUnknown` — the classification boundary; the two must
  not collapse back into one.
- `SendPixUseCaseTest#unresolvedUnknownDoesNotReleaseTheDailyLimit` — the asymmetry is deliberate and
  must be pinned, or a future reader will "fix" it.
- `SendPixUseCaseTest#unresolvedUnknownKeepsTheSameTxIdOnTheClaim` — the bridge to step 65.
- `LedgerTimeoutIT` (Testcontainers) — a real posting committed against LocalStack, then a simulated
  timeout on the response; the resolving re-POST returns `replayed` and the payer's balance moved
  exactly once. **Conservation asserted.**
- `SettlementLedgerTimeoutTest` — the same classification for `-rel` and `-rev`.

## Verify locally
```bash
mvn -pl services/payment-service -am verify
mvn -pl services/settlement-service -am verify

# a timeout no longer means "nothing happened": one debit, one balance change
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq .balanceCents
# force the ambiguity by dropping the ledger mid-send, then retry the same Idempotency-Key:
docker compose -f infra/docker-compose.yml pause ledger-service   # send, observe 503
docker compose -f infra/docker-compose.yml unpause ledger-service # retry same key, observe one debit
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq .balanceCents
```

## Definition of Done
- [ ] `LedgerOutcome` distinguishes `UNKNOWN` from failure and from refusal
- [ ] The posting response is bound and `replayed` acted on
- [ ] A committed-but-timed-out posting results in exactly one debit
- [ ] settlement-service classifies timeouts the same way
- [ ] `HttpLedgerClient` javadoc and ARCHITECTURE §6.4/§6.6 corrected in this change
- [ ] `mvn verify` green for both touched modules

## CHANGELOG entry
`### Fixed` → `A ledger timeout is now an unknown result resolved by re-posting the same txId, and the ledger's replayed flag is read instead of discarded — a committed-but-timed-out posting debits exactly once (step 66, ADR-0015)`
