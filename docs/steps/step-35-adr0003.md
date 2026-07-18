# Learning — ADR-0003 (Async settlement + bounded reconciliation) · finalized by Step 35

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0003-async-settlement-and-reconciliation.md](../adr/0003-async-settlement-and-reconciliation.md) · **Concept finalized by:** [Step 35](step-35.md) (reconciliation resolution + the `<5-min` SLO alert).
> **Why Step 35:** the ADR has two halves — the **async accept** (`202` after the atomic debit; Steps 27–31) and the **bounded convergence** (retries/DLQ + a reconciliation loop that closes every stuck transaction inside 5 minutes; Steps 32–35). The concept is only *finalized* when "eventual" is provably **bounded**: that is Step 35, where the resolver finalizes-or-reverses stuck transactions and the `reconciliation.oldest.seconds > 300` alert guards the SLO.

---

## 1. The decision, and the trade-off it resolves

BACEN's SPI settles in up to **10 seconds**, but the send API must answer in **<2s p99**, and no transaction may stay stuck longer than **5 minutes**. ADR-0003 resolves the trade-off between **UX latency** and **rail latency** by *decoupling* them:

1. The synchronous path ends at **`202 Accepted`** right after the atomic debit (payer → clearing) and the transactional persist of `tx=DEBITED` + outbox event. **The user never waits on SPI.**
2. Settlement is a queue-driven consumer (`outbox → SNS → settlement-queue → settlement-service → SPI`, timeout 12s).
3. **Retries** via SQS visibility-timeout redelivery (≤5 attempts) with **query-before-retry** — a timeout is *not* a failure, BACEN may have settled, so `GET /spi/settlements/{endToEndId}` runs first; `endToEndId` makes the `POST` idempotent either way.
4. **DLQ** after max receives (alerts on depth > 0).
5. A **reconciliation job** (every 60s) scans GSI2 for `status IN (DEBITED, SENT_TO_SPI)` older than 2 min → queries SPI → **finalizes** (SETTLED) or **compensates** (`debit clearing / credit payer`, status REVERSED, notify). Age > 5 min raises the SLO-breach alert.

The subtle, non-obvious cost the ADR makes explicit: **two sources of truth (us and BACEN) momentarily disagree**, and the reconciliation loop is not optional plumbing — *it is the consistency mechanism* that forces convergence. The compensating reversal is a **new posting, never an update/delete**, so the ledger stays append-only and auditable even on the failure branch.

---

## 2. Test the behavior (curl)

Prereq: Sprint 7 up (`settlement-service` :8086, `mock-bacen-spi` :9090), token in `$TOKEN`.

**(a) The user gets `202` in well under 2s — not blocked on a 10s rail:**
```bash
curl -s -X POST localhost:9090/admin/config -d '{"latencyMs":9000,"failureRate":0.0}' -H 'Content-Type: application/json'
time curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"25.00"}' | head -1   # 202 fast, even with a 9s BACEN
```

**(b) Settlement converges asynchronously — poll status PROCESSING → SETTLED:**
```bash
TX=<transactionId from (a)>
watch -n1 "curl -s localhost:8084/v1/payments/$TX -H 'Authorization: Bearer $TOKEN' | jq .status"
```

**(c) Bounded reconciliation — break BACEN, watch stuck tx resolve/reverse inside 5 min:**
```bash
curl -s -X POST localhost:9090/admin/config -d '{"failureRate":1.0}' -H 'Content-Type: application/json'
# send an external pix; it goes DEBITED and gets stuck. Then restore BACEN or let reconciliation reverse it:
docker compose -f infra/docker-compose.yml logs -f settlement-service | grep -E 'reconciliation.resolved|"ALERT"'
```

**(d) Query-before-retry safety — a timeout that actually settled must not double-settle** (inspect the SPI store by `endToEndId`):
```bash
curl -s localhost:9090/spi/settlements/<endToEndId> | jq   # SETTLED at BACEN even though our POST timed out
```

---

## 3. Where to confirm it in the logs

| Signal | Log line | What it proves |
|---|---|---|
| Async accept | `payment.accepted` (fast) then later `settlement.sent` / `settlement.settled` | The `202` is decoupled from the SPI call |
| Query-before-retry | WARN retry with a preceding `GET /spi/settlements/{e2e}` | A timeout isn't treated as failure — no blind re-POST |
| DLQ redrive | `settlement.dlq.depth` gauge > 0 + a firing DLQ alert | Message flagged, not lost |
| Reconciliation | `"event":"reconciliation.resolved"` with `action=settled|reversed` | The loop forced convergence |
| SLO guard | `"ALERT"` when `reconciliation.oldest.seconds > 300`, then RESOLVED | The 5-min bound is watched, not hoped for |

```bash
docker compose -f infra/docker-compose.yml logs settlement-service | grep -E 'reconciliation.resolved|settlement.settled|"ALERT"'
bash scripts/trace.sh <correlationId>   # accept → settle/reverse across services (step 44)
```

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Atomic debit → clearing + `tx=DEBITED` + outbox | payment-service external orchestration (Step 27–28) |
| SNS + `settlement-queue` (+DLQ, redrive, filter) | `infra/localstack/init/*` (Step 26) |
| Consumer + happy-path settle | `services/settlement-service/...` (Step 31) |
| Retries + query-before-retry + DLQ | settlement consumer (Step 32) |
| Finalization: `CLEARING_RELEASE` / compensating reversal | settlement finalization (Step 33) — append-only |
| Stuck-tx scanner (GSI2 status+age, 60s) | reconciliation scanner (Step 34) |
| Resolver + `reconciliation.oldest.seconds > 300` alert | `ReconciliationResolver` (Step 35) |
| Mock rail (latency/failure/timeout, status, admin) | `services/mock-bacen-spi/...` (Step 30) |
| Narrative | [ARCHITECTURE.md §6.6, §6.7, §7.2](../../ARCHITECTURE.md) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Why 2 minutes to scan, 5 minutes to alert?** Derive both numbers from the 10s SPI SLA and the 60s scan cadence. What goes wrong if the scan threshold is *below* the SPI worst case? What goes wrong if the alert threshold equals the scan threshold?
2. **Reconciliation as consistency, not cleanup.** The ADR insists the loop is part of the consistency design, not optional plumbing. Construct the exact data-loss/duplication outcome you'd get if you *shipped without it* but kept everything else (queue, retries, DLQ).
3. **Query-before-retry, formally.** Given `endToEndId` already makes the POST idempotent at BACEN, explain what the pre-retry `GET` buys you that idempotency alone does not. Then find the one case where the `GET` itself can mislead you, and how you'd guard it.
4. **Reversal vs. update.** The failure branch compensates with a *new* posting. A teammate proposes "just set status back and refund by updating the balance" as simpler. Give the two concrete audit/consistency failures that proposal causes, referencing the append-only rule.
5. **Who owns the DLQ?** A message in the DLQ is "flagged, not lost." Describe the full lifecycle of one DLQ message from arrival to resolution — which component acts, whether the reconciliation loop and a DLQ redrive can race, and why that race is safe here.
