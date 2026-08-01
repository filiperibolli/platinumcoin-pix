# Step 11 — internal key resolution endpoint (DICT role for internal keys)

> **Sprint 2 — Accounts & Pix Keys** · **Flow:** register / resolve a Pix key · **Infra que sobe:** none new · **Diagram:** ARCHITECTURE §6.2

## Objective
`GET /internal/pix-keys/resolve?key=...` on account-service resolves **internal** keys from `pix_keys` to their account. Result shape `{internal: true, accountId, keyType}` or `KEY_NOT_FOUND`. External-key delegation to mock-bacen's DICT is deferred to step 30 (Sprint 6), when mock-bacen exists.

## Why / what you'll learn
account-service plays the role of BACEN's **DICT** for keys that live inside PlatinumCoin — this is the hot lookup on the send path (every Pix resolves the destination first). Designing the response as `{internal: bool, accountId? | externalBank?, keyType}` **now** — even though the external branch is a stub returning `KEY_NOT_FOUND` until step 30 — means the send orchestration (step 21) can code against the final contract and the external path slots in later without a reshape. Deferring the external branch is the vertical plan working as intended: no mock-bacen exists yet, so we don't pretend it does.

## Prerequisites
Step 10.

## Tasks
1. `KeyResolution(internal, accountId, externalBank, keyType)` record; `resolve(key)` in a `KeyResolutionService`.
   > **Superseded name (ADR-0011):** the class shipped as `KeyResolutionService` and was later renamed
   > `ResolvePixKeyUseCase` in `domain/usecase/` when the explicit use-case layer was adopted. Same
   > logic, same external-delegation seam for step 30.
2. `GET /internal/pix-keys/resolve?key=...`: look up `KEY#<value>` in `pix_keys`; found ⇒ `{internal:true, accountId, keyType}`; not found ⇒ `KEY_NOT_FOUND` (404) — with a `// TODO(step 30): delegate unknown keys to mock-bacen DICT` seam clearly marked.
3. Keep it internal-only (no `/v1` exposure).

## Tests (TDD)
- `KeyResolutionIT` — a registered internal key resolves to its account; an unknown key ⇒ 404 KEY_NOT_FOUND; the seam is exercised by a unit test asserting the external branch is currently a not-found (so step 30 has a red test to turn green).

## Verify locally
`/internal/**` is not on the public allow-list (step-09 posture), so the endpoint needs a valid token —
mint one from auth-service first (`TOKEN=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"alice","password":"alice"}' | jq -r .accessToken)`):
```bash
# pix_keys is not seeded, so register alice's key first (as alice, so it maps to acc-001):
curl -s -X POST localhost:8082/v1/pix-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"keyType":"EMAIL","keyValue":"alice@platinum.com"}' >/dev/null

curl -s "localhost:8082/internal/pix-keys/resolve?key=alice@platinum.com" -H "Authorization: Bearer $TOKEN" | jq   # {internal:true, accountId:"acc-001", ...}
curl -si "localhost:8082/internal/pix-keys/resolve?key=someone@otherbank.com" -H "Authorization: Bearer $TOKEN" | head -1   # 404 (external deferred to step 30)
```

## Definition of Done
- [ ] Internal keys resolve to the owning account
- [ ] Response uses the final `{internal, accountId?, externalBank?, keyType}` shape
- [ ] External-delegation seam explicitly marked for step 30 (not silently missing)

## CHANGELOG entry
`### Added` → `Internal Pix key resolution endpoint (DICT role), external delegation seam left for step 30 (step 11)`
