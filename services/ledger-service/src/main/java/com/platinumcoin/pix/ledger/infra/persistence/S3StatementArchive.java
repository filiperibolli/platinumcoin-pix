package com.platinumcoin.pix.ledger.infra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import com.platinumcoin.pix.ledger.domain.port.StatementArchive;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The cold statement archive against S3 {@code pix-statement-archive} (step 43): one JSON-Lines object
 * per account and month, keyed {@code account=<id>/yyyy-MM.jsonl}.
 *
 * <h2>The key shape, and why it borrows Hive's</h2>
 * {@code account=<id>/} is the {@code key=value} partition convention Hive, Athena and Glue read
 * natively — so this archive can be queried as a table with no transformation step, which is the whole
 * reason a cold tier is acceptable in the first place: cheap storage that is still queryable. The month
 * is the object rather than another partition level because a month of one account's statement is a
 * sensible unit to fetch whole (step 53's export does exactly that).
 *
 * <h2>Overwrite is the update</h2>
 * Unlike the audit trail, this bucket is plain — no versioning, no Object Lock (step 42) — because it
 * holds <b>derived</b> data: the ledger is the source of truth, so a month's object is a projection that
 * can be rebuilt at any time. Writing it whole makes the job idempotent and lets the boundary month grow
 * as the hot window rolls forward. On a locked bucket the very same behaviour would pile up undeletable
 * versions of a regenerable file, which is why the two buckets are configured differently.
 *
 * <p>Money is serialized as {@code amountCents}, an integer — this is an internal artefact, not an API
 * edge, and the decimal formatting that belongs at the edge would be a lossy convenience in a five-year
 * record.
 */
@Repository
public class S3StatementArchive implements StatementArchive {

    private static final Logger log = LoggerFactory.getLogger(S3StatementArchive.class);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    /** JSON Lines: a stream of documents, not one document. */
    private static final String CONTENT_TYPE = "application/x-ndjson";

    private final S3Client s3;
    private final ObjectMapper mapper;
    private final String bucket;

    public S3StatementArchive(
            S3Client s3, ObjectMapper mapper, @Value("${pix.archive.bucket}") String bucket) {
        this.s3 = s3;
        this.mapper = mapper;
        this.bucket = bucket;
        log.info("Statement cold archive ready, monthly statement objects will be written to this bucket "
                + "| bucket={}", bucket);
    }

    @Override
    public String write(String accountId, YearMonth month, List<ArchivedEntry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("A monthly archive object is never written empty.");
        }
        String key = "account=" + accountId + "/" + MONTH.format(month) + ".jsonl";
        byte[] body = toJsonLines(entries);

        log.debug("S3 PutObject writing a monthly statement archive object, replacing it whole | "
                        + "bucket={} key={} entries={} bytes={} contentType={}",
                bucket, key, entries.size(), body.length, CONTENT_TYPE);

        s3.putObject(request -> request
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE),
                RequestBody.fromBytes(body));
        return key;
    }

    private byte[] toJsonLines(List<ArchivedEntry> entries) {
        StringBuilder lines = new StringBuilder();
        for (ArchivedEntry entry : entries) {
            try {
                // One document per line: a newline inside a line would split one entry into two
                // unreadable halves, which is why the writer serializes rather than concatenates.
                lines.append(mapper.writeValueAsString(entry)).append('\n');
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "A ledger entry could not be serialized for the archive: " + entry.txId(), e);
            }
        }
        return lines.toString().getBytes(StandardCharsets.UTF_8);
    }
}
