// S0 "artifact floor" — measures the WSL2 clock-instability stall rate with essentially no load,
// to test whether it is load-independent (docs/load/RESULTS.md's "Environment limitation"
// section). If the ~5-6% stall rate seen at S2's higher VU counts is ALSO present here at 1 VU /
// low constant rate, that is the evidence that licenses trimming it out of the capacity curve
// instead of treating it as a capacity signal.
//
// 1 VU, ~1 request/second (sleep(1) between iterations), 5 minutes, same endpoint/request shape
// as S2 (POST /v1/payments/pix, ring accounts so there is zero ledger contention to confound the
// reading) — deliberately no ramp, no warm-up phase to discard: at this scale there is nothing to
// warm up FOR, and discarding an early window would just shrink an already-small sample.
//
// Run (see tools/k6/run-s0.sh):
//   k6 run --out json=docs/load/raw/s0-raw.ndjson tools/k6/s0-baseline.js
import { sleep } from 'k6';
import { mintToken } from './lib/jwt.js';
import { sendPix, uniqueKey } from './lib/pix.js';
import { ringSender, ringRecipientKey } from './lib/accounts.js';

const BASE = __ENV.PAYMENT_BASE_URL || 'http://localhost:8084';
const DURATION = __ENV.S0_DURATION || '5m';
const RING_POSITION = 1; // fixed sender — 1 VU, no contention possible either way

export default function () {
  const sender = ringSender(RING_POSITION);
  const recipientKey = ringRecipientKey(RING_POSITION);
  const token = mintToken(sender.userId, sender.accountId);
  const idem = uniqueKey('s0');
  sendPix(BASE, token, recipientKey, '10.00', idem, { phase: 'measured' });
  sleep(1);
}

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-vus',
      vus: 1,
      duration: DURATION,
      exec: 'default',
    },
  },
};
