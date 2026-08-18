// S5 "async conservation" — money safety across the ASYNCHRONOUS (external) settlement path
// (docs/load/RESULTS.md). Where S1 proves conservation for internal, synchronous Pix (one atomic
// TransactWriteItems IS the settlement), S5 proves it for the harder external path, where the
// money leaves the payer into the clearing account at 202-time and only reaches its terminal state
// minutes later through debit → clearing → outbox → SNS → SQS → settlement-service → SPI → SETTLED
// (+ a CLEARING_RELEASE posting that empties clearing into SPI_SETTLED), ARCHITECTURE §6.6/§6.7.
//
// Load shape: a moderate, sustained CONSTANT ARRIVAL RATE (not max-throughput) for 3 minutes,
// deliberately at/under the single-threaded settlement consumer's drain rate so the pipeline keeps
// up and nothing crosses the 120s stuck threshold — the healthy happy path, which is the
// conservation claim worth publishing. Senders are spread across the 200-account ring so per-account
// fraud velocity never trips; destinations are the two DICT-registered external keys
// (bob@otherbank.com / carol@otherbank.com, ISPB 99999999, mock-bacen's application.yml), so every
// send takes the external branch (credit SPI_CLEARING, status DEBITED) rather than settling
// instantly like an internal one.
//
// Each accepted send's transactionId is logged as `TXID <id>` so run-s5.sh can, after the pipeline
// drains, GetItem every one of them and bucket by terminal status (SETTLED / REVERSED / still
// in-flight) — the same provable-from-data discipline S1/S3 use, never inferred from a counter.
//
// Run (see tools/k6/run-s5.sh for the full before/after/drain-wait/bucket orchestration):
//   k6 run -e S5_RUN_ID=$(date +%s) tools/k6/s5-async-conservation.js 2>&1 | tee docs/load/raw/s5.log
import { Counter } from 'k6/metrics';
import { mintToken } from './lib/jwt.js';
import { sendPix, classify, uniqueKey } from './lib/pix.js';
import { ringSender, RING_SIZE } from './lib/accounts.js';

const BASE = __ENV.PAYMENT_BASE_URL || 'http://localhost:8084';
const AMOUNT_DECIMAL = '10.00'; // 1,000 cents per external send — tiny, so HIGH_AMOUNT never fires
const RATE = Number(__ENV.S5_RATE || 3); // sends per second (<= settlement drain rate, see header)
const DURATION = __ENV.S5_DURATION || '3m';
// The two external DICT keys mock-bacen answers for (services/mock-bacen-spi/.../application.yml).
const EXTERNAL_KEYS = ['bob@otherbank.com', 'carol@otherbank.com'];

const accepted = new Counter('s5_accepted');
const fraudDenied = new Counter('s5_fraud_denied');
const otherErrors = new Counter('s5_other_errors');

export const options = {
  scenarios: {
    external_sends: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 30,
      maxVUs: 60,
      exec: 'externalSend',
    },
  },
  thresholds: {},
};

// A monotized counter so successive iterations pick different senders/destinations, spreading load
// across the ring (keeps per-account fraud velocity low) without any shared mutable state.
export function externalSend() {
  const seq = __VU * 100000 + __ITER;
  const sender = ringSender((seq % RING_SIZE) + 1);
  const destKey = EXTERNAL_KEYS[seq % EXTERNAL_KEYS.length];
  const token = mintToken(sender.userId, sender.accountId);
  const idem = uniqueKey('s5');
  const res = sendPix(BASE, token, destKey, AMOUNT_DECIMAL, idem, { phase: 'measured', scenario: 's5' });
  const { kind, code } = classify(res);
  if (kind === 'settled') {
    // 202 Accepted for an external send: status PROCESSING, money now parked in clearing.
    accepted.add(1);
    console.log(`TXID ${res.json('transactionId')}`);
  } else if (code === 'FRAUD_DENIED') {
    fraudDenied.add(1);
  } else {
    otherErrors.add(1);
    console.log(`OTHER_ERROR status=${res.status} body=${res.body}`);
  }
}
