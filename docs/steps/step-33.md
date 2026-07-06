# Step 33 — notification-service: SSE streams

## Objective
`GET /v1/notifications/stream` (JWT) holds an **SSE** connection per user; the service consumes `notification-queue` and routes events (`PixSettled`, `PixReceived`, `PixReversed`) to the connected emitter of the affected user. Heartbeats keep connections alive; disconnects clean up.

## Why / what you'll learn
Long-lived connections are a different beast from request/response: registry of `userId → SseEmitter(s)`, heartbeat comments every 15s (proxies kill idle connections), `onCompletion/onTimeout` cleanup, and the **single-instance caveat** stated honestly: with N replicas a user may be connected to replica A while replica B consumes the event — production answer is a broadcast channel (Redis pub/sub) or sticky routing; locally one instance suffices and the limitation is documented where it bites. Also *why SSE over WebSocket* here: one-way push, auto-reconnect built into `EventSource`, plain HTTP.

## Prerequisites
Steps 09, 22.

## Tasks
1. `EmitterRegistry` (concurrent multimap userId→emitters) + SSE endpoint (timeout 0, heartbeat scheduler).
2. Queue consumer: dedup (ProcessedEventStore), map event→target userId (payload carries debtor/creditor account → resolve userId via account-service internal, cached), emit `event: <type>\ndata: <json>`; delete message after emit attempt (missed push is acceptable — state is queryable; comment this deliberate at-most-once *for pushes only*).
3. Named SSE events + retry hint field.
4. Redis pub/sub fan-out marked as a documented production TODO (not implemented).

## Tests (TDD)
- IT: register test emitter, publish PixReceived to queue ⇒ SSE event arrives with payload; wrong-user event not delivered.
- Cleanup: closed emitter removed from registry; heartbeat present on an idle stream (read raw).
- Duplicate event ⇒ single push (dedup).

## Verify locally
```bash
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $BOB" &
curl -s -X POST localhost:9090/simulate/inbound-pix -H 'Content-Type: application/json' \
 -d '{"pixKey":"bob@platinum.com","amount":"10.00","payerName":"Tester"}'
# SSE terminal shows: event: PixReceived / data: {...}
```

## Definition of Done
- [ ] Per-user SSE with heartbeats and cleanup
- [ ] Push semantics (at-most-once) explicitly documented vs queryable state
- [ ] Multi-instance limitation + production fan-out documented in code and ARCHITECTURE cross-ref

## CHANGELOG entry
`### Added` → `notification-service: per-user SSE streams fed by notification-queue with heartbeats (step 33)`
