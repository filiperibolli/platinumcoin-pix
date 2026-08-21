package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import com.platinumcoin.pix.ledger.domain.port.LedgerArchiveReader;
import com.platinumcoin.pix.ledger.domain.port.StatementArchive;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cold-archive capability (ADR-0011, step 43): copy the ledger entries that have aged out of the hot
 * window into {@code pix-statement-archive}, one JSONL object per account and month.
 *
 * <h2>Why a cold archive exists at all</h2>
 * BACEN requires five years of statement history online; at the planning volume (ARCHITECTURE §1) that is
 * ~3.6TB a year of hot DynamoDB data whose oldest 95% is read almost never. Moving it to object storage
 * turns the most expensive tier into the cheapest one while keeping the whole history retrievable — the
 * asynchronous export of step 53 reads exactly these objects. The trade is stated plainly: reads of cold
 * data stop being milliseconds and become an export job, which is why the boundary (the hot window) is
 * configuration and not a constant.
 *
 * <h2>Nothing is deleted — locally, on purpose</h2>
 * A real deployment finishes the job: once an object is written <i>and verified</i>, the hot entries it
 * covers are removed (a DynamoDB TTL attribute set on the archived items, or a bounded delete pass), and
 * that removal is what actually reclaims the storage this whole feature exists for. This platform
 * <b>deliberately stops one step short</b>, for two reasons. First, the local emulator's S3 state is
 * ephemeral — a {@code docker compose down -v} takes the archive with it, so a delete here would destroy
 * history in exchange for nothing. Second, deleting ledger entries is the single most dangerous operation
 * this codebase could contain, and the sandbox has no need of it: leaving it out means no code path
 * exists that could remove a posting, which keeps domain safety rule 5 (append-only history) true by
 * construction rather than by care. The ports enforce it — neither can delete.
 *
 * <h2>Rewriting a month is the update primitive</h2>
 * The archive is derived data, so each run writes each month's object <b>whole</b>. That makes the job
 * idempotent (re-running produces the same objects) and self-healing (an interrupted run is simply
 * repeated), and it is how the boundary month grows: as the window rolls forward, more of that month
 * becomes cold and its object is written again with more lines.
 */
public class ArchiveOldEntriesUseCase {

    private static final Logger log = LoggerFactory.getLogger(ArchiveOldEntriesUseCase.class);

    private final LedgerArchiveReader ledger;
    private final StatementArchive archive;
    private final Duration hotWindow;
    private final int maxAccountsPerRun;
    private final Clock clock;

    public ArchiveOldEntriesUseCase(
            LedgerArchiveReader ledger,
            StatementArchive archive,
            Duration hotWindow,
            int maxAccountsPerRun,
            Clock clock) {
        this.ledger = ledger;
        this.archive = archive;
        this.hotWindow = hotWindow;
        this.maxAccountsPerRun = maxAccountsPerRun;
        this.clock = clock;
    }

    /**
     * One archiving run.
     *
     * @return what was copied — never what was removed, because nothing is
     */
    public ArchiveOutcome execute() {
        Instant cutoff = clock.instant().minus(hotWindow);
        List<String> accountIds = ledger.accountIds(maxAccountsPerRun);
        log.info("Statement cold-archive run started, entries older than the hot window will be COPIED to "
                        + "the archive and left untouched in the ledger | cutoff={} hotWindowDays={} "
                        + "accounts={} maxAccountsPerRun={}",
                cutoff, hotWindow.toDays(), accountIds.size(), maxAccountsPerRun);

        int entriesArchived = 0;
        int objectsWritten = 0;
        for (String accountId : accountIds) {
            List<ArchivedEntry> cold = ledger.entriesOlderThan(accountId, cutoff);
            if (cold.isEmpty()) {
                log.debug("Nothing to archive for this account, its whole history is still inside the hot "
                        + "window | accountId={} cutoff={}", accountId, cutoff);
                continue;
            }
            for (Map.Entry<YearMonth, List<ArchivedEntry>> month : groupByMonth(cold).entrySet()) {
                String objectKey = archive.write(accountId, month.getKey(), month.getValue());
                entriesArchived += month.getValue().size();
                objectsWritten++;
                log.info("Archived a month of an account's statement to cold storage, the hot entries are "
                                + "still in the ledger | accountId={} month={} entries={} objectKey={}",
                        accountId, month.getKey(), month.getValue().size(), objectKey);
            }
        }

        log.info("Statement cold-archive run finished | cutoff={} accountsScanned={} entriesArchived={} "
                + "objectsWritten={}", cutoff, accountIds.size(), entriesArchived, objectsWritten);
        return new ArchiveOutcome(accountIds.size(), entriesArchived, objectsWritten, cutoff);
    }

    /**
     * Group into calendar months <b>in UTC</b> — the same zone the ENTRY sort key is written in. Deriving
     * the month in a machine-local zone would file an entry from 23:30 on the last of the month under a
     * different object depending on where the job ran, i.e. the same ledger producing two archives.
     *
     * <p>Insertion-ordered, and the reader hands entries over oldest first, so both the months and the
     * lines inside them come out in chronological order — an archive object reads like a statement.
     */
    private static Map<YearMonth, List<ArchivedEntry>> groupByMonth(List<ArchivedEntry> entries) {
        Map<YearMonth, List<ArchivedEntry>> byMonth = new LinkedHashMap<>();
        for (ArchivedEntry entry : entries) {
            YearMonth month = YearMonth.from(entry.timestamp().atZone(ZoneOffset.UTC));
            byMonth.computeIfAbsent(month, key -> new ArrayList<>()).add(entry);
        }
        return byMonth;
    }
}
