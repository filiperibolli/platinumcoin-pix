# Learning — ADR-0005 (Fraud latency budget, fail-open) · finalized by Step 25

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0005-fraud-latency-budget-fail-open.md](../adr/0005-fraud-latency-budget-fail-open.md) · **Concept finalized by:** [Step 25](step-25.md) (payment-service integration: 200ms hard budget + fail-open).
> **Why Step 25:** Step 24 builds a fast `/score` engine, but ADR-0005 is not about the engine — it's about the **decision under a deadline**: what the *caller* does when fraud is slow or down. That call (the 200ms budget and fail-open with a `FRAUD_SKIPPED` flag) is made in Step 25, which is therefore where the ADR is finalized. This is the single most debated design call in the project.

---

## 1. The decision, and the trade-off it resolves

Fraud detection must be real-time but may add **at most 200ms** to the send flow, and the fraud-service can be slow or down. ADR-0005 resolves the trade-off between **catching fraud** and **keeping payments available**:

- **Synchronous** call between limit-check and ledger debit, with a **hard client-side timeout of 200ms** (connect 50ms / read 150ms). fraud-service targets p99 < 150ms using only pre-computed features (velocity counters in Redis, amount thresholds, payee novelty, odd-hours) — no heavy inference in-path; ML runs asynchronously on the event stream and feeds block-lists the cheap check reads.
- Decisions: `APPROVE` / `REVIEW` (approve + flag) / `DENY` (422).
- **On timeout or error ⇒ FAIL-OPEN:** the payment proceeds, flagged `fraudSkipped=true` / `fraudDecision=SKIPPED`, and a `FraudCheckSkipped` event triggers async scoring (an async DENY can only alert/hold future activity, not claw back this payment).

The trade-off, stated honestly:

| | Risk it accepts | Why it's bounded / why it wins here |
|---|---|---|
| **Fail-open** (chosen) | During an outage, unscored fraud slips through | Daily limits still cap exposure; async re-scoring still runs (detection delayed, not skipped); outages are short and alerted |
| **Fail-closed** (rejected) | Any blip (deploy, GC pause, dependency hiccup) rejects **100% of legitimate payments** to stop a fraction of a percent of fraud | For a core money-movement product that trade is worse — **availability of payments is itself a trust/security property** |

The documented production evolution is a **hybrid**: fail-open below a value threshold, fail-closed (or step-up) above it — tuned to fraud-loss vs. revenue-loss data. Locally we implement pure fail-open and log the seam.

---

## 2. Test the behavior (curl)

Prereq: Sprint 5 up (fraud-service :8083, payment-service :8084), token in `$TOKEN`.

**(a) Normal path — fraud scores APPROVE and the send proceeds:**
```bash
curl -s -X POST localhost:8083/internal/fraud/score -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-001","pixKey":"bob@platinum.com","amountCents":1200,"timestamp":"2026-07-18T12:00:00Z"}' | jq
```

**(b) DENY blocks the payment (422) and releases the limit reservation:**
```bash
# a huge amount trips the amount rule → DENY:
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"500000.00"}' | head -1   # 422 FRAUD_DENIED
```

**(c) The fail-open proof — make fraud unreachable/slow, the send STILL succeeds, flagged:**
```bash
docker compose -f infra/docker-compose.yml stop fraud-service     # or induce >200ms latency
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"10.00"}' | head -1   # 202 — payment proceeds
TX=<transactionId>; curl -s localhost:8084/v1/payments/$TX -H "Authorization: Bearer $TOKEN" | jq '.fraudDecision'
# → "SKIPPED"  (fraudSkipped=true; a FraudCheckSkipped event marks it for async re-scoring)
docker compose -f infra/docker-compose.yml start fraud-service
```

**(d) The budget is real** — even a hung fraud-service can't push the send past ~200ms (compare `time` on (c) with fraud stopped).

---

## 3. Where to confirm it in the logs

| Signal | Log line | What it proves |
|---|---|---|
| Scored | `"event":"fraud.scored"` with `decision`, `score`, `correlationId` | The synchronous check ran inside budget |
| Deny | `422 FRAUD_DENIED` + a limit-release line | DENY blocks and the reservation is returned |
| **Fail-open** | **WARN** "fraud skipped (timeout/error), proceeding" + `fraudSkipped=true` | The core ADR behavior — availability chosen at this layer |
| Async seam | a `FraudCheckSkipped` marker (event once the outbox exists, Sprint 6) | The skip is not forgotten — it feeds async re-scoring |
| Transition | `RECEIVED → FRAUD_CHECKED` recorded | The stage advanced even on skip |

```bash
docker compose -f infra/docker-compose.yml logs payment-service | grep -E 'fraud.scored|fraud skipped|FRAUD_DENIED'
```
The fail-open **rate** is a KPI (README §OKRs & KPIs) — a spike means the budget is being blown often and fraud-service needs attention.

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Rule engine (`/score`, p99 < 150ms) + Redis velocity | `services/fraud-service/...` (Step 24) |
| 200ms client budget (connect 50 / read 150) | payment-service `RestClient` to fraud (Step 25) |
| DENY → 422 + limit release; REVIEW/APPROVE proceed | payment-service send orchestration (Step 25) |
| Fail-open: `fraudSkipped=true`, `FraudCheckSkipped` seam | payment-service (Step 25) → wired as an outbox event in Step 28 |
| `RECEIVED → FRAUD_CHECKED` transition | `pix_transactions` state machine (Step 25) |
| Narrative | [ARCHITECTURE.md §6.5, §7.5](../../ARCHITECTURE.md) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **"Availability is a security property."** Defend or attack that sentence for a payments product. Then give the one product context where you'd reverse it and default to fail-closed — and what data you'd need to justify the reversal to a risk officer.
2. **Where the budget is spent.** The 200ms splits 50/150 connect/read. Explain why the split matters (not just the total), and what symptom tells you the connect budget is too tight vs. the read budget too tight.
3. **The hybrid threshold.** Design the value threshold for the documented fail-closed-above-X evolution. What two loss curves does X sit between, and how would a fail-open *rate* metric change your confidence in the chosen X over time?
4. **Skip is not ignore.** A payment goes through `fraudSkipped=true` and async scoring later returns DENY. Enumerate every action the system can still legitimately take against that already-settled payment, and which it cannot — tie each to an existing mechanism (limits, reversal, block-list).
5. **Failure isolation without a domain split.** ADR-0006 keeps fraud in-domain partly because fail-open already isolates its blast radius. Construct the incident where fail-open gives you the *same* protection a separate Risk domain would — and the incident where it does **not**, so you'd finally split.
