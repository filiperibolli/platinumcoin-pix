#!/usr/bin/env node
// Slices tools/k6/s2-capacity.js's `--out json` stream per stage (using k6's own `scenario` tag,
// e.g. "hold_50") and computes the docs/load/results.json `s2_capacity` array: achieved TPS,
// p50/p95/p99, error rate, and a breakdown of errors by HTTP status. Only points tagged with a
// `hold_*` scenario are measured — `warmup_*`/`ramp_*` points are read (for context in stderr)
// but excluded from the reported numbers, per the 30s-warm-up exclusion rule.
//
// `saturation_signal` is this script's own ASSESSMENT, not a measurement: a heuristic comparing
// each stage's error rate and p99 against the previous stage's, labelled honestly as a guess.
//
// Every stage reports BOTH raw and trimmed latency (tools/k6/lib/trim-node.js) — raw includes the
// WSL2 clock-jump stalls (docs/load/RESULTS.md's "Environment limitation" section), trimmed
// excludes samples at/above the fixed 10s threshold. `saturation_signal` is assessed off the
// TRIMMED p99: the raw p99 is dominated by whether a stage happened to draw a stall in its top 1%
// of samples, which would make every stage's raw p99 look identical (~30s) regardless of actual
// capacity — trimmed p99 is what actually tracks load.
//
// `422` responses are split out from `error_rate`/`errors_by_type` into their own
// `fraud_denied_count`/`fraud_denied_rate`, NOT counted as a capacity failure. s2-capacity.js only
// ever sends between the 200 ring accounts (tools/k6/lib/accounts.js), whose seeded balance/limit
// are enormous and "never the binding constraint" (seed-load-test-fixtures.sh) — so a 422 against
// a ring account cannot structurally be INSUFFICIENT_FUNDS or LIMIT_EXCEEDED, only FRAUD_DENIED
// (confirmed empirically the same way S1's `other_errors` was: grepping the run's log for the
// RFC7807 `code` field). This matters at real throughput: `ringPosition(vuId)` (accounts.js) maps
// each VU to a FIXED ring account for the whole run, so a low-VU stage concentrates a high TPS
// onto very few accounts — which can cross fraud-service's velocity threshold (a per-account
// rolling window) long before any infrastructure capacity limit does, and is a property of the
// FIXTURE's ring-size-vs-throughput ratio, not of the system under test.
//
// STAGE_ORDER is auto-detected from the data (every distinct `hold_<N>` scenario tag present),
// not hardcoded — so a custom `-e S2_STAGES=...` run (e.g. capped below where errors start) is
// analyzed correctly instead of silently losing stages the hardcoded list didn't anticipate.
//
// Usage: node tools/k6/analyze-s2.js docs/load/raw/s2-raw.ndjson > docs/load/raw/s2-result.json
const fs = require('fs');
const readline = require('readline');
const path = require('path');
const { summarizeDurations } = require('./lib/trim-node');

const ndjsonPath = process.argv[2];
if (!ndjsonPath) {
  console.error('usage: analyze-s2.js <k6-ndjson-output-file>');
  process.exit(1);
}

const HOLD_DURATION_S = Number(process.env.S2_HOLD_DURATION_S || 60);
// Spring Boot's default Tomcat max-threads (no override found anywhere in application.yml, per
// docs/load/RESULTS.md's Phase 1 inventory) — flagged explicitly whenever a stage's VU count
// reaches or crosses it, since that is the single most likely non-LocalStack capacity ceiling.
const TOMCAT_DEFAULT_MAX_THREADS = 200;

// stage (number) -> { durations: [ms], statuses: {status: count} }
const stages = new Map();

async function main() {
  const rl = readline.createInterface({ input: fs.createReadStream(ndjsonPath), crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line) continue;
    let row;
    try {
      row = JSON.parse(line);
    } catch (e) {
      continue;
    }
    if (row.type !== 'Point' || row.metric !== 'http_req_duration') continue;
    const tags = row.data.tags || {};
    const scenario = tags.scenario || '';
    const match = scenario.match(/^hold_(\d+)$/);
    if (!match) continue; // discards warmup_*/ramp_* points — the warm-up exclusion
    const vus = Number(match[1]);
    if (!stages.has(vus)) stages.set(vus, { durations: [], statuses: {} });
    const bucket = stages.get(vus);
    bucket.durations.push(row.data.value);
    const status = tags.status || 'network_error';
    bucket.statuses[status] = (bucket.statuses[status] || 0) + 1;
  }

  const STAGE_ORDER = [...stages.keys()].sort((a, b) => a - b);

  const results = [];
  let prevTrimmedP99 = null;
  for (const vus of STAGE_ORDER) {
    const bucket = stages.get(vus);
    const total = bucket.durations.length;
    const fraudDeniedCount = bucket.statuses['422'] || 0;
    const errorCount = Object.entries(bucket.statuses)
      .filter(([status]) => status !== '202' && status !== '422')
      .reduce((sum, [, count]) => sum + count, 0);
    const errorRate = total > 0 ? errorCount / total : null;
    const fraudDeniedRate = total > 0 ? fraudDeniedCount / total : null;
    const tps = total > 0 ? Math.round((total / HOLD_DURATION_S) * 100) / 100 : 0;
    const latency = summarizeDurations(bucket.durations);
    const trimmedP99 = latency.trimmed.p99_ms;

    let saturationSignal;
    if (total === 0) {
      saturationSignal = 'no data captured for this stage';
    } else if (errorRate > 0.01) {
      saturationSignal =
        'error-driven: non-fraud error rate crossed 1%, capacity genuinely exceeded at this stage';
    } else if (prevTrimmedP99 !== null && trimmedP99 > prevTrimmedP99 * 2 && errorRate < 0.005) {
      saturationSignal =
        vus >= TOMCAT_DEFAULT_MAX_THREADS
          ? `latency-driven (queueing, no errors yet): trimmed p99 more than doubled vs the previous stage, ` +
            `at/above Tomcat's default max-threads=${TOMCAT_DEFAULT_MAX_THREADS} (no override found in ` +
            `any service's application.yml) — a plausible cause, not confirmed without thread-pool metrics`
          : 'latency-driven (queueing, no errors yet): trimmed p99 more than doubled vs the previous stage; ' +
            'cause unclear from HTTP-level data alone (candidates: JVM GC, CPU contention across the ' +
            '8 co-located services, dynamodb-local single-process throughput)';
    } else if (prevTrimmedP99 === null && trimmedP99 !== null && trimmedP99 > 500) {
      saturationSignal =
        'already elevated at the LOWEST measured stage — see docs/load/RESULTS.md caveats';
    } else {
      saturationSignal = 'no saturation signal: trimmed latency and error rate stayed flat vs the previous stage';
    }

    results.push({
      vus,
      tps,
      latency,
      error_rate: errorRate === null ? null : Math.round(errorRate * 10000) / 10000,
      errors_by_type: Object.fromEntries(
        Object.entries(bucket.statuses).filter(([status]) => status !== '202' && status !== '422')
      ),
      fraud_denied_count: fraudDeniedCount,
      fraud_denied_rate: fraudDeniedRate === null ? null : Math.round(fraudDeniedRate * 10000) / 10000,
      sample_count: total,
      saturation_signal: saturationSignal,
    });

    if (total > 0) {
      prevTrimmedP99 = trimmedP99;
    }
  }

  console.log(JSON.stringify(results, null, 2));
}

main();
