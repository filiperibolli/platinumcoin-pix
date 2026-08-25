// STANDARD — ~58 TPS sustained for 10 minutes. The average day.
//
// WHERE 58 COMES FROM
// The brief's 5,000,000 transactions/day: 5e6 / 86,400 = 57.9 TPS. It is an AVERAGE, not a peak — real
// Pix traffic has a lunchtime and a payday — which is why `black-friday` exists as a separate shape
// rather than this one with a bigger number.
//
// WHY TEN MINUTES AND NOT ONE
// Everything this platform does asynchronously has a period longer than a minute: the outbox lanes tick
// on 200ms/1s/5s schedules (ADR-0019), the reconciliation scanner wakes every 60s, the balance cache
// expires every 5s, and DynamoDB Local's own write path degrades only once its tables have grown. A
// sixty-second run measures a cold system being nice to you. Ten minutes at 58 TPS posts ~24,000 sends
// and ~48,000 ledger entries, which is enough for the queues to reach steady state and for a backlog,
// if one exists, to become visible instead of merely starting.
//
// FRAUD PROFILE: run this under `loadtest`, and here is the honest reason
// fraud-service's velocity rule is per-account and calibrated for a human: 5 transfers in 60s. This
// profile drives ~40 sends/s across 200 seeded accounts = ~12 per account per minute, so EVERY account
// trips VELOCITY_COUNT within the first minute. That is the rule working correctly on traffic that is
// not real — production at 58 TPS spreads over millions of accounts, not 200. Left at the defaults the
// run would measure the velocity rule; under the `loadtest` profile (which raises ONLY the two velocity
// thresholds — asserted by FraudPropertiesTest) it measures the platform, with every other rule, weight
// and decision band untouched and the 200ms budget still in the path. `low.js` is the profile that
// covers the default thresholds.
//
//   bash load/k6/run.sh standard
import { SLO_THRESHOLDS, mixedScenarios } from './lib.js';

export { send, balance, statement } from './lib.js';

const RATE = Number(__ENV.STANDARD_RATE || 58);
const DURATION = __ENV.STANDARD_DURATION || '10m';

export const options = {
  scenarios: mixedScenarios(RATE, DURATION, { maxVUs: 300 }),
  thresholds: SLO_THRESHOLDS,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
