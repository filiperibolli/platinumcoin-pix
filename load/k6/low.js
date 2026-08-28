// LOW — ~5 TPS for 3 minutes. The quiet shape.
//
// WHAT THIS PROFILE IS FOR, AND WHY IT IS NOT "the small one"
// At 5 TPS nothing queues, so if a p99 threshold fails HERE it cannot be capacity — it is either the
// application or the machine. That makes `low` the control the other two profiles are read against:
// `standard` breaching a budget that `low` met is a load finding; `standard` breaching one that `low`
// also breached is a floor, and no amount of tuning the platform will move it.
//
// It is also the ONLY profile run under fraud-service's DEFAULT rule thresholds. At ~3.5 sends/s over
// 200 ring accounts each account sees ~1 send/minute, comfortably under the 5-per-60s velocity line —
// so the full scoring path is live and inside the 200ms budget while the SLO is being asserted. The
// heavier profiles cannot say that (see standard.js), which is precisely why this one must.
//
//   bash load/k6/run.sh low
import { SLO_THRESHOLDS, mixedScenarios } from './lib.js';

export { send, balance, statement } from './lib.js';

const RATE = Number(__ENV.LOW_RATE || 5);
const DURATION = __ENV.LOW_DURATION || '3m';

export const options = {
  scenarios: mixedScenarios(RATE, DURATION, { maxVUs: 50 }),
  thresholds: SLO_THRESHOLDS,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
