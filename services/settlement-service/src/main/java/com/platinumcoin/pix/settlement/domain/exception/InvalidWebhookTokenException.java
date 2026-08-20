package com.platinumcoin.pix.settlement.domain.exception;

/**
 * The inbound webhook was called without the shared secret, or with the wrong one (step 37).
 *
 * <p><b>Why this is a domain exception and not a filter's concern.</b> The endpoint is deliberately on the
 * JWT allow-list — no user is calling it, and BACEN holds no PlatinumCoin token — so the platform's normal
 * authentication does not apply. But the call <i>credits money</i>: an anonymous {@code POST} here would
 * let any process on the network mint spendable balance (threat model, boundary B4). So the check is an
 * explicit money decision taken by the use case before it touches anything, which is also what makes it
 * testable without a servlet.
 *
 * <p>Maps to {@code 401 WEBHOOK_UNAUTHORIZED}. The presented token is <b>never</b> logged and never echoed
 * in the response (ADR-0012: the log-the-values licence stops at secrets, and a rejection message that
 * quotes the secret back is an oracle).
 */
public class InvalidWebhookTokenException extends RuntimeException {

    public InvalidWebhookTokenException(String message) {
        super(message);
    }
}
