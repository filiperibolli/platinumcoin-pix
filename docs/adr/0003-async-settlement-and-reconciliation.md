# ADR-0003: Asynchronous settlement + bounded reconciliation

**Status:** Accepted · **Date:** 2026-07-02 · **Amended by [ADR-0016](0016-finalization-fencing-settle-xor-reverse.md)** (2026-08-23, step 67)

> **Amendment pointer.** Decision 5 below gives reconciliation the power to finalize a transaction the
> queue consumer may be finalizing at the same instant — and the two reach *opposite* endings under
> *different* posting identities (`-rel` vs `-rev`), so posting idempotency does not relate them. Because
> the guarded status transition ran *after* the ledger call, a settle racing a reverse drew the clearing
> account down twice against one credit: money created.
> [ADR-0016](0016-finalization-fencing-settle-xor-reverse.md) adds two **non-terminal** states —
> `FINALIZING_SETTLEMENT` and `FINALIZING_REVERSAL` — and makes a conditional transition into one of them
> the precondition for posting anything. Neither is a legal source for the other, so settle and reverse
> became mutually exclusive by condition expression. The status list in decision 5 is therefore
> `status IN (DEBITED, SENT_TO_SPI, FINALIZING_SETTLEMENT, FINALIZING_REVERSAL)`, and a transaction found
> stalled in a fencing state is **completed in the direction it was fenced**, never flipped. The 60s scan,
> the < 5-min bound and the compensating-posting rule below are unchanged.

## Context
BACEN SPI settles in up to **10 seconds**; our send API must answer in **<2s p99**; stuck transactions must resolve in **<5 min**.

## Decision
1. The synchronous path ends at **`202 Accepted`** after the atomic debit (payer → clearing) and the transactional persist of `tx=DEBITED` + outbox event. The user is never waiting on SPI.
2. Settlement is a queue-driven consumer: outbox → SNS → `settlement-queue` → settlement-service → SPI call (timeout 12s).
3. **Retries**: SQS redelivery via visibility timeout, up to 5 attempts; **query-before-retry** — after a timeout, `GET /spi/settlements/{endToEndId}` first, because a timeout is not a failure (BACEN may have settled). `endToEndId` makes the POST idempotent either way.
4. **DLQ**: redrive policy after max receives; DLQ depth > 0 alerts.
5. **Reconciliation job** (every 60s): scan GSI2 for `status IN (DEBITED, SENT_TO_SPI)` — since step 67 also the two `FINALIZING_*` fences (ADR-0016) — older than 2 min → query SPI → finalize (SETTLED) or compensate (`debit clearing / credit payer`, status REVERSED, notify user). Age > 5 min raises an SLO-breach alert. This bounds "eventual" to the required 5 minutes.

## Consequences
- Two sources of truth momentarily disagree (us vs BACEN) — the reconciliation loop is the mechanism that forces convergence; it is not optional plumbing, it is part of the consistency design.
- Users see `PROCESSING` then a push notification; product must design for async UX (industry standard for Pix).
- Compensating reversal is a *new* posting, never an update/delete of history — the ledger stays append-only and auditable.
- Two finalizers racing is a *designed-in* consequence of decision 5, not an accident, so exclusivity has to be a mechanism rather than a hope — see ADR-0016.
