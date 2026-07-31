# account-service

> Accounts & Pix-keys service for the PlatinumCoin Pix platform. The **first service that talks to
> DynamoDB** via the AWS SDK. Step 09 delivers account reads; step 10 adds Pix-key
> register/list/delete with global uniqueness; internal key resolution lands in step 11.

- **Port:** `8082`
- **Depends on:** `common-lib` (error model, correlation-id filter, JWT validation, JSON logging)
- **Infra:** LocalStack (DynamoDB, tables `pix_accounts` + `pix_keys`) — created + seeded by the step-07 init scripts

## Why it exists

account-service owns the `pix_accounts` table and is the **first AWS-SDK adapter** in the platform.
It demonstrates the ADR-0010 hexagonal-lite split in practice: an `AccountRepository` **port** in
`domain/`, a `DynamoAccountRepository` **adapter** in `infra/` (the only place `software.amazon.awssdk.*`
appears), controllers in `api/`. `GET /accounts/me` derives the account **from the JWT** (`accountId`
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

## Architecture (ADR-0010, hexagonal-lite)

```
api/    AccountController (/v1/accounts/me), InternalAccountController (/internal/accounts/{id}),
        PixKeyController (/v1/pix-keys), AccountResponse, InternalAccountResponse,
        RegisterPixKeyRequest, PixKeyResponse                                       (inbound adapters)
domain/ Account, PixKey (records), PixKeyType (enum), AccountRepository,
        PixKeyRepository (ports)                                                    (plain Java)
infra/  DynamoAccountRepository, DynamoPixKeyRepository (AWS SDK), DynamoConfig,
        AwsProperties                                                              (outbound adapter + wiring)
```

`domain/` imports nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson) — enforced by
`AccountArchitectureTest` (ArchUnit), which fails the build on a violation.

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
curl -s -X DELETE localhost:8082/v1/pix-keys/alice@platinum.com -H "Authorization: Bearer $TOKEN" -i
```

> **Local Docker note:** if `mvn verify` fails with a Testcontainers `Could not find a valid Docker
> environment` / HTTP `400` (Docker Desktop's minimum API version rejects docker-java's default
> v1.32), run the ITs with `-DargLine="-Dapi.version=1.44"` (environment quirk, no code change — see
> CHANGELOG step 08).

## Related decisions

- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition; each service
  owns its tables, so cross-service reads go through an API (the internal lookup), not a shared table.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
