# ADR-0006: Decomposition into domain microservices

**Status:** Accepted · **Date:** 2026-07-02

## Context
The system could be a modular monolith (legitimately simpler for a 3-month deadline) or microservices. The project's goals include demonstrating the target-state design of a payments platform and independent failure domains.

## Decision
Eight deployables: auth-service, account-service, payment-service, ledger-service, settlement-service, fraud-service, notification-service, mock-bacen-spi. Boundaries follow seams where **consistency, latency and scaling profiles differ**:
- **ledger-service**: the only writer of money; smallest possible surface around the strongest invariants; can be scaled/hardened/audited independently.
- **fraud-service**: strict latency profile; independently replaceable (rules → model) and failure-isolated (fail-open).
- **settlement-service**: IO-bound on a slow external system; queue-driven; scaling driven by queue depth, not user traffic.
- **notification-service**: holds long-lived SSE connections — a different resource profile from request/response services.
- **payment-service**: the orchestrator; owns the saga of a payment.
- **account-service / auth-service**: identity and reference data, read-mostly.

## Consequences
- Cost accepted: network hops, distributed debugging, eventual consistency between services, 8 JVMs locally. Mitigations: outbox pattern, correlation ids everywhere, docker-compose one-command startup, 512MB heap caps to fit 32GB RAM.
- Shared code via a thin `common-lib` (error model, JWT validation, logging, event envelopes) — kept deliberately small to avoid the distributed-monolith trap.
- Services never share tables, with **two deliberate, documented exceptions**:
  1. **settlement-service writes `pix_transactions` directly** (guarded status transitions + outbox items), even though payment-service owns the table. The transactional-outbox guarantee (ADR-0004) requires the state change and the event to commit in **one** `TransactWriteItems`; putting an internal API between the writer and the table would reintroduce exactly the dual-write problem the outbox exists to eliminate. The write surface is constrained: only guarded `ConditionExpression` transitions and outbox puts, never free-form updates.
  2. **`pix_processed_events`** is a shared dedup table with consumer-scoped keys (`CONSUMER#<name>#EVT#<id>`) — one tiny table instead of N identical ones.

  All other cross-service access is API or events.
