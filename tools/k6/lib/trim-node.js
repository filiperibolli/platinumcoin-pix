// Node-side (not k6-runtime) helper shared by every analyze-s*.js script, so the WSL2
// clock-instability trimming rule (docs/load/RESULTS.md's "Environment limitation" section) is
// defined exactly once and applied identically to S0/S1/S2/S3 instead of drifting per script.
//
// The rule and its justification: docs/load/artifacts/s2-dry-run-*.ndjson show a CLEAN bimodal
// split — every sample is either under ~2.5s (p90 of the dry runs) or in a 30-33s band (matching
// timedatectl's observed ~-30s offset); nothing falls in between. THRESHOLD_MS=10000 sits in that
// empty gap, so the split is exact, not a guess at a percentile. A sample at/above the threshold
// is reported as an "artifact" (WSL2 clock-jump stall, not application latency) and excluded from
// the "trimmed" figures — but the RAW figures (including the artifacts) are always reported
// alongside, so a reader can recompute with a different threshold or reject the rule entirely.
const THRESHOLD_MS = 10000;

function percentile(sortedAsc, p) {
  if (sortedAsc.length === 0) return null;
  const idx = Math.min(sortedAsc.length - 1, Math.ceil((p / 100) * sortedAsc.length) - 1);
  return Math.round(sortedAsc[Math.max(0, idx)] * 100) / 100;
}

// durations: array of numbers (ms). Returns raw + trimmed percentile summaries side by side, plus
// exactly how many samples the threshold removed — the number a reader needs to judge the rule.
function summarizeDurations(durations) {
  const raw = [...durations].sort((a, b) => a - b);
  const trimmed = raw.filter((d) => d < THRESHOLD_MS);
  const removedCount = raw.length - trimmed.length;
  return {
    threshold_ms: THRESHOLD_MS,
    sample_count: raw.length,
    removed_count: removedCount,
    removed_rate: raw.length > 0 ? Math.round((removedCount / raw.length) * 10000) / 10000 : null,
    raw: {
      p50_ms: percentile(raw, 50),
      p95_ms: percentile(raw, 95),
      p99_ms: percentile(raw, 99),
      max_ms: raw.length > 0 ? Math.round(raw[raw.length - 1] * 100) / 100 : null,
    },
    trimmed: {
      p50_ms: percentile(trimmed, 50),
      p95_ms: percentile(trimmed, 95),
      p99_ms: percentile(trimmed, 99),
      max_ms: trimmed.length > 0 ? Math.round(trimmed[trimmed.length - 1] * 100) / 100 : null,
    },
  };
}

module.exports = { THRESHOLD_MS, percentile, summarizeDurations };
