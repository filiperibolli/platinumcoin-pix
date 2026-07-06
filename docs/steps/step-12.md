# Step 12 — Key resolution (internal DICT) + external delegation

## Objective
`GET /internal/pix-keys/resolve?key=...` on account-service: resolves internal keys from `pix_keys`; unknown keys are looked up in mock-bacen (`GET /spi/dict/{key}`), which answers for a configurable set of "external bank" keys. Result: `{internal: bool, accountId?|externalBank?, keyType}` or KEY_NOT_FOUND.

## Why / what you'll learn
In real Pix, key→bank resolution is BACEN's **DICT** directory. We split the role: our table answers for our customers; the mock answers for the rest of the world. This step also introduces the **typed RestClient adapter with timeouts** pattern (connect 200ms / read 500ms here) — every cross-service call in this project gets explicit timeouts, because unbounded waits are how latency budgets die.

## Prerequisites
Step 11; mock-bacen skeleton exists (step 02) — add its DICT stub here.

## Tasks
1. mock-bacen-spi: `GET /spi/dict/{key}` returning a canned external account for keys matching `*@otherbank.com` / configured list; 404 otherwise.
2. account-service `KeyResolutionService`: local table first; miss → mock-bacen client (timeouts, KEY_NOT_FOUND on 404, DICT_UNAVAILABLE on error).
3. Internal endpoint + response DTO shared in common-lib (payment-service consumes it in step 20).

## Tests (TDD)
- `KeyResolutionServiceTest` — internal hit short-circuits (no external call, verify with mock); miss delegates; 404 ⇒ KEY_NOT_FOUND; timeout ⇒ DICT_UNAVAILABLE.
- `DictStubTest` (mock-bacen) — pattern-matched keys resolve; others 404.

## Verify locally
```bash
curl -s 'localhost:8082/internal/pix-keys/resolve?key=bob@platinum.com' | jq    # internal:true, accountId acc-002
curl -s 'localhost:8082/internal/pix-keys/resolve?key=zed@otherbank.com' | jq   # internal:false, externalBank
curl -si 'localhost:8082/internal/pix-keys/resolve?key=nobody@nowhere' | head -1 # 404
```

## Definition of Done
- [ ] Internal and external keys resolve; unknown ⇒ 404 KEY_NOT_FOUND
- [ ] All external calls bounded by explicit timeouts
- [ ] Resolution result DTO stable for payment-service

## CHANGELOG entry
`### Added` → `Pix key resolution: internal directory with delegation to mock-bacen DICT for external keys (step 12)`
