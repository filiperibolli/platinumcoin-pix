# Postman collection — PlatinumCoin Pix (local)

A **living** collection for testing the platform on your machine, organized **one folder per
service**. It is not a step-48-only artifact: it is created early (step 04) and grows with the
platform — **every step that adds a public endpoint adds its request here in the same commit**
(convention in `CLAUDE.md`). Step 48 finalizes it (pre-request auth, idempotency automation,
richer happy/error examples).

## Files

| File | What it is |
| ---- | ---------- |
| `pix-platform.postman_collection.json` | The requests, grouped by service folder. |
| `pix-platform.local.postman_environment.json` | Local env: one `*BaseUrl` per service (ports from `docs/local-dev.md`) + `accessToken`. |

## Use it (Postman)

1. Import both files. Select the **PlatinumCoin Pix — Local** environment (top-right).
2. Start the service(s) you want to test (e.g. `docker compose -f infra/docker-compose.yml up -d --build auth-service`).
3. Run **auth-service → Login (alice)** once. Its test script saves `accessToken` into the
   environment, so every authenticated request afterwards attaches it automatically.
4. Call any other request. Switch identity by running **Login (bob)**.

## Use it (CLI, no GUI)

```bash
newman run tools/postman/pix-platform.postman_collection.json \
  -e tools/postman/pix-platform.local.postman_environment.json
```

## The convention (why this exists)

When you implement an endpoint, you also add it to this collection — under the matching service
folder — with:

- the request pointing at the service's `{{<service>BaseUrl}}` variable (never a hard-coded host);
- `Authorization: Bearer {{accessToken}}` for authenticated endpoints;
- a minimal test script (assert the status; for money-moving POSTs, generate the idempotency key);
- at least the happy path, and ideally the main error (so the RFC 7807 contract is visible).

This keeps a runnable, up-to-date manual test harness next to the code, so any implemented point
can be exercised immediately.
