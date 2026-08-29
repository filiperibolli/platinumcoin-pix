package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.model.DownloadLink;
import com.platinumcoin.pix.payment.domain.model.MonthRange;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import java.time.Instant;

/**
 * What a poll of an export returns (step 53) — the stored request plus, when there is one, a freshly
 * minted download link.
 *
 * <p>Deliberately not {@code StatementExport} itself. The stored item holds an object <i>key</i>, which
 * is an internal address and must never reach a client; this view holds a signed URL, which is a
 * capability that exists only for the duration of one answer. Returning the entity would make it one
 * careless mapping away from publishing the bucket layout.
 *
 * @param exportId      the resource's id
 * @param status        where it is in the lifecycle
 * @param range         what was asked for
 * @param requestedAt   when it was accepted
 * @param download      the signed link and its expiry; {@code null} unless {@code READY}
 * @param failureReason why it failed; {@code null} unless {@code FAILED}
 */
public record StatementExportView(
        String exportId,
        StatementExportStatus status,
        MonthRange range,
        Instant requestedAt,
        DownloadLink download,
        String failureReason) {
}
