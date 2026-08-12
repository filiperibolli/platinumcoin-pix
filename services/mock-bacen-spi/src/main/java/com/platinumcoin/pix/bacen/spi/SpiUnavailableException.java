package com.platinumcoin.pix.bacen.spi;

/**
 * The injected transient failure: the rail is unavailable for <i>this attempt</i>. Mapped to
 * {@code 503 SPI_UNAVAILABLE}.
 *
 * <p>Nothing is recorded when this is thrown, and that is the important half. A {@code 503} says "ask
 * again" — so the same {@code endToEndId} must still be settleable on a later attempt, which is
 * precisely what step 32's retry loop depends on. If an injected failure were remembered as
 * {@link SettlementStatus#FAILED}, every retry would replay the failure and no backoff test could ever
 * go green.
 */
public class SpiUnavailableException extends RuntimeException {

    public SpiUnavailableException(String message) {
        super(message);
    }
}
