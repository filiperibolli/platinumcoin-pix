// S2 "capacity curve" — where does it bend (docs/load/RESULTS.md).
//
// POST /v1/payments/pix, internal key, DISTINCT source/destination accounts per VU (the 200-node
// ring from tools/k6/seed/seed-load-test-fixtures.sh — see tools/k6/lib/accounts.js) so ledger
// contention never confounds the throughput/latency numbers the way sharing one recipient across
// all VUs would.
//
// One 30s warm-up (discarded) at the very start, then six back-to-back stages — 5, 10, 25, 50,
// 100, 200 VUs — each a 15s ramp (discarded) followed by a 60s hold (measured). Every request is
// tagged with its stage's target VU count via k6's own `scenario` tag (one named scenario per
// ramp/hold), which is how tools/k6/analyze-s2.js later slices the raw JSON output per stage
// without any wall-clock bucketing.
//
// Run (see tools/k6/run-s2.sh for the exact reproducible command + analysis):
//   k6 run --out json=docs/load/raw/s2-raw.ndjson tools/k6/s2-capacity.js
import { sleep } from 'k6';
import { mintToken } from './lib/jwt.js';
import { sendPix, uniqueKey } from './lib/pix.js';
import { ringSender, ringRecipientKey } from './lib/accounts.js';

const BASE = __ENV.PAYMENT_BASE_URL || 'http://localhost:8084';
// Overridable only for a fast dry run against the (huge-headroom, reusable) ring accounts — the
// numbers that go into docs/load/RESULTS.md are always the defaults below.
const STAGES = __ENV.S2_STAGES ? __ENV.S2_STAGES.split(',').map(Number) : [5, 10, 25, 50, 100, 200];
const RAMP_DURATION = __ENV.S2_RAMP_DURATION || '15s';
const HOLD_DURATION = __ENV.S2_HOLD_DURATION || '60s';
const WARMUP_DURATION = __ENV.S2_WARMUP_DURATION || '30s';
const WARMUP_VUS = Number(__ENV.S2_WARMUP_VUS || 20);
const GRACEFUL_STOP = '5s';

function sendOnce(tags) {
  const vuId = __VU;
  const sender = ringSender(vuId);
  const recipientKey = ringRecipientKey(vuId);
  const token = mintToken(sender.userId, sender.accountId);
  const idem = uniqueKey('s2');
  sendPix(BASE, token, recipientKey, '10.00', idem, tags);
}

export function warmupTraffic() {
  sendOnce({ stage: 'warmup', phase: 'warmup' });
  sleep(0.05);
}

export function rampTraffic() {
  sendOnce({ phase: 'ramp' });
  sleep(0.1);
}

export function measuredTraffic() {
  sendOnce({ phase: 'measured' });
}

// Build the scenario schedule programmatically: warmup, then [ramp, hold] per stage, each
// startTime chained off the previous one's cumulative duration (+ gracefulStop so adjacent
// scenarios never overlap traffic on the same VUs).
function buildScenarios() {
  const scenarios = {
    warmup: {
      executor: 'constant-vus',
      vus: WARMUP_VUS,
      duration: WARMUP_DURATION,
      exec: 'warmupTraffic',
      startTime: '0s',
      gracefulStop: GRACEFUL_STOP,
    },
  };

  const secondsOf = (s) => Number(s.replace('s', ''));
  let cursorSeconds = secondsOf(WARMUP_DURATION) + secondsOf(GRACEFUL_STOP);
  for (const target of STAGES) {
    const rampName = `ramp_${target}`;
    const holdName = `hold_${target}`;

    scenarios[rampName] = {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{ duration: RAMP_DURATION, target: target }],
      exec: 'rampTraffic',
      startTime: `${cursorSeconds}s`,
      gracefulStop: GRACEFUL_STOP,
      tags: { stage: String(target) },
    };
    cursorSeconds += secondsOf(RAMP_DURATION) + secondsOf(GRACEFUL_STOP);

    scenarios[holdName] = {
      executor: 'constant-vus',
      vus: target,
      duration: HOLD_DURATION,
      exec: 'measuredTraffic',
      startTime: `${cursorSeconds}s`,
      gracefulStop: GRACEFUL_STOP,
      tags: { stage: String(target) },
    };
    cursorSeconds += secondsOf(HOLD_DURATION) + secondsOf(GRACEFUL_STOP);
  }
  return scenarios;
}

export const options = {
  scenarios: buildScenarios(),
};
