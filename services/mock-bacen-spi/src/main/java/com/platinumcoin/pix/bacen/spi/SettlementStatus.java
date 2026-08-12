package com.platinumcoin.pix.bacen.spi;

/**
 * What the SPI knows about an {@code endToEndId}. The three values are the whole vocabulary of
 * {@code GET /spi/settlements/{endToEndId}}, and the distinction between them is what makes bounded
 * reconciliation possible (ADR-0003):
 *
 * <ul>
 *   <li>{@link #SETTLED} — the money left PlatinumCoin and reached the counterparty. Terminal.</li>
 *   <li>{@link #FAILED} — the SPI <b>refused</b> this transfer permanently (here: an unknown creditor
 *       key). Terminal, and retrying it is pointless — the payer must be made whole with a
 *       compensating posting (step 33).</li>
 *   <li>{@link #UNKNOWN} — the SPI has no record of this {@code endToEndId}. <b>Not</b> an error and
 *       not a failure: it is the honest third answer, and the reason a caller that timed out must
 *       <i>ask</i> before retrying (step 32). Never stored — it is the absence of a record.</li>
 * </ul>
 *
 * <p>The injected {@code 503} (failure-rate) deliberately produces <b>none</b> of these: an unavailable
 * transport is transient, so nothing is recorded and the very same {@code endToEndId} can still settle
 * on a later attempt. Recording it as {@code FAILED} would make retries structurally impossible.
 */
public enum SettlementStatus {
    SETTLED,
    FAILED,
    UNKNOWN
}
