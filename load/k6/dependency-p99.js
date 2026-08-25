// Reads payment-service's Prometheus scrape and prints the p99 of every outbound dependency on the
// send path — step 47 task 7, "a p99 breach attributed rather than merely observed".
//
//   curl -s localhost:8084/actuator/prometheus | node load/k6/dependency-p99.js
//
// WHY THE SCRAPE AND NOT A PromQL RANGE QUERY
// `load/k6/run.sh` force-recreates payment-service before every profile, so its meters start at zero and
// the cumulative histogram in the scrape covers EXACTLY the run and nothing else — no window to pick, no
// boundary to argue about. A PromQL `histogram_quantile(0.99, rate(...[10m]))` would additionally depend
// on the scrape interval and on choosing a window that matches the run; this cannot drift.
//
// WHY THE NUMBER IS A BUCKET EDGE AND NOT AN INTERPOLATION
// A Prometheus histogram knows only "how many observations were ≤ this bound". The honest reading of
// p99 is therefore "the smallest bucket bound that already contains 99% of the observations" — an upper
// bound on the true p99, never an under-estimate. Prometheus' own `histogram_quantile` interpolates
// linearly inside that bucket, which invents a distribution the histogram never recorded; for
// attribution ("which dependency is big?") the upper bound is both sufficient and harder to argue with.
// `+Inf` printed as the answer means the p99 is above the last finite bucket — reported as `>Xs`, not
// silently rounded down.
//
// The two meters cover different halves of the same question:
//   pix_dependency_seconds        — the infrastructure hops (DynamoDB, SNS, Redis), from the AWS SDK /
//                                   Lettuce instrumentation (ADR-0021).
//   http_client_requests_seconds  — the service hops (ledger, fraud, accounts), one series per
//                                   client_name. It carries a percentile histogram since step 47; before
//                                   that it exported count/sum/max only and no p99 existed to read.

const QUANTILE = Number(process.env.QUANTILE || 0.99);

function parse(text) {
  // name{labels} value  →  { metric, labels, value }
  const series = [];
  for (const line of text.split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+([^\s]+)$/);
    if (!match) continue;
    const labels = {};
    if (match[3]) {
      for (const pair of match[3].match(/[a-zA-Z_][a-zA-Z0-9_]*="(\\.|[^"\\])*"/g) || []) {
        const eq = pair.indexOf('=');
        labels[pair.slice(0, eq)] = pair.slice(eq + 2, -1);
      }
    }
    series.push({ metric: match[1], labels, value: Number(match[4]) });
  }
  return series;
}

/** Groups `_bucket` series by everything except `le`, so each group is one dependency's histogram. */
function histograms(series, metric, keyLabels) {
  const groups = new Map();
  for (const s of series) {
    if (s.metric !== `${metric}_bucket`) continue;
    const key = keyLabels.map((l) => s.labels[l] || '').join(' / ');
    if (!groups.has(key)) groups.set(key, { buckets: [], count: 0 });
    groups.get(key).buckets.push({ le: Number(s.labels.le), cumulative: s.value });
  }
  for (const s of series) {
    if (s.metric !== `${metric}_count`) continue;
    const key = keyLabels.map((l) => s.labels[l] || '').join(' / ');
    if (groups.has(key)) groups.get(key).count += s.value;
  }
  return groups;
}

function quantileBound(group) {
  const buckets = group.buckets.slice().sort((a, b) => a.le - b.le);
  const total = buckets.length ? buckets[buckets.length - 1].cumulative : 0;
  if (total === 0) return null;
  const target = QUANTILE * total;
  for (const bucket of buckets) {
    if (bucket.cumulative >= target) {
      return Number.isFinite(bucket.le) ? bucket.le : Infinity;
    }
  }
  return Infinity;
}

function report(title, groups) {
  const rows = [];
  for (const [key, group] of groups) {
    const bound = quantileBound(group);
    if (bound === null) continue;
    rows.push({ key, calls: group.count, p: bound });
  }
  rows.sort((a, b) => b.p - a.p || b.calls - a.calls);
  console.log(`\n${title}`);
  console.log('| dependency | calls | p' + Math.round(QUANTILE * 100) + ' (upper bound) |');
  console.log('|---|---:|---:|');
  for (const row of rows) {
    const value = row.p === Infinity ? '> last bucket' : `${(row.p * 1000).toFixed(1)} ms`;
    console.log(`| ${row.key} | ${row.calls} | ${value} |`);
  }
}

let input = '';
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', () => {
  const series = parse(input);
  report('Service hops (http_client_requests_seconds)', histograms(series, 'http_client_requests_seconds', ['client_name', 'uri']));
  report('Infrastructure hops (pix_dependency_seconds)', histograms(series, 'pix_dependency_seconds', ['dependency', 'operation']));
  const server = histograms(series, 'http_server_requests_seconds', ['method', 'uri']);
  report('For comparison — this service\'s own endpoints (http_server_requests_seconds)', server);
});
