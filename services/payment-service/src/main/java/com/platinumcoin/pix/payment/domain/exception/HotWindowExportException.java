package com.platinumcoin.pix.payment.domain.exception;

/**
 * The requested months are all still inside the hot window, so the data is already available
 * synchronously (step 53). Mapped to {@code 422 USE_HOT_STATEMENT}.
 *
 * <h2>Why refusing is better than serving</h2>
 * The platform could happily build this export — it would simply find no archive objects and produce an
 * empty CSV, which is the worst possible answer: a customer who asked for their last two months would
 * wait for a job and receive an empty file, with nothing anywhere saying why. Refusing with a code that
 * names the alternative turns a silent wrong answer into a one-line client fix.
 *
 * <p>Only a range that is <b>entirely</b> hot is refused. One that straddles the boundary is accepted
 * and exports its cold part, because that part genuinely is not available any other way.
 */
public class HotWindowExportException extends RuntimeException {

    public HotWindowExportException(String message) {
        super(message);
    }
}
