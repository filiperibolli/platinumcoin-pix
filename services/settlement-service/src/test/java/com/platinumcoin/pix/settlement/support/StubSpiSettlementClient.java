package com.platinumcoin.pix.settlement.support;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.model.SpiReconciliation;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A hermetic rail for the integration tests: it settles whatever it is asked to settle, records every
 * call, and can be told to fail. Registered as {@code @Primary} by {@link SettlementTestSupport},
 * overriding {@code HttpSpiSettlementClient}.
 *
 * <p><b>Why stub the rail here.</b> The HTTP translation — which status becomes which domain type,
 * what the 12s budget does to a hung rail — is pinned at the adapter by
 * {@code HttpSpiSettlementClientTest} against a real HTTP server. What the ITs are for is the other
 * half: the queue, the dedup table and the two guarded transitions against real DynamoDB and real SQS.
 * Booting mock-bacen inside these tests would add a container and prove neither better.
 *
 * <p>Idempotent like the real rail: the same {@code endToEndId} always gets the same answer back, which
 * is what makes any retry above it safe.
 */
public class StubSpiSettlementClient implements SpiSettlementClient {

    public static final String CREDITOR_ISPB = "99999999";

    /** Every {@code POST} attempt, in order — so a test can prove a duplicate never reached the rail. */
    private final List<String> postAttempts = new CopyOnWriteArrayList<>();
    /** Every {@code GET} (query-before-retry) attempt, in order. */
    private final List<String> queries = new CopyOnWriteArrayList<>();
    /** What the rail knows as SETTLED — populated only by a settle that actually committed. */
    private final Map<String, SpiSettlement> settledAtRail = new ConcurrentHashMap<>();

    /** When set, the reconciliation query returns exactly this, regardless of what the rail settled. */
    private volatile SpiReconciliation reconcileOverride;
    private volatile RuntimeException failure;
    /** {@code >0} makes the next N POSTs fail transiently (recording nothing), then settle for real. */
    private final AtomicInteger transientFailures = new AtomicInteger();
    /** When true, a POST settles at the rail and then withholds the answer — a timeout that landed. */
    private volatile boolean withholdAnswer;
    private volatile Instant recordedAt = Instant.parse("2026-08-13T10:15:29Z");

    @Override
    public SpiSettlement settle(String endToEndId, String creditorKey, long amountCents,
            String description, String debtorIspb) {
        postAttempts.add(endToEndId);

        if (withholdAnswer) {
            // The rail moved the money, then the answer got lost past the caller's timeout. The settlement
            // is recorded (so a later query discovers it) but the POST itself reports UNKNOWN.
            settledAtRail.put(endToEndId, new SpiSettlement(endToEndId, amountCents, CREDITOR_ISPB, recordedAt));
            throw new SpiCallFailedException("the stub rail settled but withheld the answer (timeout)", null);
        }
        if (transientFailures.get() > 0) {
            transientFailures.decrementAndGet();
            throw new SpiCallFailedException("the stub rail is transiently unavailable", null);
        }
        if (failure != null) {
            throw failure;
        }

        SpiSettlement settlement = new SpiSettlement(endToEndId, amountCents, CREDITOR_ISPB, recordedAt);
        settledAtRail.put(endToEndId, settlement);
        return settlement;
    }

    /** The query-before-retry lookup: SETTLED iff a prior POST actually committed at this stub rail. */
    @Override
    public Optional<SpiSettlement> findSettlement(String endToEndId) {
        queries.add(endToEndId);
        return Optional.ofNullable(settledAtRail.get(endToEndId));
    }

    /**
     * The reconciliation resolver's richer query (step 35). By default it derives the honest answer from
     * what the stub rail settled — SETTLED if a POST committed, otherwise UNKNOWN — so a test that drove a
     * real settle needs no extra arrangement. A test can override it with {@link #reconcilesFailed},
     * {@link #reconcilesUnknown} or {@link #reconcilesUnreachable} to drive the reverse/leave branches.
     */
    @Override
    public SpiReconciliation reconcile(String endToEndId) {
        queries.add(endToEndId);
        if (reconcileOverride != null) {
            return reconcileOverride;
        }
        SpiSettlement settled = settledAtRail.get(endToEndId);
        return settled != null ? SpiReconciliation.settled(settled) : SpiReconciliation.unknown();
    }

    /** Force the resolver's query to report the id already SETTLED at the rail (a settle whose ack was lost). */
    public void reconcilesSettled(String endToEndId, long amountCents) {
        this.reconcileOverride = SpiReconciliation.settled(
                new SpiSettlement(endToEndId, amountCents, CREDITOR_ISPB, recordedAt));
    }

    /** Force the resolver's query to report a permanent refusal. */
    public void reconcilesFailed(String reason) {
        this.reconcileOverride = SpiReconciliation.failed(reason);
    }

    /** Force the resolver's query to report the rail has no record of the id. */
    public void reconcilesUnknown() {
        this.reconcileOverride = SpiReconciliation.unknown();
    }

    /** Force the resolver's query to report the rail could not be reached. */
    public void reconcilesUnreachable() {
        this.reconcileOverride = SpiReconciliation.unreachable();
    }

    public List<String> attempts() {
        return postAttempts;
    }

    public List<String> queries() {
        return queries;
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void failAsUnavailable() {
        failWith(new SpiCallFailedException("the stub rail is unavailable", null));
    }

    /** The next {@code n} POSTs fail transiently (nothing recorded), then the rail settles for real. */
    public void failTransientlyThenSucceed(int n) {
        transientFailures.set(n);
    }

    /** Model a timeout that actually settled: the POST records the settlement but throws UNKNOWN. */
    public void settleButWithholdAnswer() {
        this.withholdAnswer = true;
    }

    public void succeed() {
        this.failure = null;
        this.withholdAnswer = false;
        this.transientFailures.set(0);
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public void reset() {
        postAttempts.clear();
        queries.clear();
        settledAtRail.clear();
        failure = null;
        reconcileOverride = null;
        withholdAnswer = false;
        transientFailures.set(0);
    }
}
