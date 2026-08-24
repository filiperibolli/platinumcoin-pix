# Threat Model — PlatinumCoin Pix Platform

**Status:** Living document · **Owner:** @filiperibolli · **Method:** STRIDE per trust boundary

This document takes an attacker's-eye view of the platform. It complements
[`SECURITY.md`](../SECURITY.md) (policy) and the [ADRs](adr/) (decisions): here we ask
*"what can go wrong, who could make it go wrong, and what stops them?"*

> **Scope reminder.** This is a local learning system: emulated AWS (LocalStack), a
> mock BACEN SPI, no real funds, no production PII, no deployed surface. Network-layer
> threats (TLS, DDoS, cloud IAM) are therefore mostly *documented as production
> concerns* rather than mitigated locally. The threats we take seriously are the ones
> intrinsic to the **domain logic** — because those bugs would be real in production
> too, and the whole point of the project is to get them right.

---

## 1. Assets — what an attacker wants

Ranked by blast radius, most critical first.

| # | Asset | Why it matters | Worst outcome |
|---|-------|----------------|---------------|
| A1 | **Ledger integrity** (balances + entries) | It *is* the money | Funds created/destroyed; negative balance; double-spend |
| A2 | **Authorization context** (JWT → `accountId`) | Decides *whose* money moves | Debit someone else's account |
| A3 | **Idempotency guarantees** | Retries must not duplicate charges | Double debit on replay |
| A4 | **Funds availability** (the send path staying up) | Availability is a trust/security property for a payments app | Legitimate payments blocked |
| A5 | **PII / Pix keys** (CPF, email, phone) | Regulated personal data (LGPD) | Key enumeration, data leak |
| A6 | **Signing secret** (JWT HS256 key) | Forges any identity | Total auth bypass |
| A7 | **Audit trail** (S3 immutable log) | Non-repudiation, reconciliation | Tampered/rewritten history |
| A8 | **Event pipeline** (outbox → SNS/SQS) | Drives settlement & notifications | Lost, duplicated, or forged settlement |

---

## 2. Trust boundaries

```mermaid
graph LR
    subgraph Untrusted
        C[Client / Mobile app]
    end
    subgraph B1[Boundary 1: API edge]
        AUTH[auth-service]
        PAY[payment-service]
        ACC[account-service]
    end
    subgraph B2[Boundary 2: internal domain services]
        LED[ledger-service]
        FRAUD[fraud-service]
    end
    subgraph B3[Boundary 3: async pipeline]
        OUTBOX[(outbox)]
        SNS{{SNS/SQS}}
        SET[settlement-service]
        NOT[notification-service]
    end
    subgraph B4[Boundary 4: external rail]
        BACEN[mock BACEN SPI]
    end
    C -->|JWT| PAY
    C -->|login| AUTH
    PAY --> FRAUD
    PAY --> LED
    PAY --> OUTBOX --> SNS --> SET --> BACEN
    SNS --> NOT
```

- **B1 (edge)** — the only boundary an external attacker reaches directly. Everything
  crossing it is *untrusted input*; the debited account is decided **here**, from the
  token, not from the body.
- **B2 (domain)** — ledger and fraud. The ledger trusts only validated commands; it
  enforces money invariants at the database level regardless of caller correctness.
- **B3 (async)** — at-least-once delivery. Duplicates and reordering are *expected*,
  not exceptional, so they are threats mitigated by design (dedup, guarded transitions).
- **B4 (external)** — the SPI is slow and unreliable by construction; threats here are
  about idempotency and reconciliation, not confidentiality.

---

## 3. STRIDE analysis

Each row: a concrete threat → the control that stops it → residual risk. Controls that
already exist in the design are marked ✅; documented-but-not-implemented-locally gaps
are marked ⚠️.

### S — Spoofing (identity)

| Threat | Control | Residual |
|--------|---------|----------|
| Attacker calls `POST /payments/pix` as another user | JWT required; `accountId` taken from signed `sub`/`accountId` claim, never the body (ADR-0007) ✅ | Depends on secret secrecy (A6) |
| Forged JWT | HS256 signature verified in `common-lib` filter on every service ✅ | HS256 shared secret is weaker than RS256 — **prod uses RS256+JWKS** ⚠️ |
| Replayed stolen token | 15-min `exp`, `jti`, `iat` claims ✅ | No token revocation list locally ⚠️ |
| Impersonating an internal service | **Scoped service tokens** (ADR-0017): `/internal/**` accepts `typ=service` only, with `aud` = the service called and `scope` = the operation the route declares; a user's token gets `403`, and a service token gets `403` on `/v1/**`. Every internal route ships a four-case negative test ✅ | Still one shared HS256 secret, so anything holding it can mint any service identity — **prod: per-workload key (RS256+JWKS) or mTLS**, plus a gateway blocking `/internal/**` from outside ⚠️ |
| A user's own token used as a service credential on the ledger's posting endpoint | **Closed in step 68.** Was reachable: payment-service forwarded the caller's bearer, so any login could post an arbitrary double entry between arbitrary accounts. The four `forwardAuthorization` helpers are deleted, and `InternalPortForbiddenIT` keeps the exploit as a failing-if-reintroduced test that also asserts no ledger entry was written ✅ | — |
| Forged inbound settlement webhook (`POST /v1/inbound/pix`) credits an account with fake money | Shared-token header (`SPI_WEBHOOK_TOKEN`) validated by settlement-service before any posting; dedupe by `endToEndId` ✅ | Local token is a stand-in — **prod: mTLS + BACEN message signing** ⚠️ |

### T — Tampering (integrity)

| Threat | Control | Residual |
|--------|---------|----------|
| Client injects a `sourceAccount` to debit a victim | The field **does not exist** in the API contract — tampering is inexpressible (ADR-0007) ✅ | — |
| Concurrent debits drive balance negative | `balanceCents >= :amount` **inside** `TransactWriteItems`; all-or-nothing (data-model §3) ✅ | Verified by the step-15 concurrency-storm test |
| Debit written without matching credit | Both legs are the *same* atomic transaction ✅ | — |
| Rewriting ledger history to hide a debit | Entries are append-only; corrections are compensating postings, never updates/deletes ✅ | Enforced by convention + review; no DB-level immutability locally ⚠️ |
| Tampering the audit trail | S3 object-write is append-only | **Prod: S3 Object Lock / WORM** ⚠️ |
| Tampered statement cursor pages another account's entries (the base64 cursor embeds the DynamoDB partition key) | Decoded cursor's `pk` must equal the authenticated account, else `400` (steps 16/41) ✅ | — |
| Man-in-the-middle altering requests | localhost only | **Prod: TLS everywhere** ⚠️ |

### R — Repudiation

| Threat | Control | Residual |
|--------|---------|----------|
| User denies initiating a payment | `correlationId` + `txId` on every log record (in the pattern, ADR-0012) across every service; immutable S3 audit record per settlement ✅ | Log integrity not cryptographically signed ⚠️ |
| Consumer denies processing an event | `pix_processed_events` records every consumed `eventId` ✅ | TTL 7 days |

### I — Information disclosure

| Threat | Control | Residual |
|--------|---------|----------|
| Pix-key enumeration via the resolution endpoint | Authenticated endpoint; **prod: rate limiting + BACEN DICT anti-scraping** ⚠️ | Local: no rate limit ⚠️ |
| Stack traces / internals leaked in errors | RFC 7807 `problem+json` with a stable `code` and `correlationId`; **never leak stack traces** (CLAUDE.md) ✅ | — |
| Sensitive payloads in logs | **Secrets are never logged** — no password, bcrypt hash, JWT or AWS credential, at any level ✅ (review-enforced) | **Personal-shaped values (Pix keys, CPFs, e-mails) ARE logged in full — a deliberate sandbox choice over seeded fixtures ([ADR-0012](adr/0012-verbose-logs-with-real-values.md)), which production reverses with masking/tokenization at the log boundary** ⚠️ |
| Secret leaking into git | `.env`, `*.pem`, `*.key` git-ignored; no real secrets in repo ✅ | — |
| One user reads another's statement/balance | Queries scoped by `accountId` from the token ✅ | — |

### D — Denial of service

| Threat | Control | Residual |
|--------|---------|----------|
| Fraud-service slow/down stalls the send path | **200ms hard timeout, fail-open**, flagged `FRAUD_SKIPPED` (ADR-0005) ✅ | Fail-open window is unscored — bounded by daily limits + async scoring |
| Slow BACEN SPI blocks the user | Async settlement: `202 Accepted` decouples UX from the ≤10s rail ✅ | — |
| Poison message loops forever | Retries with backoff → **DLQ** after N attempts ✅ | — |
| Request flooding | Not mitigated locally | **Prod: edge rate limiting + per-account throttle** ⚠️ |
| Hot partition (single clearing item at peak) | Documented shard-out `SPI_CLEARING#00..15` (data-model §3) ⚠️ | N=1 locally |

### E — Elevation of privilege

| Threat | Control | Residual |
|--------|---------|----------|
| Move money without passing limit checks | Limit check returns an explicit decision object (`ALLOW`/`DENY`/`REQUIRE_STEP_UP`); over-limit → `422` (ADR-0007) ✅ | `REQUIRE_STEP_UP`→deny locally; MFA seam ready ⚠️ |
| Bypass idempotency to force a double charge | Conditional `PutItem attribute_not_exists(pk)` claims the key atomically; ledger `txId` guard is defense-in-depth (ADR-0002) ✅ | — |
| Replay/forge a settlement event | `endToEndId` idempotency toward SPI; consumers dedupe by `eventId`; status transitions are guarded (`#status = :expectedFrom`) so a `SETTLED` tx cannot regress ✅ | — |

---

## 4. Top risks (prioritised)

1. **Signing-secret compromise (A6 / Spoofing).** Highest blast radius: forges any
   identity. *Local:* HS256 env secret, git-ignored. *Prod path:* RS256 + JWKS so only
   the private key (in a KMS) can sign and services need only the public key.
2. **A money-invariant bug (A1 / Tampering).** The core risk the whole test strategy
   targets: negative balance, double-post, or non-conservation of money. Mitigated by
   DB-level conditions inside `TransactWriteItems` and the step-15 invariant storm.
3. **Fraud fail-open abuse (A4 vs A1).** A deliberate availability-over-strictness
   trade-off (ADR-0005). Residual exposure is bounded by daily limits and delayed (not
   skipped) async scoring, and the seam for a value-thresholded hybrid policy exists.
4. **Pix-key enumeration / PII (A5).** LGPD-relevant. Local build authenticates the
   endpoint; production needs rate limiting and DICT anti-scraping. The same asset is
   also exposed *in the logs* by choice — see [ADR-0012](adr/0012-verbose-logs-with-real-values.md)
   for why that is acceptable over seeded fixtures and exactly what changes with real data.

---

## 5. Deliberate, documented gaps (not vulnerabilities)

These are recorded trade-offs — see the table in [`SECURITY.md`](../SECURITY.md#security-posture--deliberate-gaps):
HS256 (not RS256) locally, no MFA/step-up, pure fail-open fraud, plain HTTP on
localhost, no rate limiting, no service-to-service mTLS. Each has a production posture
documented in the referenced ADR.

## 6. Assumptions

- The developer machine and the LocalStack network are trusted (single-tenant, local).
- Seeded demo credentials are non-secret by design.
- The mock SPI is adversary-neutral: it injects latency/failures, not attacks.
- Threat categories about the cloud control plane (IAM, VPC, KMS) are out of local
  scope and tracked only as production-hardening notes. The credential/authorization
  half of that is written down in [ADR-0013](adr/0013-aws-credentials-and-iam-posture.md)
  and was **swept in step 45**: static credentials now live in exactly one class behind the
  `local` profile (`common-lib`'s `LocalStackAwsOverride`), the default build resolves the
  ambient role, and per-service least-privilege policies are committed under `infra/iam/`.
  LocalStack emulates the IAM/STS APIs but **enforces nothing by default**, so no local test
  can prove a denial — those policies are reviewed as documents, and
  [`docs/security-checklist.md`](security-checklist.md) §7 records exactly which two rows are
  unprovable here and why.

## 7. Revisit triggers

Update this model when: a new money-moving endpoint is added; the auth model changes
(e.g. MFA lands); a new external integration appears; or an ADR that touches a control
here is superseded.

**Re-run [`docs/security-checklist.md`](security-checklist.md) at the same time.** This
document says what could go wrong; that one records what was actually checked, how, and what
it returned, on a date. Running one without the other leaves either an untested model or an
unexplained result — which is why they share this trigger list.
