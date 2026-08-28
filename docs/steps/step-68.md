# Step 68 — Internal-port isolation: service identity is not user identity

> **Sprint 11.5 — External review remediation (P0/P1)** · **Flow:** every service-to-service call · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §5 (security) + §6.4
>
> **Numbered out of order** — see the note in [step 65](step-65.md).
>
> **Origin:** external review by **Geison Flores** (Mercado Livre), finding **P0 · segurança** —
> *"Bloquear no gateway, usar tokens de serviço, escopos por operação, audience/issuer e testes
> negativos por endpoint."* Acceptance criterion: *"0 acesso lateral"*. ·
> **ADR:** [ADR-0017](../adr/0017-workload-identity-for-internal-ports.md) (amends ADR-0007)

## Objective
Stop presenting the end user's bearer token as a service credential. Every internal call carries a
**service token** with `typ=service`, `iss`, `aud` and an operation `scope`; the shared filter rejects
a user token on `/internal/**` with `403`, and rejects a service token whose `aud`/`scope` do not
match the route. Each internal endpoint ships a four-case negative test.

## Why this step exists
**The confused deputy, in its natural habitat.** payment-service is trusted to post to the ledger; it
carries out that duty holding a credential that belongs to someone else. The token is valid, the
signature checks, every log line looks normal — and the authority being exercised is not the
authority that was granted. You'll learn why *authentication* ("this token is real") and
*authorization* ("this caller may do this operation on this service") are different questions, why a
perimeter (a gateway) answers neither of them for a call that originates inside, and how little it
costs to carry identity in claims (`typ`/`iss`/`aud`/`scope`) once you decide the caller must say who
it is and what it wants. The negative-test matrix is the real deliverable: a security property with
no test that tries to break it is a comment.

## Prerequisites
Step 05 (shared JWT filter). Related but **distinct** from step 45, which owns AWS/IAM credentials
(ADR-0013) — this step owns HTTP identity between services.

## Problem
`/internal/**` routes require a valid token and nothing more. payment-service satisfies that by
copying the caller's user bearer onto every outbound call. The result: **a token issued to any user
is a valid credential on `POST /internal/ledger/postings`** — the platform's single money-moving
operation, which names both accounts explicitly and derives nothing from the token. Domain Safety
Rule #1 holds at the public edge and is structurally absent at the internal one.

## Evidence in the current code
- `services/payment-service/src/main/java/.../infra/client/HttpLedgerClient.java:324-331`
  (`forwardAuthorization`), used at `:171` (balance), `:226` (statement) and `:279` (**posting**).
- `HttpFraudScorer.java:112` · `HttpAccountLimitClient.java:72` · `HttpPixKeyResolver.java:115` — the
  same helper, copied four times.
- `HttpLedgerClient.java:59-61` and `HttpFraudScorer.java:40-43` — the javadocs state the intent and
  defer the fix: *"the caller's bearer token is forwarded (ADR-0007; a service credential is the
  deployed posture, step-45)."*
- `services/ledger-service/src/main/java/.../api/InternalPostingController.java:20` — the endpoint
  requires *"a valid token"*, with no notion of who holds it.
- `services/settlement-service/src/main/java/.../infra/security/ServiceTokenIssuer.java:37-77` — the
  one service that already does this correctly (it has no user token to forward). Its claim set is the
  bare minimum the filter accepts, and **nothing rejects a user token anywhere**, so its correctness is
  a convention rather than a control.

## Tasks
1. **`typ` claim on every token.** auth-service mints `typ=user`; service issuers mint `typ=service`.
   A token without `typ` is read as `user` — the safe default, since only user tokens predate this step.
2. **`ServiceTokenIssuer` moves to `common-lib`** (`security/`), parameterised by `iss` (the calling
   service), `aud` (the target service) and `scope`. settlement-service's copy is deleted; it becomes
   the first consumer of the shared one.
3. **Scopes, one per internal operation:** `ledger:post`, `ledger:read`, `fraud:score`,
   `accounts:read`, `keys:resolve`. Declared per route in each service's configuration, so the mapping
   is data a reviewer can read in one place rather than logic spread across controllers.
4. **The shared `JwtAuthFilter` learns a route class.** `/internal/**` accepts `typ=service` only, and
   validates `aud == this service` and `scope ∈ required(route)`. `/v1/**` accepts `typ=user` only —
   the two surfaces are disjoint in **both** directions, so a leaked service token is equally useless
   on a public endpoint. A failure is `403` + problem+json `code: INTERNAL_PORT_FORBIDDEN`, logged at
   `WARN` with the presented claims (**never the token** — ADR-0012).
5. **The four `forwardAuthorization` helpers are deleted, not adapted.** Leaving one working example
   invites the next adapter to copy it. Each client mints its own token per call via the shared issuer.
6. **The user travels as evidence, never as authority.** `X-PlatinumCoin-On-Behalf-Of: <userId>` on
   internal calls, alongside the existing correlation id, so logs and the audit trail still say which
   human's request caused a posting. A comment at its declaration states that **no service reads it to
   make an access decision**; an ArchUnit-style or grep-based check in the test suite asserts it is
   never consulted in a conditional.
7. **mock-bacen-spi is untouched.** It is outside the trust domain, holds no PlatinumCoin token, and
   its inbound webhook keeps its shared-secret header. That asymmetry is correct and gets a sentence
   saying so.
8. **Docs in the same change:** `SECURITY.md` (the trust model and the two token types), ARCHITECTURE
   §5, `docs/api/openapi.yaml` (the `403` on internal routes), and each affected service README's
   configuration section.

## Acceptance criteria
- [ ] No `forwardAuthorization`-style helper remains anywhere in `services/`.
- [ ] A valid **user** token gets `403` on every `/internal/**` route in every service.
- [ ] A service token with the wrong `aud` gets `403`; with the wrong `scope` gets `403`.
- [ ] A **service** token gets `403` on every public `/v1/**` route.
- [ ] The on-behalf-of header is never read in an authorization decision.
- [ ] Review acceptance criterion *"0 acesso lateral — JWT de usuário recebe 403 em toda porta
      interna; escopo de serviço é mínimo"* holds, endpoint by endpoint.

## Tests (TDD)
**The test that fails today — write it first:**
- `InternalPortForbiddenIT#aUserTokenCannotPostALedgerEntry` — log in as alice, present that exact
  token to `POST /internal/ledger/postings` with alice as creditor and bob as debtor. **Assert `403`
  and that no ledger entry was written.** Against `main` it returns `200` and moves bob's money —
  which is the finding, demonstrated as an exploit rather than described.

Then, **per internal endpoint** (a parameterised matrix, one row per route):
- valid user token ⇒ `403 INTERNAL_PORT_FORBIDDEN`
- service token, wrong `aud` ⇒ `403`
- service token, wrong `scope` ⇒ `403`
- service token, correct `aud` + `scope` ⇒ `2xx`

Plus:
- `PublicRouteIT#aServiceTokenIsRejectedOnPublicRoutes` — the reverse direction.
- `ServiceTokenIssuerTest` — claim set, TTL, and that the compact token never reaches a log (assert on
  the issuer's captured log arguments, not on prose).
- `OnBehalfOfHeaderTest` — present a forged `X-PlatinumCoin-On-Behalf-Of` with a valid service token;
  assert it changes **no** authorization outcome and appears only in logs/audit.
- Existing ITs that mint a user token and call an internal route are migrated to service tokens. **That
  churn is the point**: a test that had to change was exercising the hole.

## Verify locally
```bash
mvn verify

TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# the exploit, now refused (403 INTERNAL_PORT_FORBIDDEN)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8085/internal/ledger/postings \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"txId":"tx-probe","debitAccount":"acc-002","creditAccount":"acc-001","amountCents":100,"entryType":"PIX_INTERNAL","description":"probe"}'

# and the normal send still works, because payment-service now mints its own token
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"pixKey":"bob@platinumcoin.com","amount":"1.00","description":"identity"}' | jq
```

## Definition of Done
- [ ] Shared `ServiceTokenIssuer` in common-lib; every internal call mints its own scoped token
- [ ] Filter enforces `typ`, `aud` and `scope`; public and internal surfaces are disjoint both ways
- [ ] Negative-test matrix green for every `/internal/**` route in every service
- [ ] On-behalf-of header carries evidence only, proven by test
- [ ] `SECURITY.md`, ARCHITECTURE §5, `openapi.yaml` and the affected service READMEs updated
- [ ] `mvn verify` green across all modules

## CHANGELOG entry
`### Security` → `Internal ports no longer accept a user's JWT: every service-to-service call carries a scoped service token (typ/iss/aud/scope) and /internal/** returns 403 to a user token, closing lateral access to the ledger posting endpoint (step 68, ADR-0017)`
