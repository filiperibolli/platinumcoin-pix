// S1 "conservation" — money safety under contention (docs/load/RESULTS.md).
//
// 50 concurrent VUs fire fresh-Idempotency-Key internal Pix sends at ONE source account for 60s,
// racing an exhaustible resource. Two subsections, selected by S1_MODE, because this repo's seed
// data makes the daily-limit reservation counter bind before the ledger balance ever does for
// ANY transfer amount (500,000 cents limit < 1,000,000 cents balance on alice/bob, and
// limit/amount < balance/amount always) — see docs/load/RESULTS.md's Phase 1 findings. Rather
// than pick one invariant to prove, both run:
//
//   S1_MODE=balance (default) — acc-lt-s1bal, funded to exactly 10 successes at 100.00/send,
//     limit set so high it can never bind. Proves the ledger's non-negative-balance condition.
//   S1_MODE=limit — alice/acc-001, UNCHANGED seed balance/limit, same 100.00/send amount, which
//     yields exactly 50 successes (500,000 / 10,000) — proves the daily-limit reservation counter
//     is what actually stops a real account first, ahead of the balance guard.
//
// Warm-up (30s, discarded) intentionally does NOT hit the account under test: for an exhaustible
// resource, sending real storm traffic during "warm-up" would drain it before the measured
// window even starts, and the run would report zero successes. Instead it sends the same kind of
// traffic (same payment-service/ledger-service/fraud-service code paths, same JIT, same HTTP
// connection pools) against a richly-funded ring account, so what's warmed up is the SYSTEM, not
// this specific balance/limit.
//
// Run (see tools/k6/run-s1.sh for the full before/after/verify orchestration):
//   k6 run --summary-export=docs/load/raw/s1-balance-summary.json \
//     -e S1_MODE=balance tools/k6/s1-conservation.js | tee docs/load/raw/s1-balance.log
//   k6 run --summary-export=docs/load/raw/s1-limit-summary.json \
//     -e S1_MODE=limit tools/k6/s1-conservation.js | tee docs/load/raw/s1-limit.log
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { mintToken } from './lib/jwt.js';
import { sendPix, classify, uniqueKey } from './lib/pix.js';
import { ringSender, ringRecipientKey, ALICE, BOB, ACC_LT_S1BAL, ACC_LT_SINK } from './lib/accounts.js';

const BASE = __ENV.PAYMENT_BASE_URL || 'http://localhost:8084';
const MODE = __ENV.S1_MODE || 'balance';
const AMOUNT_DECIMAL = '100.00'; // 10,000 cents — chosen so balance-guard yields N=10, limit-guard N=50

const settled = new Counter('s1_settled');
const rejectedInsufficientFunds = new Counter('s1_rejected_insufficient_funds');
const rejectedLimitExceeded = new Counter('s1_rejected_limit_exceeded');
const otherErrors = new Counter('s1_other_errors');

// Overridable only so a throwaway low-VU/short-duration dry run doesn't have to burn through the
// exhaustible balance-guard/limit-guard accounts before they're reseeded — the numbers that go
// into docs/load/RESULTS.md are always the defaults (50 VUs, 30s warm-up, 60s storm).
const WARMUP_VUS = Number(__ENV.S1_WARMUP_VUS || 20);
const WARMUP_DURATION = __ENV.S1_WARMUP_DURATION || '30s';
const STORM_VUS = Number(__ENV.S1_STORM_VUS || 50);
const STORM_DURATION = __ENV.S1_STORM_DURATION || '60s';
const GRACEFUL_STOP = '5s';

// Both inputs are always plain "<N>s" here (the only shape this script's env vars accept).
function addDurations(a, b) {
  const seconds = (s) => Number(s.replace('s', ''));
  return `${seconds(a) + seconds(b)}s`;
}

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: WARMUP_VUS,
      duration: WARMUP_DURATION,
      exec: 'warmup',
      startTime: '0s',
      gracefulStop: GRACEFUL_STOP,
    },
    storm: {
      executor: 'constant-vus',
      vus: STORM_VUS,
      duration: STORM_DURATION,
      exec: 'storm',
      // warm-up + its gracefulStop, so the two phases never overlap traffic
      startTime: `${addDurations(WARMUP_DURATION, GRACEFUL_STOP)}`,
      gracefulStop: GRACEFUL_STOP,
    },
  },
  thresholds: {
    // No hard pass/fail gate here on purpose — S1's point is to OBSERVE the exact rejection
    // boundary, not to assert a latency SLO (that is S2's job).
  },
};

function target() {
  if (MODE === 'limit') {
    return { accountId: ALICE.accountId, userId: ALICE.userId, pixKey: BOB.pixKey };
  }
  return { accountId: ACC_LT_S1BAL.accountId, userId: ACC_LT_S1BAL.userId, pixKey: ACC_LT_SINK.pixKey };
}

export function warmup() {
  const vuId = __VU;
  const sender = ringSender(vuId);
  const recipientKey = ringRecipientKey(vuId);
  const token = mintToken(sender.userId, sender.accountId);
  const idem = uniqueKey('s1-warmup');
  sendPix(BASE, token, recipientKey, '1.00', idem, { phase: 'warmup' });
  sleep(0.05);
}

export function storm() {
  const t = target();
  const token = mintToken(t.userId, t.accountId);
  const idem = uniqueKey(`s1-${MODE}`);
  const res = sendPix(BASE, token, t.pixKey, AMOUNT_DECIMAL, idem, { phase: 'measured', mode: MODE });
  const { kind } = classify(res);
  if (kind === 'settled') {
    settled.add(1);
    console.log(`TXID ${res.json('transactionId')}`);
  } else if (kind === 'rejected_insufficient_funds') {
    rejectedInsufficientFunds.add(1);
  } else if (kind === 'rejected_limit_exceeded') {
    rejectedLimitExceeded.add(1);
  } else {
    otherErrors.add(1);
    console.log(`OTHER_ERROR status=${res.status} body=${res.body}`);
  }
}
