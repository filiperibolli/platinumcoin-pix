package com.platinumcoin.pix.payment.domain.model;

/**
 * Which side of a ledger posting a {@link StatementLine} is, exactly as ledger-service's own
 * {@code Direction} names it (step 16) — mirrored here rather than shared, because the two services
 * never share a domain type (ADR-0010) and this vocabulary is small enough that duplicating it costs
 * nothing and coupling the two modules on it would cost a compile-time dependency for two constants.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
