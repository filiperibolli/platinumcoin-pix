# auth-service

> Identity service for the PlatinumCoin Pix platform. Authenticates seeded demo users and issues
> the HS256 JWT that every later flow trusts. **AWS-free** — users are seeded in config.

- **Port:** `8081`
- **Depends on:** `common-lib` (error model, correlation-id filter, JSON logging)
- **Infra:** none (no LocalStack / Redis) — this is the first service in `infra/docker-compose.yml`

## Why it exists

The token is the backbone of the platform. Putting the `accountId` claim in it is the mechanism
behind **Domain Safety Rule #1**: later services derive the debited account *from the token*, never
from a request body. auth-service is the only place that mints tokens; other services only validate
them (from step 05).

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `POST` | `/v1/auth/login` | public | Authenticate `{username,password}` → `{accessToken, tokenType:"Bearer", expiresIn:900}` |
| `GET`  | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

**JWT claims** (exactly): `sub` (userId), `accountId`, `jti` (UUID), `iat`, `exp` (+15 min).
Signed HS256. Bad credentials ⇒ `401 application/problem+json` (`code: INVALID_CREDENTIALS`), with
no distinction between "unknown user" and "wrong password" (so usernames don't leak).

Contract source of truth: [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml) `/auth/login`.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret (**≥ 32 bytes**). Every service that later *validates* tokens must share this exact value. |
| `jwt.ttl` | `PT15M` | Token lifetime (→ `expiresIn=900`) |
| `auth.users[*]` | alice → `acc-001`, bob → `acc-002` | Seeded demo users; passwords stored as **bcrypt** hashes (username == password for the demo) |

## Architecture (ADR-0010, hexagonal-lite)

```
api/    AuthController, LoginRequest/Response, AuthExceptionHandler   (inbound adapter)
domain/ AuthenticationService + ports: UserRepository, PasswordVerifier, TokenIssuer   (plain Java)
infra/  JwtIssuer (jjwt), BCryptPasswordVerifier, InMemoryUserRepository, AuthBeansConfig   (outbound adapters + wiring)
```

`domain/` imports nothing outward (no Spring / jjwt / AWS / servlet / Jackson) — enforced by
`AuthArchitectureTest` (ArchUnit), which fails the build on a violation.

## Run

```bash
# from repo root
mvn -pl services/auth-service -am clean package
java -jar services/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
# or via compose
docker compose -f infra/docker-compose.yml up -d --build auth-service
```

## Test

```bash
mvn -pl services/auth-service verify          # unit (*Test) + integration (*IT)

# happy path (needs jq; otherwise decode the middle JWT segment manually)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq   # sub, accountId, jti, iat, exp
```

## Trade-offs: why not Keycloak (or another IdP)?

This service **hand-rolls identity**: it stores seeded users, verifies bcrypt passwords, and mints
its own HS256 JWT. A production fintech would almost certainly delegate that to a dedicated Identity
Provider (Keycloak, Auth0, AWS Cognito, Azure AD B2C). This section makes the choice explicit — it
is a deliberate scope decision (ADR-0007), not an oversight.

### What we gain by NOT using an IdP (here)

- **Zero extra infrastructure.** No stateful IdP container + its database to run, seed, patch and
  keep healthy. Sprint 1 boots with *nothing* but the service itself — the flow "login → JWT" is
  demonstrable in seconds.
- **Deterministic, fast tests.** `LoginIT` runs on MockMvc with no Testcontainers/network; the
  whole auth path is unit-testable. An IdP would push auth testing toward integration/e2e.
- **Full visibility for learning.** Every step — claim set, signing, expiry, the 401 contract — is
  our own code to read and reason about, which is the point of this project. An IdP is a black box
  by design.
- **No premature coupling.** The rest of the platform depends only on *"a validated JWT carrying
  `sub` + `accountId`"*, not on any particular vendor. Swapping the issuer later is a boundary
  change, not a rewrite.

### What we give up (the real cost)

We are re-implementing security-sensitive machinery that an IdP gives you for free — and every
line of it is a place to get subtly wrong:

- **Symmetric trust (HS256).** The signing secret is *shared*: any service that must **validate** a
  token also holds the power to **mint** one. Trust is only as strong as the least-guarded service
  holding the secret — a real problem for the service that *moves money*. RS256 + JWKS fixes this
  (only the IdP has the private key; everyone else verifies with a public key), which is exactly
  ADR-0007's documented production posture.
- **No refresh tokens / session model.** Tokens are fire-and-forget for 15 min; there is no
  refresh, sliding session, or logout.
- **No revocation.** A leaked token is valid until `exp`. IdPs offer token introspection / short
  access + revocable refresh tokens.
- **No MFA / step-up, no brute-force lockout, no password policy, no credential rotation.** (MFA is
  the one explicitly deferred gap in ADR-0007.)
- **No key rotation, no OIDC discovery** (`/.well-known/openid-configuration`, JWKS), **no standard
  flows** (Authorization Code + PKCE, social login), **no admin console, no user federation**
  (LDAP/AD), **no built-in auth audit trail.**

### How it would look **with Keycloak**

Keycloak becomes the source of identity; `auth-service` all but disappears (or shrinks to a thin
BFF that just orchestrates the login redirect). Concretely:

1. **Keycloak owns users, credentials, MFA and sessions** in a *realm*; each service (or a single
   SPA/BFF) is a registered *client*. Login is the standard **OIDC Authorization Code + PKCE** flow
   against Keycloak, not a bespoke `POST /v1/auth/login`.
2. **Keycloak mints RS256 tokens** and publishes its public keys at a **JWKS** endpoint. Our
   `accountId` claim is added via a **protocol mapper** (user attribute → token claim), so Domain
   Safety Rule #1 (debited account comes from the token) is preserved unchanged.
3. **Every downstream service becomes an OAuth2 Resource Server** and verifies tokens with the
   public key only — no shared secret anywhere:

   ```yaml
   # (illustrative) each service's application.yml — replaces our shared JWT_SECRET
   spring:
     security:
       oauth2:
         resourceserver:
           jwt:
             issuer-uri: http://keycloak:8080/realms/platinumcoin   # JWKS discovered from here
   ```

   ```
   Browser/SPA ──Authorization Code+PKCE──▶ Keycloak ──(RS256 JWT)──▶ SPA
        │                                                                │
        └──────── Bearer <JWT> ───────▶ payment-service ──verify via JWKS (public key)──▶ ✔
   ```

4. **What moves off our plate:** password storage, MFA/step-up, brute-force protection, refresh &
   revocation, key rotation, session management, audit of auth events — all Keycloak's job.
5. **What it costs:** a stateful Keycloak instance **+ its database** to run, seed (realm/clients/
   users as code), patch and keep healthy; another container in `docker-compose`; added login
   latency and a steeper learning curve — real operational weight that buys little for a *local,
   single-tenant learning demo*, which is why we defer it.

**Bottom line:** for this project the hand-rolled path maximizes clarity and reviewability at the
cost of production-grade auth features. The migration path is intentionally short — RS256 + JWKS is
already the documented target, and nothing downstream assumes the token came from *us* rather than
from Keycloak.

## Related decisions

- [ADR-0007](../../docs/adr/0007-auth-service-jwt-no-mfa.md) — dedicated auth-service, JWT only,
  HS256 locally (RS256 + JWKS is the documented production posture), MFA deferred.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
