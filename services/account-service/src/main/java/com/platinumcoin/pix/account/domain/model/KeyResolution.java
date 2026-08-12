package com.platinumcoin.pix.account.domain.model;

/**
 * The outcome of resolving a Pix key to its destination — account-service's DICT answer. The shape is
 * deliberately the <b>final</b> one now, even though the external branch is a step-30 stub: the send
 * orchestration (step 21) codes against this contract and the external path slots in without a reshape.
 *
 * <ul>
 *   <li>{@code internal == true} ⇒ the key lives inside PlatinumCoin; {@code accountId} is set and
 *       {@code externalBank} is {@code null}.</li>
 *   <li>{@code internal == false} ⇒ the key belongs to another PSP; {@code externalBank} carries the
 *       holder's ISPB and {@code accountId} is {@code null} — there is no internal account to credit, so
 *       the send debits to the clearing account and settles asynchronously (step 27). Produced since
 *       step 30, when BACEN's DICT arrived and the delegation seam closed.</li>
 * </ul>
 *
 * <p>The step-11 bet paid off: fixing this shape before the external branch existed meant step 30 added a
 * <i>factory</i>, not a migration — no caller reshaped, no contract renegotiated.
 *
 * <p>Plain Java (record + a domain enum), no framework/AWS types — ADR-0010 dependency rule.
 */
public record KeyResolution(
        boolean internal,
        String accountId,
        String externalBank,
        PixKeyType keyType) {

    /** An internal resolution: the key resolves to {@code accountId} inside PlatinumCoin. */
    public static KeyResolution internal(String accountId, PixKeyType keyType) {
        return new KeyResolution(true, accountId, null, keyType);
    }

    /**
     * An external resolution: another participant holds the key. {@code accountId} is necessarily
     * {@code null} — naming a local account for a key held elsewhere would be exactly the confusion this
     * two-branch shape exists to prevent. {@code keyType} may be {@code null} when the foreign directory
     * reports a kind we have no constant for; the ISPB is the part that routes the money.
     */
    public static KeyResolution external(String externalBank, PixKeyType keyType) {
        return new KeyResolution(false, null, externalBank, keyType);
    }
}
