# mock-bacen-spi

> Stand-in for **BACEN's SPI** (the instant-payment rail) and **DICT** (the Pix key directory). The one
> component in the stack whose job is to *misbehave on demand*. **Not a domain service** — no money lives
> here, no invariant is defended here, and its memory is wiped by a restart.

- **Port:** `9090`
- **Depends on:** `common-lib` (correlation-id log pattern + RFC 7807 error model). Since step 37 it also
  *calls* settlement-service's inbound webhook — lazily, per request, never at boot: gating startup on it
  would be a dependency cycle (settlement-service already waits for this stub to be healthy).
- **Infra:** none. Settlements are held in an in-memory map.

## Why it exists

Every reliability claim this platform makes — retries with backoff, DLQ redrive, query-before-retry,
reconciliation inside 5 minutes — is a claim about **what happens when BACEN misbehaves**. You cannot test
that against a dependency that always works, and you cannot ask the real SPI to fail on cue. So the failure
modes are made first-class and armable at runtime:

| Knob | Effect on `POST /spi/settlements` |
| ---- | --------------------------------- |
| `latencyMs` | burns that much wall-clock before answering (the real SPI SLA is ≤ 10s) |
| `failureRate` | answers `503` and **records nothing** — transient, so the same `endToEndId` can still settle later |
| `timeoutRate` | **settles, then hangs** past the caller's timeout — BACEN moved the money, the caller never heard |
| `rejectKeys` | a set of creditor keys **refused permanently at settlement** (`422 SETTLEMENT_REJECTED_BY_ADMIN`) even though the DICT knows them — the send-reachable trigger for step 33's reversal (step 35) |

The third row is the important one. It manufactures the nastiest state in distributed payments: the money
moved and the caller believes it did not. A client that blindly re-`POST`s is saved only by `endToEndId`
idempotency; a client that **queries first** learns the truth. Step 32 builds that rule, and it can only be
tested against a dependency that lies in exactly this way.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `POST` | `/spi/settlements` | none | Settle one Pix. **Idempotent by `endToEndId`**: the first terminal outcome wins forever and a replay is byte-for-byte the original answer. `200` on settlement, `503 SPI_UNAVAILABLE` / `504 SPI_TIMEOUT` when injected, `422 SPI_REJECTED` if the creditor key is in no participant's hands. |
| `GET` | `/spi/settlements/{endToEndId}` | none | `SETTLED` / `FAILED` / `UNKNOWN`. **Always `200`** — "never heard of it" is an answer reconciliation acts on, and a `404` would be indistinguishable from a wrong URL. |
| `POST` | `/admin/config` | none | Re-arm the dial at runtime. **Partial**: an absent field is left unchanged, so `{"failureRate":1.0}` does not reset the latency. Also carries `rejectKeys` (step 35): a DICT-known key on the list is refused at settlement, so a real send can be driven to a reversal — `{"rejectKeys":["bob@otherbank.com"]}` to arm, `{"rejectKeys":[]}` to clear. Out-of-range ⇒ `400 VALIDATION_ERROR`. |
| `GET` | `/admin/config` | none | What is armed right now (including the read-only `timeoutHangMs`). |
| `GET` | `/spi/dict/{key}` | none | Which participant holds a Pix key → `{key, keyType, ispb, participant}`; unknown ⇒ `404 DICT_KEY_NOT_FOUND`. |
| `POST` | `/simulate/inbound-pix` | none | **The trigger that makes money arrive** (step 37). Body `{pixKey, amount, payerName?, payerIspb?}`. Mints an `endToEndId` and presents the payment to settlement-service's webhook with the shared `X-Webhook-Token`, retrying like a real rail. |
| `GET` | `/actuator/health` | none | Liveness/readiness for the compose healthcheck. |

**Request** (`POST /spi/settlements`) — money is integer cents, never a decimal string:

```json
{"endToEndId":"E12345678202608121000abc123","creditorKey":"bob@otherbank.com","amountCents":20000,"debtorIspb":"12345678"}
```

### The two idempotency-adjacent decisions worth knowing

- **An injected `503` records nothing.** If it were remembered as `FAILED`, every retry would replay the
  failure and no backoff drill could ever succeed. Transient means *forgettable*.
- **A rejection (`422`) is terminal.** The rail looked and said no; retrying cannot change the answer, so
  the payer must be made whole with a compensating posting (step 33's `FAILED → REVERSED`). Collapsing
  it into the `503` would erase the distinction the settlement flow has to act on.
- **A retry that changes the amount replays the amount actually settled**, and logs the mismatch loudly. An
  `endToEndId` identifies *one* transfer; a second amount is not a correction.

### `POST /simulate/inbound-pix` — the one direction where the stub is the *caller* (step 37)

```bash
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"300.00","payerName":"External Payer"}' | jq
```

It performs the two acts the originating side of a Pix performs: mint the `endToEndId` and present the
payment to the receiving participant, re-presenting it while the outcome is unknown. Three decisions in it
are deliberate:

- **`/simulate/…`, not `/spi/…`.** Everything under `/spi` stubs a real BACEN API that PlatinumCoin calls.
  This has no real counterpart at all — no participant asks BACEN to send it money — so it is a *test hook*
  on the rail, in the same family as `/admin/config`. Naming it apart keeps the honest boundary visible.
- **The `endToEndId` carries the *payer's* ISPB** (`99999999` by default), never PlatinumCoin's: an
  end-to-end id names the participant that originated the payment.
- **Retrying is the feature.** A rail that delivered once and gave up would never exercise the receiving
  side's `endToEndId` dedupe. A `5xx` or no answer ⇒ re-present the **same** id (up to `INBOUND_MAX_ATTEMPTS`);
  a `4xx` ⇒ stop at once and bounce — it is a decision, and retrying a `401` forever is how a real
  integration wedges itself. The `/admin/config` dial deliberately does **not** apply here: those knobs
  model BACEN misbehaving *toward* us on the settlement path.

Money is a decimal string on this endpoint (`"300.00"`) and integer cents one hop later on the webhook —
not an inconsistency: this one is typed by a human in a runbook, the webhook is machine-to-machine. Answers:
`200` with the participant's own outcome (`CREDITED` / `ALREADY_PROCESSED`); `422 INBOUND_REFUSED` when the
participant refused permanently (bad token, unknown key); `502 INBOUND_DELIVERY_FAILED` when it never gave
an answer the rail could act on.

### The DICT is deliberately outside the failure injection

Latency/failure/timeout apply to settlement only. Key resolution sits on the **synchronous** send path with
the payer waiting on it (p99 < 2s), while settlement is the asynchronous half nobody waits for — slowing the
directory would blow the send SLO and prove nothing about settlement resilience. What happens when the
directory is *unreachable* is decided on the caller's side: account-service answers
`503 DIRECTORY_UNAVAILABLE` rather than pretending the key does not exist.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `BACEN_LATENCY_MS` / `bacen.latency-ms` | `2000` | Simulated settlement latency, 0–10000 |
| `BACEN_FAILURE_RATE` / `bacen.failure-rate` | `0.0` | Fraction of settlement calls that `503`. `0.0`/`1.0` are **exact**, never probabilistic |
| `BACEN_TIMEOUT_RATE` / `bacen.timeout-rate` | `0.0` | Fraction that settle then hang |
| `BACEN_TIMEOUT_HANG_MS` / `bacen.timeout-hang-ms` | `15000` | How long such a call hangs. Boot-time only — a value lowerable mid-drill would turn a "timeout" into a slow success |
| `bacen.dict[<key>]` | `bob@otherbank.com`, `carol@otherbank.com` → ISPB `99999999`; `+5511977776666`, `98765432100` → ISPB `88888888` | The external-PSP keys this stub answers for |
| `JWT_SECRET` | dev-only value | Present only because the inherited filter builds its key eagerly. **Nothing here verifies a signature** |
| `SETTLEMENT_BASE_URL` / `bacen.inbound.participant-base-url` | `http://localhost:8086` | Where an inbound Pix is delivered (compose: `http://settlement-service:8086`). Resolved per call, never at boot |
| `SPI_WEBHOOK_TOKEN` / `bacen.inbound.webhook-token` | empty | Shared secret presented as `X-Webhook-Token`; must match settlement-service's. Never logged (ADR-0012) |
| `INBOUND_MAX_ATTEMPTS` / `INBOUND_RETRY_DELAY_MS` | `3` / `500` | The rail's re-presentation budget for an **unknown** outcome. A `4xx` is never retried |
| `INBOUND_DEFAULT_PAYER_ISPB` / `INBOUND_DEFAULT_PAYER_NAME` | `99999999` / `External Payer` | Who an inbound payment appears to come from when the request does not say. The ISPB is Banco OtherBank's — the participant already in the DICT, so inbound and outbound examples name the same counterpart |

## Architecture (the ADR-0010 scope note, used deliberately)

```
api/     SpiSettlementController, SpiDictController, AdminConfigController,
         SpiInboundController (POST /simulate/inbound-pix, step 37),
         SettlementRequest/SettlementView, DictEntryResponse, AdminConfigRequest/Response,
         InboundPixRequest/InboundPixResponse, SpiExceptionHandler             (inbound adapters)
spi/     Settlement, SettlementStatus, SettlementStore, SpiBehavior, SpiDirectory, DictEntry,
         SpiUnavailable/SpiTimeout/SettlementRejected/DictKeyNotFound exceptions,
         InboundPixGenerator, InboundWebhookClient, Amount,
         InboundDeliveryFailedException (step 37)                                  (the stub core)
config/  BacenProperties, BacenConfig, CorsConfig
```

`InboundWebhookClient` is the one **outbound** adapter here, and it lives in `spi/` rather than an
`infra/client/` of its own: with no `domain/` to protect there is no dependency rule for it to cross, and a
lone package for a single class would be structure without a reason (the ADR-0010 scope note again).

ADR-0010 (restated in ADR-0011) grants stubs a thinner structure, and this module takes it: **no ports, no
`domain/`, no use-case layer, no `*ArchitectureTest`**. The exemption is bounded — every other item on the
new-service checklist still applies (module + POM, Dockerfile, compose entry, this README, CORS, Postman
folder, API-explorer section). Inventing a hexagonal domain for a fake would be ceremony that makes the
codebase *harder* to read, not easier: the layers exist to protect money invariants, and there are none here.

**Trust boundary.** BACEN is an external party and validates none of PlatinumCoin's tokens (a real
participant presents mTLS + an ICP-Brasil certificate). The inherited `JwtAuthFilter` is therefore
neutralised by configuration — `jwt.public-paths: /**` — rather than removed, so the reason it is open is
written down instead of implied by a missing dependency.

## Run

```bash
# from repo root
mvn -pl services/mock-bacen-spi -am clean package
java -jar services/mock-bacen-spi/target/mock-bacen-spi-0.0.1-SNAPSHOT.jar
# or via compose
docker compose -f infra/docker-compose.yml up -d --build mock-bacen-spi
```

## Test

```bash
mvn -pl services/mock-bacen-spi verify          # unit (*Test) + MockMvc integration (*IT), no Docker

# settle a Pix (no token — BACEN is outside our trust domain)
E2E=E12345678202608121000abc123
curl -s -X POST localhost:9090/spi/settlements -H 'Content-Type: application/json' \
  -d "{\"endToEndId\":\"$E2E\",\"creditorKey\":\"bob@otherbank.com\",\"amountCents\":20000}" | jq
# retry it: same answer, same recordedAt — one settlement, not two
curl -s -X POST localhost:9090/spi/settlements -H 'Content-Type: application/json' \
  -d "{\"endToEndId\":\"$E2E\",\"creditorKey\":\"bob@otherbank.com\",\"amountCents\":20000}" | jq
curl -s "localhost:9090/spi/settlements/$E2E" | jq          # SETTLED
curl -s localhost:9090/spi/settlements/E-never-sent | jq    # 200 {status:"UNKNOWN"}

# arm a drill, then disarm it
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' \
  -d '{"latencyMs":2000,"failureRate":1.0}' | jq
curl -s -X POST localhost:9090/admin/config -H 'Content-Type: application/json' -d '{"failureRate":0.0}' | jq

# the DICT — this is what makes an external send resolve at all
curl -s localhost:9090/spi/dict/bob@otherbank.com | jq
curl -si localhost:9090/spi/dict/nobody@nowhere.com | head -1   # 404
```

## Related decisions

- [ADR-0003](../../docs/adr/0003-async-settlement-and-reconciliation.md) — asynchronous settlement and
  bounded reconciliation: the reason `GET /spi/settlements/{endToEndId}` exists and answers `UNKNOWN`.
- [ADR-0002](../../docs/adr/0002-idempotency-strategy.md) — the third idempotency layer is the
  `endToEndId` toward the SPI; this stub is where that layer is actually enforced.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) / [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md)
  — the scope note that grants this module a stub's structure.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — the `[cid=… tx=…]` pattern inherited
  from `common-lib`, which is the entire reason a fake depends on the shared library: the SPI is a hop on
  the money path, so one `grep <correlationId>` must reconstruct it too. Keys, amounts in cents and every
  injected decision are logged in the clear; the injection lines are `WARN` because a designed failure is a
  degradation, not an actionable fault.
