# Step 70 — Fraud failure classification: fail-open only for transient failures

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** fraud score in the path (§6.5) · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.5
>
> **Numbered out of order** — see the note in [step 65](step-65.md).
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P1 · fraude** —
> *"Fail-open apenas para transitórias; 401/403, schema incompatível e bugs devem falhar visivelmente
> e acionar alerta."* · **ADR:** [ADR-0018](../adr/0018-fraud-failure-classification.md) (amends ADR-0005)

## Objective
Split the fraud adapter's single catch-all into two failure classes. A **transient** failure keeps
today's behaviour (`SKIPPED`, fail-open, `WARN`). A **non-transient** failure — the check is broken,
not slow — becomes a distinct `FRAUD_ERROR`: the payment still proceeds, but at `ERROR`, on its own
metric series, under its own alert, and stamped durably on the transaction.

## Why this step exists
**Not every failure is the same failure, and one `catch` block is a claim that they are.** ADR-0005's
fail-open is an argument about *capacity* — the check ran out of time, the risk is bounded, payments
should continue. This step is about noticing the exact point where that argument stops applying: a
`401` or a drifted schema is not slow, it is broken, it will not recover on its own, and it disables
a control silently. You'll practise separating **what the system does** (still proceed — availability
was chosen deliberately and stays chosen) from **what the system says about it** (a different level,
a different metric, a different alert, a durable flag on the item). That split is why this step keeps
ADR-0005's trade-off intact while removing the thing that made it dangerous: silence.

## Prerequisites
Step 25 (fraud integration). **Not** blocked by step 64 — but step 64's runtime dial is what lets a
human drill both classes against a running stack, so it is the natural companion.

## Problem
`catch (RuntimeException e) → SKIPPED` absorbs everything. The availability argument that justifies
fail-open is an argument about **capacity**: the check could not finish in the budget, the risk is
bounded, payments should not stop. It does not extend to a `401`, a drifted contract, or a bug in our
own adapter — in those the check is *broken*, the failure is durable, and every payment goes unscored
until a human notices. The only signal is a rate alert that reports a broken engine under the same
name as a busy afternoon.

## Evidence in the current code
- `services/payment-service/src/main/java/.../infra/client/HttpFraudScorer.java:99-108` —
  `catch (RuntimeException e)` → `FraudDecision.SKIPPED`.
- `HttpFraudScorer.java:34-38` — the javadoc states the intent: *"Any non-2xx fails open too… a
  4xx/5xx here means fraud-service itself is misbehaving (bad deploy, auth drift, overload) — the
  same availability argument applies."* Deliberate and documented; the review disputes the breadth,
  not the principle.
- `HttpFraudScorer.java:90-95` — a `2xx` with an unbindable/absent body also becomes `SKIPPED`.
- `docs/observability.md` §4 — `fraud_fail_open_rate`, ratio ceiling `> 5%` over 10m. One series for
  two very different conditions.
- **After step 68 this becomes a live failure mode:** a service token minted without the
  `fraud:score` scope returns `403`, which today silently disables fraud screening platform-wide.

## Tasks
1. **Classify at the adapter**, where the transport fact is visible:
   - **transient** — connect/read timeout, unreachable host, connection reset, `5xx`, `429`;
   - **non-transient** — `401`, `403`, any other `4xx`, an unbindable or absent body on a `2xx`, and
     any exception escaping the adapter's own logic.
2. **`FraudDecision` gains `FRAUD_ERROR`.** Six values: `APPROVE`, `REVIEW`, `DENY` from the wire;
   `SKIPPED` and `FRAUD_ERROR` minted only on the caller's side. The enum makes every exhaustive
   `switch` a compile-time obligation, which is the point of adding a value rather than a boolean.
3. **Both classes proceed.** ADR-0005's central trade-off is preserved deliberately: a broken fraud
   deploy must not become a payments outage. What changes is visibility.
4. **A `FRAUD_ERROR` is loud and durable.**
   - `ERROR` log with the classified cause, status and response body (never a token — ADR-0012),
     versus `WARN` for a `SKIPPED`;
   - `pix.fraud.decision{decision="FRAUD_ERROR"}` — its own series;
   - stamped on the transaction (`fraudDecision=FRAUD_ERROR`), so "which payments went out unscored
     because the check was broken" is a **query**, not a log search;
   - `FraudCheckSkipped` still written to the outbox, so async re-scoring — the compensating control
     that makes proceeding defensible — cannot miss it.
5. **A new alert rule, `fraud_broken`**, firing on *any* occurrence over a short window, not on a
   share. A broken contract is a binary fact, not a rate. `fraud_fail_open_rate` keeps its 5%
   threshold and finally means what its runbook says.
6. **No amount-based policy.** The review suggests one; ADR-0018 §6 records why it is deferred rather
   than invented — if the new series ever shows meaningful high-value volume, the threshold has a
   number to be argued from.
7. **Docs in the same change:** `docs/observability.md` §2.1 (the new decision value) and §4 (the new
   rule + its runbook), `docs/data-model.md` §4 (the fourth verdict), ADR-0005 annotated to point at
   ADR-0018, and fraud-service's README.

## Acceptance criteria
- [ ] A timeout still yields `SKIPPED`, `WARN`, fail-open — unchanged.
- [ ] A `401`/`403` yields `FRAUD_ERROR`, `ERROR`, its own counter, and the payment still succeeds.
- [ ] An unbindable body on a `2xx` yields `FRAUD_ERROR`, not `SKIPPED`.
- [ ] `fraud_fail_open_rate` no longer counts non-transient failures.
- [ ] The transaction records which of the two happened.
- [ ] `DENY` is untouched: a business verdict is never a failure.

## Tests (TDD)
**The test that fails today — write it first:**
- `HttpFraudScorerTest#anUnauthorizedResponseIsAFraudErrorNotASkip` — MockWebServer answers `401`.
  **Assert `FRAUD_ERROR`.** Against `main` it returns `SKIPPED`, indistinguishable from a timeout —
  which is the finding.

Then:
- `HttpFraudScorerTest#aReadTimeoutIsStillASkip` — the behaviour that must **not** change; pin it
  before touching the catch block.
- `HttpFraudScorerTest#aFiveHundredIsTransient` and `#aFourHundredIsNotTransient` — the classification
  boundary, stated from both sides.
- `HttpFraudScorerTest#anUnbindableBodyIsAFraudError`.
- `HttpFraudScorerTest#aDenyIsNeverAFailure` — the regression that would hurt most.
- `SendPixUseCaseTest#fraudErrorProceedsAndIsStampedOnTheTransaction`.
- `SendPixFunnelMetricsTest#fraudErrorAdvancesTheFunnelAndIsCountedSeparately` — a broken check is
  risk, not a drop-off; the funnel stage still advances, exactly as for `SKIPPED`.
- `AlertEvaluatorTest#fraudBrokenFiresOnASingleOccurrence` and
  `#failOpenRateIgnoresFraudErrors`.
- `FraudIntegrationIT` — end to end: fraud-service returning `403` produces a settled payment with
  `fraudDecision=FRAUD_ERROR` on the item.

## Verify locally
```bash
mvn -pl services/payment-service -am verify

# with step 64's dial (if merged) or by stopping fraud-service: two different signals, two different logs
docker compose -f infra/docker-compose.yml stop fraud-service     # transient → SKIPPED, WARN
curl -s localhost:8084/actuator/prometheus | grep pix_fraud_decision
docker compose -f infra/docker-compose.yml start fraud-service
docker compose -f infra/docker-compose.yml logs payment-service | grep -i 'fraud'
```

## Definition of Done
- [ ] Transient and non-transient fraud failures are distinct outcomes with distinct levels, series and alerts
- [ ] `FRAUD_ERROR` is durable on the transaction and still emits `FraudCheckSkipped`
- [ ] `fraud_fail_open_rate` measures only genuine capacity fail-opens
- [ ] `docs/observability.md`, `docs/data-model.md`, ADR-0005 pointer and the fraud-service README updated
- [ ] `mvn -pl services/payment-service -am verify` green

## CHANGELOG entry
`### Changed` → `Fraud failures are classified: fail-open stays for transient failures, while auth/contract/bug failures become a distinct FRAUD_ERROR with its own log level, metric series and alert instead of hiding behind the same SKIPPED counter (step 70, ADR-0018)`
