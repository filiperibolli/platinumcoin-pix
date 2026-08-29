package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platinumcoin.pix.payment.domain.usecase.StatementExportView;

/**
 * Wire view of an export's state (step 53) — the body of {@code GET /v1/statement-exports/{exportId}}.
 *
 * <p>The three nullable fields are the lifecycle made visible: {@code downloadUrl}/{@code expiresAt}
 * exist only on {@code READY}, {@code failureReason} only on {@code FAILED}. They are serialized as
 * explicit {@code null}s rather than omitted, so a client can bind one shape and read {@code status} to
 * know which fields to trust — the contract in {@code docs/api/openapi.yaml} declares them
 * {@code nullable} for the same reason.
 *
 * <p>What is <b>not</b> here is the artifact's object key. The stored item holds it; this view holds a
 * signed URL that stops working. Publishing the key would leak the bucket layout for no gain, since a
 * customer cannot use it.
 *
 * @param exportId       the resource's id
 * @param status         {@code PENDING}, {@code READY} or {@code FAILED}
 * @param requestedRange the months asked for
 * @param requestedAt    when the request was accepted (ISO-8601)
 * @param downloadUrl    presigned URL, minted for this response; {@code null} unless {@code READY}
 * @param expiresAt      when that URL stops working; {@code null} unless {@code READY}
 * @param failureReason  why it failed; {@code null} unless {@code FAILED}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StatementExportResponse(
        String exportId,
        String status,
        MonthRangeResponse requestedRange,
        String requestedAt,
        String downloadUrl,
        String expiresAt,
        String failureReason) {

    /** The months, echoed back so a client polling a stored id does not have to remember what it asked. */
    public record MonthRangeResponse(String fromMonth, String toMonth) {
    }

    static StatementExportResponse from(StatementExportView view) {
        return new StatementExportResponse(
                view.exportId(),
                view.status().name(),
                new MonthRangeResponse(view.range().from().toString(), view.range().to().toString()),
                view.requestedAt().toString(),
                view.download() == null ? null : view.download().url(),
                view.download() == null ? null : view.download().expiresAt().toString(),
                view.failureReason());
    }
}
