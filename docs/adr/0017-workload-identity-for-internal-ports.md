# ADR-0017: Workload identity for internal ports — a user token is not a service credential

**Status:** Accepted · **Date:** 2026-08-22 · **Implementation:** step 68 · **Amends:** ADR-0007 · **Related:** ADR-0013

> **Origin.** External architecture review by **Geison Flores** (Mercado Livre), delivered as
> `docs/solucao-e-sugestoes.html` in [PR #58](https://github.com/filiperibolli/platinumcoin-pix/pull/58).
> Finding **P0 · segurança** — *"Bloquear no gateway, usar tokens de serviço, escopos por operação,
> audience/issuer e testes negativos por endpoint."* Acceptance criterion: *"0 acesso lateral — JWT de
> usuário recebe 403 em toda porta interna; escopo de serviço é mínimo."*

## Context

`/internal/**` routes are deliberately absent from `jwt.public-paths`, so they demand a valid token.
The review's finding is about **which** token they accept: the shared `JwtAuthFilter` validates the
signature and the expiry, and stops there. It has no notion of who the caller is beyond "someone
holding a token this platform signed".

payment-service therefore authenticates to every internal port by **copying the end user's bearer
header onto the outbound call** — `forwardAuthorization` in `HttpLedgerClient` (line 324, used at 171,
226 and 279), `HttpFraudScorer` (112), `HttpAccountLimitClient` (72), `HttpPixKeyResolver` (115). The
javadocs are honest about it, each pointing at step 45 for "a service credential is the deployed
posture".

The consequence is concrete: **a token issued to any user is a valid credential on
`POST /internal/ledger/postings`** — the single money-moving operation in the platform (ADR-0006). It
names both accounts explicitly and derives nothing from the token, so anyone who can reach
ledger-service with a login can post an arbitrary double-entry between arbitrary accounts. Domain
Safety Rule #1 ("the debited account comes from the JWT, never the client payload") holds at the
*public* edge and is structurally absent at the internal one, because the internal API's whole job is
to be told both legs.

One service already does this correctly. settlement-service runs off a queue, has no user token to
forward, and mints its own (`ServiceTokenIssuer`, `infra/security/`) — a short-lived HS256 token with
a `settlement-service` principal. The shape exists; what is missing is that (a) nothing *rejects* a
user token, and (b) every service-minted token is as powerful as every other one.

This ADR is about **HTTP identity between services**. ADR-0013 is about **AWS credentials and IAM**.
They are neighbours and are often confused; step 45 owns the second and step 68 owns the first.

## Decision

1. **Two token types, distinguished by a claim.** Every token carries `typ`: `user` (minted by
   auth-service on login) or `service` (minted by a service for a service call). A token without
   `typ` is treated as `user` — the safe reading, since only user tokens exist before this step.
2. **`/internal/**` accepts `typ=service` only.** A user token on an internal route is rejected with
   `403` and a problem+json `code: INTERNAL_PORT_FORBIDDEN`, logged at `WARN` with the presented
   claims (never the token — ADR-0012). Public `/v1/**` routes are unchanged and continue to accept
   `typ=user` only, so the two surfaces are disjoint in both directions: a service token is equally
   useless on a public endpoint.
3. **Service tokens are scoped and addressed.** `iss` = the calling service, `aud` = the target
   service, `scope` = the operation being exercised (`ledger:post`, `ledger:read`, `fraud:score`,
   `accounts:read`, `keys:resolve`). The filter validates all three. A payment-service token minted
   for `ledger:post` is not accepted by fraud-service and is not accepted for `ledger:read`. This is
   what makes "escopo de serviço é mínimo" a checkable property rather than a promise.
4. **The signing key stays the shared HS256 secret.** Same key, same filter, same verification path
   as every other token in the platform. The identity is carried by the *claims*, not by a
   per-service key. This is the sandbox-proportionate choice and it is stated as such, not as the
   production posture.
5. **`ServiceTokenIssuer` moves to `common-lib` and every internal call goes through it.** The four
   `forwardAuthorization` helpers in payment-service are deleted — not adapted. Forwarding is not a
   thing this platform does any more, and leaving one working example invites the next adapter to
   copy it.
6. **The end user does not vanish from the call — the *authority* does.** The caller's user id and
   the correlation id travel as headers (`X-PlatinumCoin-On-Behalf-Of`, plus the existing correlation
   id), so logs and the audit trail can still say which human's request caused a posting. That header
   is **evidence, never authorization**: no service reads it to make an access decision, and a
   comment at its declaration says so.
7. **Every internal endpoint ships a negative test.** For each `/internal/**` route: a valid user
   token gets `403`; a service token with the wrong `aud` gets `403`; a service token with the wrong
   `scope` gets `403`; the correct service token gets through. Per endpoint, per service — the review
   asked for exactly this and it is the only way the property does not rot.

## Alternatives rejected

- **RS256 with a private key per service and a JWKS endpoint.** Closer to the review's target diagram
  (OIDC/JWKS) and to production. Rejected for this platform's scope: it adds key generation,
  distribution and rotation, a JWKS endpoint per service, and a second verification path in the
  shared filter — a large amount of moving machinery to prove a property the claim-based design
  already proves in a stack whose secret is shared by construction. Recorded here as the documented
  evolution: the claim shape (`typ`/`iss`/`aud`/`scope`) is the part that would survive the swap, and
  it is chosen so that only the signature verification changes.
- **mTLS between services.** The review's production recommendation, and the right answer with a
  service mesh or a sidecar proxy. This stack is plain docker-compose with no proxy and no
  Kubernetes (CLAUDE.md), so it would mean introducing an entire transport layer to secure a
  sandbox. Documented as the production posture in ARCHITECTURE, not built.
- **An API gateway that blocks `/internal/**` from outside.** Necessary in production and orthogonal
  here: it stops *external* reach, and does nothing about payment-service presenting a user's token
  to the ledger from *inside* the network. Perimeter control is not identity.
- **Reject the user token but skip scopes.** Closes the review's acceptance criterion ("0 acesso
  lateral") with a much smaller diff. Rejected because it stops one service short of the finding's
  actual point: without `aud`/`scope`, every service credential still opens every internal door, so
  a compromised fraud-service could post ledger entries. The scope check is three lines in the filter
  and one claim in the issuer.
- **Leave it, and note it in the step-45 security checklist.** Which is roughly where the code stands
  today. Rejected: this is the platform's single money-moving endpoint reachable with any user's
  login, and a checklist entry is not a control.

## Consequences

- Every service-to-service call gains a token mint (an HMAC signature over a tiny claim set, once per
  call, with a short TTL). Measured against the DynamoDB and HTTP work in the same call, it is noise;
  step 47's per-dependency p99 will show it.
- The shared filter grows a route-class notion (`internal` vs `public`) and a scope check. It stays
  the *one* place tokens are validated — no service acquires its own authentication logic, which is
  the property ADR-0007 bought and this ADR must not spend.
- Integration tests that today mint a user token and call an internal route directly must switch to a
  service token. That churn is the point: a test that had to be changed is a test that was exercising
  the hole.
- The audit trail becomes strictly more informative: a posting now records both the acting service
  and the on-behalf-of user, where today it records a user token that could have come from anywhere.
- mock-bacen-spi is unaffected — it is outside the trust domain, holds no PlatinumCoin token, and its
  inbound webhook keeps its shared-secret header (`InboundPixController`). That asymmetry is correct
  and stays.
