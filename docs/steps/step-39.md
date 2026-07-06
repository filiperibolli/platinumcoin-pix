# Step 39 — Hardening pass

## Objective
A deliberate quality gate before E2E: verify guarded transitions everywhere (attempt illegal jumps in tests), error-contract audit (every non-2xx is problem+json with code+correlationId — scripted check), API versioning review (all public routes /v1, additive-change policy doc), security checklist executed, dependency/CVE scan, and the TLS/prod-posture doc section.

## Why / what you'll learn
Hardening as a *checklist discipline*, not vibes. Highlights: adversarial tests (try to regress SETTLED→SENT_TO_SPI via the internal API — must 409; try payload source-account injection — must be ignored; oversized/garbage inputs); a tiny contract-conformance script (hit every error path, assert shape); OWASP dependency-check or `mvn versions` + review; and writing the "what changes in production" section (TLS 1.2+ everywhere, RS256+JWKS, IAM instead of dummy creds, sharded clearing account, Redis pub/sub for SSE fan-out) — the honest bridge between local build and real deployment, and one of the strongest portfolio artifacts of the project.

## Prerequisites
All previous steps.

## Tasks
1. Adversarial IT pack: illegal transitions, ownership bypass attempts, injection of ignored fields, malformed cursors/idempotency keys, oversized description.
2. `scripts/contract-check.sh`: curl matrix of error paths asserting problem+json shape.
3. `docs/production-posture.md`: local-vs-prod deltas (auth, TLS, sharding, fan-out, IAM, Object Lock) with ADR cross-refs.
4. Fix everything found; zero known contract violations.

## Tests (TDD)
- The adversarial pack IS the test deliverable; must run in `mvn verify` of the touched modules.

## Verify locally
```bash
bash scripts/contract-check.sh          # all green
mvn -q verify                            # full suite green
```

## Definition of Done
- [ ] Illegal state transitions impossible via any exposed surface
- [ ] 100% of error responses contract-conformant (scripted proof)
- [ ] production-posture.md complete with ADR cross-references

## CHANGELOG entry
`### Added` → `Hardening: adversarial transition/ownership tests, contract-conformance script, production-posture doc (step 39)`
