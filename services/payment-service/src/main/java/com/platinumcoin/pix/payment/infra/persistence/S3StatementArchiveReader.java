package com.platinumcoin.pix.payment.infra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.payment.domain.model.ArchivedStatementLine;
import com.platinumcoin.pix.payment.domain.port.StatementArchiveReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Reads the cold statement archive (step 53) — the monthly JSON Lines objects ledger-service writes in
 * step 43, keyed {@code account=<id>/yyyy-MM.jsonl}.
 *
 * <h2>A missing object is an empty month, not a failure</h2>
 * {@link NoSuchKeyException} is caught and reported as zero lines, because the overwhelmingly common
 * reason for it is the honest one: the account had no movement that month. Letting it propagate would
 * fail an export for the most ordinary thing an account can do — nothing — and every range longer than
 * a couple of months would hit it.
 *
 * <p>The trade-off is stated rather than hidden: this adapter cannot distinguish "no movement" from
 * "the archiving job has not run for that month yet". Neither can anything else, from the archive
 * alone, and the export's answer would be identical either way. What makes it acceptable is that the
 * archive is derived data whose source of truth is still the ledger — a gap is recoverable by re-running
 * the job, not by anything this reader could have done.
 *
 * <h2>Streaming, line by line, all the way out</h2>
 * The object is read through a {@link BufferedReader} <b>and each line is handed straight to the
 * caller's consumer</b> — nothing is collected here, and nothing is collected by the caller either. A
 * month of a busy account is an unbounded number of lines, and the export worker shares a JVM with the
 * send path, so a list would be a latent {@code OutOfMemoryError} on the money path. Each line is one
 * JSON document — a newline inside a line would split an entry in half, which is why the writer
 * serializes rather than concatenates.
 *
 * <p>One malformed line fails the whole month, deliberately: a statement with a silently dropped entry
 * is worse than an export that fails loudly and retries, because the customer cannot tell that anything
 * is missing.
 */
@Repository
public class S3StatementArchiveReader implements StatementArchiveReader {

    private static final Logger log = LoggerFactory.getLogger(S3StatementArchiveReader.class);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    private final S3Client s3;
    private final ObjectMapper mapper;
    private final String bucket;

    public S3StatementArchiveReader(
            S3Client s3, ObjectMapper mapper, @Value("${pix.export.archive-bucket}") String bucket) {
        this.s3 = s3;
        this.mapper = mapper;
        this.bucket = bucket;
        log.info("Cold statement archive reader ready, monthly export input is read from this bucket | "
                + "bucket={}", bucket);
    }

    @Override
    public int stream(String accountId, YearMonth month, Consumer<ArchivedStatementLine> onLine) {
        String key = "account=" + accountId + "/" + MONTH.format(month) + ".jsonl";
        log.debug("S3 GetObject streaming a month of the cold archive | bucket={} key={}", bucket, key);

        int streamed = 0;
        // The response stream is consumed line by line and NEVER collected: a month of a busy account
        // is an unbounded number of entries, and the whole reason a cold tier exists is that it is
        // allowed to be large. Slurping it here would move the unbounded buffer one layer up, which is
        // not a fix.
        try (var body = s3.getObject(request -> request.bucket(bucket).key(key));
                var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                onLine.accept(mapper.readValue(line, ArchivedStatementLine.class));
                streamed++;
            }
        } catch (NoSuchKeyException absent) {
            log.debug("No archive object for this account and month, it is treated as a month with no "
                    + "movement | bucket={} key={}", bucket, key);
            return 0;
        } catch (IOException e) {
            // Reading or parsing failed part-way. Surfacing it is the point: the worker's attempt budget
            // retries, and only a repeated failure becomes a FAILED export the customer can see. The
            // partial artifact written so far is aborted by the sink's try-with-resources.
            throw new UncheckedIOException(
                    "could not read the archive object " + bucket + "/" + key, e);
        }

        log.debug("Streamed a month of the cold archive | bucket={} key={} lines={}",
                bucket, key, streamed);
        return streamed;
    }
}
