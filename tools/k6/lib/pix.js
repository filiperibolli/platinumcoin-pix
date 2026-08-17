// Shared POST /v1/payments/pix helper + response classification, reused by every scenario
// script. Kept here instead of duplicated per scenario so the request shape (docs/api/openapi.yaml)
// only has to be gotten right once.
import http from 'k6/http';

export function uniqueKey(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

export function sendPix(baseUrl, token, pixKey, amountDecimalStr, idempotencyKey, tags) {
  const payload = JSON.stringify({ pixKey: pixKey, amount: amountDecimalStr });
  const params = {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': idempotencyKey,
    },
    tags: tags,
  };
  return http.post(`${baseUrl}/v1/payments/pix`, payload, params);
}

// Classifies a POST /v1/payments/pix response into the buckets the scenarios report on. `code` is
// the RFC 7807 `code` property (PaymentExceptionHandler.java); absent on a bare-network failure.
export function classify(res) {
  if (res.status === 202) {
    return { kind: 'settled', code: null };
  }
  let code = null;
  try {
    code = res.json('code');
  } catch (e) {
    // non-JSON body (network error, empty response) — falls through with code=null
  }
  if (res.status === 422 && code === 'INSUFFICIENT_FUNDS') {
    return { kind: 'rejected_insufficient_funds', code };
  }
  if (res.status === 422 && code === 'LIMIT_EXCEEDED') {
    return { kind: 'rejected_limit_exceeded', code };
  }
  if (res.status === 409 && code === 'REQUEST_IN_PROGRESS') {
    return { kind: 'conflict_in_progress', code };
  }
  if (res.status === 409 && code === 'IDEMPOTENCY_KEY_REUSED') {
    return { kind: 'conflict_key_reused', code };
  }
  return { kind: 'other_error', code: code || `http_${res.status}` };
}
