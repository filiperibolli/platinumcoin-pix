package com.platinumcoin.pix.settlement.support;

import com.platinumcoin.pix.settlement.domain.exception.SpiCallFailedException;
import com.platinumcoin.pix.settlement.domain.model.SpiSettlement;
import com.platinumcoin.pix.settlement.domain.port.SpiSettlementClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final List<String> settledEndToEndIds = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;
    private volatile Instant recordedAt = Instant.parse("2026-08-13T10:15:29Z");

    @Override
    public SpiSettlement settle(String endToEndId, String creditorKey, long amountCents,
            String description, String debtorIspb) {
        settledEndToEndIds.add(endToEndId);
        if (failure != null) {
            throw failure;
        }
        return new SpiSettlement(endToEndId, amountCents, CREDITOR_ISPB, recordedAt);
    }

    /** Every settlement attempt, in order — so a test can prove a duplicate never reached the rail. */
    public List<String> attempts() {
        return settledEndToEndIds;
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void failAsUnavailable() {
        failWith(new SpiCallFailedException("the stub rail is unavailable", null));
    }

    public void succeed() {
        this.failure = null;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public void reset() {
        settledEndToEndIds.clear();
        failure = null;
    }
}
