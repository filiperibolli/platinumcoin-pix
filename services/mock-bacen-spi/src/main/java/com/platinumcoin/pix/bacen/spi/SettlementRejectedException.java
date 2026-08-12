package com.platinumcoin.pix.bacen.spi;

/**
 * The SPI refuses this transfer <b>permanently</b>: the creditor key answers to no participant in the
 * DICT. Mapped to {@code 422 SPI_REJECTED}, and recorded as {@link SettlementStatus#FAILED} so a later
 * {@code GET /spi/settlements/{endToEndId}} reports the refusal instead of a misleading {@code UNKNOWN}.
 *
 * <p><b>Why a business refusal and not a 5xx.</b> The request was well-formed and the rail was healthy —
 * BACEN <i>looked</i> and said no. Retrying cannot change the answer, so the caller must not treat it
 * like the injected {@code 503}: the money is sitting in {@code SPI_CLEARING} and the payer has to be
 * made whole with a compensating posting (step 33's FAILED → REVERSED). Collapsing both into one status
 * would erase exactly the distinction the settlement flow has to act on.
 */
public class SettlementRejectedException extends RuntimeException {

    private final Settlement settlement;

    public SettlementRejectedException(Settlement settlement) {
        super("The SPI refused the settlement: " + settlement.rejectionReason());
        this.settlement = settlement;
    }

    /** The recorded refusal, so the error body can carry the reason the caller must log and act on. */
    public Settlement settlement() {
        return settlement;
    }
}
