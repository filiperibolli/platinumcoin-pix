package com.platinumcoin.pix.payment.domain.service;

import com.platinumcoin.pix.payment.domain.model.ArchivedStatementLine;
import com.platinumcoin.pix.payment.domain.model.Direction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export artifact's format (step 53). A CSV is the one shape a customer can open without us, which
 * is the whole reason the artifact is not the archive's JSONL forwarded verbatim.
 *
 * <p>The API is deliberately a header and a <b>row at a time</b>, not a document: the worker streams
 * rows into object storage as the archive yields them, because the cold tier is explicitly allowed to
 * hold more than fits in memory. These tests therefore assemble what the worker would, which is also
 * what keeps them honest about the format the file actually has.
 *
 * <p>The assertion worth reading twice is {@link #carriesBothTheIntegerCentsAndTheHumanDecimal()}:
 * money leaves the platform as a decimal string because a person reads it, and it leaves as integer
 * cents <b>as well</b> because a machine may read it back. Emitting only the decimal would make the
 * artifact the one place in the platform where money exists solely as a formatted string (domain
 * safety rule 6).
 */
class StatementCsvTest {

    private static final Instant WHEN = Instant.parse("2025-03-04T10:15:30Z");

    @Test
    void writesAHeaderAndOneLinePerEntryOldestFirst() {
        byte[] csv = render(
                line("tx-1", Direction.DEBIT, -12_550L, WHEN, "a debit"),
                line("tx-2", Direction.CREDIT, 500L, WHEN.plusSeconds(60), "a credit"));

        List<String> rows = rows(csv);
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst()).isEqualTo(
                "txId,timestamp,direction,amountCents,amount,counterpartAccountId,entryType,description");
        assertThat(rows.get(1)).startsWith("tx-1,2025-03-04T10:15:30Z,DEBIT,-12550,-125.50,");
        assertThat(rows.get(2)).startsWith("tx-2,2025-03-04T10:16:30Z,CREDIT,500,5.00,");
    }

    @Test
    void carriesBothTheIntegerCentsAndTheHumanDecimal() {
        byte[] csv = render(line("tx-1", Direction.DEBIT, -1L, WHEN, "one cent"));

        assertThat(rows(csv).get(1)).contains(",-1,-0.01,");
    }

    @Test
    void anEmptyRangeStillProducesAValidCsvWithItsHeader() {
        // Months with no movement are skipped, not failed — so "no lines at all" is a legitimate,
        // successful export and must still open in a spreadsheet.
        List<String> rows = rows(render());

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).startsWith("txId,");
    }

    @Test
    void quotesTheFieldsThatWouldOtherwiseBreakTheRow() {
        byte[] csv = render(line("tx-1", Direction.DEBIT, -100L, WHEN, "pizza, beer \"and\" a\nnewline"));

        String body = new String(csv, StandardCharsets.UTF_8);
        assertThat(body).contains("\"pizza, beer \"\"and\"\" a\nnewline\"");
        // The quoted newline is inside a field, so the file is still two CSV records — but a naive
        // line split sees three. That is exactly why the assertion is on the quoting, not on a count.
        assertThat(body).endsWith("\n");
    }

    @Test
    void aNullDescriptionBecomesAnEmptyFieldNotTheWordNull() {
        byte[] csv = render(line("tx-1", Direction.CREDIT, 100L, WHEN, null));

        assertThat(rows(csv).get(1)).endsWith(",PIX,").doesNotContain("null");
    }

    /** What the worker does: header first, then a row per line, straight into the artifact sink. */
    private static byte[] render(ArchivedStatementLine... lines) {
        StringBuilder csv = new StringBuilder(StatementCsv.header());
        for (ArchivedStatementLine line : lines) {
            csv.append(StatementCsv.row(line));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ArchivedStatementLine line(
            String txId, Direction direction, long cents, Instant when, String description) {
        return new ArchivedStatementLine(
                "acc-001", txId, direction, cents, "acc-002", when, "PIX", description);
    }

    private static List<String> rows(byte[] csv) {
        return List.of(new String(csv, StandardCharsets.UTF_8).split("\n"));
    }
}
