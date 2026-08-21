package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.usecase.ArchiveOldEntriesUseCase;
import com.platinumcoin.pix.ledger.domain.usecase.ArchiveOutcome;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The statement cold-archive job's inbound adapter (step 43): a slow schedule that drives one
 * {@link ArchiveOldEntriesUseCase}.
 *
 * <h2>Why this is {@code api/}</h2>
 * A schedule is a way of <i>entering</i> the application, no different in kind from an HTTP request
 * (ADR-0011), so it lives beside the controllers and obeys the same rule: hold no policy, call
 * <b>one</b> use case, map the result. What "old" means, how entries are grouped into objects and the
 * fact that nothing is ever deleted are all decisions of the use case, where a plain-Java test pins
 * them; this class only decides <i>when</i>.
 *
 * <p>The tick obeys {@code pix.schedulers.enabled} (off in ITs, which call {@link #archiveOnce()}
 * explicitly), because Spring caches contexts across test classes and a live archiver writing into the
 * shared bucket would fight unrelated assertions.
 */
@Component
public class StatementArchiver {

    private static final Logger log = LoggerFactory.getLogger(StatementArchiver.class);

    private final ArchiveOldEntriesUseCase archiveOldEntries;

    public StatementArchiver(ArchiveOldEntriesUseCase archiveOldEntries) {
        this.archiveOldEntries = archiveOldEntries;
        log.info("Statement cold-archive job ready, it will copy ledger entries older than the hot window "
                + "to the archive bucket on a schedule and delete nothing from the ledger");
    }

    /**
     * One archiving run. Never lets an exception escape: a scheduled task that throws is noise in a
     * framework log, and there is nothing to abort — the archive is a copy, so a failed run leaves the
     * ledger exactly as it was and the next run redoes the same work.
     *
     * @return the run's outcome, so an integration test can drive it deterministically instead of
     *         waiting on the schedule
     */
    @Scheduled(fixedDelayString = "${pix.archive.fixed-delay-ms}")
    public ArchiveOutcome archiveOnce() {
        try {
            return archiveOldEntries.execute();
        } catch (RuntimeException e) {
            log.error("The statement cold-archive run failed; nothing was removed from the ledger, so the "
                    + "next run simply repeats the work", e);
            return new ArchiveOutcome(0, 0, 0, Instant.EPOCH);
        }
    }
}
