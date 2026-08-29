package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.ArchivedStatementLine;
import java.time.YearMonth;
import java.util.function.Consumer;

/**
 * Outbound port for <b>reading</b> the cold statement archive (step 53) — the monthly JSONL objects
 * ledger-service writes in step 43.
 *
 * <h2>Why payment-service reads another service's artifact</h2>
 * ADR-0006 forbids a service reading another's <i>table</i>, and this is not one: the archive is an
 * object-storage artifact with a published layout ({@code account=<id>/yyyy-MM.jsonl}, Hive-style
 * partitioning chosen precisely so anything can read it — Athena and Glue included). Treating it as
 * private to ledger-service would mean adding a synchronous "give me a month of history" API in front
 * of a store whose whole purpose is being cheap to read in bulk. The boundary that matters is upheld
 * the other way round: nothing here writes.
 *
 * <h2>Why this is a callback and not a {@code List}</h2>
 * Because the answer has no bound. A month of a busy account is an unbounded number of lines, and the
 * cold tier exists <i>precisely</i> to hold data that is allowed to be large — a port returning
 * {@code List<ArchivedStatementLine>} would be promising that a month always fits in memory. It does
 * not, and the worker that calls this shares a JVM with {@code POST /v1/payments/pix}: one customer's
 * two-year export would become an {@code OutOfMemoryError} that takes the money path down with it.
 * Handing each line to a consumer as it is parsed keeps the whole flow's memory bounded by one line
 * plus whatever the artifact sink buffers, whatever the export's size.
 */
public interface StatementArchiveReader {

    /**
     * Stream every archived line for one account and one month, oldest first, to {@code onLine}.
     *
     * <p><b>A month with no object streams nothing and returns 0 — it is not a failure.</b> An account
     * simply had no movement that month, which is the common case at the edges of any range, so the
     * caller skips it and carries on (step 53, task 3). Distinguishing "no movement" from "the object
     * is missing" is not possible from the archive alone and would not change what the export can
     * contain.
     *
     * @return how many lines were streamed
     */
    int stream(String accountId, YearMonth month, Consumer<ArchivedStatementLine> onLine);
}
