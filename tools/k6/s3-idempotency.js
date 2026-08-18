// S3 "idempotency" — the retry storm (docs/load/RESULTS.md).
//
// 30 VUs, all authenticated as the SAME account (acc-lt-001 — idempotency is scoped
// accountId+key, so the race only exists if every VU shares the account), 20 rounds. Each VU's
// iteration index IS the round number (per-vu-iterations, 20 iterations/VU) — simpler and just as
// correct as wall-clock synchronization, since round keys never collide across rounds regardless
// of exactly when each VU reaches a given iteration: `s3-round-<i>` only matters to VUs that used
// that same key, and iteration i is the i-th thing every VU does, so all 30 arrive at it close
// together (typically within single-digit ms of each other for identical work).
//
// A round: every VU POSTs with the SAME Idempotency-Key. Exactly one wins the conditional claim
// and does real work (limit + fraud + ledger posting); everyone else either lands mid-flight
// (409 REQUEST_IN_PROGRESS, ADR-0002's IN_PROGRESS window) or arrives after the winner's claim
// reached COMPLETED (202, byte-identical replay — PaymentAcceptedResponse.java's own doc comment
// says the wire response of a fresh accept and a replay are indistinguishable). A VU that draws a
// 409 backs off honoring the response's `Retry-After: 2` header and retries the SAME key.
//
// The winner is identified AFTER the run, not during it: because a replay is only possible once
// the winner's claim is COMPLETED, the winner's response is *provably* the earliest-completing
// 202 of the round (see tools/k6/analyze-s3.js) — every other 202 in that round is a replay by
// construction, not a guess.
//
// Run (see tools/k6/run-s3.sh):
//   k6 run -e S3_RUN_ID=$(date +%s) tools/k6/s3-idempotency.js 2>&1 | tee docs/load/raw/s3.log
import { sleep } from 'k6';
import { mintToken } from './lib/jwt.js';
import { sendPix } from './lib/pix.js';
import { ACC_LT_SINK } from './lib/accounts.js';

const BASE = __ENV.PAYMENT_BASE_URL || 'http://localhost:8084';
const ROUNDS = 20;
const VUS = 30;
const MAX_ATTEMPTS_PER_ROUND = 6; // 1 initial + up to 5 retries; Retry-After:2 caps this well
                                  // under the per-round time a slow VU has before the test ends
const RETRY_AFTER_SECONDS = 2.1; // matches PaymentExceptionHandler's Retry-After: 2, +100ms slack

const SENDER_ACCOUNT_ID = 'acc-lt-001';
const SENDER_USER_ID = 'u-lt-001';
// Idempotency keys are scoped accountId+key with a 24h TTL — re-running this script against the
// same account without a fresh run id would collide with the PRIOR run's already-COMPLETED
// claims, and every request would come back an instant replay, silently corrupting the new run's
// data. REQUIRED, not defaulted: each VU's module init runs separately (a different JS runtime
// per VU), so a same-process default like Date.now() would NOT be the same value across VUs —
// and this test's entire mechanism depends on every VU using the identical key for round i.
// tools/k6/run-s3.sh passes one shared value (`date +%s`) to all VUs via -e.
const RUN_ID = __ENV.S3_RUN_ID;
if (!RUN_ID) {
  throw new Error('S3_RUN_ID is required (must be identical across all VUs) — see tools/k6/run-s3.sh');
}

export const options = {
  scenarios: {
    idempotency_storm: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ROUNDS,
      maxDuration: '10m',
    },
  },
};

export default function () {
  const round = __ITER; // 0..ROUNDS-1 — the round this iteration IS, not a lookup
  const key = `s3-${RUN_ID}-round-${round}`;
  const token = mintToken(SENDER_USER_ID, SENDER_ACCOUNT_ID);

  for (let attempt = 0; attempt < MAX_ATTEMPTS_PER_ROUND; attempt++) {
    const res = sendPix(BASE, token, ACC_LT_SINK.pixKey, '5.00', key, {
      round: String(round),
      attempt: String(attempt),
    });
    const completedAtMs = Date.now();

    if (res.status === 202) {
      let txId = null;
      try {
        txId = res.json('transactionId');
      } catch (e) {
        // leave null — logged as-is, analyze-s3.js treats a missing txId as a parse failure
      }
      console.log(
        `S3_202 round=${round} vu=${__VU} attempt=${attempt} completedAtMs=${completedAtMs} ` +
          `durationMs=${res.timings.duration} txId=${txId}`
      );
      return;
    }

    if (res.status === 409) {
      console.log(`S3_409 round=${round} vu=${__VU} attempt=${attempt} durationMs=${res.timings.duration}`);
      sleep(RETRY_AFTER_SECONDS);
      continue;
    }

    console.log(
      `S3_OTHER round=${round} vu=${__VU} attempt=${attempt} status=${res.status} body=${res.body}`
    );
    return;
  }
  console.log(`S3_EXHAUSTED round=${round} vu=${__VU} — gave up after ${MAX_ATTEMPTS_PER_ROUND} attempts`);
}
