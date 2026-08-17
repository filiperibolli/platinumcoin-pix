#!/usr/bin/env node
// Computes the S0 "artifact floor" result: at ~1 req/s with 1 VU there is no capacity signal to
// measure, only whether the WSL2 clock-jump stall (docs/load/RESULTS.md's "Environment
// limitation" section) shows up with essentially no load. Reuses the exact same raw/trimmed split
// (tools/k6/lib/trim-node.js) applied to S1/S2/S3, so S0's stall rate is directly comparable to
// theirs.
//
// Usage: node tools/k6/analyze-s0.js docs/load/raw/s0-raw.ndjson > docs/load/raw/s0-result.json
const fs = require('fs');
const readline = require('readline');
const { summarizeDurations } = require('./lib/trim-node');

const ndjsonPath = process.argv[2];
if (!ndjsonPath) {
  console.error('usage: analyze-s0.js <k6-ndjson-output-file>');
  process.exit(1);
}

async function main() {
  const rl = readline.createInterface({ input: fs.createReadStream(ndjsonPath), crlfDelay: Infinity });
  const durations = [];
  const statuses = {};
  for await (const line of rl) {
    if (!line) continue;
    let row;
    try {
      row = JSON.parse(line);
    } catch (e) {
      continue;
    }
    if (row.type !== 'Point' || row.metric !== 'http_req_duration') continue;
    durations.push(row.data.value);
    const status = (row.data.tags || {}).status || 'network_error';
    statuses[status] = (statuses[status] || 0) + 1;
  }

  const summary = summarizeDurations(durations);
  const result = {
    vus: 1,
    target_rate: '~1 req/s',
    ...summary,
    statuses,
  };
  console.log(JSON.stringify(result, null, 2));
}

main();
