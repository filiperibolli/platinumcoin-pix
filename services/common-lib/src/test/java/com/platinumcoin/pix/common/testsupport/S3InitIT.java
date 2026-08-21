package com.platinumcoin.pix.common.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Infrastructure IT for the object storage created by {@code 09-audit.sh} (step 42): the buckets
 * {@code pix-audit-log} and {@code pix-statement-archive} that the audit trail of step 43 writes to.
 *
 * <p><b>Why an IT for a shell script.</b> Same reasoning as {@link MessagingInitIT}: the immutability
 * posture of the audit bucket is <i>pure configuration</i> — invisible in application code, and silently
 * absent if the script drifts. A compliance requirement nobody can see is a compliance requirement
 * nobody notices losing, so the three facts that make the bucket an audit trail rather than a folder
 * are pinned here:
 * <ul>
 *   <li><b>versioning</b> — an overwrite adds a version instead of replacing the object;</li>
 *   <li><b>Object Lock, COMPLIANCE mode, 5 years</b> — every object written inherits a retention date
 *       nobody (not even the root account, in real AWS) can shorten;</li>
 *   <li><b>the delete is actually refused</b> — the assertion that turns the two configuration facts
 *       above into an observed behaviour.</li>
 * </ul>
 *
 * <p><b>LocalStack vs AWS.</b> The last assertion is the pleasant surprise of this step: LocalStack 3
 * does not merely <i>accept</i> the Object Lock configuration, it <i>enforces</i> it — deleting a
 * retained version answers {@code AccessDenied}. What stays AWS-only is everything underneath the API:
 * WORM at the storage layer, surviving a {@code docker compose down -v} (the emulator's state is
 * ephemeral), and IAM actually denying anything (ADR-0013). So this IT proves the configuration and the
 * API-level refusal — never that the local bytes are truly immutable.
 *
 * <p>Spring-free like {@link MessagingInitIT}: it builds its own client off the shared container.
 */
class S3InitIT extends LocalStackTestBase {

    /** The immutable audit trail (step 43 writes partitioned JSONL here). */
    private static final String AUDIT_BUCKET = "pix-audit-log";
    /** The cold statement archive — derived, rebuildable data, deliberately WITHOUT Object Lock. */
    private static final String ARCHIVE_BUCKET = "pix-statement-archive";

    /** 5 years, the BACEN retention the audit trail is held to (ARCHITECTURE §6.10). */
    private static final int RETENTION_DAYS = 1825;

    private static final S3Client S3 = S3Client.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            // Path-style: `http://<host>:<port>/<bucket>` instead of the virtual-hosted
            // `http://<bucket>.<host>` the SDK defaults to — a hostname the local emulator has no DNS for.
            .forcePathStyle(true)
            .build();

    @AfterAll
    static void closeClient() {
        S3.close();
    }

    @Test
    void bothAuditBucketsExist() {
        assertThat(bucketExists(AUDIT_BUCKET)).as("the immutable audit trail bucket").isTrue();
        assertThat(bucketExists(ARCHIVE_BUCKET)).as("the cold statement archive bucket").isTrue();
    }

    /**
     * Versioning is the floor of the immutability posture — and, on a lock-enabled bucket, AWS turns it
     * on for you and forbids ever suspending it. Asserting it here documents that dependency.
     */
    @Test
    void auditBucketIsVersioned() {
        assertThat(S3.getBucketVersioning(request -> request.bucket(AUDIT_BUCKET)).status())
                .as("an overwrite must add a version, never replace the object")
                .isEqualTo(BucketVersioningStatus.ENABLED);
    }

    /**
     * COMPLIANCE (not GOVERNANCE) is the deliberate choice: GOVERNANCE mode can be bypassed by a
     * principal holding {@code s3:BypassGovernanceRetention}, which is exactly the privileged operator
     * an audit trail exists to keep honest.
     */
    @Test
    void auditBucketCarriesFiveYearComplianceObjectLock() {
        var configuration = S3.getObjectLockConfiguration(request -> request.bucket(AUDIT_BUCKET))
                .objectLockConfiguration();

        assertThat(configuration.objectLockEnabled()).isEqualTo(ObjectLockEnabled.ENABLED);
        assertThat(configuration.rule().defaultRetention().mode())
                .as("COMPLIANCE — no principal may shorten it, not even root")
                .isEqualTo(ObjectLockRetentionMode.COMPLIANCE);
        assertThat(configuration.rule().defaultRetention().days())
                .as("the 5-year BACEN retention window").isEqualTo(RETENTION_DAYS);
    }

    /**
     * The behaviour the two configuration facts above buy: an object written to the audit bucket comes
     * out of {@code PutObject} already retained for 5 years, and deleting that version is refused.
     * This is the money-adjacent invariant of the audit flow — an audit line that can be deleted is not
     * an audit line — expressed the only way it can be here: as an observed refusal.
     */
    @Test
    void anAuditObjectIsRetainedForFiveYearsAndCannotBeDeleted() {
        String key = "2026/01/01/00/s3-init-it-" + UUID.randomUUID() + ".jsonl";
        PutObjectResponse written = S3.putObject(
                request -> request.bucket(AUDIT_BUCKET).key(key),
                RequestBody.fromString("{\"eventId\":\"ev-s3-init-it\"}", StandardCharsets.UTF_8));

        Instant retainedUntil = S3.getObjectRetention(request -> request.bucket(AUDIT_BUCKET).key(key))
                .retention().retainUntilDate();
        assertThat(retainedUntil)
                .as("the bucket's default retention is applied to every new object, no caller opt-in")
                .isAfter(Instant.now().plus(RETENTION_DAYS - 1L, ChronoUnit.DAYS));

        // Deleting the *version* is the real attempt to erase history: a plain delete on a versioned
        // bucket only writes a delete marker and leaves the bytes (and the audit line) in place.
        assertThatThrownBy(() -> S3.deleteObject(request -> request
                .bucket(AUDIT_BUCKET).key(key).versionId(written.versionId())))
                .as("a retained audit object must not be erasable")
                .isInstanceOf(S3Exception.class)
                .hasMessageContaining("Access Denied");
    }

    /**
     * The counterpart decision, asserted so it stays deliberate rather than forgotten: the cold archive
     * is <b>derived</b> data — step 43 rewrites its monthly {@code account=<id>/yyyy-MM.jsonl} object as
     * the window rolls, and the ledger it is copied from remains the source of truth. Locking it would
     * pile up undeletable versions of a rebuildable file and buy no compliance at all.
     */
    @Test
    void statementArchiveIsAPlainRewritableBucket() {
        assertThat(S3.getBucketVersioning(request -> request.bucket(ARCHIVE_BUCKET)).status())
                .as("derived, rebuildable data — no versioning").isNull();

        assertThatThrownBy(() -> S3.getObjectLockConfiguration(request -> request.bucket(ARCHIVE_BUCKET)))
                .as("and deliberately no Object Lock")
                .isInstanceOf(S3Exception.class);
    }

    private static boolean bucketExists(String bucket) {
        try {
            S3.headBucket(request -> request.bucket(bucket));
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        }
    }
}
