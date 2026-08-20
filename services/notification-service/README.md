# notification-service

> Real-time push for the PlatinumCoin Pix platform. Holds one **SSE** connection per authenticated
> customer and feeds it from `notification-queue`, so the async half of a payment ends with a push
> instead of a poll. The platform's first **long-lived-connection** service.

- **Port:** `8087`
- **Depends on:** `common-lib` (error model, correlation-id filter + log pattern, JWT filter,
  `ProcessedEventStore`), LocalStack **SQS** (`notification-queue`), **DynamoDB** (`pix_processed_events` only)
- **Consumes:** `notification-queue` — subscribed to SNS `pix-events` with a filter policy of
  `eventType IN [PixSettled, PixReceived, PixReversed]` (step 36)

## Why it exists

A Pix send answers `202 Accepted`: the money has not settled yet, and the honest contract is "accepted
for processing". That leaves the customer with a question — *did it go through?* — and polling
`GET /payments/{transactionId}` is the fallback answer, not a good one. This service closes the loop:
the same event that tells the platform a payment finished also lights up the customer's screen.

**It is deliberately best-effort.** A missed push costs a customer a refresh; it never costs
correctness, because every outcome remains queryable. That single fact is what licenses every design
choice below — dropping events nobody is listening for, acking on every outcome, keeping the registry
in memory.

### Why SSE and not WebSocket

Notifications are **one-directional**, server → client. SSE is plain HTTP with a `text/event-stream`
body, so it needs no protocol upgrade, no separate framing, and no client library — and the browser's
native `EventSource` **reconnects on its own**, which is most of the reliability story for free.
WebSocket buys a channel back to the server that this feature has no use for, and charges for it in
proxy compatibility and client code. The seam is `NotificationChannel`: swapping the transport is one
adapter.

## Endpoints

| Method | Path | Auth | Description |
| ------ | ---- | ---- | ----------- |
| `GET` | `/v1/notifications/stream` | Bearer | Opens the caller's SSE stream (`text/event-stream`). Frames carry `event:` = the event type and `id:` = the `eventId`; `data:` is the event payload. Heartbeat comments (`:ping`) every 25s |
| `GET` | `/actuator/health` | public | Liveness/readiness for compose healthchecks |

Contract source of truth: [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml) `/notifications/stream`.

**The stream is the caller's own, and cannot be anything else.** The account comes from the JWT
`accountId` claim — there is no path, query or body parameter naming an account, so "stream someone
else's payments" is not a request this API can express. That is the read-side of Domain Safety Rule #1,
and it is stronger than an ownership check because there is nothing left to check.

### The SSE handshake, and its one trade-off

A browser's native `EventSource` **cannot set request headers** — there is no API for it — so a stream
that only accepts `Authorization` is a stream no `EventSource` can open. Step 05 left an allow-list hook
here for that; step 38 resolved it a different way:

`SseTokenHandshakeFilter` (in `infra/security/`) runs immediately before common-lib's `JwtAuthFilter`
and, **for this one path only**, promotes `?access_token=<jwt>` into an `Authorization: Bearer` header.
The route is never made public, and common-lib stays the only code in the platform that decides whether
a token is good — so a bad token is refused by the same filter, with the same `401 problem+json`,
whichever way it was presented. An explicit header always wins over the parameter.

The cost, stated plainly: **a token in a URL is worse than a token in a header** — it lands in access
logs, in `Referer`, in browser history. Accepted here because this is a local sandbox with 15-minute
tokens, and bounded because no other route in the platform can be authenticated this way. The production
posture is a short-lived single-use stream ticket (or a cookie): same shape, credential worthless once
used.

## How an event becomes a push

```
pix-events (SNS)
   └─ filter: eventType IN [PixSettled, PixReceived, PixReversed]
        └─ notification-queue (SQS, +DLQ after 5 receives)
             └─ NotificationQueueConsumer          (api/ — a queue is a way of ENTERING the app)
                  └─ DeliverNotificationUseCase    dedupe by eventId → route → push
                       └─ SseEmitterRegistry       accountId → subscriptionId → live emitter
```

**Routing** (`NotificationRouting`) is the one policy decision here, and it reads as one sentence: *an
outcome of a send belongs to the payer, an arrival belongs to the payee.* `PixSettled`/`PixReversed` →
`debtorAccountId`; `PixReceived` → `creditorAccountId`. Both accounts travel in the event payload
precisely so this consumer never has to re-resolve the directory — a synchronous lookup inside an
asynchronous fan-out would make a directory outage stop notifications that have nothing to do with it.

**Dedup** is the shared `pix_processed_events` gate under `CONSUMER#notification-service`. The consumer
name is part of the key, which is what makes fan-out work: settlement and notification both consume the
same `PixSettled`, and each must see it exactly once without starving the other.

**Ack semantics.** SQS has no ack — not deleting *is* the retry. Every outcome here acks
(`DELIVERED`, `NO_SUBSCRIBER`, `DUPLICATE`, `UNROUTABLE`); only a thrown exception leaves the message
for redelivery, and the use case releases its dedup claim on the way out so the redelivery is real work.
Holding a message for a customer who may not open the app for a week would only fill the DLQ with work
that can never succeed.

**Claim-then-act, the opposite of the inbound webhook.** `ReceiveInboundPixUseCase` credits money
*before* it records the delivery, because a crash in between must replay. This use case claims first,
because pushing twice is a visible defect while losing a push in a crash window costs nothing already
answered by the status endpoint. Same mechanism, opposite ordering, because the risks are opposite.

## Heartbeats — a keepalive that is also the garbage collector

Every 25 seconds each open stream receives an SSE **comment** (`:ping`), which every client including
`EventSource` ignores for free. It does two jobs:

- **Outward:** a Pix stream is silent almost all the time, and proxies, load balancers and carrier NATs
  reclaim connections that look idle — commonly after 30–120s. 25s sits under the shortest of those.
- **Inward:** a customer closing the app sends this server *nothing it will notice*. An async response
  that is not being written to never learns its socket is gone, so no callback fires and the
  registration simply stays. **The next attempted write is what discovers it** — which is why a push
  service without a heartbeat leaks a registration per customer who ever connected.

`SseIT#aDisconnectedClientIsRemovedFromTheRegistry` pins exactly that mechanism.

## Configuration

| Property / env | Default (dev) | Meaning |
| -------------- | ------------- | ------- |
| `JWT_SECRET` / `jwt.secret` | dev-only 32-byte key | HS256 shared secret; must match auth-service's |
| `NOTIFICATION_QUEUE_NAME` | `notification-queue` | Queue consumed; its URL is resolved at **startup** — booting healthy while consuming nothing would be the worst failure mode |
| `NOTIFICATION_STREAM_TIMEOUT_MS` | `1800000` (30 min) | Per-connection deadline. A closing stream is a non-event: `EventSource` reconnects on its own |
| `NOTIFICATION_HEARTBEAT_DELAY_MS` | `25000` | Heartbeat sweep interval |
| `NOTIFICATION_TOKEN_PARAM` | `access_token` | Query parameter the handshake accepts a token in. **Blank it to accept only the `Authorization` header** |
| `NOTIFICATION_WAIT_TIME_SECONDS` | `20` | SQS long-poll (the maximum) |
| `NOTIFICATION_BATCH_SIZE` | `10` | Messages per receive. Larger than settlement's 5: handling one message here is a map lookup and a socket write, not a 12s call to BACEN |
| `NOTIFICATION_CONSUMER_DELAY_MS` | `500` | Gap between polls (`fixedDelay`, so ticks never overlap) |
| `PIX_SCHEDULERS_ENABLED` | `true` | Master switch for the consumer + heartbeat; ITs set it `false` and drive each tick |
| `AWS_ENDPOINT_URL` / `DYNAMODB_ENDPOINT_URL` | LocalStack / dynamodb-local | SQS and the dedup table |
| `spring.mvc.async.request-timeout` | `-1` | Must stay unbounded: an SSE response *is* a never-finishing async request, and any positive value would tear down every healthy stream on that schedule |

## Architecture (ADR-0010 + ADR-0011, hexagonal-lite with explicit use cases)

```
api/               NotificationStreamController, NotificationQueueConsumer,
                   NotificationHeartbeatJob, NotificationMessage           (inbound adapters)
domain/model/      Notification, Subscriber, HeartbeatResult                   (plain Java)
domain/port/       SubscriberRegistry<S>, NotificationChannel,
                   ProcessedEvents                                    (outbound interfaces)
domain/service/    NotificationRouting                                         (plain Java)
domain/usecase/    OpenNotificationStreamUseCase, DeliverNotificationUseCase,
                   SendHeartbeatsUseCase + their command/outcome records       (plain Java)
infra/web/         SseEmitterRegistry                          (the live-connection adapter)
infra/security/    SseTokenHandshakeFilter
infra/persistence/ DynamoProcessedEvents
infra/config/      NotificationBeansConfig, AwsClientsConfig, AwsProperties,
                   SchedulingConfig, CorsConfig             (outbound adapters + wiring)
```

**Three inbound adapters, one rule.** A controller, a queue consumer and a scheduled job are three ways
of *entering* this application, so all three live in `api/` and all three do the same thing: call one use
case, hold no policy.

**`infra/web/` is a new role folder** (ADR-0010's role list, amended by this step): a service that holds
long-lived connections has a transport adapter that is neither persistence, nor a client of another
service, nor security, nor config.

**Why `SubscriberRegistry<S>` is generic** — the one piece of this service worth reading twice. The
controller must hand Spring MVC back an `SseEmitter`, a framework type `domain/` may not name, yet the
object is *created* by the adapter that owns the transport, so it has to travel out through the use
case. The type parameter is how the domain **names that handle without knowing it**: `domain/` stays
plain Java, `api/` sees a concrete `OpenNotificationStreamUseCase<SseEmitter>`, and no transport object
is laundered through `Object` and a cast. `NotificationBeansConfig` is the single place `SseEmitter` is
named. Without it, the natural way to write a push service is to let `SseEmitter` spread into the
domain — and then the routing rule can only be tested with a servlet container running.

Two ArchUnit rules in `NotificationArchitectureTest` fail the build on a violation.

## Run

```bash
# from repo root
mvn -pl services/notification-service -am clean package
java -jar services/notification-service/target/notification-service-0.0.1-SNAPSHOT.jar
# or via compose (needs localstack + dynamodb-local)
docker compose -f infra/docker-compose.yml up -d --build notification-service
```

## Test

```bash
mvn -pl services/notification-service verify     # unit (*Test) + integration (*IT, Testcontainers)

# watch a real stream (terminal 1)
BOB=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bob"}' | jq -r .accessToken)
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $BOB"

# make money arrive (terminal 2) — mock-bacen presents an inbound Pix to bob
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@platinum.com","amount":"12.34","payerName":"Carol"}'
# terminal 1 shows:  event:PixReceived / id:evt-… / data:{…"amountCents":1234…}

# the EventSource-shaped handshake (no header)
curl -N "localhost:8087/v1/notifications/stream?access_token=$BOB"
```

## Known gaps (deliberate, and where they close)

- **The pushed payload is the raw event payload.** Step 39 standardizes it on the external status
  vocabulary `GET /payments/{transactionId}` uses (`type`, `status`, `amount` as a decimal string,
  `counterpart`, `timestamp`, `transactionId`) so clients parse one shape everywhere.
- **The payee of an *internal* send is not notified.** An internal Pix emits one `PixSettled`, routed to
  the payer; no `PixReceived` exists for it because the money never left the bank. Step 39 owns the
  event → recipient mapping and is where that gap belongs.
- **The registry is per-instance.** A second replica behind a load balancer would only reach the
  customers connected to *it*. Local runs are single-instance; the production shape is a shared pub/sub
  fan-out with the registry staying local, and `NotificationChannel` is already the seam for it.
- **No cap on connections per account.** A client that reconnects in a loop without the old streams
  being swept could accumulate registrations for up to one heartbeat interval.

## Related decisions

- [ADR-0004](../../docs/adr/0004-transactional-outbox-with-polling-publisher.md) — the outbox + SNS
  fan-out this service is the second consumer of; at-least-once delivery is why the dedup gate exists.
- [ADR-0006](../../docs/adr/0006-microservices-decomposition.md) — service boundaries, and
  `pix_processed_events` as the documented cross-service table.
- [ADR-0010](../../docs/adr/0010-clean-architecture-lite.md) — clean/hexagonal-lite; amended by this
  step with the `infra/web/` role.
- [ADR-0011](../../docs/adr/0011-explicit-use-case-layer.md) — one use case per inbound operation; no
  business policy in `api/`.
- [ADR-0012](../../docs/adr/0012-verbose-logs-with-real-values.md) — the queue consumer restores the
  event's `correlationId` onto the MDC, so one `grep <correlationId>` walks a payment from the send
  request all the way to the push. The line not crossed: the token is never logged, only the fact that
  the handshake used the query-parameter path.
