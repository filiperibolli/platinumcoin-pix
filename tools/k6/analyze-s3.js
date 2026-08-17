#!/usr/bin/env node
// Parses tools/k6/s3-idempotency.js's console.log output (k6's own log wrapper included) and
// computes the S3 result shape docs/load/results.json wants.
//
// The winner of a round is identified as the EARLIEST-COMPLETING 202 in that round — provably
// correct, not a guess: a replay is only reachable once the winner's idempotency claim is
// COMPLETED, so the winner's completion timestamp is always the smallest (see
// tools/k6/s3-idempotency.js's header comment for the full argument).
//
// Usage: node tools/k6/analyze-s3.js docs/load/raw/s3.log > docs/load/raw/s3-result.json
// Also writes docs/load/raw/s3-winners.log (TXID lines) alongside, for
// tools/k6/verify/check-double-postings.sh to cross-check each round posted exactly once.
const fs = require('fs');
const path = require('path');

const logPath = process.argv[2];
if (!logPath) {
  console.error('usage: analyze-s3.js <k6-stdout-logfile>');
  process.exit(1);
}

const lines = fs.readFileSync(logPath, 'utf8').split('\n');

const msg = (line) => {
  const m = line.match(/msg="([^"]*)"/);
  return m ? m[1] : null;
};

const rounds = new Map(); // round -> { two02: [{vu,attempt,completedAtMs,durationMs,txId}], conflicts409: n }
let totalConflicts409 = 0;
let otherErrors = 0;

for (const raw of lines) {
  const m = msg(raw);
  if (!m) continue;

  let match;
  if ((match = m.match(
    /^S3_202 round=(\d+) vu=(\d+) attempt=(\d+) completedAtMs=(\d+) durationMs=([\d.]+) txId=(\S+)$/
  ))) {
    const [, round, vu, attempt, completedAtMs, durationMs, txId] = match;
    const r = Number(round);
    if (!rounds.has(r)) rounds.set(r, { two02: [], conflicts409: 0 });
    rounds.get(r).two02.push({
      vu: Number(vu),
      attempt: Number(attempt),
      completedAtMs: Number(completedAtMs),
      durationMs: Number(durationMs),
      txId: txId === 'null' ? null : txId,
    });
  } else if ((match = m.match(/^S3_409 round=(\d+) vu=(\d+) attempt=(\d+) durationMs=([\d.]+)$/))) {
    const r = Number(match[1]);
    if (!rounds.has(r)) rounds.set(r, { two02: [], conflicts409: 0 });
    rounds.get(r).conflicts409 += 1;
    totalConflicts409 += 1;
  } else if (m.startsWith('S3_OTHER') || m.startsWith('S3_EXHAUSTED')) {
    otherErrors += 1;
  }
}

let realPostings = 0;
let replays = 0;
const claimDurations = [];
const replayDurations = [];
const winnerTxIds = [];
const roundsWithNoWinner = [];

for (const [round, data] of [...rounds.entries()].sort((a, b) => a[0] - b[0])) {
  if (data.two02.length === 0) {
    roundsWithNoWinner.push(round);
    continue;
  }
  const sorted = [...data.two02].sort((a, b) => a.completedAtMs - b.completedAtMs);
  const winner = sorted[0];
  const replaysThisRound = sorted.slice(1);
  realPostings += 1;
  replays += replaysThisRound.length;
  claimDurations.push(winner.durationMs);
  replayDurations.push(...replaysThisRound.map((r) => r.durationMs));
  if (winner.txId) winnerTxIds.push(winner.txId);
}

function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

const result = {
  rounds: rounds.size,
  vus_per_round: 30,
  real_postings: realPostings,
  replays: replays,
  conflicts_409: totalConflicts409,
  other_errors: otherErrors,
  rounds_with_no_winner: roundsWithNoWinner,
  p99_claim_ms: percentile(claimDurations, 99),
  p99_replay_ms: percentile(replayDurations, 99),
  claim_sample_count: claimDurations.length,
  replay_sample_count: replayDurations.length,
};

const outDir = path.dirname(logPath);
fs.writeFileSync(
  path.join(outDir, 's3-winners.log'),
  winnerTxIds.map((id) => `TXID ${id}`).join('\n') + '\n'
);

console.log(JSON.stringify(result, null, 2));
