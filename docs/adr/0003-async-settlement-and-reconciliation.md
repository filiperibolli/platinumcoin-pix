# ADR-0003: Asynchronous settlement + bounded reconciliation

**Status:** Accepted · **Date:** 2026-07-02

## Context
BACEN SPI settles in up to **10 seconds**; our send API must answer in **<2s p99**; stuck transactions must resolve in **<5 min**.

## Decision
1. The synchronous path ends at **`202 Accepted`** after the atomic debit (payer → clearing) and the transactional persist of `tx=DEBITED` + outbox event. The user is never waiting on SPI.
2. Settlement is a queue-driven consumer: outbox → SNS → `settlement-queue` → settlement-service → SPI call (timeout 12s).
3. **Retries**: SQS redelivery via visibility timeout, up to 5 attempts; **query-before-retry** — after a timeout, `GET /spi/settlements/{endToEndId}` first, because a timeout is not a failure (BACEN may have settled). `endToEndId` makes the POST idempotent either way.
4. **DLQ**: redrive policy after max receives; DLQ depth > 0 alerts.
5. **Reconciliation job** (every 60s): scan GSI2 for `status IN (DEBITED, SENT_TO_SPI)` older than 2 min → query SPI → finalize (SETTLED) or compensate (`debit clearing / credit payer`, status REVERSED, notify user). Age > 5 min raises an SLO-breach alert. This bounds "eventual" to the required 5 minutes.

## Consequences
- Two sources of truth momentarily disagree (us vs BACEN) — the reconciliation loop is the mechanism that forces convergence; it is not optional plumbing, it is part of the consistency design.
- Users see `PROCESSING` then a push notification; product must design for async UX (industry standard for Pix).
- Compensating reversal is a *new* posting, never an update/delete of history — the ledger stays append-only and auditable.
