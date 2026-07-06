# Step 34 — Real-time notification wiring end-to-end

## Objective
Every user-visible outcome pushes in real time: sender gets `PixSettled`/`PixReversed`/`PixRejected`; receiver gets `PixReceived`. Payload = external status vocabulary + amount + counterpart display + timestamp. Full journey verified: send → settle → both parties notified, all within seconds.

## Why / what you'll learn
Integration hardening of an event pipeline: auditing which transitions emit what (a table: transition → event → audience), making payloads *client-ready* (mobile shouldn't need lookups to render a push), and verifying **ordering/UX edge cases** — e.g. a user who reconnects after missing pushes must reconcile via `GET /payments/{id}`/statement (SSE `Last-Event-ID` noted as the production refinement). This step is mostly tests and gap-closing — expect to find small holes from earlier steps; that's the point.

## Prerequisites
Steps 23, 29, 32, 33.

## Tasks
1. Event/audience table in code + doc comment; fill gaps (e.g. ensure PixRejected emitted on fraud DENY / limit deny carries user-displayable reason).
2. Enrich payloads at emission (counterpart name/key masked: `b***@platinum.com`) — masking helper in common-lib.
3. notification-service routing covers sender & receiver audiences per event.
4. E2E happy-path IT + manual runbook check.

## Tests (TDD)
- E2E IT (compose-less, Testcontainers full wiring or component-stubbed): external send ⇒ sender receives PixSettled with masked payee; inbound ⇒ receiver PixReceived; failed settlement ⇒ sender PixReversed with reason.
- Masking unit tests (email/phone/CPF forms).

## Verify locally
```bash
# two terminals with alice's and bob's streams open; then send alice→bob and an inbound to bob
# observe both pushes < ~3s after settlement, payloads render-ready
```

## Definition of Done
- [ ] Transition→event→audience table complete; no silent outcomes
- [ ] Payloads masked and client-ready
- [ ] Documented recovery path for missed pushes

## CHANGELOG entry
`### Added` → `End-to-end real-time notifications for all payment outcomes with masked, client-ready payloads (step 34)`
