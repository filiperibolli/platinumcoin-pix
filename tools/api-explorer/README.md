# API explorer — PlatinumCoin Pix (local)

A **single self-contained HTML file** (`index.html`) that presents every API on one page, grouped by
service, each endpoint pre-filled with valid seed data and a **Send** button that runs the call
against your local stack and pretty-prints the live response. Vanilla JS + embedded CSS — **no build
step, no server, no CDN**. Open it straight from disk.

Like `tools/postman/`, it is a **living** artifact: created early and grown **one card per endpoint,
in the same step that adds the endpoint** (convention in `CLAUDE.md`). Step 49 finalized it: today it
carries **64 cards across nine service tabs** and **seven runnable journeys**, and every card ships a
**captured response** you can read with the stack down.

## Files

| File | What it is |
| ---- | ---------- |
| `index.html` | The whole explorer — session panel, journeys, per-service cards, the phone, the consoles, the seeders. |

## How it is organised

Three groups in the top bar, because they answer three different questions:

| Group | Question it answers |
| ----- | ------------------- |
| **Journeys** | *Does the product work, end to end?* Seven ordered chains of real calls — receive · send internal · send external · balance & statement · reversal · idempotency · observability — each step carrying the ids the previous one produced, and each explaining the decision it proves. Cleanup steps are marked `always` so a failed run still restores the sandbox. |
| **Services** | *What exactly does this endpoint do?* One tab per service, one card per endpoint, plus the negative cases worth watching (401 · 403 · 404 · 409 · 422). |
| **Phone** | *What does the customer see?* The SSE stream rendered as an app would render it. |
| **Consoles** | *What can I look at?* The web UIs `docker compose up -d` already started next to the eight services. |
| **Seed** | *Why is everything zero?* One-click generators that push real traffic through the platform so the dashboards and the statement have something to show. |

## Consoles — the UIs the stack already runs

Everything else in this file is an API client; the **Consoles** group is the one place that points at the
ready-made web UIs, deep-linked to the view worth seeing rather than to a product home page.

| Console | Port | Goes straight to |
| ------- | ---- | ---------------- |
| **Grafana** | `3000` | the [business funnel](http://localhost:3000/d/pix-business-funnel) and the [technical](http://localhost:3000/d/pix-technical) dashboard — provisioned as code, anonymous Viewer, no login |
| **Prometheus** | `9091` | [targets](http://localhost:9091/targets) (the pull model made visible) and [graph](http://localhost:9091/graph) for ad-hoc PromQL |
| **Jaeger** | `16686` | [traces from payment-service](http://localhost:16686/search?service=payment-service&limit=20) — the question a counter cannot answer |

They are **links, not embeds**: Grafana and Jaeger both refuse to be framed, and a dashboard folded into
a 900px column would teach less than its own tab. The dot beside each name is a **live reachability
check** — a cross-origin `no-cors` fetch, which cannot read the response but does resolve on a
connection and reject when nothing is listening, which is the one bit worth reporting. Stop a container
and the dot goes red.

Two things the panel says out loud because they cost people time:

- **Prometheus is on `:9091`, not `:9090`** — compose maps `9091:9090` because mock-bacen-spi already
  owns 9090 on the host, so links Prometheus builds from its own internal port can mislead.
- **`/alerts` is empty on purpose.** Alerts are evaluated in-process by settlement-service's
  `AlertEvaluator`, not by Prometheus rules (`docs/observability.md` §4).

The panel closes with the three containers that ship **no** UI — LocalStack, DynamoDB Local, Redis —
and the shell command that reaches each, because their absence is otherwise a question a reader asks.

## Seed — one button, a stack with a story in it

A freshly reset stack draws a wall of zeroes, and two seeded ledger rows cannot demonstrate pagination.
The **Seed** group fixes that with **Everything, once** — one button, about 20 seconds — or five
narrower recipes if you want only one part of the picture:

| Recipe | Produces | Where to look |
| ------ | -------- | ------------- |
| **Warm the funnel** | 12 internal Pix (~R$ 30) — RECEIVED → FRAUD_CHECKED → DEBITED → SETTLED, rail untouched | Grafana business funnel |
| **Fill the statement** | 8 sent + 6 received, so both sides page and amounts appear signed in both directions | the statement cards, Grafana technical |
| **Populate the rejected branch** | 3 refusals at 3 different stages, so *"where do payments die?"* is non-empty | Grafana business funnel |
| **Absorb duplicates** | 1 payment + 4 replays of one `Idempotency-Key` — `pix_idempotency_replayed_total` moves, the balance does not | Grafana business funnel (KR1.1) |
| **Exercise the rail** | 3 external sends settled asynchronously + 1 armed refusal walked through the compensating reversal | Grafana, Jaeger |

**It drives the real public API.** Nothing is written into DynamoDB behind the platform's back — every
centavo moves through `POST /v1/payments/pix` or the rail's inbound webhook, so what the dashboards
show afterwards is what the platform actually did. A generator that wrote rows the code path never
produced would make the dashboard lie, which is worse than leaving it empty.

**It is sized against the platform's rules rather than around them.** The daily limit is R$ 5,000.00 per
account and fraud scores every send: a burst fires `VELOCITY_COUNT` (40) which, with `NEW_PAYEE` (15)
and — overnight — `ODD_HOURS` (10), reaches 65 against a deny band of 70. It stays `REVIEW`, and the
payments go through. One `HIGH_AMOUNT` (70, anything over R$ 5,000) would tip the whole burst into
`DENY`, which is why every amount is a couple of reais. A full run spends about R$ 90, so the button
survives roughly fifty presses a day before the platform correctly starts refusing.

**It checks itself.** The funnel recipe asserts Σ of both balances is unchanged to the cent, the replay
recipe asserts exactly one debit for five identical requests, and the reversal recipe asserts the refund
is exact — a data generator that quietly created money would be the worst possible thing in this repo.
The rail recipe disarms mock-BACEN in a `finally`, because leaving `rejectKeys` set is how the next
person's perfectly good external Pix mysteriously comes back `REVERSED`.

## Captured responses (step 49)

Every card carries one or more collapsed **example responses**. They are **transcripts, not prose**:
produced by opening this file in a headless Chromium against a freshly reset stack, clicking Send on
every card, and writing down what came back — with JWTs summarised to their claims on the way in.
Where the *contract* is the pair rather than the single answer, both halves are kept: `201` then the
`409` that follows it, a cache miss then the hit with the same `asOf`, a posting then the same `txId`
replaying with `replayed: true`. Change a card and re-capture, or the transcript starts lying.

## Two things a card can do that are worth knowing

- **`asUser`** — borrow another user's token for a *single* request, leaving your session alone.
  Only `Register bob@platinum.com (as bob)` uses it, and without it the page had no payee: the seed
  creates accounts, not keys, so every card paying `bob@platinum.com` answered `422 KEY_NOT_FOUND`.
- **`forceUserToken`** — deliberately present your *user* token to an `/internal` port, so
  step 68's `403 INTERNAL_PORT_FORBIDDEN` is watchable instead of taken on faith. Exactly one card
  opts in; every other `/internal` card mints a service token and no journey helper can reach the flag.

## Use it

1. Start the service(s) you want to exercise (e.g. `docker compose -f infra/docker-compose.yml up -d --build auth-service`).
2. Open `tools/api-explorer/index.html` in a browser (double-click / `open` it — no server needed).
3. In **Session**, click **Log in** (alice/alice by default). The access token is held **in memory
   only** and auto-attached as `Authorization: Bearer …` to authenticated requests.
4. Expand any card and click **Send**, or pick a **Journey** and click **Run all steps**.

**The Session panel owns identity, and nothing else may reassign it.** No card silently switches who
you are — auth-service's `Login (bob)` shows you a second user's token *without* capturing it,
because when it did capture it, one click quietly repointed every authenticated card on every tab
(alice's key landing on bob's account, `Send Pix — alice → bob` becoming a self-transfer the ledger
refuses). Type `bob`/`bob` in the Session panel to actually become bob.

## CORS (why opening from disk works)

Opened from `file://`, the browser Origin is `null`, so every call is **cross-origin**. Each service
must allow CORS for the explorer to reach it; **auth-service** enables a permissive local-dev CORS
policy (`CorsConfig`) ordered *before* the JWT auth filter so pre-flight `OPTIONS` isn't rejected as
unauthenticated. Later services enable the same as they land; pinning origins is a step-45 hardening
concern (the local policy is deliberately open and credential-free — the token is a Bearer header,
never a cookie).

Note the one service that is **not** ours: the observability tab points at Prometheus, and it is
marked `foreign` so the explorer does not seed its `X-Correlation-Id` onto those cards. Prometheus
allows exactly `Accept, Authorization, Content-Type, Origin` in its CORS policy, so that one extra
header failed the browser preflight and all six cards died with `Failed to fetch` while `curl`
against the same URLs answered `200`.

## The convention (why this exists)

When you implement an endpoint you also add its card here — under the matching service section —
pre-filled with valid seed data (real users, correct headers), an auto-attached token when
authenticated, and an auto-UUID **Idempotency-Key** helper for money-moving POSTs. This keeps a
zero-install, click-and-it-works manual harness next to the code — the friendliest front door to the
platform and the same anti-drift rule the Postman collection follows.

Contract source of truth: [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml).
