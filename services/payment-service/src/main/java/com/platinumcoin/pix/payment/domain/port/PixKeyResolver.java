package com.platinumcoin.pix.payment.domain.port;

/**
 * Outbound port for resolving a Pix key to its creditor account — account-service's <b>DICT</b> role
 * (ADR-0006: services read each other's data over HTTP, never by sharing a table). The send flow's
 * first step is "who is the money going to?" (step 21), and the answer is authoritative to
 * account-service, not to the payer's request.
 *
 * <p>The domain declares the shape; {@code infra/} implements it against
 * {@code GET /internal/pix-keys/resolve?key=…} (so no HTTP type reaches the use case, ADR-0010).
 *
 * <p><b>Internal only, this step.</b> The internal-send flow (Sprint 4) can only pay a key that lives
 * inside PlatinumCoin, so this port returns just the creditor's internal {@code accountId}; an
 * unresolvable key is a {@link KeyNotFoundException}. External keys (another PSP) are out of scope
 * until the asynchronous settlement flow resolves them via mock-bacen's DICT (steps 27/30) — the
 * adapter treats a non-internal resolution as not-found for now, documented at the call site.
 */
public interface PixKeyResolver {

    /**
     * Resolve {@code key} to the internal account it belongs to.
     *
     * @return the creditor's internal {@code accountId}
     * @throws KeyNotFoundException the key does not resolve to an internal account (unknown, or — until
     *                              step 27/30 — external)
     */
    String resolveInternalCreditor(String key);
}
