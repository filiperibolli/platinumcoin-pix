package com.platinumcoin.pix.payment.domain.model;

import com.platinumcoin.pix.payment.domain.exception.InvalidExportRangeException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * An inclusive range of calendar months — what a cold-statement export is asked for (step 53).
 *
 * <h2>Why this is a type and not two strings on a command</h2>
 * The archive is keyed by month ({@code account=<id>/yyyy-MM.jsonl}, step 43), so "which months" is the
 * export's whole input, and every rule that can reject a request before any work happens is a statement
 * about this pair. Making it a value object puts all of those rules in one plain-Java class with one
 * unit test, and leaves {@code RequestStatementExportUseCase} holding only the two rules that need a
 * fact from elsewhere — when the account was opened, and where the hot window ends.
 *
 * <h2>Months are UTC calendar months, because the archive's are</h2>
 * {@code ArchiveOldEntriesUseCase} groups entries into months in UTC, explicitly so that the same
 * ledger cannot produce two different archives depending on where the job ran. This range therefore
 * means UTC months too — anything else would ask for a file that is not the file the writer wrote.
 *
 * @param from first month, inclusive
 * @param to   last month, inclusive
 */
public record MonthRange(YearMonth from, YearMonth to) {

    /**
     * The most months one export may cover. Two years is a deliberate bound rather than a technical
     * one: it caps how many archive objects a single worker run must read and merge, which is what
     * keeps one customer's request from becoming an unbounded job. A client that wants more asks for
     * consecutive ranges — the resource is cheap and the artifacts are independent.
     */
    public static final int MAX_MONTHS = 24;

    /**
     * Parse and validate the wire form ({@code yyyy-MM}).
     *
     * @throws InvalidExportRangeException null, unparseable, inverted, or longer than {@link #MAX_MONTHS}
     */
    public static MonthRange parse(String fromMonth, String toMonth) {
        YearMonth from = parseMonth(fromMonth, "fromMonth");
        YearMonth to = parseMonth(toMonth, "toMonth");

        if (from.isAfter(to)) {
            throw new InvalidExportRangeException(
                    "fromMonth " + from + " is after toMonth " + to + "; the range is inverted.");
        }
        long months = from.until(to, java.time.temporal.ChronoUnit.MONTHS) + 1;
        if (months > MAX_MONTHS) {
            throw new InvalidExportRangeException("An export covers at most " + MAX_MONTHS
                    + " months; " + from + ".." + to + " covers " + months + ".");
        }
        return new MonthRange(from, to);
    }

    /** Every month in the range, oldest first — one archive object each. */
    public List<YearMonth> months() {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(month);
        }
        return List.copyOf(months);
    }

    /**
     * Does this range reach back before the account existed?
     *
     * <p>The opening month itself counts as in-range: an account opened on the 20th still has movement
     * in that month, and refusing it would hide real history behind a rule meant to catch typos.
     */
    public boolean startsBefore(YearMonth accountOpenedMonth) {
        return from.isBefore(accountOpenedMonth);
    }

    /**
     * Is every month of this range still inside the hot window — i.e. is there nothing here the archive
     * could hold?
     *
     * @param newestColdMonth the most recent month the archive can hold anything for (the month
     *                        containing the hot/cold cutoff: part of it is already older than the
     *                        window, so part of it is archived)
     */
    public boolean isEntirelyHot(YearMonth newestColdMonth) {
        return from.isAfter(newestColdMonth);
    }

    private static YearMonth parseMonth(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidExportRangeException(field + " is required, in yyyy-MM form.");
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException notAMonth) {
            throw new InvalidExportRangeException(
                    field + " is not a calendar month in yyyy-MM form: " + value);
        }
    }
}
