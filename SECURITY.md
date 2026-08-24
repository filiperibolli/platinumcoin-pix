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
   Internally, **a user's token is never a service credential**: `/internal/**` accepts
   only a scoped service token and `/v1/**` only a user token (ADR-0017).
2. **No negative balance** — enforced by the `balanceCents >= :amount` condition
   *inside* the `TransactWriteItems`, never as a read-then-check.
3. **Atomic double-entry** — debit and credit commit together or not at all.
4. **Idempotency** — a retried money-moving POST never debits twice (ADR-0002).
5. **Append-only ledger** — history is never updated or deleted; corrections are
   compensating postings.
6. **Money is integer cents end-to-end** — no floating-point money.

The full attacker's-eye analysis of how these are attacked and defended lives in the
**[Threat Model](docs/threat-model.md)**.

## The trust model: two token types, two surfaces

Everything in this platform authenticates with an HS256 JWT signed by one shared secret (ADR-0007).
Since **step 68 (ADR-0017)** those tokens come in two kinds, distinguished by a `typ` claim, and each
kind is accepted on exactly one surface:

| | `typ=user` | `typ=service` |
|---|---|---|
| **Minted by** | auth-service, on `POST /v1/auth/login` | any calling service, per call, via common-lib's `ServiceTokenIssuer` |
| **Claims** | `sub` (userId), `accountId`, `jti`, `iat`, `exp` | `sub`/`iss` (calling service), `aud` (target service), `scope` (one operation), `jti`, `iat`, `exp` — **no `accountId`** |
| **Lifetime** | 15 min | 60s |
| **Accepted on** | `/v1/**` only | `/internal/**` only, and only when `aud` = the service called and `scope` = the scope that route declares |
| **Refused with** | `403 PUBLIC_ROUTE_FORBIDDEN` if presented internally | `403 INTERNAL_PORT_FORBIDDEN` if presented publicly, misaddressed, or misscoped |

The five scopes are one per internal operation: `ledger:post`, `ledger:read`, `fraud:score`,
`accounts:read`, `keys:resolve`. Each service declares its route→scope map in its own
`application.yml` under `jwt.internal-routes`, so a reviewer reads a service's entire internal attack
surface in one screen. **An internal route matching no declared scope is refused** — an unscoped port
is a configuration mistake, and the safe reading of a mistake on a money path is "no".

**Why this exists.** Before step 68, `/internal/**` required "a valid token" and nothing more, and
payment-service satisfied that by copying the end user's bearer header onto every outbound call. The
consequence was concrete: **a token issued to any user was a valid credential on
`POST /internal/ledger/postings`**, the platform's single money-moving operation, which names both
accounts explicitly and derives nothing from the token. Rule 1 above held at the public edge and was
structurally absent at the internal one. That is the classic confused deputy, and it is preserved as a
test (`InternalPortForbiddenIT#aUserTokenCannotPostALedgerEntry`) rather than as a paragraph.

**The end user still appears on internal calls — as evidence, never as authority.** Their id travels in
`X-PlatinumCoin-On-Behalf-Of` so a log line and an audit record can say *whose* payment caused a
posting. No service reads that header to make an access decision; the rule is enforced by a test that
fails the build if any service's main source ever branches on it.

**Boundary note.** mock-bacen-spi is outside the trust domain: it holds no PlatinumCoin token, and its
inbound webhook to settlement-service keeps its own shared-secret header (`SPI_WEBHOOK_TOKEN`). That
asymmetry is correct — an external participant authenticates as an external participant, and in
production that is mTLS with an ICP-Brasil certificate, not one of our JWTs.

**What production reverses.** The shared secret. Locally the identity is carried entirely by the
*claims*, verified with the same key by every service — which means anything holding the secret can
mint any identity (`scripts/service-token.sh` does exactly that, for the runbook). A deployment gives
each workload its own credential: RS256 with a per-service key and a JWKS endpoint, or mTLS with a
service mesh. Both were considered and rejected for this sandbox (ADR-0017, *Alternatives rejected*),
and the claim shape was chosen so that the swap changes only the signature verification.

## Security posture & deliberate gaps

> The table below is the **posture**. What was actually verified, when, and what it returned lives in
> [`docs/security-checklist.md`](docs/security-checklist.md) — executed 2026-08-24 in step 45, which
> found and fixed three real defects (the error contract escaping on four framework-generated statuses,
> and a `500` on the payee's own payment poll that made a real transaction id distinguishable from an
> unknown one).

This project makes some security trade-offs *on purpose* and documents them rather
than hiding them. These are **not** vulnerabilities to report — they are recorded
decisions:

| Area | Local posture | Production posture (documented) | Reference |
|------|---------------|----------------------------------|-----------|
| Token signing | HS256, shared secret via env var | RS256 + JWKS; services verify with public key only | ADR-0007 |
| Service-to-service identity | Scoped service tokens (`typ`/`iss`/`aud`/`scope`) signed with the **same** shared secret — the identity is in the claims, not in a per-service key | Per-workload credential: RS256 + JWKS, or mTLS via a service mesh; plus a gateway that blocks `/internal/**` from outside (perimeter *and* identity, not perimeter instead of identity) | ADR-0017 |
| MFA / step-up | Deferred; over-limit → `422 LIMIT_EXCEEDED` | `REQUIRE_STEP_UP` seam already in the limit decision object | ADR-0007 |
| Fraud on outage | **Fail-open**, flagged `FRAUD_SKIPPED` | Hybrid: fail-open below a value threshold, fail-closed/step-up above | ADR-0005 |
| Secrets | `.env` / compose env vars (git-ignored) | Secrets manager / KMS | — |
| Transport | Plain HTTP on localhost | TLS everywhere, mTLS between services | — |
| Rate limiting | Not implemented locally | Edge rate limiting + per-account throttles | — |
| AWS credentials | `test`/`test` static keys, reachable **only under the `local` Spring profile** and written in exactly one class (`common-lib`'s `LocalStackAwsOverride`) — a signing formality, not authentication: LocalStack validates no signature and only reads the key to derive the account id | No long-lived credential: the default build passes neither an endpoint nor a credentials provider, so the `DefaultCredentialsProvider` chain resolves the ambient ECS task role / EKS IRSA / EC2 instance profile, with STS credentials the SDK rotates | ADR-0013, swept in step 45 |
| IAM authorization | **Not enforced** — LocalStack emulates the IAM/STS *APIs* but authorizes everything by default (`ENFORCE_IAM` off, paid feature); the step-26 SQS resource policy is likewise accepted but unenforced | Least-privilege role per service, committed as [`infra/iam/<service>-policy.json`](infra/iam/) with concrete ARNs; payment-service holds **no** `sqs:*` and settlement-service **no** `sns:Publish`, which is what makes the outbox topology an authorization boundary | ADR-0013 |

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
