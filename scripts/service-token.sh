#!/usr/bin/env bash
#
# service-token.sh — mint a scoped service token for a local /internal/** call (step 68, ADR-0017).
#
# Since step 68 the internal ports refuse a user's login token: they accept only a token with
# typ=service, addressed to the target service (aud) and scoped to one operation (scope). That is a
# good property and an inconvenient one for a human with curl, so this script mints exactly what
# payment-service's ServiceTokenIssuer mints — same claims, same shape, same shared HS256 secret.
#
# It exists for the runbook (docs/local-dev.md), which used to reach the ledger with $TOKEN from
# /v1/auth/login. That $TOKEN now correctly gets a 403, and the fix belongs in a tool rather than in
# a fifteen-line openssl incantation nobody will retype.
#
#   Usage:  scripts/service-token.sh <audience> <scope> [issuer]
#
#   $ TOKEN=$(scripts/service-token.sh ledger-service ledger:read)
#   $ curl -s localhost:8085/internal/ledger/accounts/acc-001/balance -H "Authorization: Bearer $TOKEN"
#
# Audience / scope pairs the platform actually uses (see InternalApi.java):
#   ledger-service   ledger:post      POST /internal/ledger/postings
#   ledger-service   ledger:read      GET  /internal/ledger/accounts/{id}/balance|entries
#   account-service  accounts:read    GET  /internal/accounts/{id}
#   account-service  keys:resolve     GET  /internal/pix-keys/resolve
#   fraud-service    fraud:score      POST /internal/fraud/score
#
# THIS IS A SANDBOX TOOL. It works only because the local build signs every token with one shared
# secret (ADR-0007) — which is precisely what a real deployment does not do: there, minting a service
# credential requires the calling workload's own key or an ambient IAM identity, and no human holds
# a script that can impersonate a service. The secret below is the committed dev default; overriding
# JWT_SECRET here without overriding it in the services yields a 401, not a 403, because the
# signature check runs first.
set -euo pipefail

AUDIENCE="${1:-}"
SCOPE="${2:-}"
ISSUER="${3:-local-cli}"
SECRET="${JWT_SECRET:-dev-only-hs256-secret-change-me-please-32b}"
TTL_SECONDS="${SERVICE_TOKEN_TTL_SECONDS:-300}"

if [[ -z "$AUDIENCE" || -z "$SCOPE" ]]; then
  sed -n '3,30p' "$0" >&2
  echo "error: audience and scope are required" >&2
  exit 2
fi

# base64url: standard base64, minus padding, with the two URL-unsafe characters swapped. JWT uses it
# for every segment, and getting it wrong produces a token that verifies nowhere and explains nothing.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
EXP=$((NOW + TTL_SECONDS))
JTI=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)

HEADER=$(printf '{"alg":"HS256"}' | b64url)
# aud is an ARRAY, matching what jjwt emits on the Java side; the verifier accepts either form, but
# a tool that produces a different shape than production is a tool that hides shape bugs.
PAYLOAD=$(printf '{"sub":"%s","typ":"service","iss":"%s","aud":["%s"],"scope":"%s","jti":"%s","iat":%s,"exp":%s}' \
  "$ISSUER" "$ISSUER" "$AUDIENCE" "$SCOPE" "$JTI" "$NOW" "$EXP" | b64url)

SIGNING_INPUT="${HEADER}.${PAYLOAD}"
SIGNATURE=$(printf '%s' "$SIGNING_INPUT" \
  | openssl dgst -binary -sha256 -hmac "$SECRET" \
  | b64url)

printf '%s.%s\n' "$SIGNING_INPUT" "$SIGNATURE"
