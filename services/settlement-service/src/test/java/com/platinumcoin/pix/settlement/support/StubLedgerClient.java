package com.platinumcoin.pix.settlement.support;

import com.platinumcoin.pix.settlement.domain.model.LedgerOutcome;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A hermetic ledger for the integration tests: it applies each finalization posting to an in-memory
 * balance map so an IT can assert "the payer was refunded", "clearing nets to zero" and "Σ balances is
 * conserved" without booting ledger-service — the same way {@link StubSpiSettlementClient} stands in for
 * BACEN. The HTTP translation of the real {@code HttpSettlementLedgerClient} is pinned separately by its
 * own adapter test; what the ITs care about is the orchestration and the money it moves.
 *
 * <p><b>Idempotent by {@code txId}, exactly like the real ledger.</b> A posting whose {@code txId} was
 * already applied is a no-op replay — which is what lets an IT prove "re-run ⇒ no double refund" by simply
 * driving the consumer twice. Registered {@code @Primary} by {@link SettlementTestSupport}, overriding the
 * real adapter (which stays in the context so a wiring mistake still fails startup).
 */
public class StubLedgerClient implements LedgerClient {

    /** The credit counterpart of a CLEARING_RELEASE — the test's model of "settled out to the network". */
    public static final String SETTLED_ACCOUNT = "SPI_SETTLED";

    public record Posting(String txId, String debitAccount, String creditAccount, long amountCents) {
    }

    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Set<String> appliedTxIds = ConcurrentHashMap.newKeySet();
    private final List<Posting> postings = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    @Override
    public LedgerOutcome releaseClearing(String txId, String clearingAccount, long amountCents,
            String description) {
        return apply(new Posting(txId, clearingAccount, SETTLED_ACCOUNT, amountCents));
    }

    @Override
    public LedgerOutcome reverseToPayer(String txId, String clearingAccount, String payerAccount,
            long amountCents, String description) {
        return apply(new Posting(txId, clearingAccount, payerAccount, amountCents));
    }

    @Override
    public LedgerOutcome creditInbound(String txId, String clearingAccount, String payeeAccount,
            long amountCents, String description) {
        return apply(new Posting(txId, clearingAccount, payeeAccount, amountCents));
    }

    private LedgerOutcome apply(Posting posting) {
        if (unavailable) {
            // A ledger that answers and declines: nothing moves, and the caller must not transition.
            return LedgerOutcome.REFUSED;
        }
        postings.add(posting);
        // Idempotent by txId: a replay records the attempt (so a test can see it happened), moves no
        // money a second time, and SAYS it was a replay — the ledger's own guard and flag, modelled here.
        if (!appliedTxIds.add(posting.txId())) {
            return LedgerOutcome.REPLAYED;
        }
        balances.merge(posting.debitAccount(), -posting.amountCents(), Long::sum);
        balances.merge(posting.creditAccount(), posting.amountCents(), Long::sum);
        return LedgerOutcome.POSTED;
    }

    /** Arrange a starting balance for an account (e.g. the clearing account holding the parked money). */
    public void setBalance(String account, long cents) {
        balances.put(account, cents);
    }

    public long balance(String account) {
        return balances.getOrDefault(account, 0L);
    }

    /** Σ over every account this stub has touched — the conservation invariant, checkable in a test. */
    public long totalBalance() {
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }

    public List<Posting> postings() {
        return postings;
    }

    public void beUnavailable() {
        this.unavailable = true;
    }

    public void reset() {
        balances.clear();
        appliedTxIds.clear();
        postings.clear();
        unavailable = false;
    }
}
