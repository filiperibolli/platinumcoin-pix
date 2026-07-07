# The Brief — PlatinumCoin Pix Platform (exercise statement)

> This file states the exercise brief the whole repository answers, so the design in
> [`ARCHITECTURE.md`](../ARCHITECTURE.md) can be judged against the questions it was asked — verbatim,
> in-repo, not paraphrased. Referenced throughout the docs as "the brief".

## The problem

PlatinumCoin, a Brazilian fintech, needs its **Pix instant-payment infrastructure built from a blank
page**. You are the engineer responsible for designing and building it.

## Functional requirements

Core (mandatory):

1. **Send Pix** to any Pix key — CPF, e-mail, phone or random key (EVP) — at any bank.
2. **Pix key management**: register, list and delete keys, with global uniqueness.
3. **Configurable daily limits** per account; transactions **above the daily limit require MFA**.

Plus **three optional features, chosen for this build**:

4. **Receive Pix** from any bank, with **real-time notification** to the user.
5. **Balance** (real-time) and **paginated statement**.
6. **Real-time fraud detection** on outgoing payments.

> Deferred/adapted in this local build (documented, not hidden): MFA above the limit is replaced by a
> hard rejection with an explicit step-up seam (ADR-0007). Out of scope per the brief: scheduled Pix,
> dynamic QR (Pix Cobrança), automatic refunds, other payment rails, KYC/onboarding.

## Non-functional requirements

| Category | Requirement |
|---|---|
| Performance | Send request → `202 Accepted` in **< 2s p99** |
| Performance | Balance read **< 300ms p99** |
| Performance | ~**58 TPS** average, **500+ TPS** peak (e.g. Black Friday) |
| Reliability | **99.99%** availability (< 52 min downtime/yr) |
| Reliability | Ledger with **strong consistency**: debit and credit atomic — no double-spend, no negative balance |
| Reliability | **Zero loss** of confirmed transactions |
| Reliability | **Idempotency** guaranteed under client retries |
| Reliability | Stuck transactions reconciled in **< 5 minutes** |
| Security | JWT authentication; the **debited account comes from the token, never from the payload** |
| Security | Fraud scoring adds **at most 200ms** to the payment flow |
| Security | **Immutable audit trail**, retained ≥ 5 years (BACEN) |
| Security | TLS 1.2+ in transit |
| Scalability | Horizontal scaling with no manual intervention; 5 years of data online, then cold storage |
| Compatibility | API versioning — no breaking changes for mobile clients in the field |
| Observability | SLOs monitored; alert before users notice |

## Constraints & context

- Pix is operated by BACEN through the **SPI**; participants integrate via API and must respect a
  **10-second settlement SLA** in business hours.
- Reference scale: **50M users, 5M transactions/day**.
- Project constraint (self-imposed): everything runs **100% locally** — LocalStack emulating AWS,
  docker-compose, no Kubernetes; BACEN SPI is a configurable mock.

## The seven design questions (verbatim)

1. Which components make up the platform, and how does a Pix send flow through them end to end?
2. How do you guarantee that money is never debited from the payer without being credited to the
   receiver — what is the consistency mechanism?
3. Which database do you choose for the ledger and the transaction history, and why?
4. The SPI can take up to 10 seconds to settle. Does the user wait? How does the API behave?
5. How do you add real-time fraud detection without adding more than 200ms to the payment flow?
6. Which REST endpoints does the platform expose, and how do you guarantee idempotency on
   money-moving operations?
7. How do you reach 99.99% availability — and what exactly happens if the ledger is down for
   30 seconds?

Every question is answered inline in [`ARCHITECTURE.md`](../ARCHITECTURE.md) and indexed in its §10.
