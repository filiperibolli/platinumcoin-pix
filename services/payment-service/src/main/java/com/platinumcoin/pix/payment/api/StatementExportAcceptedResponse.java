package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.payment.domain.usecase.RequestStatementExportOutcome;

/**
 * Wire view of an accepted export request (step 53) — the body of the {@code 202}.
 *
 * <p>A fresh acceptance and an idempotent replay render <b>identically</b>: same status, same
 * {@code Location}, same body from the same id. That is deliberate, and it is the same choice
 * {@code POST /payments/pix} makes — a client that lost the original response must be able to retry and
 * get an answer it cannot distinguish from the first, or the retry would need its own handling and the
 * whole point of idempotency would be gone. Whether the platform created or replayed is on the log
 * line, not on the wire.
 *
 * @param exportId  the request resource's id
 * @param status    its state right now — {@code PENDING} on a first request, and whatever the export
 *                  has since become on a replay
 * @param statusUrl where to poll, same value as the {@code Location} header
 */
public record StatementExportAcceptedResponse(String exportId, String status, String statusUrl) {

    static StatementExportAcceptedResponse from(RequestStatementExportOutcome outcome) {
        return new StatementExportAcceptedResponse(
                outcome.exportId(), outcome.status().name(), statusPath(outcome.exportId()));
    }

    /** The polling route, as a path — the service knows its own routes, not its public base URL. */
    static String statusPath(String exportId) {
        return "/v1/statement-exports/" + exportId;
    }
}
