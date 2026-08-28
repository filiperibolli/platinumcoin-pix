// Counts the DynamoDB/SNS/Redis operations one profile actually performed, per service, by diffing the
// Prometheus scrapes `load/k6/run.sh` takes either side of the run — step 47 task 6, the input to the
// WCU/RCU budget in load/RESULTS.md.
//
//   node load/k6/capacity-delta.js standard 24360
//
// The second argument is the number of accepted sends in that run (`sends_accepted` in the k6 summary),
// which turns totals into the number the budget is actually built from: **operations per send**.
//
// WHY A DIFF AND NOT THE `after` SCRAPE ALONE
// `run.sh` force-recreates payment-service, so its counters do start at zero — but ledger-, account-,
// fraud- and settlement-service have been up since the stack came up and carry every request the sandbox
// has ever served. Reading their totals would attribute the seed script, the health checks and yesterday's
// e2e run to this profile. Only the difference belongs to the run.
//
// WHY OPERATIONS AND NOT CONSUMED CAPACITY
// DynamoDB reports `ConsumedCapacity` per call, and the SDK could be asked for it — but the platform does
// not ask, and adding that request to the money path to make a load test easier would be measuring
// something the production path does not do. Operation counts are what the platform already emits
// (`pix_dependency_seconds_count`, ADR-0021); the WCU arithmetic is then done in the document, from real
// measured item sizes, where a reader can check every multiplication.

const fs = require('fs');
const path = require('path');

const profile = process.argv[2];
const sends = Number(process.argv[3] || 0);
if (!profile) {
  console.error('usage: node load/k6/capacity-delta.js <profile> [acceptedSends]');
  process.exit(2);
}

const RESULTS = path.join(__dirname, '..', 'results');
const SERVICES = ['payment-service', 'ledger-service', 'account-service', 'fraud-service', 'settlement-service'];

/** `pix_dependency_seconds_count{dependency="…",operation="…"} 123` → Map("dep/op" → count). */
function counts(file) {
  const map = new Map();
  if (!fs.existsSync(file)) return map;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!line.startsWith('pix_dependency_seconds_count{')) continue;
    const labels = line.slice(line.indexOf('{') + 1, line.lastIndexOf('}'));
    const value = Number(line.slice(line.lastIndexOf('}') + 1).trim());
    const dependency = (labels.match(/dependency="([^"]*)"/) || [])[1] || '?';
    const operation = (labels.match(/operation="([^"]*)"/) || [])[1] || '?';
    map.set(`${dependency}/${operation}`, value);
  }
  return map;
}

console.log(`# Operations performed by the \`${profile}\` profile`);
if (sends) console.log(`\nAccepted sends: **${sends}**. "per send" divides by that.\n`);

let grandTotal = 0;
for (const service of SERVICES) {
  const before = counts(path.join(RESULTS, `${profile}-${service}-before.txt`));
  const after = counts(path.join(RESULTS, `${profile}-${service}-after.txt`));
  const rows = [];
  for (const [key, afterValue] of after) {
    // payment-service is recreated per run, so its `before` file is a fresh process whose counters may
    // already be non-zero (startup reads) but are never LARGER than `after`; a negative delta could only
    // mean a restart mid-run, which is worth seeing rather than clamping away.
    const delta = afterValue - (before.get(key) || 0);
    if (delta === 0) continue;
    rows.push({ key, delta });
  }
  if (!rows.length) continue;
  rows.sort((a, b) => b.delta - a.delta);
  console.log(`\n## ${service}\n`);
  console.log('| dependency / operation | calls | per send |');
  console.log('|---|---:|---:|');
  for (const row of rows) {
    grandTotal += row.delta;
    console.log(`| ${row.key} | ${row.delta.toLocaleString('en-US')} | ${sends ? (row.delta / sends).toFixed(2) : '—'} |`);
  }
}
console.log(`\n**Total dependency calls across all five services: ${grandTotal.toLocaleString('en-US')}**`
  + (sends ? ` = ${(grandTotal / sends).toFixed(2)} per send.` : ''));
