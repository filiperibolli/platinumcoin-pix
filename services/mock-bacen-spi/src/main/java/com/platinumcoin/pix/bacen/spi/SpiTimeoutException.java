package com.platinumcoin.pix.bacen.spi;

/**
 * The injected timeout: the settlement <b>was decided</b> and then the answer never arrived in time.
 * Mapped to {@code 504 SPI_TIMEOUT} — a status a well-behaved client will normally never see, because
 * by the time this is written its own read timeout has already fired.
 *
 * <p><b>This is the nastiest failure in the whole platform, which is why the stub reproduces it
 * faithfully.</b> The outcome is recorded <i>before</i> the hang, so the world ends up in the state
 * distributed systems are hardest at: BACEN settled, the caller believes nothing happened. A client
 * that reacts by blindly re-{@code POST}ing would be gambling that {@code endToEndId} idempotency saves
 * it (here it does — and that is the design); a client that queries first
 * ({@code GET /spi/settlements/{endToEndId}}) discovers the truth and finalises instead of retrying.
 * Step 32 builds that query-before-retry rule, and it can only be tested against a dependency that
 * lies this specific way.
 */
public class SpiTimeoutException extends RuntimeException {

    public SpiTimeoutException(String message) {
        super(message);
    }
}
