package com.platinumcoin.pix.fraud.domain.model;

/**
 * The individual risk signals a score can carry, returned in {@link ScoreResult#reasons()} so the
 * decision is explainable (an analyst — or the human reading the logs — sees <i>why</i>, not just a
 * number). Each reason contributes its configured weight to the cumulative score (see
 * {@link FraudRules}); the reasons list is the audit trail behind the band.
 *
 * <p>All four families the step calls for, plus the reason texts double as the block-list seam a future
 * async model would extend without touching the in-path rules:
 *
 * <ul>
 *   <li>{@link #HIGH_AMOUNT} — a single transfer above the absolute high-value threshold.</li>
 *   <li>{@link #VELOCITY_COUNT} — too many transfers from this account inside the short window.</li>
 *   <li>{@link #VELOCITY_AMOUNT} — too much money moved from this account inside the long window
 *       (the "vs the account's own recent profile" signal, read from the rolling Redis sum).</li>
 *   <li>{@link #NEW_PAYEE} — this account has never paid this Pix key before.</li>
 *   <li>{@link #ODD_HOURS} — the transfer happened during the configured overnight window.</li>
 * </ul>
 */
public enum FraudReason {
    HIGH_AMOUNT,
    VELOCITY_COUNT,
    VELOCITY_AMOUNT,
    NEW_PAYEE,
    ODD_HOURS
}
