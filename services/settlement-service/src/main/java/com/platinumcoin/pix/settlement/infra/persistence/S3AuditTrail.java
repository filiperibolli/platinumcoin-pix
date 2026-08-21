package com.platinumcoin.pix.settlement.infra.persistence;

import com.platinumcoin.pix.settlement.domain.port.AuditTrail;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The {@link AuditTrail} against S3 {@code pix-audit-log} — the platform's long-term event store
 * (ARCHITECTURE §6.10), and the SNS/SQS answer to "where is the replayable log?"
 * ({@code docs/messaging-kafka-appendix.md}).
 *
 * <h2>The key: {@code yyyy/MM/dd/HH/<service>-<uuid>.jsonl}</h2>
 * S3 has no directories — the slashes are just characters in a flat key — but a {@code ListObjectsV2}
 * with a prefix is cheap, so a time-shaped key <i>is</i> the index: "everything recorded on 21 Aug
 * between 14:00 and 15:00" is one prefix scan instead of reading the bucket. That is the whole reason
 * the partition exists, and why it is coarse (an hour) rather than per-minute: fewer, bigger objects
 * read faster and cost less, and an hour is a fine enough grain for an audit query.
 *
 * <p><b>The time in the key is the ingestion instant, not the event's.</b> A batch is written once, as
 * one object, and its lines may carry {@code occurredAt} values from either side of an hour boundary —
 * so partitioning by event time would mean splitting a flush into several objects and, worse, writing
 * into an hour a reader may already have scanned. Ingestion time is monotonic and belongs to the writer,
 * which is what keeps one flush = one {@code PutObject}. The cost is stated plainly: an event delayed
 * across the boundary lands in the following hour's prefix, so an exact-by-event-time query reads the
 * neighbouring partition too and filters on the {@code occurredAt} inside each line.
 *
 * <p><b>The {@code <uuid>} is what makes the write safe.</b> Every object key is unique, so two writers
 * (or one writer retrying) never contend and never overwrite: the trail only ever grows. It is also
 * required by the bucket's posture — on an Object-Lock bucket an overwrite would silently pile up
 * retained versions of the "same" file, none of which could be deleted for five years.
 *
 * <h2>What this adapter deliberately does NOT do</h2>
 * It sets no retention and no legal hold. {@code pix-audit-log} carries a <b>default</b> Object Lock
 * configuration (COMPLIANCE, 1825 days — step 42), which S3 stamps onto every object at
 * {@code PutObject} time. A writer that opted in per object would be a writer that could forget to;
 * making it the bucket's property removes that possibility from the code entirely.
 */
@Repository
public class S3AuditTrail implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(S3AuditTrail.class);

    /** UTC, always: a partition whose hour depends on the writer's timezone is not an index. */
    private static final DateTimeFormatter HOUR_PARTITION =
            DateTimeFormatter.ofPattern("uuuu/MM/dd/HH").withZone(ZoneOffset.UTC);

    /** JSON Lines. Not {@code application/json}: the object is a stream of documents, not one. */
    private static final String CONTENT_TYPE = "application/x-ndjson";

    private final S3Client s3;
    private final String bucket;
    private final String serviceName;

    public S3AuditTrail(
            S3Client s3,
            @Value("${pix.audit.bucket}") String bucket,
            @Value("${pix.audit.writer-name}") String serviceName) {
        this.s3 = s3;
        this.bucket = bucket;
        this.serviceName = serviceName;
        log.info("Audit trail writer ready, batches will be appended as JSON lines to this bucket | "
                + "bucket={} writerName={}", bucket, serviceName);
    }

    @Override
    public String append(List<String> jsonLines, Instant writtenAt) {
        if (jsonLines.isEmpty()) {
            throw new IllegalArgumentException("An audit object is never written empty.");
        }
        String key = keyFor(writtenAt);
        // Trailing newline: JSONL convention, and it is what lets a reader `cat` two objects together
        // without gluing the last line of one onto the first line of the next.
        byte[] body = (String.join("\n", jsonLines) + "\n").getBytes(StandardCharsets.UTF_8);

        log.debug("S3 PutObject appending an audit batch | bucket={} key={} lines={} bytes={} "
                + "contentType={}", bucket, key, jsonLines.size(), body.length, CONTENT_TYPE);

        s3.putObject(request -> request
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE),
                RequestBody.fromBytes(body));

        log.info("Audit batch stored immutably, the bucket's default Object Lock retention was applied "
                        + "by S3 without this writer asking for it | bucket={} key={} lines={} bytes={}",
                bucket, key, jsonLines.size(), body.length);
        return key;
    }

    private String keyFor(Instant writtenAt) {
        return HOUR_PARTITION.format(writtenAt) + "/" + serviceName + "-" + UUID.randomUUID() + ".jsonl";
    }
}
