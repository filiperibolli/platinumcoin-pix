#!/usr/bin/env node
// Applies the same WSL2 clock-jump raw/trimmed split (tools/k6/lib/trim-node.js) used by
// S0/S2/S3 to S1's storm-phase requests, so a reader can see whether the same artifact rate shows
// up here too — S1's own result (settled/rejected counts) doesn't need trimming to stay correct
// (a stalled request just completes late, it doesn't change which invariant it hit), but a stall
// eating into the fixed 60s storm window does reduce how many attempts fit in it, which belongs
// next to the counts for context.
//
// Only points tagged phase=measured are counted (tools/k6/s1-conservation.js's `storm` exec) —
// warm-up traffic is excluded, same rule as S2/S3.
//
// Usage: node tools/k6/analyze-s1-latency.js docs/load/raw/s1-<mode>-raw.ndjson
const fs = require('fs');
const readline = require('readline');
const { summarizeDurations } = require('./lib/trim-node');

const ndjsonPath = process.argv[2];
if (!ndjsonPath) {
  console.error('usage: analyze-s1-latency.js <k6-ndjson-output-file>');
  process.exit(1);
}

async function main() {
  const rl = readline.createInterface({ input: fs.createReadStream(ndjsonPath), crlfDelay: Infinity });
  const durations = [];
  for await (const line of rl) {
    if (!line) continue;
    let row;
    try {
      row = JSON.parse(line);
    } catch (e) {
      continue;
    }
    if (row.type !== 'Point' || row.metric !== 'http_req_duration') continue;
    if ((row.data.tags || {}).phase !== 'measured') continue;
    durations.push(row.data.value);
  }
  console.log(JSON.stringify(summarizeDurations(durations), null, 2));
}

main();
