# API explorer — PlatinumCoin Pix (local)

A **single self-contained HTML file** (`index.html`) that presents every API on one page, grouped by
service, each endpoint pre-filled with valid seed data and a **Send** button that runs the call
against your local stack and pretty-prints the live response. Vanilla JS + embedded CSS — **no build
step, no server, no CDN**. Open it straight from disk.

Like `tools/postman/`, it is a **living** artifact: created early and grown **one card per endpoint,
in the same step that adds the endpoint** (convention in `CLAUDE.md`). It is not a step-49-only
artifact — **step 49 only finalizes it** (guided-journey polish once the money flows exist, richer
happy/error examples). Today it mirrors the Postman collection 1:1: the **auth-service** endpoints.

## Files

| File | What it is |
| ---- | ---------- |
| `index.html` | The whole explorer — session panel, per-service endpoint cards, guided journey. |

## Use it

1. Start the service(s) you want to exercise (e.g. `docker compose -f infra/docker-compose.yml up -d --build auth-service`).
2. Open `tools/api-explorer/index.html` in a browser (double-click / `open` it — no server needed).
3. In **Session**, click **Log in** (alice/alice by default). The access token is held **in memory
   only** and auto-attached as `Authorization: Bearer …` to authenticated requests.
4. Expand any card and click **Send**, or click **Run journey** for the guided story.

## CORS (why opening from disk works)

Opened from `file://`, the browser Origin is `null`, so every call is **cross-origin**. Each service
must allow CORS for the explorer to reach it; **auth-service** enables a permissive local-dev CORS
policy (`CorsConfig`) ordered *before* the JWT auth filter so pre-flight `OPTIONS` isn't rejected as
unauthenticated. Later services enable the same as they land; pinning origins is a step-45 hardening
concern (the local policy is deliberately open and credential-free — the token is a Bearer header,
never a cookie).

## The convention (why this exists)

When you implement an endpoint you also add its card here — under the matching service section —
pre-filled with valid seed data (real users, correct headers), an auto-attached token when
authenticated, and an auto-UUID **Idempotency-Key** helper for money-moving POSTs. This keeps a
zero-install, click-and-it-works manual harness next to the code — the friendliest front door to the
platform and the same anti-drift rule the Postman collection follows.

Contract source of truth: [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml).
