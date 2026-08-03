# account-service

> Accounts & Pix-keys service for the PlatinumCoin Pix platform. The **first service that talks to
> DynamoDB** via the AWS SDK. Step 09 delivers account reads; step 10 adds Pix-key
> register/list/delete with global uniqueness; step 11 adds internal key resolution (the DICT role).

- **Port:** `8082`
- **Depends on:** `common-lib` (error model, correlation-id filter, JWT validation, JSON logging)
- **Infra:** LocalStack (DynamoDB, tables `pix_accounts` + `pix_keys`) — created + seeded by the step-07 init scripts

## Why it exists

account-service owns the `pix_accounts` table and is the **first AWS-SDK adapter** in the platform.
It demonstrates the ADR-0010 hexagonal-lite split in practice: an `AccountRepository` **port** in
`domain/`, a `DynamoAccountRepository` **adapter** in `infra/` (the only place `software.amazon.awssdk.*`
appears), **use cases** in `domain/usecase/` (ADR-0011) and thin controllers in `api/` that hold no
business policy. `GET /accounts/me` derives the account **from the JWT** (`accountId`
claim), never from a path or body — the same principle that protects the debited account in the send
flow (Domain Safety Rule #1).

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `GET` | `/v1/accounts/me` | Bearer | The caller's own account → `{accountId, status, dailyLimit}`; `dailyLimit` is a decimal BRL string ("5000.00"). Account derived from the token. |
| `GET` | `/internal/accounts/{accountId}` | Bearer | Service-to-service lookup by id → `{accountId, userId, status, dailyLimitCents, createdAt}`; money as **integer cents**. |
| `POST` | `/v1/pix-keys` | Bearer | Register a Pix key (`CPF`/`EMAIL`/`PHONE`/`EVP`). EVP ⇒ server-generated UUID. `201` → `{keyType, keyValue, createdAt}`. Owner from the JWT. |
| `GET` | `/v1/pix-keys` | Bearer | List the caller's Pix keys (scoped to the JWT account) → `[{keyType, keyValue, createdAt}]`. |
| `DELETE` | `/v1/pix-keys/{keyValue}` | Bearer | Delete an **owned** key → `204`. Another account's key ⇒ `403`; absent ⇒ `404`. |
| `GET` | `/internal/pix-keys/resolve?key=…` | Bearer | Service-to-service **DICT** lookup → `{internal:true, accountId, keyType}` for an internal key; unknown ⇒ `404 KEY_NOT_FOUND` (external delegation deferred to step 30). |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

Unknown account ⇒ `404 application/problem+json` with `code: ACCOUNT_NOT_FOUND`. No token ⇒ `401`
(`code: UNAUTHORIZED`) — including on `/internal/**`.

### Pix-key semantics (step 10)

- **Global uniqueness** is enforced by a conditional `PutItem` (`attribute_not_exists(pk)`) on
  `KEY#<keyValue>` — the DynamoDB equivalent of a UNIQUE constraint. Two accounts racing for the same
  value: exactly one wins, the other gets `409 KEY_ALREADY_EXISTS`. No read-then-check race exists,
  because the check and the write are one atomic operation. `EMAIL` is normalized (trim + lowercase)
  so `Alice@x.com` and `alice@x.com` cannot both be registered; format is validated per type (a
  malformed value ⇒ `422 INVALID_PIX_KEY`).
- **Delete is ownership-guarded** and reveals existence on purpose: a foreign key ⇒ `403 KEY_FORBIDDEN`
  (not `404`). Pix keys are globally resolvable identifiers, so their existence is not secret — unlike
  a foreign `transactionId`, which returns `404` (step 22) to avoid leaking that it exists.

### Key resolution — the DICT role (step 11)

`GET /internal/pix-keys/resolve?key=…` is account-service acting as BACEN's **DICT** for keys living
inside PlatinumCoin — the hot lookup on the send path (every Pix resolves its destination key first,
step 21). The answer uses the **final** shape now, even though the external branch is still a stub:

- **internal key found** ⇒ `200 {internal:true, accountId, keyType}` (`externalBank` omitted/null).
- **unknown key** ⇒ `404 KEY_NOT_FOUND`. External-PSP delegation is deferred to **step 30**, when
  mock-bacen exists — a `// TODO(step 30)` seam in `ResolvePixKeyUseCase.resolveExternal` marks it.
  Designing `{internal, accountId?, externalBank?, keyType}` up front lets the send orchestration code
  against the final contract; the external path slots in later without a reshape.

The incoming key is **lowercase-normalized before lookup**, mirroring registration (EMAIL is stored
lowercase). So a payer who typed `Alice@x.com` still resolves the registration stored as `alice@x.com`;
it is a no-op for CPF/PHONE and the server-minted (lowercase) EVP.

### Why `/me` and `/internal` differ

`/me` is the **public** view: the account comes only from the token, and money is formatted to a
decimal string at the API edge. `/internal/accounts/{id}` is a **service-to-service seam** (ADR-0006):
it takes the account id from the path (the caller is asking about *some* account, e.g. payment-service
resolving a payee's limit), and keeps `dailyLimitCents` as an integer because its consumers do integer
arithmetic on the limit (step-20 daily-limit reservation). It is **not** on the public allow-list, so
it still requires a valid JWT — but it is deliberately not account-scoped. A deployed posture would
gate it with a service credential/scope or mTLS rather than an end-user token (step-45 hardening).

Contract source of truth: [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml) `/accounts/me`. The
internal seam is intentionally left out of the *public* OpenAPI contract and documented here instead.

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/    AccountController (/v1/accounts/me), InternalAccountController (/internal/accounts/{id}),
        PixKeyController (/v1/pix-keys), InternalPixKeyController (/internal/pix-keys/resolve),
        AccountResponse, InternalAccountResponse, RegisterPixKeyRequest, PixKeyResponse,
        AccountExceptionHandler (domain exception → problem+json)                  (inbound adapters)
domain/         Account, PixKey, KeyResolution (records), PixKeyType (enum),
                AccountRepository, PixKeyRepository (ports),
                AccountNotFound / InvalidPixKey / PixKeyAlreadyExists /
                PixKeyNotFound / PixKeyNotOwned exceptions                              (plain Java)
domain/usecase/ GetMyAccountUseCase, GetAccountUseCase, RegisterPixKeyUseCase,
                ListPixKeysUseCase, DeletePixKeyUseCase, ResolvePixKeyUseCase           (plain Java)
infra/  DynamoAccountRepository, DynamoPixKeyRepository (AWS SDK), DynamoConfig,
        AccountBeansConfig (composition root + Clock), AwsProperties        (outbound adapter + wiring)
```

**`domain/usecase/` is the capability list** — one class per inbound operation, single `execute(...)`,
named for the business intent (ADR-0011). Controllers hold no policy: EVP server-generation, e-mail
normalization, format validation, the global-uniqueness outcome and the delete ownership guard all
live in use cases, unit-tested as plain Java with a fake port and a fixed `Clock` — no MockMvc, no
LocalStack.

Two ArchUnit rules in `AccountArchitectureTest` fail the build on a violation: `domain/` imports
nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson), and `api/` never depends on an
interface in `domain/` — which is what makes "a controller may not reach a repository" mechanical.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must equal auth-service's. This service only **validates** tokens. |
| `jwt.public-paths` | `/actuator/**` | Paths the shared `JwtAuthFilter` skips. `/internal/**` is **not** here — it requires a token. |
| `AWS_ENDPOINT_URL` / `aws.endpoint-url` | `http://localhost:4566` | LocalStack edge; compose overrides to `http://localstack:4566`. |
| `AWS_REGION` / `aws.region` | `us-east-1` | SDK region. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds LocalStack ignores but the SDK demands. |

## Run

```bash
# from repo root — build the jar first
mvn -pl services/account-service -am clean package

# LocalStack must be up (provides DynamoDB + seeded pix_accounts)
docker compose -f infra/docker-compose.yml up -d localstack

# then either run standalone…
java -jar services/account-service/target/account-service-0.0.1-SNAPSHOT.jar
# …or via compose (builds the image, waits on localstack healthy)
docker compose -f infra/docker-compose.yml up -d --build account-service
```

## Test

```bash
mvn -pl services/account-service verify        # unit (*Test) + integration (*IT, Testcontainers)

# happy path (needs a token; mint one from auth-service)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

curl -s localhost:8082/v1/accounts/me -H "Authorization: Bearer $TOKEN" | jq
curl -s localhost:8082/internal/accounts/acc-001 -H "Authorization: Bearer $TOKEN" | jq

# Pix keys (step 10): register → list → delete
curl -s -X POST localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"keyType":"EMAIL","keyValue":"alice@platinum.com"}' | jq
curl -s localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" | jq

# key resolution / DICT role (step 11) — internal key resolves; unknown ⇒ 404 (external deferred to step 30)
curl -s "localhost:8082/internal/pix-keys/resolve?key=alice@platinum.com" -H "Authorization: Bearer $TOKEN" | jq
curl -si "localhost:8082/internal/pix-keys/resolve?key=someone@otherbank.com" -H "Authorization: Bearer $TOKEN" | head -1

curl -s -X DELETE localhost:8082/v1/pix-keys/alice@platinum.com -H "Authorization: Bearer $TOKEN" -i
```

> **Local Docker note:** the Docker Engine API version Testcontainers speaks is **pinned in the
> parent POM** (`docker.api.version`, default `1.44`) — a plain `mvn verify` works, no flag. If you
> are on an engine older than API 1.44, override it: `mvn verify -Ddocker.api.version=1.41`
> (see `docs/local-dev.md` §6).

## Related decisions

- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition; each service
  owns its tables, so cross-service reads go through an API (the internal lookup), not a shared table.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`, and **Pix keys
  logged in full** (raw *and* normalized side by side, so a casing or format miss is visible) — a
  deliberate LGPD trade-off for seeded data that production reverses with masking. `com.platinumcoin.pix`
  runs at DEBUG, so the DynamoDB `GetItem`/`Query`/`PutItem` and their keys are in the log too.
