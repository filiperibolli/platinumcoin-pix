# Step 39 — Wire PixSettled/PixReceived/PixReversed to real-time pushes

> **Sprint 8 — Receive & notify** · **Flow:** inbound Pix → SSE push · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.8

## Objective
Every user-visible outcome pushes in real time: sender gets `PixSettled`/`PixReversed`; receiver gets `PixReceived`. Payload = external status vocabulary + amount + counterpart display + timestamp. Full journey verified: send → settle → both parties notified, all within seconds.

## Why this step exists
Closing the F2 loop end to end and making the async UX honest: the user saw `202 PROCESSING`, and now the final state arrives as a push (with polling `GET /payments/{id}` as the fallback). You'll standardize the **notification payload** on the same external vocabulary the status endpoint uses (step 22) — clients parse one shape everywhere. This is also the first time the *whole* platform runs a real journey across all services by one `correlationId`, foreshadowing the observability audit (step 44).

## Prerequisites
Steps 33 (PixSettled/PixReversed), 37 (PixReceived), 38 (SSE).

## Tasks
1. Define the SSE payload DTO (`type`, external `status`, `amount`, `counterpart`, `timestamp`, `transactionId`).
2. Ensure `PixSettled`/`PixReversed` route to the **sender**, `PixReceived` to the **receiver** (map event → affected account).
3. Verify heartbeats + reconnect give an acceptable UX under a brief disconnect.
4. Mask/format money at the edge (decimal string).

## Tests (TDD)
- `RealtimeJourneyIT` — external send: sender's stream receives PixSettled with correct fields within seconds; forced failure ⇒ sender receives PixReversed; inbound ⇒ receiver receives PixReceived.
- Payload contract test — matches the external status vocabulary.

## Verify locally
```bash
# terminal 1: alice streams; terminal 2: send external pix from alice → observe PixSettled on alice's stream
curl -N localhost:8087/v1/notifications/stream -H "Authorization: Bearer $TOKEN"
```

## Definition of Done
- [ ] Sender notified on settle/reverse; receiver notified on receive — within seconds
- [ ] Payload uses the external status vocabulary, money formatted at the edge
- [ ] Full send→settle→notify journey passes end to end

## CHANGELOG entry
`### Added` → `Real-time pushes wired end to end: PixSettled/PixReversed to sender, PixReceived to receiver (step 39)`
