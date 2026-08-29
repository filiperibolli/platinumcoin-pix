# Postman collection — PlatinumCoin Pix (local)

A **living** collection for testing the platform on your machine. It is not a step-48-only artifact:
it was created early (step 04) and grew with the platform — **every step that adds a public endpoint
adds its request here in the same commit** (convention in `CLAUDE.md`). Step 48 finalized it:
automatic auth, saved examples taken from real responses, two journey folders, and an order that
runs clean top to bottom.

Its twin is the [HTML API explorer](../api-explorer/) (`tools/api-explorer/`): the same living,
one-entry-per-endpoint convention, but a zero-install, open-from-disk page. Both are kept in
lock-step — an endpoint added to one is added to the other in the same step.

## Files

| File | What it is |
| ---- | ---------- |
| `pix-platform.postman_collection.json` | The requests: two journey folders, then one folder per service. |
| `pix-platform.local.postman_environment.json` | Local env: one `*BaseUrl` per service (ports from `docs/local-dev.md`), the login identity, and the tokens the scripts fill in. |

## Use it (Postman)

1. Import both files. Select the **PlatinumCoin Pix — Local** environment (top-right).
2. Start the stack: `docker compose -f infra/docker-compose.yml up -d --build`.
3. Click any request and send it. **There is no login step to remember** — see below.

## Use it (CLI, no GUI)

```bash
newman run tools/postman/pix-platform.postman_collection.json \
  -e tools/postman/pix-platform.local.postman_environment.json
```

91 request items in the file, which `newman run` reports as 92 executed and **241-243 assertions**, 0 failed (the SSE stream is skipped by design). The count moves by one or two between runs — a handful are conditional on state (a registration answers `201` the first time and `409` the next, and each branch asserts a different contract), which is the collection reporting the stack it found rather than pretending every run is the first. It is registered in `docs/local-dev.md` §6 alongside the other checks
that cannot be a `mvn verify`, because their facts live in eight processes rather than one JVM.

## What the collection does for you

**Auth is automatic.** A collection pre-request script logs in when `{{accessToken}}` is missing or
within 60s of expiring, reading the expiry from the token's own `exp` claim. Change identity by
editing `loginUsername`/`loginPassword` in the environment and clearing `accessToken`. Running
**Login (bob)** does *not* hijack the shared token — it fills `{{bobAccessToken}}`, and the handful
of requests that must act as bob ask for it by name, so who a request acts as is readable in the
request instead of implied by run order.

**Service tokens are minted per request.** `/internal/**` stopped accepting user tokens in step 68
(ADR-0017), so the same script mints exactly the token the calling service would — `typ=service`,
the right `aud`, one `scope` — from the same route table each service declares in its
`application.yml`. This only works because the sandbox shares one HS256 secret; a real deployment
gives each workload its own credential and no client-side script can forge one. **fraud-service ›
Score with a USER token** is where you can watch the refusal.

**Idempotency keys generate themselves** (`{{$guid}}`) on every money-moving POST — except the two
replay demos, which pin a fixed key on purpose so that sending them twice proves the memoized answer
instead of making a second payment.

**Every request ships a saved example**, and every one is a transcript: the collection was run
against a live local stack and the real responses were captured, so what is committed is what the
platform actually answered. Three oversized ones (the two Prometheus scrape surfaces and the Jaeger
trace) are excerpted, with the excerpt saying what was cut and why.

## The two views

**`Flows — the journeys`** (first) — a send and a receive, each a chain where every step consumes
what the previous one produced. They carry the assertions that cannot exist inside a single request:
two ledger balances that still sum to their original total after a payment (money moved, none was
created), and a third read after replaying the same `Idempotency-Key` (it moved *once*). They are
also the collection's setup, which is why the service folders below find bob's Pix key registered
and an inbound payment waiting to be polled.

**One folder per service** (below) — how you test a service right after building it. Each folder
runs top to bottom as a story: for `payment-service`, a baseline balance, then every happy path and
every refusal the contract defines, then the same balance again.

## The convention (why this exists)

When you implement an endpoint, you also add it to this collection — under the matching service
folder — with:

- the request pointing at the service's `{{<service>BaseUrl}}` variable (never a hard-coded host);
- `Authorization: Bearer {{accessToken}}` for authenticated endpoints (auto-auth fills it);
- a test script that asserts the contract, not the plumbing;
- at least the happy path, and ideally the main error, so the RFC 7807 shape is visible;
- **a saved example**, captured from a real response rather than written by hand.

Two things to know before you write a script here. `CryptoJS` is already a **global** in the Postman
sandbox, so `const CryptoJS = require('crypto-js')` at the top level of a script is a redeclaration
`SyntaxError` — require it inside a function, where the name legally shadows the global, or just use
the global. And a request must not depend on another folder having run first: mint what you need in
a pre-request script, and tolerate the second run (a `409` on a registration means the postcondition
holds just as much as a `201` does).
