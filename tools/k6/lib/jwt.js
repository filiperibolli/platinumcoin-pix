// Mints an HS256 JWT locally instead of calling POST /v1/auth/login, so a k6 run with hundreds
// of distinct source accounts (docs/load/RESULTS.md, S2) doesn't need 200+ real credentials
// seeded into services/auth-service's static user list (production code, out of scope for a
// load-measurement deliverable) and doesn't spend the run's own request budget on auth-service.
//
// This is safe ONLY because the platform's own design makes it safe: common-lib's JwtAuthFilter
// verifies the signature and the `sub`/`accountId` claims and nothing else (no issuer check, no
// server-side session/allowlist) — see services/common-lib/.../security/JwtAuthFilter.java. The
// shared secret is a dev-only value already committed in plaintext to infra/docker-compose.yml
// (JWT_SECRET); it is not a discovered credential.
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const DEFAULT_SECRET = 'dev-only-hs256-secret-change-me-please-32b';
const SECRET = __ENV.JWT_SECRET || DEFAULT_SECRET;

function b64url(obj) {
  return encoding.b64encode(JSON.stringify(obj), 'rawurl');
}

// Same claim set JwtIssuer.java signs: sub, accountId, jti, iat, exp — nothing else.
export function mintToken(userId, accountId, ttlSeconds = 900) {
  const header = { alg: 'HS256', typ: 'JWT' };
  const nowSeconds = Math.floor(Date.now() / 1000);
  const payload = {
    sub: userId,
    accountId: accountId,
    jti: `k6-${userId}-${nowSeconds}-${Math.floor(Math.random() * 1e9)}`,
    iat: nowSeconds,
    exp: nowSeconds + ttlSeconds,
  };
  const signingInput = `${b64url(header)}.${b64url(payload)}`;
  const signature = crypto.hmac('sha256', SECRET, signingInput, 'base64rawurl');
  return `${signingInput}.${signature}`;
}

export function authHeader(userId, accountId, ttlSeconds = 900) {
  return `Bearer ${mintToken(userId, accountId, ttlSeconds)}`;
}
