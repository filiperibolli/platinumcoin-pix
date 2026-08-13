package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The rail could not be reached, or did not answer in time — the outcome of a {@code 503}, a {@code 504},
 * a connection failure or the 12s read timeout expiring.
 *
 * <p><b>"I do not know" is not "it failed".</b> A timeout means the settlement <i>may</i> have happened
 * at BACEN: mock-bacen's timeout injection deliberately settles and then withholds the answer, exactly
 * as a real rail can. So this exception never leads to a local decision about the money — it leaves the
 * message on the queue and the transaction at {@code SENT_TO_SPI}, which is the state that tells step
 * 32 to <i>ask</i> ({@code GET /spi/settlements/{endToEndId}}) before retrying, and tells step 35's
 * reconciliation loop that this payment is still open.
 */
public class SpiCallFailedException extends RuntimeException {

    public SpiCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
