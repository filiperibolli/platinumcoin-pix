package com.platinumcoin.pix.payment.domain.service;

import com.platinumcoin.pix.payment.domain.model.ArchivedStatementLine;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Renders the cold-statement export artifact (step 53): archive lines in, one CSV file out.
 *
 * <h2>Why CSV, and why this is the flow's API edge</h2>
 * The archive is JSON Lines because a query engine reads it; the export is CSV because a <b>person</b>
 * reads it, in the spreadsheet they already have. That makes this class the point where the export
 * leaves the platform, and therefore the point where money is allowed to become a decimal string
 * (domain safety rule 6) — there is no controller in this path at all, the artifact is written straight
 * to object storage by a worker.
 *
 * <p><b>Both money columns, on purpose.</b> {@code amountCents} is the lossless integer the platform
 * holds; {@code amount} is the signed decimal a human expects. Emitting only the decimal would make
 * this artifact the single place in the platform where an amount exists <i>solely</i> as a formatted
 * string, and a customer re-importing it would be parsing money back out of presentation. Emitting only
 * the integer would hand someone a column of cents to divide by hand.
 *
 * <p><b>Signs are the archive's, unchanged</b>: a DEBIT is negative and a CREDIT is positive, exactly
 * as the ledger wrote them, so summing the {@code amountCents} column of a full-history export gives
 * the account's movement rather than a number that needs a legend.
 *
 * <p>Plain Java in {@code domain/service/} (ADR-0010): no Jackson, no Spring, no AWS — the text is
 * built here and some adapter decides where it goes.
 */
public final class StatementCsv {

    /**
     * The header, and with it the column contract of the artifact. Adding a column at the end is
     * additive for every consumer that reads by name; reordering is not.
     */
    static final String HEADER =
            "txId,timestamp,direction,amountCents,amount,counterpartAccountId,entryType,description";

    /** ISO-8601 in UTC — the archive's own timestamp format, so a round-trip changes nothing. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private StatementCsv() {
    }

    /** The header row, terminated. Written first, and written even when there is nothing after it. */
    public static String header() {
        return HEADER + "\n";
    }

    /**
     * One entry as a terminated CSV row.
     *
     * <p><b>One row at a time, not a whole document.</b> The cold archive is the tier that is
     * explicitly allowed to be large, and the worker that calls this shares a JVM with the send path —
     * so nothing here may assume the result fits in memory. The caller streams rows straight into the
     * artifact sink as the archive yields them; this class never sees more than one line at once.
     */
    public static String row(ArchivedStatementLine line) {
        return field(line.txId()) + ','
                + field(TIMESTAMP.format(line.timestamp())) + ','
                + field(line.direction().name()) + ','
                + line.amountCents() + ','
                + decimal(line.amountCents()) + ','
                + field(line.counterpartAccountId()) + ','
                + field(line.entryType()) + ','
                + field(line.description())
                + '\n';
    }

    /**
     * Signed integer cents → signed fixed 2-decimal string ({@code -12550 → "-125.50"}), through
     * {@link BigDecimal} and never a {@code double}: an exact base-10 shift cannot invent or lose a
     * cent. Same conversion the statement API's edge does, in the other direction from
     * {@link com.platinumcoin.pix.payment.domain.model.Money}.
     */
    private static String decimal(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }

    /**
     * RFC 4180 quoting, applied only where it is needed. A description is free text a customer typed,
     * so it may hold a comma, a quote or a newline — any of which would silently split a row into
     * garbage if written raw. A {@code null} becomes an empty field, never the four letters
     * {@code null}: a spreadsheet showing "null" in a column is a bug report, not a blank.
     */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
