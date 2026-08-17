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

const STAGE_ORDER = [5, 10, 25, 50, 100, 200];
const HOLD_DURATION_S = Number(process.env.S2_HOLD_DURATION_S || 60);
// Spring Boot's default Tomcat max-threads (no override found anywhere in application.yml, per
// docs/load/RESULTS.md's Phase 1 inventory) — flagged explicitly whenever a stage's VU count
// reaches or crosses it, since that is the single most likely non-LocalStack capacity ceiling.
const TOMCAT_DEFAULT_MAX_THREADS = 200;

// stage (number) -> { durations: [ms], statuses: {status: count} }
const stages = new Map();
for (const vus of STAGE_ORDER) stages.set(vus, { durations: [], statuses: {} });

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
    if (!stages.has(vus)) continue;
    const bucket = stages.get(vus);
    bucket.durations.push(row.data.value);
    const status = tags.status || 'network_error';
    bucket.statuses[status] = (bucket.statuses[status] || 0) + 1;
  }

  const results = [];
  let prevTrimmedP99 = null;
  for (const vus of STAGE_ORDER) {
    const bucket = stages.get(vus);
    const total = bucket.durations.length;
    const errorCount = Object.entries(bucket.statuses)
      .filter(([status]) => status !== '202')
      .reduce((sum, [, count]) => sum + count, 0);
    const errorRate = total > 0 ? errorCount / total : null;
    const tps = total > 0 ? Math.round((total / HOLD_DURATION_S) * 100) / 100 : 0;
    const latency = summarizeDurations(bucket.durations);
    const trimmedP99 = latency.trimmed.p99_ms;

    let saturationSignal;
    if (total === 0) {
      saturationSignal = 'no data captured for this stage';
    } else if (errorRate > 0.01) {
      saturationSignal =
        'error-driven: error rate crossed 1%, capacity genuinely exceeded at this stage';
    } else if (prevTrimmedP99 !== null && trimmedP99 > prevTrimmedP99 * 2 && errorRate < 0.005) {
      saturationSignal =
        vus >= TOMCAT_DEFAULT_MAX_THREADS
          ? `latency-driven (queueing, no errors yet): trimmed p99 more than doubled vs the previous stage, ` +
            `at/above Tomcat's default max-threads=${TOMCAT_DEFAULT_MAX_THREADS} (no override found in ` +
            `any service's application.yml) — a plausible cause, not confirmed without thread-pool metrics`
          : 'latency-driven (queueing, no errors yet): trimmed p99 more than doubled vs the previous stage; ' +
            'cause unclear from HTTP-level data alone (candidates: LocalStack single-process DynamoDB ' +
            'emulator, JVM GC, CPU contention across the 7 co-located services)';
    } else if (prevTrimmedP99 === null && trimmedP99 !== null && trimmedP99 > 500) {
      saturationSignal =
        'already elevated at the LOWEST measured stage — see docs/load/RESULTS.md caveats: ' +
        'LocalStack DynamoDB emulator latency under ANY concurrent transactional load is the ' +
        'leading candidate, not application-layer capacity';
    } else {
      saturationSignal = 'no saturation signal: trimmed latency and error rate stayed flat vs the previous stage';
    }

    results.push({
      vus,
      tps,
      latency,
      error_rate: errorRate === null ? null : Math.round(errorRate * 10000) / 10000,
      errors_by_type: Object.fromEntries(
        Object.entries(bucket.statuses).filter(([status]) => status !== '202')
      ),
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
