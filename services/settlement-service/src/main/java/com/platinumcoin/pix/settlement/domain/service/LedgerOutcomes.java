package com.platinumcoin.pix.settlement.domain.service;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a queue-driven service does with a {@link LedgerOutcome} (step 66, ADR-0015) — the single place
 * settlement-service's three money-moving call sites share, so a release, a reversal and an inbound
 * credit cannot drift into different readings of the same answer.
 *
 * <p><b>The decision, stated once.</b> Money must be on the books before the status that claims it is:
 * every caller here posts first and transitions second. So the only question this class answers is
 * <i>may the transition run?</i> — and the answer is yes exactly when the ledger said the money is
 * there, on this call ({@code POSTED}) or an earlier one ({@code REPLAYED}).
 *
 * <p><b>Refused and unknown both stop the caller, for opposite reasons.</b> A refusal is knowledge
 * (nothing committed); an unknown is the absence of it. Neither may be turned into a status write: on a
 * refusal because the money is not there, on an unknown because nobody knows whether it is. Both raise
 * {@link LedgerUnavailableException}, the message is not acked, and SQS redelivers it — which re-posts
 * the <b>same deterministic {@code txId}</b> and so either commits the posting or is told it already
 * happened. That redelivery <i>is</i> the resolution loop ADR-0015 §2 describes; payment-service runs
 * the same loop in-process only because it is holding a user's HTTP request open and cannot wait for a
 * queue. Neither service ever converts doubt into a "no".
 */
public final class LedgerOutcomes {

    private static final Logger log = LoggerFactory.getLogger(LedgerOutcomes.class);

    private LedgerOutcomes() {
    }

    /**
     * Assert the money is on the ledger's books before the caller records that it is.
     *
     * @throws LedgerUnavailableException the posting was refused, or its outcome is unknown — either way
     *                                    the transition must not run and the message must redeliver
     */
    public static void requireMoneyMoved(LedgerOutcome outcome, String txId, String entryType) {
        switch (outcome) {
            case POSTED, REPLAYED -> {
                // The money is there. A replay is the deterministic txId doing its job under an
                // at-least-once queue, so it is as good an answer as a fresh commit — better, even: it
                // proves the identity is being reused rather than re-minted.
            }
            case REFUSED -> {
                log.warn("The ledger definitively refused this finalization posting, so nothing was "
                                + "committed and the local transition must not run; leaving the message "
                                + "for redelivery under the same txId | txId={} entryType={}",
                        txId, entryType);
                throw new LedgerUnavailableException("ledger refused the posting for txId " + txId, null);
            }
            case UNKNOWN -> {
                log.warn("The ledger outcome for this finalization posting is UNKNOWN — it may or may "
                                + "not have committed — so the local transition must not run on a guess; "
                                + "the redelivery re-posts the SAME deterministic txId, which either "
                                + "commits it or reports it as a replay | txId={} entryType={}",
                        txId, entryType);
                throw new LedgerUnavailableException(
                        "the ledger outcome for txId " + txId + " is unknown", null);
            }
        }
    }
}
