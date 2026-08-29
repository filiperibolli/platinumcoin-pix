package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.model.StatementWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publish the hot/cold statement boundary (step 53).
 *
 * <h2>Why a use case for one subtraction</h2>
 * Two reasons, and neither is ceremony. First, ADR-0011: one class per inbound operation, so
 * {@code ls domain/usecase/} is this service's capability list — an operation that hid inside a
 * controller would be invisible there. Second, and more usefully, this is the <b>same</b> arithmetic
 * {@link ArchiveOldEntriesUseCase} does at the top of every run ({@code clock.instant() -
 * hotWindow}), and the whole point of exposing it is that callers get the boundary the archiver
 * actually uses. Both read the same injected {@link Clock} and the same configured window, so the
 * answer here cannot drift from the behaviour it describes.
 *
 * <p>Nothing is cached on this side. The value changes every instant (it is relative to now), and the
 * caller is the one who knows how stale an answer it can tolerate — payment-service memoizes it for
 * seconds, because a boundary that moves by a second does not change which month a range starts in.
 */
public class GetStatementWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetStatementWindowUseCase.class);

    private final Duration hotWindow;
    private final Clock clock;

    public GetStatementWindowUseCase(Duration hotWindow, Clock clock) {
        this.hotWindow = hotWindow;
        this.clock = clock;
    }

    public StatementWindow execute() {
        Instant coldBefore = clock.instant().minus(hotWindow);
        log.info("Statement window requested, reporting the boundary the archiving job uses | "
                + "hotWindowDays={} coldBefore={}", hotWindow.toDays(), coldBefore);
        return new StatementWindow(hotWindow.toDays(), coldBefore);
    }
}
