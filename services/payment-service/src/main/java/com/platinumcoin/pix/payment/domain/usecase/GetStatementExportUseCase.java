package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.StatementExportNotFoundException;
import com.platinumcoin.pix.payment.domain.model.DownloadLink;
import com.platinumcoin.pix.payment.domain.model.StatementExport;
import com.platinumcoin.pix.payment.domain.model.StatementExportStatus;
import com.platinumcoin.pix.payment.domain.port.StatementExportArtifactStore;
import com.platinumcoin.pix.payment.domain.port.StatementExportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poll an export, and mint its download link once there is one (step 53).
 *
 * <h2>Ownership is the whole security of this endpoint</h2>
 * The route is addressed by id, so nothing but the check below stands between a guessed id and another
 * customer's financial history. Two properties make it hold:
 * <ul>
 *   <li>The account comes from the JWT, never from the request (Domain Safety Rule #1) — the caller
 *       cannot name whose export it is asking for.</li>
 *   <li>"Not yours" and "does not exist" raise the <b>same</b> exception, so the API answers {@code 404}
 *       to both and never confirms that an id is real.</li>
 * </ul>
 * And the order matters: ownership is checked <i>before</i> the link is minted. A presigned URL handed
 * to the wrong caller is a leak no later status code can take back.
 *
 * <h2>Signed on read, never stored</h2>
 * The item holds the object key; the URL is created here, for this answer, with the store's configured
 * lifetime. An export therefore stays downloadable for as long as it exists — a link minted when the
 * worker finished would start expiring while the customer was still being told the file was ready.
 */
public class GetStatementExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetStatementExportUseCase.class);

    private final StatementExportRepository exports;
    private final StatementExportArtifactStore artifacts;

    public GetStatementExportUseCase(
            StatementExportRepository exports, StatementExportArtifactStore artifacts) {
        this.exports = exports;
        this.artifacts = artifacts;
    }

    public StatementExportView execute(String callerAccountId, String exportId) {
        StatementExport export = exports.findById(exportId).orElseThrow(() -> {
            log.warn("Statement export read refused, no export exists with this id, returning 404 | "
                    + "exportId={} callerAccountId={}", exportId, callerAccountId);
            return new StatementExportNotFoundException("no export " + exportId);
        });

        if (!export.accountId().equals(callerAccountId)) {
            // Both ids in the clear: this is a sandbox and an ownership refusal is exactly the line an
            // operator needs to read whole (ADR-0012). The client is told nothing beyond 404.
            log.warn("Statement export read refused, the export belongs to another account, returning "
                            + "404 rather than 403 so the id is not confirmed | exportId={} "
                            + "callerAccountId={} ownerAccountId={}",
                    exportId, callerAccountId, export.accountId());
            throw new StatementExportNotFoundException("no export " + exportId);
        }

        DownloadLink download = null;
        if (export.status() == StatementExportStatus.READY) {
            download = artifacts.presign(export.downloadKey());
            log.info("Statement export read, it is READY and a fresh download link was minted for this "
                            + "answer | exportId={} accountId={} objectKey={} expiresAt={}",
                    exportId, callerAccountId, export.downloadKey(), download.expiresAt());
        } else {
            log.info("Statement export read | exportId={} accountId={} status={} fromMonth={} toMonth={}",
                    exportId, callerAccountId, export.status(), export.range().from(),
                    export.range().to());
        }

        return new StatementExportView(
                export.exportId(), export.status(), export.range(), export.requestedAt(), download,
                export.failureReason());
    }
}
