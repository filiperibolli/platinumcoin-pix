package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;

/**
 * What accepting an export request produced (step 53).
 *
 * <p>{@code status} is the export's <b>current</b> state, not a constant {@code PENDING}. A replay
 * arriving after the worker has finished gets {@code READY} here and says so on the wire — telling a
 * client "PENDING" about an export that is already downloadable would be a lie the client would then
 * poll to discover.
 *
 * @param exportId the resource's id — the same value on every replay of the same key and range
 * @param status   where the export is right now
 * @param replayed whether this call created the export ({@code false}) or found it already there
 *                 ({@code true}). Not on the wire: the HTTP answer is deliberately identical either
 *                 way, so a client cannot build behaviour on the difference. It exists for the log
 *                 line, which is where an operator asks "did this customer really request it twice?"
 */
public record RequestStatementExportOutcome(
        String exportId, StatementExportStatus status, boolean replayed) {
}
