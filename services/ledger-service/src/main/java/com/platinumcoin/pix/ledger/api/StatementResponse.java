package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.StatementPage;
import java.util.List;

/**
 * Wire view of one statement page: the entries newest-first plus the opaque {@code nextCursor} to
 * fetch the following page ({@code null} on the last page). The cursor is passed straight through from
 * the domain — it is already the base64 token the adapter produced, and the client only ever echoes it
 * back on the next request.
 *
 * @param entries    the page's entries, newest first
 * @param nextCursor the token for the next page, or {@code null} when there is no more history
 */
public record StatementResponse(List<StatementEntry> entries, String nextCursor) {

    static StatementResponse from(StatementPage page) {
        return new StatementResponse(
                page.entries().stream().map(StatementEntry::from).toList(),
                page.nextCursor());
    }
}
