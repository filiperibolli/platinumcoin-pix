// Deterministic mirror of tools/k6/seed/seed-load-test-fixtures.sh — every id/key computed here
// must match that script's formulas exactly, since there is no generated-artifacts file passed
// between seeding and running (both sides just compute the same thing).
export const RING_SIZE = 200;

export function ringAccountId(i) {
  return `acc-lt-${String(i).padStart(3, '0')}`;
}

export function ringUserId(i) {
  return `u-lt-${String(i).padStart(3, '0')}`;
}

// The CPF-format Pix key registered TO ring account i (see seed script §1) — i.e. what account
// i-1 resolves when it sends to "the next account in the ring".
export function ringKeyValue(i) {
  return String(90000000000 + i);
}

// 1-indexed ring position for VU u (k6 exec.vu.idInTest is 1-indexed already); wraps at RING_SIZE.
export function ringPosition(vuId) {
  return ((vuId - 1) % RING_SIZE) + 1;
}

// The account a VU at this ring position sends FROM, and the Pix key it sends TO (its neighbour).
export function ringSender(vuId) {
  const pos = ringPosition(vuId);
  return { accountId: ringAccountId(pos), userId: ringUserId(pos) };
}

export function ringRecipientKey(vuId) {
  const pos = ringPosition(vuId);
  const nextPos = (pos % RING_SIZE) + 1;
  return ringKeyValue(nextPos);
}

export const ALICE = { accountId: 'acc-001', userId: 'u-alice', pixKey: '80000000001' };
export const BOB = { accountId: 'acc-002', userId: 'u-bob', pixKey: '80000000002' };

export const ACC_LT_S1BAL = { accountId: 'acc-lt-s1bal', userId: 'u-lt-s1bal' };
export const ACC_LT_SINK = { accountId: 'acc-lt-sink', userId: 'u-lt-sink', pixKey: '80000000003' };
