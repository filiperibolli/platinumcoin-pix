# Learning — ADR-0006 (Microservices decomposition) · finalized by Step 45

> **Type:** ADR learning companion (not an implementation step — no tasks/DoD of its own).
> **ADR:** [docs/adr/0006-microservices-decomposition.md](../adr/0006-microservices-decomposition.md) · **Concept finalized by:** [Step 45](step-45.md) (the hardening quality gate).
> **Why Step 45:** the eight services come online gradually across Sprints 1–11, but ADR-0006 is not "there are eight JVMs" — it's a set of **disciplines**: seams drawn on consistency/latency/scaling profiles, API/event-only communication, exactly two documented shared-table exceptions, a thin `common-lib`, guarded write surfaces. Those disciplines are only *verified* — adversarially, across the whole surface — in Step 45 (guarded-transition sweep, error-contract audit, versioning review, security checklist). That's where the decomposition is finalized as a property, not just a fact.

---

## 1. The decision, and the trade-off it resolves

The system could have been a **modular monolith** (legitimately simpler for a 3-month deadline). ADR-0006 chooses **eight deployables** to demonstrate the target-state design and independent failure domains, drawing boundaries where **consistency, latency and scaling profiles differ**:

| Service | Distinct profile that earns it a boundary |
|---|---|
| **ledger-service** | The only writer of money; smallest surface around the strongest invariants; scaled/hardened/audited independently |
| **fraud-service** | Strict latency budget; independently replaceable (rules → model) and failure-isolated (fail-open) |
| **settlement-service** | IO-bound on a slow external rail; scaling driven by **queue depth**, not user traffic |
| **notification-service** | Holds long-lived SSE connections — a different resource profile |
| **payment-service** | The orchestrator; owns the payment saga |
| **account / auth** | Identity & reference data, read-mostly |

The trade-off it resolves is **"simplicity of a monolith" vs. "independent failure domains + target-state fidelity."** The cost accepted: network hops, distributed debugging, eventual consistency between services, 8 JVMs locally. Mitigations: the transactional outbox (ADR-0004), `correlationId` everywhere, one-command `docker-compose`, 512MB heap caps.

The sharpest, most examinable part is the **anti-distributed-monolith discipline**: services **never share tables**, with **exactly two deliberate, documented exceptions** —
1. **settlement-service writes `pix_transactions` directly** (guarded status transitions + outbox puts), because the outbox guarantee (ADR-0004) requires the state change and the event to commit in *one* `TransactWriteItems`; an internal API between writer and table would reintroduce the dual-write problem. The write surface is constrained to guarded `ConditionExpression` transitions and outbox puts — never free-form updates.
2. **`pix_processed_events`** is one tiny shared dedup table (consumer-scoped keys) instead of N identical ones.

Everything else is API or events. That "two exceptions, and here's exactly why each is safe" is the difference between a decomposition and a distributed monolith.

---

## 2. Test the behavior (curl)

Prereq: the full compose stack up (Sprints 1–11), token in `$TOKEN`.

**(a) Independent edges — each capability answers on its own port (there is no gateway):**
```bash
curl -s localhost:8081/actuator/health   # auth-service
curl -s localhost:8082/actuator/health   # account-service
curl -s localhost:8085/actuator/health   # ledger-service
curl -s localhost:8086/actuator/health   # settlement-service
```

**(b) One `correlationId` reconstructs a path *across* the seams** — this is what makes distributed debugging tractable:
```bash
CID=$(curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@otherbank.com","amount":"15.00"}' | grep -i '^x-correlation-id' | tr -d '\r' | awk '{print $2}')
bash scripts/trace.sh "$CID"   # payment → ledger → outbox → settlement → notification, one id (step 44)
```

**(c) Failure isolation — stop fraud, payments still flow (fail-open); the boundary contains the blast radius:**
```bash
docker compose -f infra/docker-compose.yml stop fraud-service
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"5.00"}' | head -1   # still 202
docker compose -f infra/docker-compose.yml start fraud-service
```

**(d) The discipline, machine-checked** (Step 45's own gates):
```bash
bash scripts/error-contract-audit.sh   # every non-2xx is problem+json with code + correlationId
mvn -q verify                          # includes the guarded illegal-transition sweep (SETTLED→SENT_TO_SPI rejected)
```

---

## 3. Where to confirm it in the logs

| Signal | Log line | What it proves |
|---|---|---|
| Cross-service path | the same `correlationId` in `payment.accepted`, `ledger.posted`, `settlement.settled`, `notification.pushed` | The seams are traceable — one id, many services |
| Guarded shared-table write | settlement-service writing `pix_transactions` only via guarded transitions | Exception #1 stays constrained, not free-form |
| Shared dedup | `pix_processed_events` rows keyed `CONSUMER#<name>#EVT#<id>` | Exception #2 — one table, consumer-scoped |
| Blast-radius containment | fraud down → WARN "fraud skipped" but no error cascade in payment/ledger | The boundary + fail-open isolate failure |

```bash
docker compose -f infra/docker-compose.yml logs | grep '"correlationId":"'<CID>'"'
```

---

## 4. Code & infra references

| Concern | Where it lives (planned layout / conventions) |
|---|---|
| Thin shared code (error model, JWT, logging, envelopes) | `services/common-lib/...` — kept deliberately small (the anti-distributed-monolith lever) |
| Exception #1: settlement writes `pix_transactions` (guarded) | settlement-service (Steps 31–33) + [docs/data-model.md](../data-model.md) |
| Exception #2: shared `pix_processed_events` | `services/common-lib/...ProcessedEventStore` (Step 29) |
| Guarded-transition sweep + error-contract audit | Step 45 (`GuardedTransitionIT`, `scripts/error-contract-audit.sh`) |
| Per-service ports & responsibilities | [ARCHITECTURE.md §2, §3](../../ARCHITECTURE.md) |
| Correlation-id contract | `CorrelationIdFilter` in common-lib (Step 02); `scripts/trace.sh` (Step 44) |

---

## 5. Questions to fix the learning (staff level — synthesis, not recall)

1. **Two exceptions, not zero.** ADR-0006 says services never share tables — then documents two that do. Argue why "zero exceptions, purely via APIs/events" would be *worse* here, using the dual-write problem for exception #1 specifically. What review rule keeps exceptions from multiplying?
2. **Monolith counterfactual.** For a real 3-month deadline, sketch the modular-monolith version of this system. Name the two things you'd *lose* that matter most, and the two you'd *gain* — and say honestly which you'd ship if this were a job, not a portfolio.
3. **Seams by profile.** The boundaries follow consistency/latency/scaling profiles. Pick two services and describe how they'd scale *differently* under the Black Friday ramp — and how a wrong boundary (e.g., folding settlement into payment) would break that.
4. **The distributed-monolith smell.** Give three concrete code/PR-review signals that this decomposition is *drifting* into a distributed monolith, and the earliest cheap intervention for each.
5. **Split readiness.** ADR-0006 claims any capability can graduate to its own domain later "without rewriting the orchestrator." Take fraud → a Risk domain and prove (or disprove) that claim by naming exactly what changes at the payment-service call site and what does not. Cross-reference the data-split discussion in README §"Why one domain".
