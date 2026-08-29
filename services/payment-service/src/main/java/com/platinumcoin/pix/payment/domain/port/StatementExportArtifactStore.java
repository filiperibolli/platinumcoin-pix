package com.platinumcoin.pix.payment.domain.port;

import com.platinumcoin.pix.payment.domain.model.DownloadLink;

/**
 * Outbound port for the export artifact itself (step 53): write the CSV, and hand a customer a
 * time-limited way to fetch it.
 *
 * <p>Backed by the {@code pix-statement-exports} bucket. Separate from
 * {@link StatementArchiveReader} even though both are object storage, because they are opposite
 * halves of the flow with opposite permissions: the archive is read-only input the platform did not
 * produce here, the export bucket is write-and-sign output nobody else reads. Two ports make that
 * asymmetry visible in the composition root and in the IAM policy
 * ({@code infra/iam/payment-service-policy.json}).
 */
public interface StatementExportArtifactStore {

    /**
     * Open the artifact for writing. The caller appends rows as the archive yields them and then
     * {@link Sink#finish() finishes}; closing without finishing <b>discards</b> whatever was written.
     *
     * <p><b>The key is a pure function of the export, which is what makes a redelivery harmless.</b> A
     * second attempt at the same export overwrites the same object with the same bytes rather than
     * adding a second artifact — so "one export, one file" is true by construction here, and the
     * guarded status transition only has to keep the <i>bookkeeping</i> single.
     */
    Sink open(String accountId, String exportId);

    /**
     * An artifact being written incrementally.
     *
     * <h2>Why the port is a sink and not a {@code write(byte[])}</h2>
     * A single-shot write would require the whole document in memory before the first byte reaches
     * storage, which is exactly the failure {@link StatementArchiveReader}'s callback exists to avoid —
     * moving the unbounded buffer one layer up is not fixing it. With a sink, memory is bounded by
     * whatever the implementation buffers before flushing, independently of how large the export is.
     *
     * <h2>Finish or abort — there is no third outcome</h2>
     * It is {@link AutoCloseable} so that the caller's try-with-resources guarantees one of the two,
     * including on an exception mid-stream. That matters more than tidiness: an object storage
     * multipart upload that is neither completed nor aborted leaves its parts <b>billable and
     * invisible</b> — they do not appear in a bucket listing, and nothing ever cleans them up.
     */
    interface Sink extends AutoCloseable {

        /** Append text to the artifact. Encoded by the implementation; the domain deals in rows. */
        void append(String text);

        /**
         * Complete the artifact and return its object key. After this, {@link #close()} is a no-op.
         *
         * @return the storage key the export request item will point at
         */
        String finish();

        /** Abort unless {@link #finish()} already ran, leaving no object behind. Never throws. */
        @Override
        void close();
    }

    /**
     * A presigned URL for an already-written artifact, valid for the configured time from <b>now</b>.
     *
     * <p>Signed at read time and never stored: a URL minted when the worker finished would start
     * expiring while the customer was still being told the export was ready, and an export whose only
     * handle had expired would be permanently undownloadable even though the bytes are right there. The
     * step file says "presign, mark READY"; doing it in this order keeps every other property it asked
     * for and removes a failure mode it did not consider.
     */
    DownloadLink presign(String objectKey);
}
