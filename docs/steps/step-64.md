# Step 64 — fraud-service runtime latency/failure injection (PROPOSED)

> **Sprint 12 — Hardening, E2E & load** · **Flow:** fraud score in the path (§6.5) · **Infra que sobe:** none new

> **This step is a PROPOSAL, not yet scheduled.** It was drafted while building the load-measurement
> deliverable in `docs/load/` (S4 "fraud fail-open"), not while executing PLAN.md top-to-bottom, so its
> step number (64) is the next free number in the document rather than its position in Sprint 12's
> natural 45-47 sequence — it is appended here, unimplemented, for the human to prioritize against
> steps 48-63. Do not start this step under the normal "first unchecked step" workflow without an
> explicit go-ahead; it exists to record the gap it fixes and a ready-to-review design for closing it.

> **Its value rose (2026-08-22).** [Step 70](step-70.md) splits fraud failures into transient and
> non-transient classes ([ADR-0018](../adr/0018-fraud-failure-classification.md), from the external
> review in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58)). This dial is what lets
> a human drill **both** classes against a running stack instead of only inside a test process — a
> latency knob exercises `SKIPPED`, a `401`/malformed-response knob exercises `FRAUD_ERROR`. Step 70
> does not require step 64; step 64 is how step 70's behaviour gets demonstrated rather than asserted.

## Objective
Give `fraud-service` the same runtime-armable behavior dial `mock-bacen-spi` already has
(`AdminConfigController`, step 30): a way to inject artificial latency and/or a failure rate into
`POST /internal/fraud/score` **while the stack is running**, so the send path's fail-open behavior
(step 25, ADR-0005) can be exercised from outside a test process — by a human running a drill, or by a
load-testing tool like `tools/k6/`.

## Why this step exists
**The gap this closes.** `docs/load/RESULTS.md` (S4) found that `mock-bacen-spi`'s settlement leg is
drillable at runtime (`POST /admin/config` — latency, failure rate, timeout rate, reject-by-key, all
armable without a restart) but `fraud-service` is not: `FraudProperties` only binds the *scoring rule*
knobs (amount thresholds, velocity windows, weights) — there is no equivalent of BACEN's dial for
latency or failure. Step 25's own "Verify locally" section says to "make fraud slow/unreachable", but the
only way to do that today is `docker compose stop fraud-service` (an instant, total outage — not a slow
dependency crossing the 200ms budget) or a network-layer trick outside the application entirely. The
platform can drill "BACEN is slow" end to end and prove reconciliation resolves it; it cannot drill
"fraud is slow" the same way, even though ADR-0005 calls fail-open under a slow fraud-service **the
single most debated design call in the project**. This step makes that trade-off demonstrable at
runtime, not just provable in an IT.

**Same pattern as step 30, deliberately.** `AdminConfigController` already sets a precedent for "not an
endpoint a real dependency has" (see its own Javadoc) — this step mirrors it rather than inventing a new
shape, so the two admin dials read the same way to anyone who has seen one of them.

## Prerequisites
Step 25.

## Tasks
1. `SpiBehavior`-equivalent for fraud-service — a small mutable holder (`FraudScoreBehavior` or similar)
   the use case reads before scoring: `latencyMs` (sleep before responding), `failureRate` (fraction of
   calls that return `503` instead of a score), both defaulting to `0` so normal operation is
   unaffected until armed.
2. `POST /admin/config` / `GET /admin/config` on fraud-service, same shape as
   `services/mock-bacen-spi/.../AdminConfigController.java`: absent fields left unchanged, `GET` reports
   the currently-armed dial. Under `/admin`, not `/v1`, for the same reason BACEN's is — nothing in the
   platform may call it, only a human or a test.
3. Wire the injected latency/failure into `ScoreFraudUseCase` (or the controller in front of it) so it
   applies to the real `POST /internal/fraud/score` path payment-service already calls — the point is
   that payment-service's existing 200ms client-side timeout (step 25) is what should observably kick in
   and fail the payment open, unmodified.
4. `docs/local-dev.md` §5.5-style runbook entry: arm latency above 200ms, send a payment, confirm `202`
   with `fraudSkipped=true`, disarm.

## Tests (TDD)
- `AdminConfigControllerIT` (fraud-service) — arms latency/failure, confirms `GET` reports it, confirms
  `POST /internal/fraud/score` actually sleeps/fails accordingly; absent fields leave the current dial
  unchanged (mirrors `mock-bacen-spi`'s own `AdminConfigControllerIT`).
- Extend `FraudIntegrationIT` (payment-service) or add a new IT: arm fraud-service latency above 200ms
  via the new endpoint (not a WireMock stub), send a real payment through the running fraud-service, and
  assert `202` + `fraudSkipped=true` + `FraudCheckSkipped` — the same assertion step 25's IT already
  makes with a stub, now provable against the real service.

## Verify locally
```bash
curl -s -X POST localhost:8083/admin/config -H 'Content-Type: application/json' \
  -d '{"latencyMs": 300}' | jq   # above the 200ms budget

curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"10.00"}' | jq   # 202, fraudSkipped=true

curl -s -X POST localhost:8083/admin/config -H 'Content-Type: application/json' \
  -d '{"latencyMs": 0}' | jq   # disarm
```

## Definition of Done
- [ ] `POST`/`GET /admin/config` on fraud-service, same shape/spirit as mock-bacen-spi's
- [ ] Injected latency/failure observably reaches `POST /internal/fraud/score`
- [ ] A real payment through the live services fails open (`fraudSkipped=true`) when armed above 200ms
- [ ] `docs/load/RESULTS.md`'s S4 gap can be closed by re-running that scenario against this endpoint

## CHANGELOG entry
`### Added` → `fraud-service runtime latency/failure injection (AdminConfigController, mirrors
mock-bacen-spi) — closes the S4 load-test gap (step 64)`
