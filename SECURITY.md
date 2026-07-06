# Security Policy

PlatinumCoin is a **local-only learning and portfolio project**. It runs entirely
on a developer machine against emulated infrastructure (LocalStack) and a **mock**
BACEN SPI. It processes **no real money, no real Pix keys and no production personal
data**. There is no deployed environment and no real users to put at risk.

That framing is deliberate and it shapes the rest of this document: the goal here is
to demonstrate *security engineering discipline* — threat modelling, deliberate
trade-offs, and honest documentation of gaps — not to defend a live system.

## Supported versions

The project is **pre-1.0 and under active, step-by-step development** (see
[`PLAN.md`](PLAN.md)). Only the `main` branch is maintained. There are no released
versions and no backports.

| Version | Supported |
|---------|-----------|
| `main`  | ✅ (moving target) |
| tags / releases | none yet |

## Reporting a vulnerability

Even though this is a learning project, responsible-disclosure hygiene is part of the
exercise. If you spot a security defect — especially one that breaks a **money
invariant** (see below) or the **authorization model**:

1. **Do not open a public issue** for anything that could be a real weakness in a
   pattern others might copy.
2. Email the maintainer (see the profile of [@filiperibolli](https://github.com/filiperibolli)),
   or open a **GitHub Security Advisory** (Security → Advisories → *Report a vulnerability*).
3. Include: the invariant or control you believe is broken, a minimal reproduction
   (ideally a failing test), and the affected `docs/steps/step-XX.md` if applicable.

Expected response time is best-effort — this is a personal project — but reproducible
reports that break a documented invariant will be prioritised, because those are
exactly the bugs the test suite exists to prevent.

## What "a security bug" means here

The security-critical properties of this platform are enumerated as **domain safety
rules** in [`CLAUDE.md`](CLAUDE.md) and as invariants in
[`docs/data-model.md`](docs/data-model.md). A break in any of these is a security bug,
not merely a functional one:

1. **Authorization** — the debited account is derived **only** from the JWT
   `accountId` claim; the request body cannot express a source account (ADR-0007).
2. **No negative balance** — enforced by the `balanceCents >= :amount` condition
   *inside* the `TransactWriteItems`, never as a read-then-check.
3. **Atomic double-entry** — debit and credit commit together or not at all.
4. **Idempotency** — a retried money-moving POST never debits twice (ADR-0002).
5. **Append-only ledger** — history is never updated or deleted; corrections are
   compensating postings.
6. **Money is integer cents end-to-end** — no floating-point money.

The full attacker's-eye analysis of how these are attacked and defended lives in the
**[Threat Model](docs/threat-model.md)**.

## Security posture & deliberate gaps

This project makes some security trade-offs *on purpose* and documents them rather
than hiding them. These are **not** vulnerabilities to report — they are recorded
decisions:

| Area | Local posture | Production posture (documented) | Reference |
|------|---------------|----------------------------------|-----------|
| Token signing | HS256, shared secret via env var | RS256 + JWKS; services verify with public key only | ADR-0007 |
| MFA / step-up | Deferred; over-limit → `422 LIMIT_EXCEEDED` | `REQUIRE_STEP_UP` seam already in the limit decision object | ADR-0007 |
| Fraud on outage | **Fail-open**, flagged `FRAUD_SKIPPED` | Hybrid: fail-open below a value threshold, fail-closed/step-up above | ADR-0005 |
| Secrets | `.env` / compose env vars (git-ignored) | Secrets manager / KMS | — |
| Transport | Plain HTTP on localhost | TLS everywhere, mTLS between services | — |
| Rate limiting | Not implemented locally | Edge rate limiting + per-account throttles | — |

## Secure-development practices in this repo

- **Secrets never committed**: `.env`, `*.pem`, `*.key` are git-ignored; seeded demo
  credentials are non-secret by design.
- **Least-privilege authorization by construction**: the source account is
  *inexpressible* in the API payload, so it cannot be tampered with.
- **Tests as security guardrails**: every money invariant has an explicit test
  (the step-15 invariant storm suite); AI-generated code is human-reviewed before
  acceptance (`CLAUDE.md`).
- **Immutable audit trail**: settlement writes an append-only audit record to S3.
- **Dependency hygiene**: dependencies are pinned via the Maven parent POM; CI runs
  the full test suite on every change.
