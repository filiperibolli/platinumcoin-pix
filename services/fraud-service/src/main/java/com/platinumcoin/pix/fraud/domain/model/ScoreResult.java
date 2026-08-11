package com.platinumcoin.pix.fraud.domain.model;

import java.util.List;

/**
 * The scoring verdict: the {@link Decision} band, the numeric {@code score} (0–100) it came from, and
 * the {@link FraudReason}s that fired. Returned straight to the wire — the JSON shape
 * {@code {decision, score, reasons[]}} is identical to this record, so there is no mirror DTO
 * (ADR-0010). {@code reasons} is an immutable copy so a caller cannot mutate a scored result.
 */
public record ScoreResult(Decision decision, int score, List<FraudReason> reasons) {

    public ScoreResult {
        reasons = List.copyOf(reasons);
    }
}
