package com.platinumcoin.pix.payment.domain.usecase;

/**
 * One delivery of a {@code StatementExportRequested} message, as the worker sees it (step 53).
 *
 * @param eventId         the envelope's id — what the dedup gate keys on (Domain Safety Rule #2)
 * @param exportId        which export to assemble
 * @param deliveryAttempt SQS's {@code ApproximateReceiveCount}: 1 on the first delivery, higher on a
 *                        redelivery. It is the <b>queue's</b> count and not one the platform keeps,
 *                        which is what makes the attempt budget survive a restart of this service —
 *                        an in-memory counter would reset and retry for ever
 */
public record BuildStatementExportCommand(String eventId, String exportId, int deliveryAttempt) {
}
