package com.platinumcoin.pix.ledger.domain.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import com.platinumcoin.pix.ledger.domain.model.Direction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The cold-archive policy, framework-free (ADR-0011): what counts as cold, how it is grouped into
 * objects, and — the invariant that matters — that archiving <b>copies</b>.
 */
class ArchiveOldEntriesUseCaseTest {

    /** "Now" for every test: the middle of August 2026. */
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final FakeLedgerArchiveReader ledger = new FakeLedgerArchiveReader();
    private final FakeStatementArchive archive = new FakeStatementArchive();

    /**
     * The hot window is the boundary: an entry inside it belongs to the online statement, one outside it
     * is cold. The cutoff is computed from the injected clock, never from {@code Instant.now()}.
     */
    @Test
    void onlyEntriesOlderThanTheHotWindowAreArchived() {
        ledger.given("acc-001",
                entry("tx-old", "2026-05-04T10:00:00Z", -12_550L),
                entry("tx-recent", "2026-08-20T10:00:00Z", -3_300L));

        ArchiveOutcome outcome = useCase(Duration.ofDays(30)).execute();

        assertThat(outcome.entriesArchived()).isEqualTo(1);
        assertThat(archive.written()).hasSize(1);
        assertThat(archive.written().getFirst().entries())
                .extracting(ArchivedEntry::txId)
                .as("an entry inside the hot window stays out of the cold archive")
                .containsExactly("tx-old");
        assertThat(outcome.cutoff()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    }

    /**
     * One object per account and month — {@code account=<id>/yyyy-MM.jsonl}. Grouping by month is what
     * makes a later export (step 53) a bounded read instead of a scan of the whole account.
     */
    @Test
    void coldEntriesAreGroupedIntoOneObjectPerAccountAndMonth() {
        ledger.given("acc-001",
                entry("tx-may-1", "2026-05-04T10:00:00Z", -1_000L),
                entry("tx-may-2", "2026-05-19T10:00:00Z", 2_000L),
                entry("tx-jun-1", "2026-06-01T10:00:00Z", -3_000L));
        ledger.given("acc-002",
                entry("tx-may-3", "2026-05-07T10:00:00Z", 4_000L));

        ArchiveOutcome outcome = useCase(Duration.ofDays(30)).execute();

        assertThat(outcome.accountsScanned()).isEqualTo(2);
        assertThat(outcome.entriesArchived()).isEqualTo(4);
        assertThat(outcome.objectsWritten()).isEqualTo(3);
        assertThat(archive.written())
                .extracting(FakeStatementArchive.Written::accountId, FakeStatementArchive.Written::month)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("acc-001", YearMonth.of(2026, 5)),
                        org.assertj.core.api.Assertions.tuple("acc-001", YearMonth.of(2026, 6)),
                        org.assertj.core.api.Assertions.tuple("acc-002", YearMonth.of(2026, 5)));
        assertThat(archive.written().getFirst().entries())
                .extracting(ArchivedEntry::txId)
                .as("within a month the archive reads oldest first, like a bank statement")
                .containsExactly("tx-may-1", "tx-may-2");
    }

    /**
     * The month is derived in <b>UTC</b>, the same zone the ENTRY sort key is written in. Deriving it in
     * a local zone would file an entry from 23:30 on the 31st under the following month on one machine
     * and the current one on another — the same ledger producing two different archives.
     */
    @Test
    void theMonthOfAnEntryIsDerivedInUtcLikeTheSortKey() {
        ledger.given("acc-001", entry("tx-boundary", "2026-05-31T23:30:00Z", -500L));

        useCase(Duration.ofDays(30)).execute();

        assertThat(archive.written().getFirst().month()).isEqualTo(YearMonth.of(2026, 5));
    }

    /** An account whose history is entirely inside the hot window writes no object at all. */
    @Test
    void anAccountWithNothingColdWritesNoObject() {
        ledger.given("acc-001", entry("tx-recent", "2026-08-20T10:00:00Z", -3_300L));

        ArchiveOutcome outcome = useCase(Duration.ofDays(30)).execute();

        assertThat(outcome.entriesArchived()).isZero();
        assertThat(outcome.objectsWritten()).isZero();
        assertThat(archive.written()).as("an empty object is noise in a bucket, not an archive").isEmpty();
    }

    /**
     * <b>Archiving copies; it never removes.</b> Running the job twice must produce the same archive from
     * the same ledger — which is only possible because the first run took nothing away. The ports make it
     * structurally true (neither of them can delete), and this pins the consequence.
     */
    @Test
    void archivingIsIdempotentBecauseItOnlyEverCopies() {
        ledger.given("acc-001", entry("tx-old", "2026-05-04T10:00:00Z", -12_550L));
        var useCase = useCase(Duration.ofDays(30));

        ArchiveOutcome first = useCase.execute();
        ArchiveOutcome second = useCase.execute();

        assertThat(second.entriesArchived()).isEqualTo(first.entriesArchived());
        assertThat(archive.written()).hasSize(2);
        assertThat(archive.written().get(1).entries())
                .as("the second run still sees the entry the first one archived")
                .isEqualTo(archive.written().getFirst().entries());
    }

    /** Money crosses into the archive as the signed integer cents it is in the ledger. */
    @Test
    void moneyIsCarriedIntoTheArchiveAsSignedIntegerCents() {
        ledger.given("acc-001",
                entry("tx-debit", "2026-05-04T10:00:00Z", -12_550L),
                entry("tx-credit", "2026-05-05T10:00:00Z", 12_550L));

        useCase(Duration.ofDays(30)).execute();

        assertThat(archive.written().getFirst().entries())
                .extracting(ArchivedEntry::amountCents)
                .containsExactly(-12_550L, 12_550L);
    }

    private ArchiveOldEntriesUseCase useCase(Duration hotWindow) {
        return new ArchiveOldEntriesUseCase(
                ledger, archive, hotWindow, 500, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ArchivedEntry entry(String txId, String timestamp, long amountCents) {
        return new ArchivedEntry(
                "acc-001",
                txId,
                amountCents < 0 ? Direction.DEBIT : Direction.CREDIT,
                amountCents,
                "acc-999",
                Instant.parse(timestamp),
                "PIX_OUT",
                "PIX to bob@otherbank.com");
    }
}
