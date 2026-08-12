package com.platinumcoin.pix.account.domain.exception;

/**
 * The external DICT could not be consulted — unreachable, timed out, or answering with an error. Mapped to
 * {@code 503 DIRECTORY_UNAVAILABLE} (with {@code Retry-After}) by {@code AccountExceptionHandler}.
 *
 * <p><b>Why this is not a 404.</b> When a key is absent locally and the registry cannot be asked, the truth
 * is "we do not know", and there are only two ways to report it:
 *
 * <ul>
 *   <li>{@code 404 KEY_NOT_FOUND} — cheap, but a <i>lie</i>: the payer is told their payee's key does not
 *       exist, on the strength of our own outage. It also reads as final, so the natural reaction is to
 *       give up or re-type a key that was correct all along.</li>
 *   <li>{@code 503} — "ask again in a moment", which is exactly what is true and exactly what invites the
 *       one action that helps.</li>
 * </ul>
 *
 * <p>No money moves in either case, so this is not a money-safety decision — it is an honesty decision, and
 * it is the deliberate <b>opposite</b> of the fraud fail-<i>open</i> (ADR-0005). There, proceeding without an
 * answer carries bounded, quantified risk and blocking every payment would be worse. Here, proceeding is not
 * even on the table (we have no destination), so the only question is what to tell the caller — and failing
 * closed with the truth costs nothing but a retry.
 */
public class ExternalDirectoryUnavailableException extends RuntimeException {

    public ExternalDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
