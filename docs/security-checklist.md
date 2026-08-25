# Security checklist — executed

> **Executed:** 2026-08-24, step 45 (Sprint 12 hardening gate) · **Against:** `main` at
> `37832eb` + the step-45 branch · **Companion docs:** [`docs/threat-model.md`](threat-model.md)
> (what an attacker wants), [`SECURITY.md`](../SECURITY.md) (posture & deliberate gaps),
> [ADR-0007](adr/0007-jwt-hs256-local-rs256-production.md), [ADR-0012](adr/0012-verbose-logs-with-real-values.md),
> [ADR-0013](adr/0013-aws-credentials-and-iam-posture.md), [ADR-0017](adr/0017-workload-identity-for-internal-ports.md)

## How to read this

The threat model says what could go wrong. This says **what was checked, how, and what the check
returned** — on a date, against a commit. An item is only ✅ when something *executed* proved it: a
test, a script, or a command whose output is quoted. Where the answer is "cannot be proven here", the
row says so and says why; a checklist whose every row is green is a checklist that was written rather
than run.

Re-run the whole thing with:

```bash
mvn verify                              # every item marked "test"
bash scripts/error-contract-audit.sh    # items 5-6, against the running compose stack
```

---

## 1. Identity & authentication

| # | Control | Verified by | Result |
|---|---|---|---|
| 1.1 | Every customer route requires a valid JWT; no token ⇒ `401 UNAUTHORIZED` | `ErrorContractIT` probes 1–2; audit script §Authentication | ✅ |
| 1.2 | A malformed / unsigned / expired token is refused, never partially trusted | `JwtAuthFilterTest`, `PublicRouteIT` | ✅ |
| 1.3 | Only `/v1/auth/login` and the BACEN webhook are public paths | `JwtAuthProperties` defaults + `PublicRouteIT` | ✅ |
| 1.4 | The BACEN webhook is authenticated by its own shared token; missing ⇒ `401 WEBHOOK_UNAUTHORIZED` and **nothing is credited** | `InboundWebhookAuthIT`; audit script §settlement | ✅ |
| 1.5 | Passwords are bcrypt-hashed; the hash never leaves the service and is never logged | `LoginIT`; ADR-0012 forbids logging it | ✅ |
| 1.6 | Signing is HS256 with a shared secret — a **deliberate local-only** posture | ADR-0007; production path is RS256 + JWKS | ⚠️ documented gap |

## 2. Authorization

| # | Control | Verified by | Result |
|---|---|---|---|
| 2.1 | **The debited account comes from the JWT `accountId`, never the payload** — the request body has no source-account field at all | `SendPixRequest` has no such component; `AccountControllerIT`, `SendSkeletonIT` | ✅ |
| 2.2 | A user token on `/internal/**` ⇒ `403 INTERNAL_PORT_FORBIDDEN` | `InternalPortMatrixIT` (account, ledger), `LateralAccessIT` | ✅ |
| 2.3 | A service token on `/v1/**` ⇒ `403 PUBLIC_ROUTE_FORBIDDEN` | `InternalPortMatrixIT` | ✅ |
| 2.4 | A service token addressed to another service (`aud`) or scoped for another operation (`scope`) ⇒ `403` | `InternalPortMatrixIT`, both rows | ✅ |
| 2.5 | Scopes are one per operation — `ledger:read` cannot post, `keys:resolve` cannot read an account | `InternalApi`; `InternalPortMatrixIT#theWrongScopeIsForbidden` | ✅ |
| 2.6 | Reading another account's payment ⇒ `404`, **identical** to an unknown id (no existence leak) | `StatusQueryIT`, `InboundPaymentStatusIT` | ✅ |
| 2.7 | Deleting another account's Pix key ⇒ `403 KEY_FORBIDDEN`; a key nobody owns ⇒ `404 KEY_NOT_FOUND` | `PixKeyControllerIT` | ✅ |
| 2.8 | Ownership of an inbound payment follows the **direction**, not "debtor or creditor" — the payee of an internal *send* gains nothing | `Transaction.ownerAccountId()`; `InboundPaymentStatusIT` | ✅ (**fixed in this step** — see item 6.3) |

## 3. Money invariants

Full detail in the step-15 invariant suite and the step-69 recovery suite; repeated here because a
security review that skips the money is not one.

| # | Control | Verified by | Result |
|---|---|---|---|
| 3.1 | Never a negative balance — `balanceCents >= :amount` lives **inside** `TransactWriteItems`, never as a read-then-check | `LedgerInvariantsIT` (concurrent-storm suite, step 15) | ✅ |
| 3.2 | Debit and credit are one atomic transaction; no path writes one leg | `LedgerInvariantsIT` | ✅ |
| 3.3 | Ledger is append-only; corrections are compensating postings | `ReversalIT`; no update/delete path exists on `pix_ledger` | ✅ |
| 3.4 | `Idempotency-Key` required on money-moving POSTs; replay returns the first result | `IdempotencyIT` | ✅ |
| 3.5 | Every event consumer dedupes by `eventId`, and **releases** the claim on failure | `ProcessedEventStoreIT`, `SettlementRetryIT` | ✅ |
| 3.6 | Settle XOR reverse: no transaction can be both | `GuardedTransitionIT#neitherFenceAcceptsTheOtherAsASource`, `FencingInvariantsIT` | ✅ |
| 3.7 | **Every illegal status transition is refused by the database, not by ordering** | `GuardedTransitionIT` — 40-cell matrix, **new in this step** | ✅ |

## 4. Input handling

| # | Control | Verified by | Result |
|---|---|---|---|
| 4.1 | Money is integer cents end to end; decimal only at the `api/` edge | `MoneyTest`, `PaymentResponseTest` | ✅ |
| 4.2 | Amount is validated server-side (positive, within the daily limit) — never trusted from the client | `DailyLimitIT`, `SendPixUseCaseTest` | ✅ |
| 4.3 | Daily limits are enforced by a reservation the ledger transaction owns, not by a read | `DailyLimitIT` (leak-free under concurrency) | ✅ |
| 4.4 | Bean validation on every request body; failures ⇒ `400 VALIDATION_ERROR` | `ErrorContractIT` probe 4 | ✅ |
| 4.5 | Pix-key values are normalized before storage, and raw + stored are both logged | `PixKeyControllerIT`; ADR-0012 | ✅ |
| 4.6 | No SQL — DynamoDB expression attributes are parameterised by construction, so injection has no surface | design; `TABLE`/`#status` are constants, never concatenated user input | ✅ |

## 5. Error contract & information disclosure

| # | Control | Verified by | Result |
|---|---|---|---|
| 5.1 | Every non-2xx is `application/problem+json` | `ErrorContractIT` (10 probes), audit script (**24 probes, 7 services, PASS on 2026-08-24**) | ✅ |
| 5.2 | Every non-2xx carries a stable `code` | same | ✅ (**fixed in this step** — see 6.1) |
| 5.3 | Every non-2xx carries a `correlationId`, equal to the `X-Correlation-Id` header | same | ✅ (**fixed in this step**) |
| 5.4 | **No stack trace, internal type or package name reaches a client** — a 500 is a generic `INTERNAL_ERROR` | `GlobalExceptionHandler#handleUnexpected`; asserted on every probe in both harnesses | ✅ |
| 5.5 | Not-found and not-yours are indistinguishable on the payment endpoint | `StatusQueryIT`, `InboundPaymentStatusIT` | ✅ |
| 5.6 | A JWT is never logged — the *claims* are, the token is not | ADR-0012; `JwtAuthFilter` logs `userId`/`accountId` only | ✅ |
| 5.7 | PII (Pix keys, CPFs, e-mails, amounts) **is** logged in the clear | ADR-0012 — a deliberate sandbox trade-off, reversed in production | ⚠️ documented gap |

## 6. What this execution actually found

Four items were **not** green when the sweep started. Three were fixed in this step; the fourth is
recorded and deferred, with the reason. They are listed here rather than quietly absorbed, because a
hardening gate that finds nothing usually means the gate was aimed at what already worked.

Three further probes failed on first run because **the expectation was wrong, not the platform** —
`INVALID_PIX_KEY` is a `422` (the body parsed; a business rule failed), a zero amount answers the
domain's `INVALID_AMOUNT` rather than the generic `VALIDATION_ERROR`, and the webhook path is
`/v1/inbound/pix`. Each was corrected in the script. Worth recording: on a first audit, a red probe is
about as likely to be a wrong assumption as a real defect, and the difference is decided by reading the
response, not by trusting the checklist.

1. **The four framework rejections escaped the error contract.** Unknown route (404), wrong method
   (405), unsupported media type (415) and unparseable body (400) returned Spring's bare
   `ProblemDetail`: right status, right content type, and **neither** `code` nor `correlationId`. They
   happen before any controller runs, so no application-layer code was in a position to stamp them. A
   client branching on `code` read `null`; a support ticket arrived with no id to grep. Fixed once in
   `GlobalExceptionHandler#handleExceptionInternal` — one file in common-lib, auto-configured into all
   eight services.
2. **The audit had no scripted form.** `scripts/error-contract-audit.sh` now exists: the outer half of
   the same four assertions, run against the compose stack, reaching the six services' own domain codes
   that no single-module test can.
3. **`GET /v1/payments/in-<endToEndId>` answered 500** — the poll the payee's own `PixReceived`
   notification points them at, and which ARCHITECTURE §6.8 makes *authoritative*. An inbound
   transaction carries no `debtorAccountId` and no `description`, and payment-service read both
   unguarded. The security consequence is sharper than the outage: an unknown id answered `404` and a
   real inbound id answered `500`, so **the two were distinguishable** — the existence leak the uniform
   404 exists to prevent. Fixed with `TransactionDirection` and `Transaction.ownerAccountId()`.

4. **On the inbound webhook, bean validation runs before the shared-token check.** `POST
   /v1/inbound/pix` with an empty body answers `400 VALIDATION_ERROR`; only a *well-formed* body reaches
   the `X-Webhook-Token` guard and gets `401 WEBHOOK_UNAUTHORIZED`. So an unauthenticated caller can
   probe the request schema of a money-crediting route — learning which fields exist and which are
   required — without holding the token.

   **Severity: low, and it is not an authentication bypass.** Nothing is resolved, credited or persisted
   on that path; the guard still refuses every actual delivery, which `InboundWebhookAuthIT` asserts.
   What leaks is a schema that is also published in `docs/api/openapi.yaml`.

   **Recorded, not fixed here.** The correct fix is ordering — authenticate before you validate, by
   moving the token check into a filter ahead of argument resolution, the way the JWT filter already
   sits ahead of every other route. That is a behavioural change to a route that credits money, and it
   belongs in a step that can test it properly rather than in the gate that found it. The audit script's
   probe sends a valid body and is commented as to why, so the finding cannot be lost by a green run.

The guarded-transition sweep (40 cells) and the credential posture found **nothing** — the guards and
the ADR-0006 boundaries were already correct. Reported as such: the sweep's value there is that the
absence of a hole is now a build failure away from being reintroduced, not that it discovered one.

## 7. AWS credentials & IAM (ADR-0013)

| # | Control | Verified by | Result |
|---|---|---|---|
| 7.1 | No long-lived credential on the production code path — the default build passes neither `endpointOverride` nor `credentialsProvider` | `AwsCredentialPostureTest` × 5 services | ✅ |
| 7.2 | The static credential exists in exactly one class, behind `@Profile("local")` | `grep -rn StaticCredentialsProvider services/*/src/main` ⇒ one file: `common-lib/LocalStackAwsOverride.java` | ✅ |
| 7.3 | A new client cannot reintroduce the shape | `PlatformArchRules.noServiceCarriesAStaticAwsCredential()`, checked by all 7 `*ArchitectureTest` | ✅ |
| 7.4 | Per-service least-privilege policies exist, with concrete ARNs and no `"Resource": "*"` | `infra/iam/*-policy.json`, 5 files | ✅ as documents |
| 7.5 | payment-service has **no** `sqs:*`; settlement-service has **no** `sns:Publish` | `infra/iam/` review | ✅ as documents |
| 7.6 | **Those policies are enforced by nothing locally** | LocalStack `ENFORCE_IAM` is off by default and gated as a paid feature (ADR-0001, ADR-0013) | ❌ **unprovable here** |
| 7.7 | The step-26 SQS queue resource policy (`pix-events` only, `ArnEquals` on `aws:SourceArn`) is likewise accepted and ignored by the emulator | `infra/localstack/init/06-messaging-core.sh` | ❌ **unprovable here** |

7.6 and 7.7 are the two rows that matter most and can be verified least. **"It works locally" is not
evidence they are right** — locally every call is allowed, including the ones these policies exist to
deny. That is why they are least-privilege *by construction* (written from what each service's code
actually calls) rather than by testing, and why `infra/iam/README.md` opens by saying so instead of
shipping a test that would only assert the files parse.

## 8. Dependencies & CVEs

- **Scan run:** `mvn versions:display-dependency-updates`, 2026-08-24.
- **Result:** no dependency is on a version with a known advisory that this scan surfaces; 20+ artifacts
  have newer releases available, the largest gaps being `software.amazon.awssdk` 2.28.29 → 2.54.2 and
  `org.testcontainers` 1.19.8 → 2.0.5 (a major, so not a drop-in).
- **Standing control: none.** ❌ Dependabot was configured in step 45 and **removed on 2026-08-25**, so
  nothing watches this repository's dependencies continuously any more. Recording that plainly is the
  point of this row: a checklist that quietly drops a control it once claimed is worse than one with a
  ❌ in it, and this section is the only place a reader would find out.
  - *Why it was removed:* the automation opened more PRs than the project was emptying, and the ones it
    opened were not the ones worth merging — a Spring Boot **major** (3.3 → 4.x) that contradicts the
    architecture this repo documents, next to same-major bumps that were failing CI. A queue nobody
    empties is not a control; it is a control-shaped object. The honest state is "no standing control",
    which is what this row now says.
  - *What replaces it today:* nothing automatic. The point-in-time scan above
    (`mvn versions:display-dependency-updates`) is a manual act, dated, and only as current as its date.
- **Deliberately not** `mvn org.owasp:dependency-check`: it is the better report and it downloads the NVD
  data set (needs an API key since 2024), which would make `mvn verify` depend on the network and break
  CLAUDE.md's "an IT runs on a plain `mvn verify`". A scanner that turns a red build into "was the NVD
  reachable today?" trains everyone to ignore the build. It is where this goes the day there is a
  pipeline with network budget; the trade-off is recorded rather than glossed. **With Dependabot gone
  this is no longer a trade-off between two controls — it is the gap.**
- **Version currency is a maintenance debt, not a finding.** It is listed here so the next reviewer sees
  the size of it; upgrading Testcontainers across a major is its own change, not a hardening step.

## 9. Transport & production posture

| Area | Local | Production posture | Where |
|---|---|---|---|
| TLS | Plain HTTP on `localhost` | TLS everywhere; mTLS between services, or a service mesh | SECURITY.md |
| Service identity | Scoped service tokens signed with the same shared HS256 secret | Per-workload credential (RS256 + JWKS, or mTLS), **plus** a gateway that blocks `/internal/**` from outside — perimeter *and* identity, never perimeter instead of identity | ADR-0017 |
| Token signing | HS256, secret via env var | RS256 + JWKS; only a KMS holds the private key | ADR-0007 |
| Secrets | `.env` / compose env vars, git-ignored | Secrets manager / KMS | SECURITY.md |
| AWS auth | `test`/`test` under the `local` profile | `DefaultCredentialsProvider` chain → ambient role, STS credentials the SDK rotates | ADR-0013 |
| Rate limiting | None | Edge rate limiting + per-account throttles | SECURITY.md |
| Audit trail | S3 Object Lock, COMPLIANCE mode, 1825 days | Same, plus a separate account and restricted deletion | step 42 |
| PII in logs | In the clear | Masked/tokenised; the log pipeline becomes an LGPD-scoped system | ADR-0012 |

Confirmed present locally: the audit bucket is **versioned with Object Lock in COMPLIANCE mode and a
1825-day (5-year, BACEN) default retention**, applied as the bucket default so every `PutObject`
inherits it with no caller opt-in — and settlement-service's IAM policy grants `s3:PutObject` and
deliberately no `PutObjectRetention`, so the writer cannot shorten what the bucket stamped.

## 10. Revisit triggers

Re-run this checklist when: a new money-moving endpoint is added; the auth model changes; a new external
integration appears; a new service is scaffolded; or an ADR that owns a control here is superseded — the
same triggers as `docs/threat-model.md` §7, so the two documents are never re-run apart.
