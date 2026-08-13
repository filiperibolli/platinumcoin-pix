package com.platinumcoin.pix.settlement.domain.exception;

/**
 * BACEN refused the transfer permanently ({@code 422 SPI_REJECTED} — today: a creditor key no
 * participant answers for).
 *
 * <p>The opposite failure mode of {@link SpiCallFailedException}: here the answer is <b>known</b> and
 * final, so retrying is pointless — the money is sitting in the clearing account and the payer must be
 * made whole by a compensating posting (step 33; the ledger is append-only, so a reversal is a new
 * posting, never an edit). Step 31 stops at recognising the refusal and refusing to call it a
 * settlement; nothing local is decided on it yet.
 */
public class SpiSettlementRejectedException extends RuntimeException {

    private final String reason;

    public SpiSettlementRejectedException(String reason, Throwable cause) {
        super("the SPI refused this settlement permanently, reason=" + reason, cause);
        this.reason = reason;
    }

    /** BACEN's machine-readable refusal reason, e.g. {@code CREDITOR_KEY_NOT_IN_DICT}. */
    public String reason() {
        return reason;
    }
}
