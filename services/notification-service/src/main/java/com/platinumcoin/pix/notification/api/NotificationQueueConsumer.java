package com.platinumcoin.pix.notification.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.notification.domain.usecase.DeliverNotificationUseCase;
import com.platinumcoin.pix.notification.domain.usecase.DeliverOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.platinumcoin.pix.common.tracing.ForceSample;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The event-driven half of this service: long-polls {@code notification-queue} and hands each
 * user-facing event to {@link DeliverNotificationUseCase}.
 *
 * <p>A queue is a way of <i>entering</i> the application, so this lives in {@code api/} beside the
 * controller and obeys the same rule: bind the wire shape, call <b>one</b> use case, map the result —
 * here onto "delete the message or leave it". Same shape as settlement-service's consumer, which is the
 * point: the second consumer off {@code pix-events} looks like the first, because fan-out means two
 * independent queues with their own filter policies (step 36), not two different programming models.
 *
 * <h2>Ack semantics, and why they are simpler here than in settlement</h2>
 * SQS has no ack: not deleting <i>is</i> the retry. settlement-service has to weigh that decision per
 * branch, because deleting a message whose work did not happen loses a payment. Here the worst case is
 * a notification the customer does not see, and the state stays queryable on
 * {@code GET /payments/{id}} — so <b>every outcome acks</b>, and only a thrown exception (a broken
 * transport, a broken adapter) leaves the message for redelivery. That is the whole meaning of
 * "best-effort", written down as code rather than as an intention.
 *
 * <p>A body that cannot be parsed is deliberately <b>not</b> deleted: a poison message rides its five
 * receives into {@code notification-queue-dlq}, where a message nobody can process belongs — flagged,
 * not lost.
 */
@Component
public class NotificationQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueueConsumer.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper;
    private final DeliverNotificationUseCase deliverNotification;
    private final int batchSize;
    private final int waitTimeSeconds;

    /** Tracing collaborators (step 72). Nullable: a push must never wait on the observability stack. */
    private final Tracer tracer;

    private final TracePropagation tracing;

    /**
     * The queue URL is resolved from its <b>name</b> at startup, like settlement's consumer: a consumer
     * must call {@code GetQueueUrl} before it can receive anything, and failing to start when the queue
     * is absent is the honest behaviour — a notification service that boots healthy while consuming
     * nothing would look perfectly fine and push nothing at all.
     */
    public NotificationQueueConsumer(
            SqsClient sqs,
            ObjectMapper mapper,
            DeliverNotificationUseCase deliverNotification,
            @Value("${pix.notifications.queue-name}") String queueName,
            @Value("${pix.notifications.consumer.batch-size}") int batchSize,
            @Value("${pix.notifications.consumer.wait-time-seconds}") int waitTimeSeconds,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<TracePropagation> tracing) {
        this.tracer = tracer.getIfAvailable();
        this.tracing = tracing.getIfAvailable();
        this.sqs = sqs;
        this.queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        this.mapper = mapper;
        this.deliverNotification = deliverNotification;
        this.batchSize = batchSize;
        this.waitTimeSeconds = waitTimeSeconds;
        log.info("Notification consumer ready, it will long-poll this queue for the user-facing events "
                        + "PixSettled/PixReceived/PixReversed | queueName={} queueUrl={} batchSize={} "
                        + "waitTimeSeconds={}", queueName, this.queueUrl, batchSize, waitTimeSeconds);
    }

    /**
     * One poll. Never lets an exception escape — anything not deleted is still on the queue.
     *
     * @return how many messages this tick received, so an IT can drive the loop deterministically
     *         instead of sleeping on a schedule
     */
    @Scheduled(fixedDelayString = "${pix.notifications.consumer.fixed-delay-ms}")
    public int pollOnce() {
        try {
            var messages = sqs.receiveMessage(request -> request
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(batchSize)
                            .waitTimeSeconds(waitTimeSeconds)
                            // SQS returns message attributes only when asked for by name. Without this
                            // the traceparent the publisher attached is dropped and the push a user sees
                            // is a trace of its own, unlinkable to the payment that caused it (step 72).
                            .messageAttributeNames(TracePropagation.TRACEPARENT))
                    .messages();

            if (messages.isEmpty()) {
                log.debug("Notification queue poll returned no messages | queueUrl={} batchSize={} "
                        + "waitTimeSeconds={}", queueUrl, batchSize, waitTimeSeconds);
                return 0;
            }

            log.info("Notification queue poll received messages, handling them one by one | received={} "
                    + "batchSize={}", messages.size(), batchSize);
            for (Message message : messages) {
                handle(message);
            }
            return messages.size();
        } catch (RuntimeException e) {
            log.error("The notification consumer tick failed before it could handle its messages, "
                            + "nothing was acked so every message returns after the visibility timeout "
                            + "| queueUrl={}", queueUrl, e);
            return 0;
        }
    }

    private void handle(Message message) {
        NotificationMessage parsed;
        try {
            parsed = mapper.readValue(message.body(), NotificationMessage.class);
        } catch (Exception e) {
            log.error("A notification message could not be parsed, leaving it on the queue so it "
                            + "redrives to the DLQ rather than disappearing | messageId={} body={}",
                    message.messageId(), message.body(), e);
            return;
        }

        if (!parsed.isComplete()) {
            log.error("A notification message is missing the fields an event envelope must carry, "
                            + "leaving it on the queue so it redrives to the DLQ | messageId={} body={}",
                    message.messageId(), message.body());
            return;
        }

        // The scheduler's thread never saw an HTTP filter, so nothing put anything in the MDC. Adopting
        // the event's ids makes the shared log pattern prefix every line below with [cid=… tx=…], which
        // is what keeps one `grep <correlationId>` walking a payment from the send request all the way
        // to the push (ADR-0012). Cleared in the finally: the thread is reused and a leaked id would
        // mislabel the next message.
        CorrelationId.restore(parsed.correlationId(), parsed.txId());
        // Opened after the MDC is populated: CorrelationIdSpanProcessor stamps the ids at onStart, so the
        // other order would produce a span with no correlation id (ADR-0021 decision 2).
        Span consume = openConsumeSpan(message);
        try (Tracer.SpanInScope scope = tracer == null || consume == null
                ? null : tracer.withSpan(consume)) {
            DeliverOutcome outcome = deliverNotification.execute(parsed.toCommand());
            // Every outcome acks — see the class javadoc. The use case has already logged which one and
            // why, so this line records only the acknowledgement itself.
            delete(message);
            log.info("Notification message handled and acked | messageId={} eventId={} eventType={} "
                            + "outcome={} subscribersReached={}",
                    message.messageId(), parsed.eventId(), parsed.eventType(), outcome.kind(),
                    outcome.subscribersReached());
        } catch (RuntimeException e) {
            // The use case released its dedup claim on the way out, so leaving the message here means
            // the redelivery is real work rather than being deduped away.
            log.error("Handling a notification message failed, leaving it on the queue for redelivery "
                            + "| messageId={} eventId={} eventType={}",
                    message.messageId(), parsed.eventId(), parsed.eventType(), e);
        } finally {
            if (consume != null) {
                consume.end();
            }
            CorrelationId.clear();
            ForceSample.clear();
        }
    }

    /**
     * Continue the trace of the payment that caused this push, or start a new one when the message has no
     * context. This is the hop that makes "the user saw their money arrive" the last span of the same
     * trace that began at {@code POST /v1/payments/pix}.
     */
    private Span openConsumeSpan(Message message) {
        if (tracing == null) {
            return null;
        }
        String traceparent = message.messageAttributes().containsKey(TracePropagation.TRACEPARENT)
                ? message.messageAttributes().get(TracePropagation.TRACEPARENT).stringValue()
                : null;
        return tracing.continuedSpan("pix.notification.consume", traceparent);
    }

    private void delete(Message message) {
        log.debug("SQS DeleteMessage acknowledging a handled message | queueUrl={} messageId={}",
                queueUrl, message.messageId());
        sqs.deleteMessage(request -> request
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle()));
    }
}
