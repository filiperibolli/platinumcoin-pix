package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.model.KeyResolution;

/**
 * Outbound port for resolving a Pix key to its destination — account-service's <b>DICT</b> role
 * (ADR-0006: services read each other's data over HTTP, never by sharing a table). The send flow's
 * first step is "who is the money going to?" (step 21), and the answer is authoritative to
 * account-service, not to the payer's request.
 *
 * <p>The domain declares the shape; {@code infra/} implements it against
 * {@code GET /internal/pix-keys/resolve?key=…} (so no HTTP type reaches the use case, ADR-0010).
 *
 * <p><b>Internal or external (step 27).</b> The answer is a {@link KeyResolution}, not an account id,
 * because <i>where</i> the key lives is exactly what the send flow branches on: an internal key settles
 * in one atomic posting, an external one is debited to the clearing account and settled asynchronously.
 * A key that resolves nowhere is a {@link KeyNotFoundException}, never a third value in the record.
 *
 * <p>"External" became reachable end-to-end in step 30, when account-service began delegating keys it does
 * not hold to mock-bacen's DICT; the branch is still also exercised directly on this port, which keeps the
 * payment-service tests hermetic.
 */
public interface PixKeyResolver {

    /**
     * Resolve {@code key} to its destination — an internal creditor account, or the external PSP that
     * holds it.
     *
     * @throws KeyNotFoundException the key does not resolve at all (unknown to the DICT)
     */
    KeyResolution resolve(String key);
}
