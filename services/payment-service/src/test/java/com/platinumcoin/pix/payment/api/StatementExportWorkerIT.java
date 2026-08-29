package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.event.OutboxLane;
import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import com.platinumcoin.pix.payment.support.PaymentTestSupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The asynchronous half of the cold statement export, end to end against LocalStack (step 53): a
 * request written with its outbox event, published to SNS, delivered through the filtered
 * {@code statement-export-queue} subscription, assembled from real archive objects into a real CSV in
 * S3, and downloaded through the presigned URL the polling endpoint hands out.
 *
 * <p><b>What this proves that the plain-Java tests cannot.</b> The unit suite pins the worker's
 * decisions against fakes; this one pins the <i>wiring</i> — that the event type passes the
 * subscription's filter policy, that the archive object this service reads is the one ledger-service's
 * layout describes, that the CSV survives a round trip through object storage, and that a presigned URL
 * minted inside the JVM is actually fetchable from outside it. Every one of those is a place where an
 * assumption can be wrong while every unit test stays green.
 *
 * <p><b>The schedule is not under test; the ticks are.</b> Background polling is off in integration
 * tests ({@code pix.schedulers.enabled=false}), so each test drives the outbox publisher and the queue
 * consumer explicitly — deterministic, no sleeps, and exactly the path the schedule would call.
 *
 * <p><b>What this suite deliberately does not cover: the multipart branch.</b> The artifact sink flushes
 * a part every 5 MiB, and 5 MiB is S3's own minimum for a non-final part, so the only way to exercise
 * that path end to end is to seed an archive month large enough to exceed it — tens of thousands of
 * lines, which would dominate the runtime of the whole module's suite for one branch. Every fixture here
 * therefore takes the single-{@code PutObject} path. The streaming contract that makes the split safe —
 * that content reaches the sink incrementally, and that a failure mid-stream aborts rather than leaving
 * an object — is pinned in plain Java by {@code BuildStatementExportUseCaseTest}, and proven non-vacuous
 * there by mutation. The gap that remains is genuine and worth saying out loud: nothing automated
 * exercises {@code CreateMultipartUpload}/{@code UploadPart}/{@code CompleteMultipartUpload} against a
 * real emulator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentTestSupport.class)
class StatementExportWorkerIT extends LocalStackTestBase {

    private static final String ARCHIVE_BUCKET = "pix-statement-archive";
    private static final String EXPORT_BUCKET = "pix-statement-exports";

    /** Its own account per run, so a re-run does not read the previous one's archive objects. */
    private String accountId;
    private String token;

    private static final S3Client S3 = S3Client.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxPublisher outbox;

    @Autowired
    StatementExportQueueConsumer consumer;

    /** Three months back from a month that is comfortably cold under any hot window this platform sets. */
    private static YearMonth month(int monthsAgo) {
        return YearMonth.now(ZoneOffset.UTC).minusMonths(monthsAgo);
    }

    /**
     * A one-second long poll <b>for this class only</b>. The deployed value is 20s and rightly so — an
     * idle consumer should cost one request every 20 seconds, not a stream of empty ones. But here an
     * empty poll is how a failure announces itself, and at 20s a broken assertion took 220 seconds to
     * surface instead of ten. Overriding it forks the Spring context for this class, which is a fair
     * price for a suite that fails fast.
     */
    @DynamicPropertySource
    static void fastPolling(DynamicPropertyRegistry registry) {
        registry.add("pix.export.consumer.wait-time-seconds", () -> "1");
    }

    @BeforeEach
    void freshAccount() {
        accountId = "acc-export-" + UUID.randomUUID().toString().substring(0, 8);
        token = TestTokens.forUser("u-export", accountId);
        drainTheSharedNotificationLane();
    }

    /**
     * <b>Start from an empty notification lane, because it is not this test's lane.</b>
     *
     * <p>The first version of this class published the lane a few times and expected its own event to
     * come out. It passed alone and failed in the full suite, and the reason is worth keeping: the
     * other payment ITs leave their terminal events on the same lane — <b>2024 {@code PixSettled}
     * items</b> in one observed run — and the publisher drains oldest-first, 100 per tick. So ten ticks
     * moved a thousand events that the export queue's filter policy then dropped, never reached the
     * event written moments earlier, and every poll saw an empty queue and blocked for the full long
     * poll. The export sat {@code PENDING} and the failure looked like a broken worker.
     *
     * <p>Draining first makes this test's own event the only one on the lane, which is what the
     * assertions were always assuming. Same reasoning — and the same {@code @BeforeEach} — as
     * {@code OutboxPublisherIT}.
     */
    private void drainTheSharedNotificationLane() {
        for (int tick = 0; tick < 200; tick++) {
            if (outbox.publishLane(OutboxLane.NOTIFICATION).found() == 0) {
                return;
            }
        }
        throw new IllegalStateException(
                "the notification lane would not drain in 200 ticks — something is republishing");
    }

    @Test
    void assemblesEveryArchivedMonthIntoOneDownloadableCsv() throws Exception {
        // Three months in the range; the middle one deliberately has NO object, because "a month with
        // no movement is skipped, not failed" is the rule most likely to be broken by an eager writer.
        seedArchive(month(14), archived("tx-a", "DEBIT", -12_550L, month(14).atDay(3)));
        seedArchive(month(12),
                archived("tx-b", "CREDIT", 40_000L, month(12).atDay(9)),
                archived("tx-c", "DEBIT", -1L, month(12).atDay(28)));

        String exportId = requestExport(month(14), month(12));
        assertThat(statusOf(exportId)).isEqualTo("PENDING");

        drainToReady(exportId);

        JsonNode ready = poll(exportId);
        assertThat(ready.get("status").asText()).isEqualTo("READY");
        assertThat(ready.get("expiresAt").asText()).isNotBlank();

        String csv = download(ready.get("downloadUrl").asText());
        List<String> rows = csv.lines().toList();

        // The system-level assertion, not just "a file exists": the artifact holds exactly the entries
        // the archive holds for this range, and the cents column sums to what was seeded. An export
        // that silently dropped or duplicated a line would pass a row-count check on its own.
        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst()).startsWith("txId,timestamp,direction,amountCents,amount,");
        assertThat(rows.stream().skip(1).map(row -> row.split(",")[0]))
                .containsExactly("tx-a", "tx-b", "tx-c");
        assertThat(rows.stream().skip(1).mapToLong(row -> Long.parseLong(row.split(",")[3])).sum())
                .isEqualTo(-12_550L + 40_000L - 1L);
    }

    @Test
    void aRangeWithNothingArchivedStillSucceedsWithAHeaderOnlyCsv() throws Exception {
        String exportId = requestExport(month(14), month(12));

        drainToReady(exportId);

        String csv = download(poll(exportId).get("downloadUrl").asText());
        assertThat(csv.lines()).hasSize(1);
        assertThat(csv).startsWith("txId,");
    }

    @Test
    void aRedeliveredMessageLeavesOneArtifactAndOneReadyTransition() throws Exception {
        seedArchive(month(13), archived("tx-only", "DEBIT", -500L, month(13).atDay(1)));

        String exportId = requestExport(month(13), month(13));
        drainToReady(exportId);

        String completedAtAfterFirstRun = poll(exportId).get("requestedAt").asText();
        String csvAfterFirstRun = download(poll(exportId).get("downloadUrl").asText());

        // Put the very same event back on the queue by republishing it, then tick the consumer again.
        // The eventId is unchanged, so this is the exact redelivery SQS is free to produce on its own.
        republishAndConsume(exportId);

        JsonNode afterRedelivery = poll(exportId);
        assertThat(afterRedelivery.get("status").asText()).isEqualTo("READY");
        assertThat(afterRedelivery.get("requestedAt").asText()).isEqualTo(completedAtAfterFirstRun);
        assertThat(download(afterRedelivery.get("downloadUrl").asText())).isEqualTo(csvAfterFirstRun);
        assertThat(objectsUnder("exports/" + accountId + "/")).hasSize(1);
    }

    /**
     * <b>The item stores an object key, not a URL — every poll signs a new link.</b>
     *
     * <p>The first version of this test simply polled twice and asserted the two URLs differed. It
     * passed alone and failed in the full suite, for a reason worth keeping: a SigV4 presigned URL is a
     * pure function of (key, credentials, expiry, {@code X-Amz-Date}) and that date has <b>second</b>
     * granularity — so two signatures taken inside the same second are byte-identical <i>even though
     * both were freshly computed</i>. The assertion was testing the clock, not the design.
     *
     * <p>So the gap here is deliberate rather than incidental: past a second tick, a regenerated link
     * must carry a later expiry, which a link minted once at completion and stored could not. The
     * airtight, clock-free half of this claim lives in {@code GetStatementExportUseCaseTest}, which
     * counts the calls to the artifact store and sees one per read.
     */
    @Test
    void theDownloadLinkIsSignedPerReadRatherThanStoredAtCompletion() throws Exception {
        seedArchive(month(13), archived("tx-only", "DEBIT", -500L, month(13).atDay(1)));
        String exportId = requestExport(month(13), month(13));
        drainToReady(exportId);

        JsonNode first = poll(exportId);
        // Just past a second, because that is the resolution the signature's timestamp has.
        Thread.sleep(1_100);
        JsonNode second = poll(exportId);

        assertThat(second.get("expiresAt").asText())
                .as("a link regenerated later expires later; a stored one could not")
                .isGreaterThan(first.get("expiresAt").asText());
        assertThat(second.get("downloadUrl").asText()).isNotEqualTo(first.get("downloadUrl").asText());
        // And the older link is still the same artifact — regenerating is not re-uploading.
        assertThat(download(second.get("downloadUrl").asText()))
                .isEqualTo(download(first.get("downloadUrl").asText()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String requestExport(YearMonth from, YearMonth to) throws Exception {
        MvcResult result = mvc.perform(post("/v1/accounts/me/statement/exports")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromMonth\":\"" + from + "\",\"toMonth\":\"" + to + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return json.readTree(result.getResponse().getContentAsString()).get("exportId").asText();
    }

    /**
     * Publish the pending event on its lane and let the consumer work it. Two explicit ticks rather
     * than a sleep, and the assertion that the export actually reached {@code READY} lives here so a
     * broken filter policy fails as "never became READY" rather than as a confusing later NPE.
     */
    private void drainToReady(String exportId) throws Exception {
        // The lane was drained in @BeforeEach, so this test's event is the only one on it and the first
        // tick normally suffices. The loop covers SNS-to-SQS delivery lag and the possibility that a
        // batch contains another export ITs' message alongside ours; looping until the resource reaches
        // its terminal state is the assertion-friendly version of what the schedule does, with no sleep.
        for (int tick = 0; tick < 10 && "PENDING".equals(statusOf(exportId)); tick++) {
            outbox.publishLane(OutboxLane.NOTIFICATION);
            consumer.pollOnce();
        }
        assertThat(statusOf(exportId))
                .as("the export should have been assembled by a consumer tick")
                .isEqualTo("READY");
    }

    /**
     * Re-publish the export's outbox event so SQS delivers it a second time. It goes through the same
     * SNS topic and subscription, so the redelivery is indistinguishable from one SQS produced itself.
     */
    private void republishAndConsume(String exportId) {
        // The publisher removed gsi3pk when it marked the event published, so a second publishLane()
        // finds nothing. Re-arming it is what makes this a redelivery of the SAME eventId rather than
        // a brand-new event: the item is put back on the sparse index exactly as it was written.
        rearmOutboxEvent(exportId);
        outbox.publishLane(OutboxLane.NOTIFICATION);
        // Two ticks for the same reason drainToReady loops: the redelivered message shares the queue
        // with whatever else is in flight.
        consumer.pollOnce();
        consumer.pollOnce();
    }

    private void rearmOutboxEvent(String exportId) {
        var dynamo = software.amazon.awssdk.services.dynamodb.DynamoDbClient.builder()
                .endpointOverride(localstack().getEndpoint())
                .region(Region.of(localstack().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        localstack().getAccessKey(), localstack().getSecretKey())))
                .build();
        var items = dynamo.query(request -> request
                        .tableName("pix_transactions")
                        .keyConditionExpression("pk = :pk AND begins_with(sk, :sk)")
                        .expressionAttributeValues(java.util.Map.of(
                                ":pk", software.amazon.awssdk.services.dynamodb.model.AttributeValue
                                        .fromS("EXPORT#" + exportId),
                                ":sk", software.amazon.awssdk.services.dynamodb.model.AttributeValue
                                        .fromS("OUTBOX#"))))
                .items();
        assertThat(items).as("the export must have written exactly one outbox event").hasSize(1);
        var event = items.getFirst();
        dynamo.updateItem(request -> request
                .tableName("pix_transactions")
                .key(java.util.Map.of(
                        "pk", event.get("pk"),
                        "sk", event.get("sk")))
                .updateExpression("SET gsi3pk = :lane, gsi3sk = :at")
                .expressionAttributeValues(java.util.Map.of(
                        ":lane", software.amazon.awssdk.services.dynamodb.model.AttributeValue
                                .fromS(OutboxLane.NOTIFICATION.gsi3pk()),
                        ":at", event.get("occurredAt"))));
        dynamo.close();
    }

    private String statusOf(String exportId) throws Exception {
        return poll(exportId).get("status").asText();
    }

    private JsonNode poll(String exportId) throws Exception {
        MvcResult result = mvc.perform(get("/v1/statement-exports/{id}", exportId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Fetch the artifact the way a customer's browser would: straight at object storage, no JWT. */
    private String download(String presignedUrl) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(presignedUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .as("the presigned URL must be usable from outside the service")
                .isEqualTo(200);
        return response.body();
    }

    private void seedArchive(YearMonth month, String... jsonLines) {
        String key = "account=" + accountId + "/" + month + ".jsonl";
        S3.putObject(request -> request.bucket(ARCHIVE_BUCKET).key(key).contentType("application/x-ndjson"),
                RequestBody.fromString(String.join("\n", jsonLines) + "\n", StandardCharsets.UTF_8));
    }

    /**
     * One archive line, written by hand in <b>ledger-service's</b> shape rather than by reusing a class.
     * That is the point: the archive object is the contract between the two services (a file format, not
     * a Java type), so this test fails if either side drifts from it — which a shared record would hide.
     */
    private String archived(String txId, String direction, long cents, java.time.LocalDate day) {
        return "{\"accountId\":\"" + accountId + "\",\"txId\":\"" + txId + "\",\"direction\":\"" + direction
                + "\",\"amountCents\":" + cents + ",\"counterpartAccountId\":\"acc-other\",\"timestamp\":\""
                + day.atStartOfDay(ZoneOffset.UTC).toInstant() + "\",\"entryType\":\"PIX_OUT\","
                + "\"description\":\"archived leg\"}";
    }

    private List<String> objectsUnder(String prefix) {
        try {
            var listed = S3.listObjectsV2(request -> request.bucket(EXPORT_BUCKET).prefix(prefix));
            List<String> keys = new ArrayList<>();
            listed.contents().forEach(object -> keys.add(object.key()));
            return keys;
        } catch (NoSuchKeyException absent) {
            return List.of();
        }
    }
}
