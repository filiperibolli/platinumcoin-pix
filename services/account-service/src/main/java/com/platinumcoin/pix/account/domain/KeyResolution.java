package com.platinumcoin.pix.account.domain;

/**
 * The outcome of resolving a Pix key to its destination — account-service's DICT answer. The shape is
 * deliberately the <b>final</b> one now, even though the external branch is a step-30 stub: the send
 * orchestration (step 21) codes against this contract and the external path slots in without a reshape.
 *
 * <ul>
 *   <li>{@code internal == true} ⇒ the key lives inside PlatinumCoin; {@code accountId} is set and
 *       {@code externalBank} is {@code null}.</li>
 *   <li>{@code internal == false} ⇒ (step 30) the key belongs to another PSP; {@code externalBank}
 *       carries the ISPB/participant and {@code accountId} is {@code null}. Not produced yet.</li>
 * </ul>
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
}
