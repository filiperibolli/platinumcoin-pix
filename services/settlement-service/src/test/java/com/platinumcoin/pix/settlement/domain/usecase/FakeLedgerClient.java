package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import java.util.ArrayList;
import java.util.List;

/**
 * The ledger, recording every finalization posting so a test can prove the right money moved under the
 * right {@code txId}, in the right order relative to the guarded transition. It can be told to answer
 * with any {@link LedgerOutcome}, which is how the retry branches — a definite refusal and, since step
 * 66, an <b>unknown</b> outcome — are pinned without DynamoDB or HTTP.
 */
final class FakeLedgerClient implements LedgerClient {

    /** One recorded posting — enough to assert accounts, amount and the deterministic txId. */
    record Posting(String txId, String debitAccount, String creditAccount, long amountCents) {
    }

    private final List<String> trace;
    private final List<Posting> releases = new ArrayList<>();
    private final List<Posting> reversals = new ArrayList<>();
    private final List<Posting> inboundCredits = new ArrayList<>();
    private LedgerOutcome outcome = LedgerOutcome.POSTED;

    FakeLedgerClient(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public LedgerOutcome releaseClearing(String txId, String clearingAccount, long amountCents,
            String description) {
        trace.add("ledger.releaseClearing");
        if (movedMoney()) {
            releases.add(new Posting(txId, clearingAccount, null, amountCents));
        }
        return outcome;
    }

    @Override
    public LedgerOutcome reverseToPayer(String txId, String clearingAccount, String payerAccount,
            long amountCents, String description) {
        trace.add("ledger.reverseToPayer");
        if (movedMoney()) {
            reversals.add(new Posting(txId, clearingAccount, payerAccount, amountCents));
        }
        return outcome;
    }

    @Override
    public LedgerOutcome creditInbound(String txId, String clearingAccount, String payeeAccount,
            long amountCents, String description) {
        trace.add("ledger.creditInbound");
        if (movedMoney()) {
            inboundCredits.add(new Posting(txId, clearingAccount, payeeAccount, amountCents));
        }
        return outcome;
    }

    /** Only a committed posting is recorded; a refusal and an unknown both leave the books untouched. */
    private boolean movedMoney() {
        return outcome == LedgerOutcome.POSTED;
    }

    /** A ledger that answers every posting with {@code outcome} — the retry branches, driven on purpose. */
    void answerWith(LedgerOutcome outcome) {
        this.outcome = outcome;
    }

    void beUnavailable() {
        // Kept as the name the existing tests use for "the posting does not land and the caller must not
        // transition"; since step 66 that is expressed as a definite refusal rather than an exception.
        answerWith(LedgerOutcome.REFUSED);
    }

    List<Posting> releases() {
        return releases;
    }

    List<Posting> reversals() {
        return reversals;
    }

    List<Posting> inboundCredits() {
        return inboundCredits;
    }
}
