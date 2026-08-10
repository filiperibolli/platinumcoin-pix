# payment-service

> The **send-Pix entry point** of the PlatinumCoin platform — the one endpoint an end user reaches to
> move money. Step 18 delivers the **walking skeleton** of `POST /v1/payments/pix`: JWT-authenticated,
> body validated per the OpenAPI contract, `txId` + Pix-standard `endToEndId` generated, the
> transaction persisted as `RECEIVED` in `pix_transactions`, and a `202 Accepted` returned with a
> `Location` header. Step 19 adds the **idempotency layer** (claim / replay / `409`). Step 20 adds
> **daily-limit enforcement** (a calendar-day reservation counter with an MFA seam). Key resolution and
> the ledger debit thicken the skeleton in step 21.

- **Port:** `8084`
- **Depends on:** `common-lib` (error model, correlation-id log pattern, JWT validation); **account-service**
  (reads `dailyLimitCents` from `GET /internal/accounts/{id}` before reserving limit headroom, step 20)
- **Infra:** LocalStack (DynamoDB, tables `pix_transactions` + `pix_idempotency`) — created by the
  step-17 init script `infra/localstack/init/03-dynamodb-payment.sh` (no seed rows; transactions are
  born from the flow, not seeded). The daily-limit counter lives in `pix_transactions` as
  `LIMIT#<accountId>`/`DAY#<yyyy-MM-dd>` items with a ~48h TTL on `expiresAt`.

## Why it exists

Sending a Pix is the platform's headline flow, and it is the endpoint where the domain safety rules
actually bite: the debited account must come from the token and never the payload, money must be
idempotent, and the debit must be atomic with its credit. This service is where all of that is
enforced on the way in.

The walking-skeleton technique is deliberate: get a **real, persisted, JWT-protected** request working
with the correct *shape* — status codes, headers, ids, the debtor-from-JWT rule — before adding any
behaviour. The `endToEndId` is minted now in the Pix standard `E<ISPB><timestamp><random>` because it
is stable for the whole life of the transaction and later becomes the idempotency key toward BACEN.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `POST` | `/v1/payments/pix` | Bearer | Accept a send-Pix → `202` + `Location: /v1/payments/{txId}` + `{transactionId, endToEndId, status:"PROCESSING"}`. Persists the transaction as `RECEIVED`. |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

| Outcome | Status | `code` |
| ------- | ------ | ------ |
| accepted for processing (or an idempotent replay of one) | `202` | — |
| body fails bean validation (`pixKey` blank, `amount` not `^\d{1,9}\.\d{2}$`, `description` > 140) | `400` | `VALIDATION_ERROR` |
| amount well-formed but not strictly-positive money (`"0.00"`) or sub-cent | `400` | `INVALID_AMOUNT` |
| `Idempotency-Key` header absent/blank | `400` | `IDEMPOTENCY_KEY_REQUIRED` |
| same `Idempotency-Key` replayed with a different payload | `409` | `IDEMPOTENCY_KEY_REUSED` |
| a concurrent request with the same key is still in flight (carries `Retry-After: 2`) | `409` | `REQUEST_IN_PROGRESS` |
| the send would breach the debtor's daily Pix limit | `422` | `LIMIT_EXCEEDED` |
| account-service could not supply the debtor's limit (not found / unreachable) | `502` | `ACCOUNT_LOOKUP_FAILED` |
| no / invalid token | `401` | `UNAUTHORIZED` |

**Idempotency (step 19, ADR-0002 layer 1).** The `Idempotency-Key` header is **required** and the send
is de-duplicated per `(accountId, key)` in `pix_idempotency` (24h TTL). The lifecycle is claim →
execute → memoize → replay: a conditional claim wins or loses atomically; the winner does the work and
stores the response; a losing retry with the **same** request-hash replays the memoized response (same
`transactionId`), a **different** hash is `409 IDEMPOTENCY_KEY_REUSED`, and an `IN_PROGRESS` claim is
`409 REQUEST_IN_PROGRESS` + `Retry-After` — **unless** it is stale (claimed > 60s ago, i.e. a crash
left it orphaned), in which case the retry re-claims it, so a crash never blocks the client until the
TTL. The request-hash is a canonical-JSON SHA-256 over the normalized fields (key order / whitespace
never change it; a different amount does — `common-lib`'s `CanonicalJson`). DynamoDB TTL deletion is
lazy, so reads treat an expired-but-present record as absent.

**Daily limit (step 20, ADR-0007).** Before any money moves, the use case reads the debtor's
`dailyLimitCents` from account-service and **reserves** the amount against a per-account,
per-calendar-day counter (`LIMIT#<accountId>`/`DAY#<yyyy-MM-dd>` in `pix_transactions`, window = the
**America/São Paulo** calendar day). The reserve is a conditional `UpdateItem ADD usedCents` — an
atomic increment bounded by the limit, **not** a query-and-sum (the table has no index by debtor, and a
counter is what makes `release` well-defined). Over the limit ⇒ `422 LIMIT_EXCEEDED` with nothing
persisted. The check returns a **decision object** (`ALLOW`/`DENY`/`REQUIRE_STEP_UP`), not a boolean:
`REQUIRE_STEP_UP` is the **MFA seam** — today it maps to the same deny as `DENY`, so plugging in a
step-up challenge later changes one branch, not the flow. The reservation lives *inside* the won
idempotency claim, so a double-tap or a replay never reserves twice; `release` (`ADD -:amount`) is
provided for a later rejection/reversal to hand back exactly what it reserved (wired in steps 21/25/33).

### The send request

- **The debtor is the token, and the payload cannot express one.** `SendPixRequest` has `pixKey`,
  `amount` and `description` and **no source-account field**; the debited account is
  `AuthenticatedUser.accountId()` from the validated JWT (Domain Safety Rule #1). An extra JSON key
  like `debtorAccountId` is silently ignored, never bound — the safest enforcement is to make the
  wrong thing inexpressible.
- **Money is integer cents end to end.** `amount` arrives as a decimal string (`"125.50"`); the wire
  `@Pattern` bounds its shape (≤ 9 integer digits, exactly 2 decimals — comfortably inside a `long`),
  and `Money.toCents` converts it **without ever touching a `double`** (`BigDecimal.movePointRight(2)`
  + `longValueExact()`), enforcing the two rules the pattern cannot: strictly positive (`"0.00"` ⇒
  `400`) and no sub-cent precision.
- **The `endToEndId` is a contract, not a label.** Format `E<ISPB(8)><yyyyMMddHHmm-UTC(12)><random(11)>`
  — a fixed 32 chars. The timestamp is UTC from an injected `Clock` (deterministic across
  environments; it is an opaque id, never shown to a user). The ISPB is configuration (`pix.ispb`).
- **The persisted item is index-consistent from the first write.** The `TX#<txId> / META` item carries
  `gsi1pk = E2E#<endToEndId>` (reconciliation / inbound-dedup lookup) and `gsi2pk = STATUS#RECEIVED` +
  `gsi2sk = updatedAt` (the stuck-transaction scan), so later steps' access patterns work without a
  backfill. Fields a later step owns — the resolved creditor account + `creditorInternal` (step 21),
  the fraud verdict (step 25), the settlement fields (steps 27/31) — are deliberately not invented.

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/    PaymentController (POST /v1/payments/pix), SendPixRequest (wire shape + bean validation),
        PaymentAcceptedResponse (internal RECEIVED → external "PROCESSING"),
        PaymentExceptionHandler (domain exception → problem+json)                  (inbound adapters)
domain/         Transaction (record), TransactionStatus (enum), TransactionRepository (port),
                Money (string → strictly-positive long cents), EndToEndIdGenerator,
                AccountLimitClient + DailyLimitReservation (ports), LimitDecision (enum),
                InvalidAmountException, LimitExceededException, AccountLookupException   (plain Java)
domain/usecase/ SendPixUseCase, SendPixCommand                                        (plain Java)
infra/  DynamoTransactionRepository (AWS SDK — the only place a transaction is written),
        DynamoDailyLimitReservation (the LIMIT#/DAY# counter), HttpAccountLimitClient (RestClient →
        account-service, forwards the bearer token), DynamoConfig,
        PaymentBeansConfig (composition root: Clock, EndToEndIdGenerator, use case),
        AwsProperties, CorsConfig                                         (outbound adapter + wiring)
```

`Clock` is injected rather than read as `Instant.now()`: the transaction's instant, and the minute
baked into its `endToEndId`, are values the service decides — and values a test can pin.

Two ArchUnit rules in `PaymentArchitectureTest` fail the build on a violation: `domain/` imports
nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson), and `api/` never depends on an
interface in `domain/` — which is what makes "a controller may not reach the transactions table"
mechanical rather than a review habit.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must equal auth-service's. This service only **validates** tokens. |
| `jwt.public-paths` | `/actuator/**` | Paths the shared `JwtAuthFilter` skips. `/v1/payments/**` is **not** here — every send requires a token. |
| `PIX_ISPB` / `pix.ispb` | `12345678` | PlatinumCoin's 8-digit Pix participant id, baked into every `endToEndId`. |
| `ACCOUNT_SERVICE_BASE_URL` / `services.account-service.base-url` | `http://localhost:8082` | account-service base URL for the daily-limit lookup; compose overrides to `http://account-service:8082`. |
| `AWS_ENDPOINT_URL` / `aws.endpoint-url` | `http://localhost:4566` | LocalStack edge; compose overrides to `http://localstack:4566`. |
| `AWS_REGION` / `aws.region` | `us-east-1` | SDK region. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds LocalStack ignores but the SDK demands. |

## Run

```bash
# from repo root — build the jar first
mvn -pl services/payment-service -am clean package

# LocalStack must be up (provides DynamoDB + the step-17 payment tables)
docker compose -f infra/docker-compose.yml up -d localstack

# then either run standalone…
java -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
# …or via compose (builds the image, waits on localstack healthy)
docker compose -f infra/docker-compose.yml up -d --build payment-service
```

## Test

```bash
mvn -pl services/payment-service verify        # unit (*Test) + integration (*IT, Testcontainers)

# happy path (needs a token; mint one from auth-service)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# send R$ 125.50 to bob ⇒ 202 + Location + {transactionId, endToEndId, status:"PROCESSING"}
curl -si -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"125.50","description":"lunch"}' | head -8

# "0.00" ⇒ 400 INVALID_AMOUNT (the strictly-positive rule the wire pattern cannot express)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"0.00"}' | jq

# malformed amount ⇒ 400 VALIDATION_ERROR
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"12.5"}' | jq

# above the daily limit ⇒ 422 LIMIT_EXCEEDED (seeded limit is R$ 5,000.00; a single R$ 9,000 send
# alone exceeds it, or repeated sends accumulate past it on the same São Paulo calendar day)
curl -s -X POST localhost:8084/v1/payments/pix -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: '"$(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"9000.00"}' | jq

# no token ⇒ 401 UNAUTHORIZED
curl -si -X POST localhost:8084/v1/payments/pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"1.00"}' | head -1
```

> **Local Docker note:** the Docker Engine API version Testcontainers speaks is **pinned in the
> parent POM** (`docker.api.version`, default `1.44`) — a plain `mvn verify` works, no flag. If you
> are on an engine older than API 1.44, override it: `mvn verify -Ddocker.api.version=1.41`
> (see `docs/local-dev.md` §6).

## Related decisions

- [ADR-0002](../../docs/adr/0002-idempotency-strategy.md) — the three-layer idempotency strategy
  (API `Idempotency-Key`, ledger `txId`, SPI `endToEndId`); step 18 mints the `endToEndId`, step 19
  adds the API layer.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition;
  payment-service owns `pix_transactions` and orchestrates the send, calling ledger-service to move
  money.
- [ADR-0007](../../docs/adr/0007-auth-service-jwt-no-mfa.md) — the JWT whose `accountId` claim is the
  debtor, never the payload.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`, amounts in
  cents and account/creditor ids in the clear (an LGPD trade-off production reverses).
