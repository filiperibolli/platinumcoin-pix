package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** BACEN's rail, under the test's control: it answers, or it fails exactly how the test says. */
final class FakeSpiSettlementClient implements SpiSettlementClient {

    /** What the fake rail reports back for a settled Pix. */
    static final String CREDITOR_ISPB = "99999999";
    static final Instant RECORDED_AT = Instant.parse("2026-08-13T10:15:29Z");

    private final List<String> trace;
    private RuntimeException failure;
    private int calls;
    private String lastEndToEndId;
    private String lastCreditorKey;
    private String lastDebtorIspb;
    private long lastAmountCents;
    private SpiSettlement queryAnswer;

    FakeSpiSettlementClient(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public SpiSettlement settle(String endToEndId, String creditorKey, long amountCents,
            String description, String debtorIspb) {
        trace.add("spi.settle");
        calls++;
        lastEndToEndId = endToEndId;
        lastCreditorKey = creditorKey;
        lastAmountCents = amountCents;
        lastDebtorIspb = debtorIspb;
        if (failure != null) {
            throw failure;
        }
        return new SpiSettlement(endToEndId, amountCents, CREDITOR_ISPB, RECORDED_AT);
    }

    /** The query-before-retry lookup: empty unless a test arranged a settled answer with {@link #settledAtRail}. */
    @Override
    public Optional<SpiSettlement> findSettlement(String endToEndId) {
        trace.add("spi.findSettlement");
        return Optional.ofNullable(queryAnswer);
    }

    /** The resolver's richer query — unused by SettlePixUseCaseTest; mirrors {@link #findSettlement}. */
    @Override
    public SpiReconciliation reconcile(String endToEndId) {
        trace.add("spi.reconcile");
        return queryAnswer != null ? SpiReconciliation.settled(queryAnswer) : SpiReconciliation.unknown();
    }

    /** Arrange the rail so a query-before-retry discovers this id already SETTLED (a timeout that landed). */
    void settledAtRail(String endToEndId, long amountCents) {
        this.queryAnswer = new SpiSettlement(endToEndId, amountCents, CREDITOR_ISPB, RECORDED_AT);
    }

    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    int calls() {
        return calls;
    }

    String lastEndToEndId() {
        return lastEndToEndId;
    }

    String lastCreditorKey() {
        return lastCreditorKey;
    }

    String lastDebtorIspb() {
        return lastDebtorIspb;
    }

    long lastAmountCents() {
        return lastAmountCents;
    }
}
