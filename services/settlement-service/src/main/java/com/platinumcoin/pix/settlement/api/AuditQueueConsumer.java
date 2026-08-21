package com.platinumcoin.pix.settlement.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.settlement.domain.model.AuditEvent;
import com.platinumcoin.pix.settlement.domain.usecase.AuditFlushOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.RecordAuditEventsUseCase;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The audit trail's inbound adapter (step 43): long-polls {@code audit-queue} — the platform's one
 * <b>unfiltered</b> subscription (step 42) — and hands every event to {@link RecordAuditEventsUseCase}.
 *
 * <h2>Why this consumer is different from {@code SettlementQueueConsumer}</h2>
 * That one acts on an event and acks it within the tick. This one <i>batches</i>: a message received now
 * may only be deleted several ticks later, once its line is durable in S3. Everything below follows from
 * that single difference.
 *
 * <h2>1. The buffer outlives the visibility timeout, so the buffer must own the lease</h2>
 * {@code audit-queue}'s visibility timeout is 30s (step 26's tuning, inherited by step 42) and the batch
 * may hold an event for up to {@code max-age} — so without intervention SQS would redeliver a message
 * that is sitting in this JVM's buffer, and the platform would write the line twice for no reason. The
 * fix is to say so out loud: every message that enters the buffer immediately gets its visibility
 * extended to {@code lease-seconds}. <b>Whoever holds the message owns the lease.</b> If this process
 * dies mid-batch the lease simply expires and SQS redelivers — at-least-once, exactly as designed.
 *
 * <h2>2. Delete strictly after the write</h2>
 * The use case returns acknowledgement tokens only for lines that are durable, and this class does
 * nothing but obey that list. Deleting on receipt would make the batch window a hole in the audit trail;
 * deleting after the write costs, at worst, a duplicate line — which is the trade an audit trail should
 * always take (see {@code AuditBatch}).
 *
 * <h2>3. The long poll is capped by the flush deadline</h2>
 * A 20s receive against a 30s flush promise would let a batch age up to 50s. So when something is
 * buffered the wait is capped at the time left before the deadline: the tick returns in time to write.
 *
 * <h2>4. Backpressure</h2>
 * While the batch is at its cap (a failing S3, typically) the tick stops receiving and only retries the
 * write. The backlog then waits in SQS — durable, with a DLQ and an alertable depth — rather than
 * growing without bound in this heap.
 *
 * <p>A body that cannot be parsed is deliberately <b>not</b> deleted and never has its visibility
 * extended: it rides its five receives into {@code audit-queue-dlq}, where a message nobody can process
 * belongs (ADR-0003 — a DLQ message is flagged, not lost).
 */
@Component
public class AuditQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditQueueConsumer.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper;
    private final RecordAuditEventsUseCase recordAuditEvents;
    private final int batchSize;
    private final int waitTimeSeconds;
    private final int leaseSeconds;

    public AuditQueueConsumer(
            SqsClient sqs,
            ObjectMapper mapper,
            RecordAuditEventsUseCase recordAuditEvents,
            @Value("${pix.audit.queue-name}") String queueName,
            @Value("${pix.audit.consumer.batch-size}") int batchSize,
            @Value("${pix.audit.consumer.wait-time-seconds}") int waitTimeSeconds,
            @Value("${pix.audit.consumer.lease-seconds}") int leaseSeconds) {
        this.sqs = sqs;
        // Resolved at startup, like the settlement consumer: an audit writer that boots healthy while
        // consuming nothing would leave a five-year compliance obligation silently unmet.
        this.queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        this.mapper = mapper;
        this.recordAuditEvents = recordAuditEvents;
        this.batchSize = batchSize;
        this.waitTimeSeconds = waitTimeSeconds;
        this.leaseSeconds = leaseSeconds;
        log.info("Audit consumer ready, it will long-poll every event off this queue and batch them into "
                        + "the immutable trail | queueName={} queueUrl={} batchSize={} waitTimeSeconds={} "
                        + "leaseSeconds={}",
                queueName, this.queueUrl, batchSize, waitTimeSeconds, leaseSeconds);
    }

    /**
     * One tick: receive (unless the batch is full), lease, buffer, and write + ack when due. Never lets
     * an exception escape — nothing was acked, so everything comes back.
     *
     * @return the outcome of this tick, so an integration test can drive the loop deterministically
     *         instead of sleeping on a schedule
     */
    @Scheduled(fixedDelayString = "${pix.audit.consumer.fixed-delay-ms}")
    public AuditFlushOutcome pollOnce() {
        try {
            List<Message> messages = recordAuditEvents.bufferIsFull() ? List.of() : receive();
            List<AuditEvent> events = parse(messages);
            if (!events.isEmpty()) {
                // Take the lease BEFORE handing the events to the buffer: from here on this process is
                // responsible for them, and SQS must not hand them to anyone else meanwhile.
                extendLease(events, messages);
            }

            AuditFlushOutcome outcome = recordAuditEvents.execute(events);
            if (outcome.flushed()) {
                delete(outcome.ackTokens());
            }
            return outcome;
        } catch (RuntimeException e) {
            log.error("The audit consumer tick failed, nothing was acked so every buffered and in-flight "
                            + "event is still owed a line in the trail | queueUrl={}", queueUrl, e);
            // The real buffer size, not a zero: a failed tick still owes those events a line, and a
            // caller (or a log reader) must not be told the buffer is empty when it is not.
            return new AuditFlushOutcome(null, 0, List.of(), recordAuditEvents.bufferedEvents());
        }
    }

    private List<Message> receive() {
        // Cap the block by what is left before the batch is late; 1s floor so a nearly-due batch still
        // gets a chance to pick up company rather than spinning.
        long deadlineSeconds = recordAuditEvents.timeUntilFlushDeadline().toSeconds();
        int wait = (int) Math.max(1, Math.min(waitTimeSeconds, deadlineSeconds));

        var messages = sqs.receiveMessage(request -> request
                .queueUrl(queueUrl)
                .maxNumberOfMessages(batchSize)
                .waitTimeSeconds(wait)).messages();

        if (messages.isEmpty()) {
            log.debug("Audit queue poll returned no messages | queueUrl={} batchSize={} waitTimeSeconds={}",
                    queueUrl, batchSize, wait);
        } else {
            log.info("Audit queue poll received events to record | received={} batchSize={} "
                    + "waitTimeSeconds={}", messages.size(), batchSize, wait);
        }
        return messages;
    }

    /**
     * Bind each body to an {@link AuditEvent}. The line stored is the envelope <b>re-serialized from the
     * parsed tree</b> — same JSON, guaranteed to be a single line, which is what JSONL requires; nothing
     * is added, removed or renamed, because an audit trail records what was published rather than what
     * this service understood of it.
     */
    private List<AuditEvent> parse(List<Message> messages) {
        List<AuditEvent> events = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                JsonNode envelope = mapper.readTree(message.body());
                String eventId = text(envelope, "eventId");
                if (eventId == null) {
                    // Left on the queue on purpose: without an identity the line cannot be deduped and
                    // the message cannot be reasoned about, so the DLQ is where a human should find it.
                    log.error("An audit message carries no eventId, leaving it on the queue so it "
                                    + "redrives to the DLQ rather than being recorded anonymously | "
                                    + "messageId={} body={}", message.messageId(), message.body());
                    continue;
                }
                events.add(new AuditEvent(
                        eventId,
                        text(envelope, "eventType"),
                        text(envelope, "correlationId"),
                        mapper.writeValueAsString(envelope),
                        message.receiptHandle()));
            } catch (Exception e) {
                log.error("An audit message could not be parsed as JSON, leaving it on the queue so it "
                                + "redrives to the DLQ | messageId={} body={}",
                        message.messageId(), message.body(), e);
            }
        }
        return events;
    }

    /**
     * Hold the lease for the whole batch window. Best-effort per the SQS batch API's partial-failure
     * shape: a message whose extension failed simply keeps the queue's 30s timeout and may be
     * redelivered — a duplicate line, never a lost one.
     */
    private void extendLease(List<AuditEvent> events, List<Message> messages) {
        List<ChangeMessageVisibilityBatchRequestEntry> entries = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            entries.add(ChangeMessageVisibilityBatchRequestEntry.builder()
                    .id(String.valueOf(i))
                    .receiptHandle(events.get(i).ackToken())
                    .visibilityTimeout(leaseSeconds)
                    .build());
        }
        try {
            var response = sqs.changeMessageVisibilityBatch(request -> request
                    .queueUrl(queueUrl).entries(entries));
            log.debug("SQS ChangeMessageVisibilityBatch took the batch lease on the buffered events | "
                            + "queueUrl={} leased={} failed={} leaseSeconds={}",
                    queueUrl, response.successful().size(), response.failed().size(), leaseSeconds);
            if (!response.failed().isEmpty()) {
                log.warn("Some audit messages could not be leased for the batch window, they keep the "
                                + "queue's default visibility and may be redelivered into a later batch "
                                + "(a duplicate line, never a lost one) | failed={} leaseSeconds={}",
                        response.failed().size(), leaseSeconds);
            }
        } catch (RuntimeException e) {
            log.warn("Taking the batch lease on the audit messages failed, they keep the queue's default "
                            + "visibility timeout instead | messages={} leaseSeconds={}",
                    messages.size(), leaseSeconds, e);
        }
    }

    /** Acknowledge exactly what the use case said is durable, in chunks of the SQS batch maximum. */
    private void delete(List<String> receiptHandles) {
        for (int from = 0; from < receiptHandles.size(); from += 10) {
            List<String> chunk = receiptHandles.subList(from, Math.min(from + 10, receiptHandles.size()));
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(DeleteMessageBatchRequestEntry.builder()
                        .id(String.valueOf(i))
                        .receiptHandle(chunk.get(i))
                        .build());
            }
            var response = sqs.deleteMessageBatch(request -> request.queueUrl(queueUrl).entries(entries));
            log.debug("SQS DeleteMessageBatch acknowledging events whose lines are durable | queueUrl={} "
                    + "acked={} failed={}", queueUrl, response.successful().size(), response.failed().size());
            if (!response.failed().isEmpty()) {
                // Harmless by design: an un-acked message whose line IS written comes back and is
                // deduped by eventId within its next batch, or recorded twice at worst.
                log.warn("Some audit messages could not be acked although their lines are durable, they "
                        + "will be redelivered and recorded again | failed={}", response.failed().size());
            }
        }
    }

    private static String text(JsonNode envelope, String field) {
        JsonNode value = envelope.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
