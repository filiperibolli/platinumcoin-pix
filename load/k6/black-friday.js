// BLACK FRIDAY — 58 → 300 → 500+ TPS, spike and soak. The peak shape.
//
// THE SHAPE, AND WHY EACH SEGMENT EXISTS
//   58 TPS  (start)      the ordinary day the peak arrives on top of — a spike into an idle system is a
//                        different, easier experiment than a spike into a working one.
//   → 300   over 2m      a ramp, so the point where the platform bends is a position on a curve rather
//                        than a pass/fail at one rate.
//   300     for 2m       a plateau, long enough for queues to reach steady state at that rate.
//   → 500   over 30s     the SPIKE: fast on purpose. A slow ramp lets every pool, cache and connection
//                        limit adapt; the interesting failure is the one that only happens when demand
//                        arrives faster than the system can grow into it.
//   500     for 3m       the soak at peak — where a backlog that merely *starts* during the spike gets
//                        long enough to be visible in the outbox lag and the settlement queue.
//   → 58    over 1m      the descent, and then
//   58      for 2m       RECOVERY. This segment is the one people skip and the one that answers the
//                        question an operator actually has at 03:00: after the peak, does latency come
//                        back down, or did the platform accumulate a backlog it never drains? A profile
//                        that stops at the peak cannot tell those two apart.
//
// WHAT THIS PROFILE MEASURES ON THIS HOST, STATED UP FRONT
// `docs/load/RESULTS.md` Context 1 measured this machine's ceiling at ~150–172 req/s (dynamodb-local is
// a single-process JVM, and this is WSL2). 500 TPS is therefore NOT reachable here and this profile is
// not expected to prove it. What it does prove is where the ceiling is and what the platform does above
// it — which the open arrival-rate model reports honestly as `dropped_iterations` plus an achieved rate
// that flattens while the target keeps climbing. See load/RESULTS.md §"Representative infrastructure".
//
// Run under the `loadtest` fraud profile, for the reason standard.js documents at length.
//
//   bash load/k6/run.sh black-friday
import { SLO_THRESHOLDS, rampingMixedScenarios } from './lib.js';

export { send, balance, statement } from './lib.js';

const PEAK = Number(__ENV.PEAK_RATE || 500);
const PLATEAU = Number(__ENV.PLATEAU_RATE || 300);
const BASELINE = Number(__ENV.BASELINE_RATE || 58);

export const options = {
  scenarios: rampingMixedScenarios(
    [
      { duration: '2m', target: PLATEAU },
      { duration: '2m', target: PLATEAU },
      { duration: '30s', target: PEAK },
      { duration: '3m', target: PEAK },
      { duration: '1m', target: BASELINE },
      { duration: '2m', target: BASELINE },
    ],
    { startRate: BASELINE, preAllocatedVUs: 60, maxVUs: 600 },
  ),
  thresholds: SLO_THRESHOLDS,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
