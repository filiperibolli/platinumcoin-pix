package com.platinumcoin.pix.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.platinumcoin.pix.common.testsupport.LocalStackTestBase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The consumer half of ADR-0004 against the real {@code pix_processed_events} table (step 29).
 *
 * <p><b>What is being proven.</b> The outbox publisher publishes to SNS and only <i>then</i> marks the
 * item published, so a crash in between republishes the same {@code eventId} on the next tick —
 * delivery is at-least-once <b>by design</b>. That is only safe because every consumer records the
 * event before its side effect: the first {@code markProcessed} wins, every redelivery loses, and
 * "at-least-once + idempotent consumer" becomes effectively-once. This IT pins that contract for all
 * the consumers that will lean on it (settlement step 31, notification step 38, audit step 43).
 *
 * <p>Spring-free like {@code MessagingInitIT}: it builds its own client off the shared container and
 * runs under failsafe on {@code mvn verify}, with the compose stack DOWN.
 */
class ProcessedEventStoreIT extends LocalStackTestBase {

    private static final String TABLE = "pix_processed_events";

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .endpointOverride(localstack().getEndpoint())
            .region(Region.of(localstack().getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack().getAccessKey(), localstack().getSecretKey())))
            .build();

    private final ProcessedEventStore store = new ProcessedEventStore(DYNAMO);

    @AfterAll
    static void closeClient() {
        DYNAMO.close();
    }

    /** The headline: the second delivery of the same event is refused, and nothing throws. */
    @Test
    void theFirstDeliveryIsProcessedAndTheDuplicateIsRefused() {
        String eventId = "evt-" + UUID.randomUUID();

        assertThat(store.markProcessed("settlement-service", eventId))
                .as("first delivery — the consumer may run its side effect")
                .isTrue();
        assertThat(store.markProcessed("settlement-service", eventId))
                .as("redelivery of the same event — the side effect must NOT run again")
                .isFalse();
        assertThat(store.markProcessed("settlement-service", eventId))
                .as("and it stays refused however many times SQS redelivers it")
                .isFalse();
    }

    /**
     * The consumer name is part of the key, not an attribute. Two services consuming the same event
     * must each see it once — if they shared a key, whichever consumed first would silently starve the
     * other (a settled payment that never notifies the user).
     */
    @Test
    void twoConsumersOfTheSameEventDoNotDedupeEachOtherOut() {
        String eventId = "evt-" + UUID.randomUUID();

        assertThat(store.markProcessed("settlement-service", eventId)).isTrue();
        assertThat(store.markProcessed("notification-service", eventId)).isTrue();
        assertThat(store.markProcessed("audit-service", eventId)).isTrue();

        assertThat(store.markProcessed("notification-service", eventId))
                .as("each consumer still dedupes against ITSELF")
                .isFalse();
    }

    /**
     * The claim is <b>released</b> when the side effect fails, so the redelivery is genuinely
     * reprocessed instead of being silently swallowed by the dedup gate.
     *
     * <p>Without this, a consumer that claims-then-fails turns SQS's whole retry mechanism into a
     * no-op: the message comes back, the gate says "already processed", the consumer acks, and the work
     * never happens. The claim marks "I am handling this", and it only becomes "this is done" once the
     * side effect committed (step 31; step 32's retries depend on it).
     */
    @Test
    void aReleasedClaimIsReprocessedByTheRedelivery() {
        String eventId = "evt-" + UUID.randomUUID();

        assertThat(store.markProcessed("settlement-service", eventId)).isTrue();
        store.release("settlement-service", eventId);

        assertThat(store.markProcessed("settlement-service", eventId))
                .as("the attempt failed and released its claim — the redelivery must run for real")
                .isTrue();
        assertThat(store.markProcessed("settlement-service", eventId))
                .as("and once it succeeds and keeps the claim, dedup is back in force")
                .isFalse();
    }

    /** Releasing something never claimed is a no-op, never an error — the caller may be a retry. */
    @Test
    void releasingAnUnclaimedEventIsHarmless() {
        store.release("settlement-service", "evt-" + UUID.randomUUID());
    }

    /** The record shape the table was created for: consumer-scoped key, {@code META} sk, 7-day TTL. */
    @Test
    void theRecordCarriesTheConsumerScopedKeyAndASevenDayTtl() {
        Instant now = Instant.parse("2026-08-12T10:15:30Z");
        ProcessedEventStore pinned =
                new ProcessedEventStore(DYNAMO, Clock.fixed(now, ZoneOffset.UTC));
        String eventId = "evt-" + UUID.randomUUID();

        assertThat(pinned.markProcessed("settlement-service", eventId)).isTrue();

        Map<String, AttributeValue> item = DYNAMO.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("CONSUMER#settlement-service#EVT#" + eventId),
                        "sk", AttributeValue.fromS("META")))).item();

        assertThat(item).as("keyed by CONSUMER#<name>#EVT#<eventId> / META").isNotEmpty();
        assertThat(item.get("consumer").s()).isEqualTo("settlement-service");
        assertThat(item.get("eventId").s()).isEqualTo(eventId);
        assertThat(item.get("processedAt").s()).isEqualTo("2026-08-12T10:15:30.000Z");
        // Epoch seconds, exactly 7 days out — DynamoDB reaps it lazily after that.
        assertThat(Long.parseLong(item.get("expiresAt").n()))
                .isEqualTo(now.plus(Duration.ofDays(7)).getEpochSecond());
    }
}
