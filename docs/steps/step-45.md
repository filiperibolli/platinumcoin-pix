# Step 45 — Hardening: transitions, error contract, versioning, security

> **Sprint 12 — Hardening, E2E & load** · **Flow:** quality gate · **Infra que sobe:** none new

## Objective
A deliberate quality gate before E2E: verify guarded transitions everywhere (attempt illegal jumps in tests), an error-contract audit (every non-2xx is problem+json with `code`+`correlationId` — scripted check), an API versioning review (all public routes `/v1`, additive-change policy documented), a security checklist executed, a dependency/CVE scan, and the TLS/prod-posture doc section.

## Why / what you'll learn
Hardening is a *distinct* activity from building — you go back over the whole surface with an adversarial eye. You'll script an error-contract audit (hit every error path, assert the shape) so the RFC 7807 promise is machine-checked, not hoped for; try illegal status jumps (SETTLED→SENT_TO_SPI) and confirm the guarded transitions reject them; and run the security checklist (JWT everywhere, debit-from-token, no stack traces, limits server-side, audit immutability). This is where "it works on the happy path" becomes "it holds under abuse".

## Prerequisites
All prior flows (Sprints 1–11).

## Tasks
1. Guarded-transition sweep: tests attempting every illegal transition on `pix_transactions`; assert rejection.
2. Error-contract audit script: exercise each documented non-2xx; assert problem+json with `code`+`correlationId`, no stack trace.
3. API versioning review: all public routes under `/v1`; document the additive-only policy and the `/v2`-side-by-side rule.
4. Security checklist (from `docs/threat-model.md`): execute and record results; dependency/CVE scan; TLS/prod-posture section confirmed.

## Tests (TDD)
- `GuardedTransitionIT` — illegal jumps rejected for each status.
- `ErrorContractIT`/script — every error path is problem+json with the required fields.

## Verify locally
```bash
bash scripts/error-contract-audit.sh   # all non-2xx are problem+json with code + correlationId
mvn -q verify                          # full suite green
```

## Definition of Done
- [ ] Illegal status transitions provably rejected everywhere
- [ ] Every non-2xx is problem+json with code + correlationId (scripted)
- [ ] Versioning + security checklist documented and executed

## CHANGELOG entry
`### Changed` → `Hardening: guarded-transition sweep, scripted error-contract audit, versioning review and security checklist (step 45)`
