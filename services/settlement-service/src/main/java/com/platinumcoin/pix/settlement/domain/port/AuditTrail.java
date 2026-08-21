package com.platinumcoin.pix.settlement.domain.port;

import java.time.Instant;
import java.util.List;

/**
 * Outbound port for the immutable audit trail (ADR-0010: the domain declares it, {@code infra/}
 * implements it against S3). One method, and it is deliberately batch-shaped: the whole point of the
 * writer is that many events become <b>one</b> object, so a per-event {@code append} would push the
 * cost decision into the caller and make "batched" a convention rather than a contract.
 *
 * <p><b>The instant is a parameter, not something the adapter reads.</b> It becomes the object's
 * time partition, so passing it in is what makes the key assertable in a test (ADR-0011: no
 * {@code Instant.now()} below the use case).
 */
public interface AuditTrail {

    /**
     * Write {@code jsonLines} as one JSON-Lines object, partitioned by {@code writtenAt}.
     *
     * <p>Must be <b>all or nothing from the caller's point of view</b>: either the object exists with
     * every line in it and the key comes back, or this throws and the caller keeps its buffer. Anything
     * in between would have the use case acknowledge messages whose lines were never stored.
     *
     * @param jsonLines one compacted event envelope per line, in arrival order; never empty
     * @param writtenAt the ingestion instant the key partitions on
     * @return the object key written, for the log line and the tests
     */
    String append(List<String> jsonLines, Instant writtenAt);
}
