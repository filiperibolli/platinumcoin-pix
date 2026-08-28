-- labs/ledger-pg — the ledger's schema, relationally (ADR-0009, step 50).
--
-- This file is the whole point of the lab's read: it is docs/data-model.md §3 rewritten in the
-- vocabulary of a relational engine, so the two designs can be compared line by line.
--
--   DynamoDB (pix_ledger)                          PostgreSQL (here)
--   ------------------------------------------     --------------------------------------------
--   pk=ACCOUNT#<id>, sk=BALANCE                    accounts (one row per account)
--   pk=ACCOUNT#<id>, sk=ENTRY#<ts>#<txId>          entries  (two rows per posting)
--   pk=TX#<txId>,    sk=POSTING  (guard item)      — none needed; see "no guard row" below
--   conditionExpression balanceCents >= :amount    CHECK (balance_cents >= 0) + the WHERE clause
--   conditionExpression attribute_not_exists(pk)   PRIMARY KEY (tx_id, direction)
--
-- Two of those rows are worth stopping on.
--
-- NO GUARD ROW. DynamoDB needs a fifth item (TX#<txId>) to make a posting idempotent, because its
-- entry items carry the timestamp in their sort key: the same txId replayed a second later would
-- produce a *different* key and collide with nothing. Here the identity of a leg is (tx_id,
-- direction) and the timestamp is an ordinary column, so the primary key of `entries` already is the
-- idempotency guard. One index does the work of an extra item and its ALL_OLD round-trip — a real
-- point for the relational side, and the reason a replay in this lab is detected by a 23505 rather
-- than by reading a guard back.
--
-- THE CHECK IS NOT THE GUARD, IT IS THE BACKSTOP. Domain safety rule 3 says the balance condition
-- lives inside the transaction, never as a separate read-then-check. Both strategies honour that in
-- their own way (see PessimisticLedger / OptimisticLedger); this CHECK exists so that a bug in
-- either one fails loudly at the engine instead of quietly producing a negative balance. Belt and
-- braces, on the one invariant where the belt breaking is unacceptable.
--
-- WHAT THIS SCHEMA DELIBERATELY DOES NOT MODEL: the system accounts. ledger-service exempts
-- ACCOUNT#SEED and ACCOUNT#SPI_CLEARING from the balance guard (AccountPolicy) because their balance
-- is a position, not a wallet. A table-level CHECK cannot express "except for these" — it would need
-- a per-row flag and a CHECK over two columns. The lab studies contention between user accounts, so
-- it models only user accounts and says so here rather than inventing a half-answer.

DROP TABLE IF EXISTS entries;
DROP TABLE IF EXISTS accounts;

CREATE TABLE accounts (
    account_id    TEXT   PRIMARY KEY,
    -- Integer cents, exactly as everywhere else in this platform. BIGINT and never NUMERIC-with-
    -- scale, and certainly never a float: the invariant tests assert hard equality on sums.
    balance_cents BIGINT NOT NULL CHECK (balance_cents >= 0),
    -- In ledger-service this counter is documentation ("how many postings has this account seen").
    -- Here, under OptimisticLedger, it becomes load-bearing: it IS the lock. Same column, different
    -- meaning — which is exactly the contrast ADR-0009 was written to expose.
    version       BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE entries (
    tx_id                  TEXT        NOT NULL,
    direction              TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    account_id             TEXT        NOT NULL REFERENCES accounts (account_id),
    counterpart_account_id TEXT        NOT NULL,
    -- Signed by direction: DEBIT negative, CREDIT positive (same convention as Direction.java), so
    -- the two legs of a posting sum to zero and SUM(amount_cents) over the table equals SUM of the
    -- balances. That is the equality step 15 asserts, and it must hold here identically.
    amount_cents           BIGINT      NOT NULL,
    entry_type             TEXT        NOT NULL,
    description            TEXT        NOT NULL DEFAULT '',
    posted_at              TIMESTAMPTZ NOT NULL,
    -- "No double-post" as an index. A replay tries to insert (tx_id, 'DEBIT') a second time and the
    -- engine refuses it — the caller never gets to decide, which is the same property the
    -- conditional write buys in DynamoDB.
    PRIMARY KEY (tx_id, direction)
);

-- No index on (account_id, posted_at) yet, on purpose: the statement query's covering index is
-- step 51's subject, and it can only be measured with-and-without if it starts absent.
