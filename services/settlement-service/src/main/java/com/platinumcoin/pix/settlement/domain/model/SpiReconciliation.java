package com.platinumcoin.pix.settlement.domain.model;

import java.util.Optional;

/**
 * What BACEN's status endpoint says about one {@code endToEndId} when the reconciliation resolver
 * (step 35) asks — the <b>three-way</b> answer the resolver decides on, richer than step 32's binary
 * {@code findSettlement}.
 *
 * <h2>Why four kinds, not the two {@code findSettlement} collapses</h2>
 * {@code findSettlement} (step 32) only needs "settled or not" — anything that is not a settlement is
 * {@link Optional#empty()}, because its caller (query-before-retry) then safely re-POSTs. The resolver
 * needs more: it has to <i>decide the transaction's fate</i>, and "the rail refused permanently"
 * (a definitive reversal) is not the same as "the rail cannot be reached right now" (leave it for the
 * next cycle). Collapsing those two is exactly how a transaction that BACEN merely could not answer for
 * gets reversed while the money is in fact gone — the failure the type system prevents here.
 *
 * <ul>
 *   <li>{@link Kind#SETTLED} — the money moved; {@link #settlement()} carries BACEN's confirmation.
 *       ⇒ finalize.</li>
 *   <li>{@link Kind#FAILED} — the rail refused this transfer permanently; {@link #reason()} is why.
 *       ⇒ reverse (definitive, no waiting).</li>
 *   <li>{@link Kind#UNKNOWN} — the rail has no record of this id. Not an error: for a transaction we
 *       never managed to send, this is the expected answer. ⇒ reverse <i>only</i> once it is older than
 *       the safety window; within the window, leave.</li>
 *   <li>{@link Kind#UNREACHABLE} — the status query itself could not be completed. Nothing is decided.
 *       ⇒ leave for the next cycle.</li>
 * </ul>
 *
 * @param kind       which of the four answers the rail gave
 * @param settlement present only for {@link Kind#SETTLED}; the confirmation to finalize on
 * @param reason     present only for {@link Kind#FAILED}; the rail's machine-readable refusal reason
 */
public record SpiReconciliation(Kind kind, SpiSettlement settlement, String reason) {

    public enum Kind {
        SETTLED,
        FAILED,
        UNKNOWN,
        UNREACHABLE
    }

    public static SpiReconciliation settled(SpiSettlement settlement) {
        return new SpiReconciliation(Kind.SETTLED, settlement, null);
    }

    public static SpiReconciliation failed(String reason) {
        return new SpiReconciliation(Kind.FAILED, null, reason);
    }

    public static SpiReconciliation unknown() {
        return new SpiReconciliation(Kind.UNKNOWN, null, null);
    }

    public static SpiReconciliation unreachable() {
        return new SpiReconciliation(Kind.UNREACHABLE, null, null);
    }

    public Optional<SpiSettlement> settlementOptional() {
        return Optional.ofNullable(settlement);
    }
}
