package com.platinumcoin.pix.payment.infra.persistence;

import com.platinumcoin.pix.payment.domain.model.DownloadLink;
import com.platinumcoin.pix.payment.domain.port.StatementExportArtifactStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * The export artifact in S3 {@code pix-statement-exports} (step 53): streamed in at
 * {@code exports/<accountId>/<exportId>.csv}, handed to a customer as a presigned URL.
 *
 * <h2>The key is derived, which is what makes the worker safe to retry</h2>
 * Nothing about the object key is random or time-based, so a second attempt at the same export
 * overwrites the same object with the same bytes. "One export, one artifact" is therefore true by
 * construction rather than by the worker being careful — and the guarded status transition upstream only
 * has to keep the bookkeeping single, not the file.
 *
 * <p>The account is in the key as well as the export id, purely so that the bucket is browsable by
 * owner during an incident. Nothing authorizes on it: ownership is checked before a link is ever minted
 * ({@code GetStatementExportUseCase}), and the key is never returned to a client.
 *
 * <h2>Small exports are one PutObject; large ones become a multipart upload</h2>
 * The sink buffers what it is given and decides at the end which it was. That split is the whole point
 * of the design:
 * <ul>
 *   <li><b>Below the part size</b> — which is nearly every export — it is a single {@code PutObject},
 *       exactly as before. A multipart upload for a 3 KB file would be three API calls to achieve
 *       what one does.</li>
 *   <li><b>Above it</b>, parts are flushed as they fill, so <b>memory is bounded by the part size and
 *       not by the export</b>. That bound is the reason this class exists in this shape: the worker
 *       runs in the JVM that serves {@code POST /v1/payments/pix}, and an unbounded buffer here would
 *       make one customer's two-year export an {@code OutOfMemoryError} on the money path.</li>
 * </ul>
 * 5 MiB is not a tuning choice — it is S3's minimum size for every part but the last, so a smaller
 * threshold would produce uploads the API rejects on completion.
 *
 * <h2>Abort is not tidiness</h2>
 * A multipart upload that is neither completed nor aborted leaves its parts <b>billable and invisible</b>:
 * they do not appear in a bucket listing, and nothing ever reclaims them. So {@link Sink#close()} aborts
 * whatever {@link Sink#finish()} did not complete, and the use case opens the sink in a
 * try-with-resources so a failure mid-stream cannot skip it.
 *
 * <h2>Presigned, and why the platform does not stream the bytes back out</h2>
 * A presigned URL is signed locally and used by the customer's browser directly against object storage,
 * so a two-year export never passes back through this JVM either. The trade is that the URL <b>is</b>
 * the credential while it lives, which is why it is minted per read with a short lifetime and never
 * stored.
 */
@Repository
public class S3StatementExportArtifactStore implements StatementExportArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(S3StatementExportArtifactStore.class);

    /** So a browser opens it in a spreadsheet rather than rendering it as a wall of text. */
    private static final String CONTENT_TYPE = "text/csv";

    /**
     * S3's minimum size for every multipart part except the last. It is a protocol constant, not a
     * knob: below it, {@code CompleteMultipartUpload} rejects the upload.
     */
    private static final int PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration linkTtl;

    public S3StatementExportArtifactStore(
            S3Client s3,
            S3Presigner presigner,
            @Value("${pix.export.bucket}") String bucket,
            @Value("${pix.export.download-link-ttl-minutes}") long linkTtlMinutes) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.linkTtl = Duration.ofMinutes(linkTtlMinutes);
        log.info("Statement export artifact store ready, CSV artifacts are streamed here and handed out "
                        + "as short-lived presigned links | bucket={} partSizeBytes={} "
                        + "downloadLinkTtlMinutes={}",
                bucket, PART_SIZE_BYTES, linkTtlMinutes);
    }

    @Override
    public Sink open(String accountId, String exportId) {
        return new S3Sink(objectKey(accountId, exportId), exportId);
    }

    @Override
    public DownloadLink presign(String objectKey) {
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(linkTtl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .build());

        // The expiry the SDK computed, not one derived here: signing and reporting the same lifetime
        // from two sources is how a link ends up advertised as valid for longer than it is.
        Instant expiresAt = presigned.expiration();
        log.debug("Signed a download link for an export artifact, the URL itself is never logged | "
                + "bucket={} key={} expiresAt={}", bucket, objectKey, expiresAt);
        return new DownloadLink(presigned.url().toString(), expiresAt);
    }

    private static String objectKey(String accountId, String exportId) {
        return "exports/" + accountId + "/" + exportId + ".csv";
    }

    /**
     * One artifact being written. Buffers up to {@link #PART_SIZE_BYTES}, then starts a multipart upload
     * and keeps flushing; if the whole artifact fits in the first buffer it never starts one at all.
     *
     * <p>Not thread-safe, and does not need to be: a sink belongs to one export being assembled by one
     * worker thread. Sharing one would mean two workers interleaving rows into the same file, which no
     * amount of synchronization here would make meaningful.
     */
    private final class S3Sink implements Sink {

        private final String key;
        private final String exportId;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(PART_SIZE_BYTES);
        private final List<CompletedPart> completedParts = new ArrayList<>();

        /** Null until the buffer first overflows — i.e. until the artifact is known to be large. */
        private String uploadId;

        private int nextPartNumber = 1;
        private long totalBytes;
        private boolean finished;

        private S3Sink(String key, String exportId) {
            this.key = key;
            this.exportId = exportId;
        }

        @Override
        public void append(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            buffer.writeBytes(bytes);
            totalBytes += bytes.length;
            if (buffer.size() >= PART_SIZE_BYTES) {
                flushPart();
            }
        }

        @Override
        public String finish() {
            if (uploadId == null) {
                // The common case: the whole export fits in one buffer, so it is one ordinary PutObject
                // and no multipart upload was ever created.
                log.debug("S3 PutObject writing an export artifact in one request, replacing any previous "
                                + "attempt's object | bucket={} key={} bytes={} contentType={}",
                        bucket, key, totalBytes, CONTENT_TYPE);
                s3.putObject(request -> request
                                .bucket(bucket)
                                .key(key)
                                .contentDisposition(contentDisposition())
                                .contentType(CONTENT_TYPE),
                        RequestBody.fromBytes(buffer.toByteArray()));
                finished = true;
                return key;
            }

            // The last part may be smaller than the minimum, and only the last one may.
            if (buffer.size() > 0) {
                flushPart();
            }
            String upload = uploadId;
            s3.completeMultipartUpload(request -> request
                    .bucket(bucket)
                    .key(key)
                    .uploadId(upload)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build()));
            finished = true;
            log.info("Completed a multipart export artifact upload | bucket={} key={} exportId={} "
                    + "parts={} bytes={}", bucket, key, exportId, completedParts.size(), totalBytes);
            return key;
        }

        @Override
        public void close() {
            if (finished || uploadId == null) {
                // Nothing to reclaim: either the artifact is complete, or no upload was ever started and
                // the buffered bytes simply go out of scope.
                return;
            }
            String upload = uploadId;
            try {
                s3.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(upload));
                log.warn("Aborted an unfinished export artifact upload, no partial object is left behind "
                        + "| bucket={} key={} exportId={} uploadId={} partsDiscarded={}",
                        bucket, key, exportId, upload, completedParts.size());
            } catch (RuntimeException e) {
                // Never throw from close(): the failure that got us here is the one worth reporting, and
                // an abort that fails costs storage, not correctness. Loud enough to be found.
                log.error("Could not abort an unfinished export artifact upload; its parts will keep "
                        + "costing storage until a bucket lifecycle rule reclaims them | bucket={} "
                        + "key={} uploadId={}", bucket, key, upload, e);
            }
        }

        /** Send whatever is buffered as the next part, starting the upload on the first call. */
        private void flushPart() {
            if (uploadId == null) {
                uploadId = s3.createMultipartUpload(request -> request
                                .bucket(bucket)
                                .key(key)
                                .contentDisposition(contentDisposition())
                                .contentType(CONTENT_TYPE))
                        .uploadId();
                log.info("Export artifact exceeded the single-request threshold, switching to a multipart "
                                + "upload so memory stays bounded by the part size | bucket={} key={} "
                                + "exportId={} partSizeBytes={} uploadId={}",
                        bucket, key, exportId, PART_SIZE_BYTES, uploadId);
            }

            byte[] part = buffer.toByteArray();
            buffer.reset();
            int partNumber = nextPartNumber++;
            String upload = uploadId;
            log.debug("S3 UploadPart | bucket={} key={} uploadId={} partNumber={} bytes={}",
                    bucket, key, upload, partNumber, part.length);
            String eTag = s3.uploadPart(request -> request
                                    .bucket(bucket).key(key).uploadId(upload).partNumber(partNumber),
                            RequestBody.fromBytes(part))
                    .eTag();
            completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
        }

        /** A filename a customer recognises when the browser saves it, not the opaque id in the URL. */
        private String contentDisposition() {
            return "attachment; filename=\"statement-" + exportId + ".csv\"";
        }
    }
}
