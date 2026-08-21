package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.model.StatementPage;
import java.util.List;

/**
 * Wire view of one statement page (step 41): the entries newest-first plus the opaque
 * {@code nextCursor} to fetch the following page ({@code null} on the last page). The cursor is passed
 * straight through from the domain, which itself passed it straight through from ledger-service — this
 * edge never decodes it, it only ever echoes back what a client sends it on the next request.
 *
 * @param entries    this page's entries, newest first
 * @param nextCursor the token for the next page, or {@code null} when there is no more history
 */
public record StatementResponse(List<StatementEntry> entries, String nextCursor) {

    static StatementResponse from(StatementPage page) {
        return new StatementResponse(
                page.entries().stream().map(StatementEntry::from).toList(), page.nextCursor());
    }
}
