package com.platinumcoin.pix.ledger.domain;

import java.util.Optional;

/**
 * Outbound port for the ledger table (ADR-0010: the domain declares the interface, {@code infra/}
 * implements it against DynamoDB). One access pattern in this step — the hottest read of the whole
 * platform:
 *
 * <ul>
 *   <li>{@link #getBalance(String)} — the BALANCE item of one account. The adapter reads it
 *       <b>strongly consistently</b>, because the ledger must read its own writes.</li>
 * </ul>
 *
 * <p>It returns {@link Optional} rather than throwing: "no BALANCE item for this account" is an
 * ordinary empty result at this level. Turning it into {@link LedgerAccountNotFoundException} is the
 * use case's decision, and turning that into {@code 404} is the edge's — the same three-layer split
 * account-service uses, and the reason the domain never imports {@code HttpStatus}.
 *
 * <p>The port grows with the flows: the atomic double-entry posting in step 14, the paginated
 * statement in step 16.
 */
public interface LedgerRepository {

    /** The balance of {@code accountId}, or empty if the account has no BALANCE item. */
    Optional<Balance> getBalance(String accountId);
}
