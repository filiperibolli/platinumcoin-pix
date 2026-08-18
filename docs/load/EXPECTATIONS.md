# Load-measurement expectations — written before this run

Every number below is derived from the code and the seed data as they exist on disk **right now**,
before `docker compose down -v` or any scenario runs. None of it is copied from a previous run's
observed output. The point of writing this first is that Phase B's results get compared against a
prediction made in ignorance of them, not reconciled after the fact.

## Limitation: the leftover-same-day-usage hypothesis for the prior 49-vs-50 cannot be re-tested

The previous session's compose stack (and its DynamoDB volumes) was already torn down before this
audit began. `docs/load/RESULTS.md`'s existing writeup argued, from code review alone, that 49
settled (instead of the arithmetically exact 50) on alice's limit-guard subsection was most likely
explained by (a) leftover same-day consumption of alice's daily-limit counter from an earlier
smoke/dry run — never (b) a reservation leak, since `DynamoDailyLimitReservation`'s conditional
`ADD` leaves no race window and every failure path that takes a reservation also releases it. That
argument was made **by elimination**, not by reading the actual counter value from that run, and
the counter state that would have proven it directly is gone — there is nothing left to grep or
query from the prior run. **This run's result is therefore the only evidence available.** The
decision rule below treats it as such: a clean 50 corroborates the leftover-usage explanation after
the fact (still not a certainty — see the rule's wording), but a second 49 has no prior-run data to
fall back on and must be treated as a live, uninvestigated finding, not written off.

## Decision rule (verbatim, apply after B2's S1 limit-guard result is in)

> limit-guard settling 50 confirms the prior run's 49 was leftover same-day usage. Settling 49
> again on a freshly reseeded environment rules that out and makes a reservation leak the leading
> hypothesis — investigate before any of these numbers are published.

## S1 — balance-guard (`acc-lt-s1bal`)

**Seed** (`tools/k6/seed/seed-load-test-fixtures.sh`): `S1BAL_BALANCE_CENTS=100000` (R$1,000.00),
`S1BAL_DAILY_LIMIT_CENTS=99999999900` (never binding — the point of this subsection is the balance
guard specifically, not the limit guard).

**Transfer amount** (`tools/k6/s1-conservation.js`, `AMOUNT_DECIMAL`): `100.00` = 10,000 cents.

**Derivation**: the ledger's non-negative-balance condition lives *inside* the same
`TransactWriteItems` as the debit (`balanceCents >= :amount`, ARCHITECTURE §6.3, Domain Safety
Rule #3) — every send either fully succeeds or is refused, there is no partial-amount send. The
account starts at 100,000 cents and each successful send removes exactly 10,000.

**Expected settled = ⌊100,000 / 10,000⌋ = 10.** The 11th (and every subsequent) concurrent request
finds `balanceCents(90,000... down to 0) < 10,000` at some point and is refused
`INSUFFICIENT_FUNDS` — expected `rejected_insufficient_funds` is whatever's left of the storm's
total requests, not a specific number (it depends on how many requests the 60s window generates).

## S1 — limit-guard (`acc-001` / alice)

**Seed** (`infra/localstack/init/04-seed-accounts.sh`, `05-seed-ledger.sh`, unchanged by the
load-test fixtures script): `dailyLimitCents=500000` (R$5,000.00), ledger `balanceCents=1000000`
(R$10,000.00). Balance (1,000,000) is double the limit (500,000), so the limit binds strictly
first for any transfer amount — the balance guard never has a chance to fire here.

**Transfer amount**: `100.00` = 10,000 cents (same script, same constant).

**Derivation** (`DynamoDailyLimitReservation.reserve`,
`services/payment-service/.../infra/persistence/DynamoDailyLimitReservation.java`): a single
conditional `UpdateItem` — `ADD usedCents :amount` guarded by `attribute_not_exists(usedCents) OR
usedCents <= :limitMinusAmount`, `limitMinusAmount = 500,000 − 10,000 = 490,000` (a constant,
computed once, independent of the item's current value). DynamoDB serializes writes to a single
item, so each concurrent caller's condition check reads the item's true, fully-committed state at
the moment that request is processed — there is no stale-read race window. Starting from
`usedCents` absent (fresh calendar day, freshly reseeded stack — see the limitation above): the
condition holds for `usedCents` at 0, 10,000, 20,000, ..., 490,000 (the 1st through 50th calls) and
fails at 500,000 (the 51st call).

**Expected settled = ⌊500,000 / 10,000⌋ = 50, deterministically** — not "usually 50," not "50
absent contention": the arithmetic above has no dependency on VU count or timing. Anything other
than exactly 50 is either (a) the account was not at a clean `usedCents`-absent starting state for
today's calendar day, or (c) something not yet identified. See the decision rule above for how to
tell those apart from this run's actual number.

## S1 — both subsections: conservation and duplicate postings

**Expected Σ(balanceCents) delta = 0.** Internal transfers move money between two accounts inside
one atomic `TransactWriteItems`; nothing outside that transaction can create or destroy a cent
(ARCHITECTURE §6.3 — "money moves, it is never created or destroyed"). This must hold across the
*whole* `pix_ledger` table, not just the two accounts in the storm, since `ledger-snapshot.sh`
sums every `BALANCE` item.

**Expected duplicate postings = 0, and expected orphaned legs = 0.** The 5th write of every posting
transaction is a conditional put on `TX#<txId>/POSTING` keyed on the `txId` alone
(`attribute_not_exists`) — a second attempt at the same `txId` is rejected by DynamoDB before any
entry is written, so no `txId` can ever have more than its original 2 entries (1 DEBIT + 1 CREDIT,
docs/data-model.md §3, ARCHITECTURE §6.3's "why the 5th write"). Each storm request mints a fresh
UUID `txId` (`SendPixUseCase.settleInternally`), so there is no cross-request collision to begin
with — the guard is defense in depth against retries of the *same* `txId`, not against the storm
generating new ones. The check this run adds (per instruction) queries this directly: group every
`ENTRY#` item under `pix_ledger` by its `gsi1pk` (`TX#<txId>`) and assert every group has exactly
2 members — a `txId` with 1 member is an orphaned leg (a debit or credit that posted without its
counterpart, which the same atomic transaction makes structurally impossible), a `txId` with 3+ is
a duplicate posting.

## S3 — idempotency retry storm (`acc-lt-001`)

**Setup** (`tools/k6/s3-idempotency.js`): 30 VUs, all authenticated as `acc-lt-001`, 20 rounds, one
shared `Idempotency-Key` per round.

**Derivation** (ADR-0002): the idempotency claim is a conditional `PutItem`
(`attribute_not_exists(pk)`) scoped `accountId + key` — exactly one caller among the 30 racing a
given round's key can win that conditional write; DynamoDB serializes it the same way it serializes
the daily-limit counter above. Every loser either lands `409` (in-progress) or, once the winner's
claim reaches `COMPLETED`, replays the winner's stored response byte-for-byte. The winner alone
proceeds to the real ledger posting.

**Expected real postings per round = exactly 1. Expected total across 20 rounds = 20.** Zero
rounds with no winner (every round's 30 racing requests contend for the same claim, so exactly one
must win it, deterministically — a round only shows "no winner" if the run's own bookkeeping failed
to observe the winner, not because none existed). Expected double postings on winners = 0, for the
same conditional-put reasoning as S1.

## S5 — conservation across the async (external) path

**New scenario** (this run): sustained external Pix sends (ring-account senders,
`bob@otherbank.com`/`carol@otherbank.com` as destinations — both DICT-registered at ISPB
`99999999`, `services/mock-bacen-spi/src/main/resources/application.yml`) for 3 minutes, then a
wait for every transaction to reach a terminal state before the final snapshot.

**Path** (ARCHITECTURE §6.6): `POST /v1/payments/pix` → ledger debit payer / credit
`SPI_CLEARING` (atomic) → transaction persisted `DEBITED` + outbox event, `202` returned → outbox
publisher (1s tick) → SNS → `settlement-queue` → settlement-service → `POST /spi/settlements`
(mock-bacen, default `BACEN_LATENCY_MS=2000`, default `BACEN_FAILURE_RATE=0.0`,
`BACEN_TIMEOUT_RATE=0.0` — **no failure/timeout injection is planned for this run**, so this proves
the happy path's conservation property, not the reversal path) → settlement-service finalizes:
`SETTLED` + a `debit SPI_CLEARING / credit SPI_SETTLED` (`CLEARING_RELEASE`) posting, step 33.

**Expected end states**: with both `BACEN_FAILURE_RATE` and `BACEN_TIMEOUT_RATE` at their
defaults (0.0), every accepted (`202`) send is expected to reach **SETTLED**. Expected
**REVERSED = 0** (nothing triggers the permanent-refusal branch this run). Expected **still
in-flight (DEBITED/SENT_TO_SPI) at the final snapshot = 0**, given the wait described below is
long enough: a healthy settlement completes in low single-digit seconds (BACEN's 2s latency +
~1s outbox tick + processing), nowhere near the 120s `stuck-after-seconds` threshold
(`settlement-service`'s `application.yml`) that would even involve the reconciliation
scanner (60s tick). If anything is still non-terminal at the final snapshot after waiting past
these thresholds, that is a live finding to report, not something to wait out silently.

**Expected Σ(balanceCents) delta = 0** across the whole `pix_ledger` table, before vs. after —
same closed-system argument as S1, extended to the three-account external chain (payer → clearing
→ settled) instead of two. **Expected `ACCOUNT#SPI_CLEARING` balance returns to its pre-run value**
(0, per the seed script, and untouched by S1/S2/S3, all of which stay internal-only) once every
send reaches SETTLED — a `CLEARING_RELEASE` posting is what moves each settled amount out of
`SPI_CLEARING` into `SPI_SETTLED`, so a nonzero `SPI_CLEARING` balance at the final snapshot means
either something is still in flight (see above) or money is orphaned there with no owning
non-terminal transaction, which would be the finding to chase.

## Which account each scenario runs against, and the calendar date

Recorded here before the run for later same-day-consumption tracing, filled in from actual system
clock at run time (America/Sao_Paulo — the daily-limit's own zone, `SendPixUseCase.LIMIT_ZONE`):

| Scenario | Account(s) | Calendar day (America/Sao_Paulo) |
|---|---|---|
| S0 | `acc-lt-001` (ring position 1) | *(fill in at run time)* |
| S1 balance-guard | `acc-lt-s1bal` | *(fill in at run time)* |
| S1 limit-guard | `acc-001` (alice) | *(fill in at run time)* |
| S2 | `acc-lt-001` .. `acc-lt-200` (ring) | *(fill in at run time)* |
| S3 | `acc-lt-001` | *(fill in at run time)* |
| S5 | ring accounts (senders), `bob@otherbank.com`/`carol@otherbank.com` (external destinations) | *(fill in at run time)* |

At the time this document was written (before teardown), `date` in America/Sao_Paulo reads
**2026-08-17**. If the actual run crosses midnight São Paulo time, that must be called out
explicitly in `docs/load/RESULTS.md`, since it would split S1's traffic across two different
`LIMIT#acc-001/DAY#<date>` counter items and invalidate the limit-guard derivation above.

---

# S5b — external conservation WITH reversals (added 2026-08-18, before the S5b run)

S5 (run 2026-08-17) had mock-bacen's `failure-rate` and `timeout-rate` at 0.0, so all 515 accepted
sends settled and **0 reversed** — the reversal half of the conservation claim ("money parked in
clearing always leaves it, to SPI_SETTLED on settlement **or back to the payer on reversal**, never
stranded") never executed. S5b exercises the reversal branch.

## What actually triggers a REVERSED (established from the code, before running)

Read end to end (`SettlePixUseCase`, `SettlementFinalizer`, `SpiBehavior`, `SpiSettlementController`,
`HttpSpiSettlementClient`):

- **`reject-keys`** (a creditor key the SPI refuses even though the DICT resolves it) → mock-bacen
  records the settlement as `FAILED` and answers **HTTP 422** → the client maps 422 to
  `SpiSettlementRejectedException` → `SettlePixUseCase` calls `finalizer.reverse(...)` →
  **REVERSED**. This is the *only* code path that produces a compensating posting from a live send
  (`SpiBehavior` calls reject-keys "the send-reachable reversal trigger of step 35").
- **`failure-rate`** → HTTP **503** ("record nothing", transient) → `SpiCallFailedException` =
  **UNKNOWN**, not FAILED → the transaction stays `SENT_TO_SPI`, the SQS message is redelivered, and
  step 32's query-before-retry re-attempts. It does **not** reverse; it retries and (being
  transient) eventually settles. Exhausting 5 deliveries sends the message to the DLQ, and the
  reconciliation loop then resolves the transaction independently.
- **`timeout-rate`** → the SPI **settles and then hangs** past the client's 12s timeout →
  `SpiCallFailedException` = UNKNOWN → redelivery → query-before-retry finds it **SETTLED**. A
  distinct code path from a rejection, but it resolves to **SETTLED, not REVERSED**.

**Consequence for this run, stated honestly:** the literal request was "failure-rate 0.2", but
failure-rate alone cannot make a reversal happen in this codebase — it drives the retry path, not
the reversal path. To make reversals *actually happen* (the stated goal), S5b uses **reject-keys**
as the reversal driver **and** sets **failure-rate 0.2** on top, so both the reversal branch and the
transient-retry/query-before-retry branch execute. `timeout-rate` is left at **0**: it resolves to
SETTLED (not a reversal), and at a 15s hang against the single-threaded settlement consumer it would
dominate the 3-minute run and mass-trip the 120s stuck threshold — out of scope for a
conservation-focused pass, and called out rather than run.

## S5b configuration (same rate/duration/amount as S5, for comparability)

- Destinations: **`carol@otherbank.com`** added to reject-keys (every send to carol → **REVERSED**);
  **`bob@otherbank.com`** left alone (every send to bob → **SETTLED**). The k6 script alternates the
  two, so the run produces a mix of both terminal outcomes.
- `failure-rate = 0.2`, `timeout-rate = 0`, BACEN latency `100ms`, outbox batch `800` (same
  load-test knobs as S5, restored afterwards and documented identically).
- 3 sends/s, 3 minutes, 1,000c per send.

## Expected (per the instruction)

Let `accepted` = 202s, `settled` = bucketed SETTLED, `reversed` = bucketed REVERSED (both from a
DynamoDB scan, not a counter), `A` = 1,000c.

1. **`settled + reversed = accepted`**, with **0 left in `DEBITED`/`SENT_TO_SPI`** after the
   reconciliation window closes (0 missing, 0 other).
2. **`SPI_CLEARING` returns to its before value exactly** (0 → 0). Every reversed send parked `A` in
   clearing at accept-time and must draw exactly `A` back out; every settled send draws its `A` out
   to `SPI_SETTLED`. Net clearing change = 0.
3. **`SPI_SETTLED` delta = `settled × A` exactly** — a reversal does **not** credit `SPI_SETTLED`
   (it credits the payer), so only settled sends contribute.
4. **Each reversed transaction returns the exact original amount to its OWN payer** — asserted
   **per transaction**, not on the aggregate: for every reversed `tx-X`, the ledger holds a
   `tx-X-rev` posting whose CREDIT leg is `ACCOUNT#<the original debtorAccountId of tx-X>` for
   exactly `A`, and whose DEBIT leg is the clearing account. (Equal-and-opposite errors across two
   accounts would cancel in a total but fail this per-tx check.)
5. **Whole-table `Σ balanceCents` delta = 0.**
6. **Every reversal produces a compensating posting that is itself a complete DEBIT+CREDIT pair** —
   the whole-ledger integrity scan (`begins_with(sk,"ENTRY#")` → group by `txId` → assert each group
   is exactly `{DEBIT,CREDIT}`) is re-run after S5b and must still show 0 duplicates, 0 orphaned
   legs, 0 malformed pairs, now including every `-rev` and `-rel` posting.

**Stop-and-report condition:** if any reversal leaves money in clearing, or returns an amount that
does not match the original, or credits an account other than the original payer — that is a real
money-safety bug, reported before finishing, not explained away.

## S5b account/date

- Senders: 200-account ring; destinations: `bob@otherbank.com` (SETTLED) / `carol@otherbank.com`
  (REVERSED). Calendar day filled in at run time from America/Sao_Paulo.
