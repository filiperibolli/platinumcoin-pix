# ADR-0018: Fraud failure classification — fail-open only for transient failures

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 70 · **Amends:** ADR-0005

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P1 · fraude** — *"Fail-open apenas para transitórias; 401/403, schema incompatível e bugs
> devem falhar visivelmente e acionar alerta."* Table row: *"`RuntimeException` genérica também
> esconde erro de contrato e autenticação."*

## Context

ADR-0005 chose fail-open under a hard 200ms budget, and that choice is not in question here — the
review adopts it ("Adaptar", not "Rejeitar"). What it disputes is the *breadth* of the catch.

`HttpFraudScorer#score` ends in `catch (RuntimeException e)` → `FraudDecision.SKIPPED` (line 99). The
class javadoc states the intent plainly: *"Any non-2xx fails open too… a 4xx/5xx here means
fraud-service itself is misbehaving (bad deploy, auth drift, overload) — the same availability
argument applies."* It is a deliberate, documented decision, not an oversight.

The argument holds for **overload** and breaks for the rest. A read timeout is information about
capacity: the check could not finish in the budget, the risk of skipping one score is bounded by
daily limits and async re-scoring, and payments should not stop. But the same branch also absorbs:

- **`401`/`403`** — the credential is wrong. After ADR-0017 this becomes a *live* failure mode: a
  token minted without the `fraud:score` scope silently disables fraud screening platform-wide.
- **`400` / an unbindable body** — the contract drifted. A field rename in fraud-service's
  `ScoreResult` turns every payment into an unscored one, and the deploy looks green.
- **`NullPointerException` and friends in our own adapter** — a bug in the code that decides whether
  to screen for fraud, silently answering "don't".

In all three the check is not *slow*, it is **broken**, and the failure is durable: it does not
recover when load falls, and every payment is unscored until a human notices. The only signal today
is the step-44 `fraud_fail_open_rate > 5% over 10m` alert — which does fire, and which reports the
symptom under the same name as a legitimate capacity blip, so the operator cannot tell a busy
afternoon from a fraud engine that has been off since the last deploy.

## Decision

1. **Failures are classified into two classes at the adapter, where the transport fact is visible.**
   - **Transient** — connect/read timeout, unreachable host, connection reset, `5xx`, `429`. The
     check *could not complete in time*.
   - **Non-transient** — `401`, `403`, any other `4xx`, an unbindable or absent body on a `2xx`, and
     any exception escaping our own adapter logic. The check is *broken*.
2. **A third outcome joins the port's vocabulary: `FRAUD_ERROR`.** The port now returns four verdicts
   from fraud-service (`APPROVE`, `REVIEW`, `DENY`) plus two failure verdicts (`SKIPPED`,
   `FRAUD_ERROR`). `SKIPPED` means "not scored, transiently"; `FRAUD_ERROR` means "not scored,
   because the check is broken".
3. **Both classes let the payment proceed.** This is the deliberate part, and it keeps ADR-0005's
   central choice intact: availability of payments wins at this layer. A broken fraud deploy must not
   become a payments outage — that would convert a detection gap into a revenue incident, and it
   would make every fraud-service deploy a money-moving change.
4. **What changes is visibility, which is what the finding is actually about.** A `FRAUD_ERROR`:
   - logs at **`ERROR`** with the classified cause, the status and the response body (never a token),
     where a `SKIPPED` logs at `WARN` — the ADR-0012 level rule applied honestly, since this *is*
     actionable;
   - increments `pix.fraud.decision{decision="FRAUD_ERROR"}`, a series distinct from `SKIPPED`;
   - raises its **own alert rule**, `fraud_broken`, firing on *any* occurrence over a short window
     rather than on a 5% share — a broken contract is not a rate, it is a binary fact;
   - is stamped **durably on the transaction** (`fraudDecision=FRAUD_ERROR`), so the set of payments
     that went out unscored-because-broken is a query, not a log search;
   - emits `FraudCheckSkipped` on the outbox as today, so async re-scoring picks it up — the
     re-scoring is the compensating control that makes proceeding defensible in both classes.
5. **`fraud_fail_open_rate` keeps its meaning.** It now measures only genuine capacity fail-opens, so
   its 5% threshold means what its runbook says. The two questions — "is fraud struggling?" and "is
   fraud broken?" — get two answers.
6. **No amount-based policy, for now.** The review suggests a per-value/risk policy ("política por
   valor/risco"). Deliberately not adopted: it introduces a threshold that must be justified,
   configured, tested at its boundary, and explained to a user whose payment was refused because
   *our* fraud service was down. Recorded as the documented evolution, gated on evidence — if the
   `FRAUD_ERROR` series ever shows a meaningful volume of high-value unscored sends, the threshold has
   a number to be argued from instead of being invented now.

## Alternatives rejected

- **Fail closed on non-transient failures (`503`, payment refused).** The strongest protection
  against a silent fraud bypass, and the option a strict reading of the review supports. Rejected
  because it inverts ADR-0005's central trade-off for a failure class we cannot bound: a bad
  fraud-service deploy at 03:00 would stop every payment on the platform, and the blast radius of
  *that* is larger and faster than the fraud losses from a bounded window of unscored, daily-limited,
  asynchronously-rescored payments. The decision is recorded here so a future operator can reverse it
  knowingly — the classification this ADR introduces is exactly what makes reversing it a one-branch
  change.
- **Keep one bucket and rely on the existing rate alert.** The status quo. Rejected: it reports a
  broken engine and a busy one under the same name, which makes the alert un-actionable in the case
  that most needs action.
- **Retry non-transient failures.** A `401` or a schema mismatch does not improve on the second
  attempt, and retrying inside a 200ms budget spends the budget on a certain failure.
- **Fail closed only for `401`/`403`.** Tempting, since an auth failure is the most alarming case.
  Rejected as an arbitrary line: a `400` from a drifted contract disables the check just as
  completely, and a rule that treats them differently is one a reader cannot derive from anything.

## Consequences

- `FraudDecision` gains a value; every exhaustive `switch` over it must handle `FRAUD_ERROR`
  explicitly (the compiler enforces this for the domain, which is the point of the enum).
- The metric catalog in `docs/observability.md` §2.1 and the alert table in §4 gain the new decision
  value and the `fraud_broken` rule; ADR-0005's decision list is annotated to point here.
- `pix_transactions` records a fourth fraud verdict; `docs/data-model.md` §4 is updated in the same
  change. Existing rows are unaffected — the value is additive.
- The wire never carries `FRAUD_ERROR`, exactly as it never carries `SKIPPED`: both are minted on the
  caller's side, because only the caller can observe that the call failed.
- Step 64's runtime latency/failure dial becomes considerably more valuable — it is what lets a human
  drill *both* classes against a running stack instead of only inside a test process. Step 70 records
  that dependency; it does not require it.
