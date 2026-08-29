package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.model.StatementWindow;

/**
 * Wire view of the hot/cold statement boundary (step 53).
 *
 * <p>Both fields ship, and they are not redundant. {@code coldBefore} is the answer a caller computes
 * with — an absolute instant, immune to any disagreement about what "a day" means across a DST change
 * or a clock skew. {@code hotWindowDays} is the answer a human reads in a log line to understand why
 * an export was refused, and it is the number an operator recognises from the configuration.
 *
 * @param hotWindowDays how many days of statement stay online
 * @param coldBefore    entries older than this instant are in the archive (ISO-8601, UTC)
 */
public record StatementWindowResponse(long hotWindowDays, String coldBefore) {

    static StatementWindowResponse from(StatementWindow window) {
        return new StatementWindowResponse(window.hotWindowDays(), window.coldBefore().toString());
    }
}
