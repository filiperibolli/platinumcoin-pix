# Step 38 — notification-service: SSE stream per user

> **Sprint 8 — Receive & notify** · **Flow:** inbound Pix → SSE push · **Infra que sobe:** notification-service (in compose) · **Diagram:** ARCHITECTURE §6.8

## Objective
`notification-service` (port 8087): `GET /v1/notifications/stream` (JWT) holds an **SSE** connection per user; the service consumes `notification-queue` and routes events (`PixSettled`, `PixReceived`, `PixReversed`) to the connected emitter of the affected user. Heartbeats keep connections alive; disconnects clean up.

## Why / what you'll learn
**SSE over WebSocket** (documented choice): notifications are one-directional server→client push, so SSE is simpler, auto-reconnecting and plain HTTP. You'll learn the different resource profile of a service that holds **long-lived connections** (a per-user emitter registry, heartbeats to defeat idle timeouts, cleanup on disconnect) — and the auth nuance for the SSE handshake (the allow-list hook left in step 05). The consumer dedupes by `eventId` like every other; a lost notification degrades UX only (state is still queryable), so this consumer can be best-effort.

## Prerequisites
Steps 05 (JWT/SSE allow-list hook), 36 (notification-queue).

## Tasks
1. Scaffold `services/notification-service` (skeleton + Dockerfile + compose + `README.md`, port 8087).
2. `GET /v1/notifications/stream` (JWT): register an `SseEmitter` under the user's account; heartbeat pings; remove on completion/timeout/error.
3. Consume `notification-queue`; dedupe by `eventId`; route each event to the affected user's emitter(s) if connected (drop if not — state remains queryable).
4. Resolve the SSE auth handshake per the step-05 allow-list hook.

## Tests (TDD)
- `SseIT` — connect as bob; publish a PixReceived for bob ⇒ event arrives on bob's stream; a PixSettled for alice does **not** arrive on bob's; disconnect ⇒ emitter cleaned up.
- Heartbeat test — idle connection stays open.

## Verify locally
```bash
BOB=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"bob","password":"bob"}' | jq -r .accessToken)
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $BOB"   # keeps open; events appear live
```

## Definition of Done
- [ ] `README.md` present (purpose, port, endpoints, config, run/test) — per-service README convention (CLAUDE.md)
- [ ] Per-user SSE stream; events routed only to the affected user
- [ ] Heartbeats keep connections alive; disconnects clean up
- [ ] Consumer dedupes; missed pushes don't affect correctness

## CHANGELOG entry
`### Added` → `notification-service: per-user SSE stream consuming notification-queue with heartbeats and cleanup (step 38)`
