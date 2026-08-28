// Shared harness for the three SLO profiles (step 47): auth, the account ring, the three verbs of the
// traffic mix, the tags the thresholds select on, and the thresholds themselves.
//
// WHY THE PROFILES ARE OPEN-MODEL (arrival rate), AND THE AD-HOC PASS WAS NOT
// `docs/load/` measured with `constant-vus`: N clients, each sending as fast as it can. In that closed
// model throughput is an OUTPUT — you turn the VU dial and read what the system gave you — which is the
// right shape for "where does it bend" and the wrong shape for "does it meet 58 TPS". A profile named
// "standard ~58 TPS" has to make 58 an INPUT: `constant-arrival-rate` starts iterations on a schedule
// regardless of whether the previous ones finished, which is how real Pix traffic arrives (a customer
// does not wait for the previous customer's request to return). The consequence is the point: when the
// platform cannot keep up, an open model shows it as `dropped_iterations` and a falling achieved rate
// rather than silently slowing the clients down and reporting a healthy-looking latency.
//
// WHY THE TOKEN IS MINTED HERE INSTEAD OF CALLING POST /v1/auth/login
// Same reason tools/k6/lib/jwt.js gives, and this file reuses that minter rather than copying it: the
// 200 ring accounts have no seeded auth-service credentials, and spending the run's own request budget
// on auth-service would measure auth-service. The ring formulas come from tools/k6/lib/accounts.js for
// a harder reason than convenience — they must match tools/k6/seed/seed-load-test-fixtures.sh EXACTLY,
// and two copies of a formula that must agree is a drift bug waiting for someone to change one of them.
import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { mintToken } from '../../tools/k6/lib/jwt.js';
import { ringSender, ringRecipientKey } from '../../tools/k6/lib/accounts.js';

export const BASE = __ENV.PAYMENT_BASE_URL || 'http://localhost:8084';

// The amount every send moves. Deliberately below fraud-service's R$5,000 high-amount line so the mix
// measures the platform rather than one scoring rule, and small against the ring's R$1,000,000 balances
// so a ten-minute soak cannot exhaust an account (the ring is closed: account i receives from i-1 while
// it pays i+1, so balances stay near their seeded value however long the profile runs).
const AMOUNT = __ENV.SEND_AMOUNT || '10.00';

// What fraction of sends go to a key OUTSIDE the platform (the asynchronous path: debit → SPI_CLEARING →
// outbox → SNS → settlement-service → the rail). Zero in all three SLO profiles, and that is a modelling
// decision worth defending rather than a simplification: the two budgets the brief states are on the
// synchronous acknowledgement, and an external send answers `202 PROCESSING` BEFORE the rail is touched.
// Mixing external traffic into the SLO profiles would therefore add rail latency to a measurement the
// rail is architecturally not part of, and would hide the settlement pipeline's own behaviour inside an
// HTTP percentile where nobody would find it.
//
// The degradation drill (load/k6/run-degradation.sh) turns this up precisely to test that claim: if the
// acknowledgement really is independent of the rail, an 8-second BACEN must not move the send p99 at
// all — and whatever DOES move is the honest answer to "what does the platform give up first".
const EXTERNAL_SHARE = Number(__ENV.EXTERNAL_SHARE || 0);

// Seeded in infra/localstack/init — a key the account-service DICT seam resolves as NOT internal, which
// is what routes the send down the asynchronous path.
const EXTERNAL_KEY = __ENV.EXTERNAL_KEY || 'bob@otherbank.com';

// ---------------------------------------------------------------------------------------------------
// Metrics the RESULTS table is built from.
//
// `http_req_failed` is k6's built-in and counts every non-2xx as a failure — which is wrong for this
// platform: a 422 LIMIT_EXCEEDED or FRAUD_DENIED is the system WORKING, and gating an SLO on it would
// fail a run for refusing payments it is supposed to refuse. So the error budget is asserted on
// `server_errors` (5xx and network failures only) and the refusals are counted separately, reported and
// never gated. A profile that hides its business rejections is a profile that can pass while denying
// every payment.
// ---------------------------------------------------------------------------------------------------
export const serverErrors = new Rate('server_errors');
export const businessRejections = new Counter('business_rejections');
export const sendsAccepted = new Counter('sends_accepted');

// Per-VU token cache. A VU keeps one account for the whole run (the ring position derived from its id),
// so this mints once instead of once per iteration — at 500 iterations/s the HMAC would otherwise be
// k6's own CPU showing up in the latency it is measuring. TTL is 900s in jwt.js; no profile runs that
// long, and a profile that did would need a refresh here rather than a longer TTL.
const tokenCache = {};

function tokenFor(sender) {
  if (!tokenCache[sender.accountId]) {
    tokenCache[sender.accountId] = mintToken(sender.userId, sender.accountId);
  }
  return tokenCache[sender.accountId];
}

function uniqueIdempotencyKey() {
  return `s47-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

// One place that decides whether a response counts against the error budget. `status === 0` is k6's
// encoding of "no HTTP response at all" (connection refused, reset, client-side timeout) — the honest
// place for it is with the 5xx, because from a payer's point of view both are the platform failing to
// answer, and excluding it is how a load test reports a clean run against a service that stopped
// accepting connections.
function record(res, endpoint, okStatus) {
  const isServerError = res.status === 0 || res.status >= 500;
  serverErrors.add(isServerError, { endpoint: endpoint });
  if (!isServerError && res.status !== okStatus) {
    businessRejections.add(1, { endpoint: endpoint, status: String(res.status) });
  }
  return !isServerError && res.status === okStatus;
}

// --- the three verbs of the mix -------------------------------------------------------------------

/** 70% of the mix — POST /v1/payments/pix, internal key, ring neighbour. KR2.1: p99 < 2s. */
export function send() {
  const sender = ringSender(__VU);
  const external = EXTERNAL_SHARE > 0 && Math.random() < EXTERNAL_SHARE;
  const res = http.post(
    `${BASE}/v1/payments/pix`,
    JSON.stringify({ pixKey: external ? EXTERNAL_KEY : ringRecipientKey(__VU), amount: AMOUNT }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${tokenFor(sender)}`,
        'Idempotency-Key': uniqueIdempotencyKey(),
      },
      // `rail` is a second dimension on the same requests: it costs nothing when EXTERNAL_SHARE is 0
      // and lets the degradation run compare internal and external acknowledgement latency inside ONE
      // run, against the same warm caches and the same machine minute — which is a far stronger
      // comparison than two runs taken at different times.
      tags: { endpoint: 'send', rail: external ? 'external' : 'internal' },
    },
  );
  if (record(res, 'send', 202)) {
    sendsAccepted.add(1);
  }
}

/** 20% of the mix — GET /v1/accounts/me/balance, the cache-aside read. KR2.2: p99 < 300ms. */
export function balance() {
  const sender = ringSender(__VU);
  const res = http.get(`${BASE}/v1/accounts/me/balance`, {
    headers: { Authorization: `Bearer ${tokenFor(sender)}` },
    tags: { endpoint: 'balance' },
  });
  record(res, 'balance', 200);
}

/** 10% of the mix — GET /v1/accounts/me/statement, the paginated ledger query. No SLO in the brief. */
export function statement() {
  const sender = ringSender(__VU);
  const res = http.get(`${BASE}/v1/accounts/me/statement?limit=20`, {
    headers: { Authorization: `Bearer ${tokenFor(sender)}` },
    tags: { endpoint: 'statement' },
  });
  record(res, 'statement', 200);
}

// --- thresholds ------------------------------------------------------------------------------------

/**
 * The gate. These are the brief's numbers written as assertions: a breach makes `k6 run` exit non-zero,
 * which is what turns a load test into a check rather than a graph someone eyeballs.
 *
 * `statement` carries no latency BUDGET on purpose. The brief states two — 2s for the send
 * acknowledgement and 300ms for the balance read — and inventing a third would be inventing an SLO. It
 * still gets an entry below, because k6 only prints (and exports) a tagged sub-metric that some
 * threshold names: `p(99)>=0` is true of every possible run, so it asserts nothing and exists purely to
 * make the number appear in the summary. A reported number with no promise attached is exactly what
 * load/RESULTS.md wants for the statement path.
 */
export const SLO_THRESHOLDS = {
  'http_req_duration{endpoint:send}': [{ threshold: 'p(99)<2000', abortOnFail: false }],
  'http_req_duration{endpoint:balance}': [{ threshold: 'p(99)<300', abortOnFail: false }],
  'http_req_duration{endpoint:statement}': ['p(99)>=0'],
  // Reporting-only, same trick, and the whole point of the degradation drill: with a slow rail armed,
  // these two rows are the SAME machine, the SAME minute and the SAME saturation — so the difference
  // between them is the rail's contribution to the acknowledgement and nothing else. Comparing two
  // separate runs could never isolate that on a host whose own latency moves between runs.
  'http_req_duration{rail:internal}': ['p(99)>=0'],
  'http_req_duration{rail:external}': ['p(99)>=0'],
  'server_errors{endpoint:send}': ['rate<0.01'],
  'server_errors{endpoint:balance}': ['rate<0.01'],
  'server_errors{endpoint:statement}': ['rate<0.01'],
};

/**
 * Builds the 70/20/10 mix at a given total rate as three separate scenarios rather than one scenario
 * that rolls a die per iteration. Two reasons: the ratio is then exact instead of approximate, and each
 * endpoint's arrival rate is independent — a send path that slows down must not starve the balance
 * reads, which is exactly what would happen if all three shared one iteration budget.
 *
 * `preAllocatedVUs`/`maxVUs` are the honest part of an open model: k6 spawns VUs to hold the schedule,
 * and when even maxVUs is not enough it records `dropped_iterations` — the difference between "we asked
 * for 500 TPS" and "the platform took 500 TPS", which on this host is the whole finding.
 */
export function mixedScenarios(totalRatePerSecond, duration, opts) {
  const options = opts || {};
  const startTime = options.startTime || '0s';
  const gracefulStop = options.gracefulStop || '30s';
  const share = { send: 0.7, balance: 0.2, statement: 0.1 };
  const scenarios = {};

  for (const endpoint of ['send', 'balance', 'statement']) {
    // timeUnit '10s' so a fractional per-second rate (5 TPS × 10% = 0.5/s) stays an integer here.
    const ratePer10s = Math.max(1, Math.round(totalRatePerSecond * share[endpoint] * 10));
    scenarios[endpoint] = {
      executor: 'constant-arrival-rate',
      rate: ratePer10s,
      timeUnit: '10s',
      duration: duration,
      preAllocatedVUs: Math.max(5, Math.ceil(ratePer10s / 5)),
      maxVUs: options.maxVUs || 200,
      exec: endpoint,
      startTime: startTime,
      gracefulStop: gracefulStop,
      tags: { endpoint: endpoint },
    };
  }
  return scenarios;
}

/**
 * The ramping counterpart of {@link mixedScenarios}, for a profile whose target rate changes over time.
 * `stages` are given as total TPS — `[{ duration, target }]` — and each endpoint's scenario gets the
 * same shape scaled by its share of the mix, so the 70/20/10 ratio holds at every point of the ramp and
 * not merely on average over the run.
 *
 * `startRate` matters more than it looks: `ramping-arrival-rate` begins at this rate and interpolates
 * toward the first stage's target, so starting at the standard-day rate rather than at 0 makes the
 * profile a peak arriving at a system already doing its normal work — which is what a Black Friday is.
 */
export function rampingMixedScenarios(stages, opts) {
  const options = opts || {};
  const share = { send: 0.7, balance: 0.2, statement: 0.1 };
  const scenarios = {};

  for (const endpoint of ['send', 'balance', 'statement']) {
    const scaled = stages.map((stage) => ({
      duration: stage.duration,
      target: Math.max(1, Math.round(stage.target * share[endpoint] * 10)),
    }));
    scenarios[endpoint] = {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round((options.startRate || stages[0].target) * share[endpoint] * 10)),
      timeUnit: '10s',
      stages: scaled,
      preAllocatedVUs: options.preAllocatedVUs || 50,
      maxVUs: options.maxVUs || 600,
      exec: endpoint,
      gracefulStop: options.gracefulStop || '30s',
      tags: { endpoint: endpoint },
    };
  }
  return scenarios;
}
