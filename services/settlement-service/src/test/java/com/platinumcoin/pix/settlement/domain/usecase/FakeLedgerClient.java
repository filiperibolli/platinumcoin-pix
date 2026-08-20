package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import java.util.ArrayList;
import java.util.List;

/**
 * The ledger, recording every finalization posting so a test can prove the right money moved under the
 * right {@code txId}, in the right order relative to the guarded transition. It can be told to be
 * unavailable, to pin the "nothing posted, retry" branch without DynamoDB or HTTP.
 */
final class FakeLedgerClient implements LedgerClient {

    /** One recorded posting — enough to assert accounts, amount and the deterministic txId. */
    record Posting(String txId, String debitAccount, String creditAccount, long amountCents) {
    }

    private final List<String> trace;
    private final List<Posting> releases = new ArrayList<>();
    private final List<Posting> reversals = new ArrayList<>();
    private final List<Posting> inboundCredits = new ArrayList<>();
    private boolean unavailable;

    FakeLedgerClient(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public void releaseClearing(String txId, String clearingAccount, long amountCents, String description) {
        trace.add("ledger.releaseClearing");
        if (unavailable) {
            throw new LedgerUnavailableException("fake ledger unavailable", null);
        }
        releases.add(new Posting(txId, clearingAccount, null, amountCents));
    }

    @Override
    public void reverseToPayer(String txId, String clearingAccount, String payerAccount, long amountCents,
            String description) {
        trace.add("ledger.reverseToPayer");
        if (unavailable) {
            throw new LedgerUnavailableException("fake ledger unavailable", null);
        }
        reversals.add(new Posting(txId, clearingAccount, payerAccount, amountCents));
    }

    @Override
    public void creditInbound(String txId, String clearingAccount, String payeeAccount, long amountCents,
            String description) {
        trace.add("ledger.creditInbound");
        if (unavailable) {
            throw new LedgerUnavailableException("fake ledger unavailable", null);
        }
        inboundCredits.add(new Posting(txId, clearingAccount, payeeAccount, amountCents));
    }

    void beUnavailable() {
        this.unavailable = true;
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
