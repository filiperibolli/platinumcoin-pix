# ledger-service

> The **ledger** of the PlatinumCoin Pix platform — the single owner and the only writer of the
> `pix_ledger` table. Step 13 delivers the read half: the data model plus a **strongly consistent**
> balance read. The atomic double-entry posting lands in step 14, the invariant suite in step 15 and
> the paginated statement in step 16.

- **Port:** `8085`
- **Depends on:** `common-lib` (error model, correlation-id log pattern, JWT validation)
- **Infra:** LocalStack (DynamoDB, table `pix_ledger`) — created by the step-12 init script and seeded
  with the platform's money supply by `infra/localstack/init/05-seed-ledger.sh`

## Why it exists

Money correctness is the one non-negotiable of this platform, so exactly one service is allowed to
touch the ledger table (ADR-0006). Everything else — payment-service, reconciliation, the balance
cache — asks *this* service, which means the invariants (never a negative balance, never a debit
without its credit, never the same posting twice) live in one place and cannot be routed around.

This step deliberately reads before it writes: mapping the domain model onto the **already seeded**
table proves the model against real items while nothing is at stake, so step 14's first
`TransactWriteItems` is not also the first time the shape of an item is tested.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `GET` | `/internal/ledger/accounts/{accountId}/balance` | Bearer | Balance of one ledger account → `{accountId, balance, balanceCents, version}`. Strongly consistent read. |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

Unknown account ⇒ `404 application/problem+json` with `code: LEDGER_ACCOUNT_NOT_FOUND`. No token ⇒
`401` (`code: UNAUTHORIZED`) — this service has **no public surface at all**: `/internal/**` is
deliberately absent from `jwt.public-paths`, and there is no `/v1` API because no end user talks to
the ledger; payment-service does, on their behalf.

### The balance read (step 13)

- **`ConsistentRead=true`, always.** DynamoDB reads are eventually consistent by default (they cost
  half as much), but the ledger has to **read its own writes**: a balance is queried right after a
  posting, and a stale answer would show money that is already spent. The flag is asserted on the
  request itself in `DynamoLedgerRepositoryTest` — LocalStack is a single node and would return the
  right value either way, so only the request shape can prove it. It also explains why the balance
  lives at a base-table key: a GSI is *always* eventually consistent.
- **Both money representations on the wire.** `balance` is a decimal BRL string (`"10000.00"`) for
  the human running the runbook curl; `balanceCents` is the same amount as an integer for the
  services that do arithmetic on it (payment-service in step 21, the cache in step 40). Inside the
  domain it is only ever a `long` — `BalanceResponse` is the single place that formats.
- **`version` is a change counter, not a lock.** It is bumped by every posting and is there for audit
  and debugging. Nothing reads it, decides, and writes back conditioned on it: conflicting writers are
  serialized by DynamoDB transactions themselves (ARCHITECTURE §6.3). The version-as-optimistic-lock
  strategy is implemented for contrast in the relational lab (ADR-0009, step 50).
- **Not account-scoped, on purpose.** The account comes from the path, because this is an internal
  seam: payment-service reads a payee's balance, reconciliation reads `SPI_CLEARING`. The rule that
  "the debited account comes from the JWT" binds the money-moving endpoint in payment-service, which
  is where a client can name an account. Nothing here moves money.
- **404, never a zero.** An account with no BALANCE item is a hard miss. If it answered `0`, "this
  account does not exist" would be indistinguishable from "this account has no money" — opposite
  facts in a ledger.

### System accounts

`ACCOUNT#SEED` (the funding source, negative by construction) and `ACCOUNT#SPI_CLEARING` (money in
flight to/from BACEN, `0` at rest) are ordinary partitions and are read like any other. They are
exempt from the `balanceCents >= :amount` guard on **writes** (step 14), never from being read. Their
existence is what makes Σ over every account equal **zero** — the conservation invariant step 15
asserts under a concurrent debit storm, and which `DynamoLedgerRepositoryIT` pins at its baseline.

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/    InternalLedgerController (/internal/ledger/accounts/{id}/balance),
        BalanceResponse (the only cents → decimal-string conversion),
        LedgerExceptionHandler (domain exception → problem+json)                  (inbound adapters)
domain/         Balance, LedgerEntry (records), Direction (enum),
                LedgerRepository (port), LedgerAccountNotFoundException               (plain Java)
domain/usecase/ GetBalanceUseCase                                                     (plain Java)
infra/  DynamoLedgerRepository (AWS SDK — the only ConsistentRead in the module),
        DynamoConfig, LedgerBeansConfig (composition root), AwsProperties,
        CorsConfig                                                        (outbound adapter + wiring)
```

`LedgerEntry` and `Direction` are written for the first time in step 14; they exist now because the
model is what this step validates against the seeded table.

Two ArchUnit rules in `LedgerArchitectureTest` fail the build on a violation: `domain/` imports
nothing outward (no Spring / AWS SDK / servlet / JWT / Jackson), and `api/` never depends on an
interface in `domain/` — which is what makes "a controller may not reach the ledger table"
mechanical rather than a review habit.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must equal auth-service's. This service only **validates** tokens. |
| `jwt.public-paths` | `/actuator/**` | Paths the shared `JwtAuthFilter` skips. `/internal/**` is **not** here — every balance read requires a token. |
| `AWS_ENDPOINT_URL` / `aws.endpoint-url` | `http://localhost:4566` | LocalStack edge; compose overrides to `http://localstack:4566`. |
| `AWS_REGION` / `aws.region` | `us-east-1` | SDK region. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` | Dummy creds LocalStack ignores but the SDK demands. |

## Run

```bash
# from repo root — build the jar first
mvn -pl services/ledger-service -am clean package

# LocalStack must be up (provides DynamoDB + the seeded pix_ledger money supply)
docker compose -f infra/docker-compose.yml up -d localstack

# then either run standalone…
java -jar services/ledger-service/target/ledger-service-0.0.1-SNAPSHOT.jar
# …or via compose (builds the image, waits on localstack healthy)
docker compose -f infra/docker-compose.yml up -d --build ledger-service
```

## Test

```bash
mvn -pl services/ledger-service verify         # unit (*Test) + integration (*IT, Testcontainers)

# happy path (needs a token; mint one from auth-service)
TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)

# alice's seeded balance → {"accountId":"acc-001","balance":"10000.00","balanceCents":1000000,"version":0}
curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN" | jq

# the system accounts — SEED is negative by construction, and the four balances sum to zero
curl -s localhost:8085/internal/ledger/accounts/SEED/balance -H "Authorization: Bearer $TOKEN" | jq
curl -s localhost:8085/internal/ledger/accounts/SPI_CLEARING/balance -H "Authorization: Bearer $TOKEN" | jq

# unknown account ⇒ 404 LEDGER_ACCOUNT_NOT_FOUND (not a zero balance)
curl -s localhost:8085/internal/ledger/accounts/acc-999/balance -H "Authorization: Bearer $TOKEN" | jq

# no token ⇒ 401 UNAUTHORIZED, even on /internal/**
curl -si localhost:8085/internal/ledger/accounts/acc-001/balance | head -1
```

> **Local Docker note:** the Docker Engine API version Testcontainers speaks is **pinned in the
> parent POM** (`docker.api.version`, default `1.44`) — a plain `mvn verify` works, no flag. If you
> are on an engine older than API 1.44, override it: `mvn verify -Ddocker.api.version=1.41`
> (see `docs/local-dev.md` §6).

## Related decisions

- [ADR-0001](../../docs/adr/0001-dynamodb-for-the-ledger.md) — DynamoDB for the ledger: the posting is
  one `TransactWriteItems`, and the guards are conditions inside it.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service decomposition; the ledger
  owns `pix_ledger`, so every other service reads it through this API, never through the table.
- [ADR-0009](../../docs/adr/0009-relational-ledger-counterpart-lab.md) — the PostgreSQL counterpart lab
  that implements the same ledger with pessimistic and optimistic locking, for contrast.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite per service.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — explicit use-case layer; no business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — verbose sandbox logging inherited
  from `common-lib`: `[cid=… tx=…]` on every record, English sentences plus `key=value`, and **amounts
  in cents and account ids logged in the clear** — a deliberate LGPD trade-off for seeded data that
  production reverses. `com.platinumcoin.pix` runs at DEBUG, so the DynamoDB `GetItem` and the exact
  key it read are in the log too.
