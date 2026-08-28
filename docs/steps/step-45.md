# Step 45 — Hardening: transitions, error contract, versioning, security

> **Sprint 12 — Hardening, E2E & load** · **Flow:** quality gate · **Infra que sobe:** none new

## Objective
A deliberate quality gate before E2E: verify guarded transitions everywhere (attempt illegal jumps in tests), an error-contract audit (every non-2xx is problem+json with `code`+`correlationId` — scripted check), an API versioning review (all public routes `/v1`, additive-change policy documented), a security checklist executed, a dependency/CVE scan, and the TLS/prod-posture doc section.

## Why this step exists
Hardening is a *distinct* activity from building — you go back over the whole surface with an adversarial eye. You'll script an error-contract audit (hit every error path, assert the shape) so the RFC 7807 promise is machine-checked, not hoped for; try illegal status jumps (SETTLED→SENT_TO_SPI) and confirm the guarded transitions reject them; and run the security checklist (JWT everywhere, debit-from-token, no stack traces, limits server-side, audit immutability). This is where "it works on the happy path" becomes "it holds under abuse".

## Prerequisites
All prior flows (Sprints 1–11).

## Tasks
1. Guarded-transition sweep: tests attempting every illegal transition on `pix_transactions`; assert rejection.
2. Error-contract audit script: exercise each documented non-2xx; assert problem+json with `code`+`correlationId`, no stack trace.
3. API versioning review: all public routes under `/v1`; document the additive-only policy and the `/v2`-side-by-side rule.
4. Security checklist (from `docs/threat-model.md`): execute and record results; dependency/CVE scan; TLS/prod-posture section confirmed.
5. **AWS credential & IAM posture sweep (ADR-0013).** Every AWS client in every service at once — never
   service by service, which would leave two competing shapes mid-migration:
   - move the static credentials + `endpointOverride` behind a `local` Spring profile in each service's
     AWS-client configuration class; the default build passes **neither**, so the SDK's
     `DefaultCredentialsProvider` chain resolves the ambient role (ECS task role / EKS IRSA / EC2
     instance profile) and no long-lived key/secret exists on the production path;
   - add `infra/iam/<service>-policy.json` — least-privilege, concrete ARNs and conditions (e.g.
     payment-service: `sns:Publish` on the `pix-events` ARN and **no** SQS permission; settlement-service:
     `sqs:ReceiveMessage`/`DeleteMessage`/`ChangeMessageVisibility` on its queue and **no** SNS permission);
   - record in the checklist that neither these policies nor the step-26 queue resource policy are
     enforced by LocalStack (`ENFORCE_IAM` is off by default and gated as a paid feature) — they carry
     production semantics and are reviewed as documents, not proven by any test.

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
- [ ] No AWS client carries static credentials outside the `local` profile; per-service least-privilege
      IAM policies committed under `infra/iam/` (ADR-0013)

## CHANGELOG entry
`### Changed` → `Hardening: guarded-transition sweep, scripted error-contract audit, versioning review and security checklist (step 45)`
