package com.platinumcoin.pix.payment.domain.model;

/**
 * Where a destination Pix key lives — the DICT answer the send flow branches on (step 27). It mirrors
 * account-service's own {@code KeyResolution}, deliberately re-declared here rather than shared: it is
 * this service's <i>domain</i> view of the answer, and copying a four-field record is cheaper than the
 * coupling a shared model would create between two services that own different lifecycles (ADR-0006).
 *
 * <ul>
 *   <li>{@code internal == true} ⇒ the key belongs to a PlatinumCoin account; {@code accountId} is the
 *       creditor and the send settles in one atomic posting (step 21).</li>
 *   <li>{@code internal == false} ⇒ the key is held at another PSP; {@code accountId} is {@code null}
 *       (there is no internal account to credit) and the send debits to the clearing account instead,
 *       leaving settlement to the asynchronous half (steps 28–31). {@code externalBank} carries whoever
 *       the DICT named, for the log trail and — later — the SPI call.</li>
 * </ul>
 *
 * <p>An <b>unresolvable</b> key is not a value here: it is
 * {@link com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException} thrown by the port, so a
 * caller cannot forget to check a third state.
 */
public record KeyResolution(boolean internal, String accountId, String externalBank) {

    /** The key resolves to {@code accountId} inside PlatinumCoin. */
    public static KeyResolution internal(String accountId) {
        return new KeyResolution(true, accountId, null);
    }

    /** The key is held at another PSP ({@code externalBank}); there is no internal creditor account. */
    public static KeyResolution external(String externalBank) {
        return new KeyResolution(false, null, externalBank);
    }
}
