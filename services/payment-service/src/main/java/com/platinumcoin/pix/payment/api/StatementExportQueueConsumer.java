package com.platinumcoin.pix.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.payment.domain.usecase.BuildStatementExportCommand;
import com.platinumcoin.pix.payment.domain.usecase.BuildStatementExportOutcome;
import com.platinumcoin.pix.payment.domain.usecase.BuildStatementExportUseCase;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * payment-service's first queue-driven inbound adapter (step 53): long-polls
 * {@code statement-export-queue} and hands each {@code StatementExportRequested} to
 * {@link BuildStatementExportUseCase}.
 *
 * <p><b>Why this is {@code api/} and not {@code infra/}.</b> A queue is a way of <i>entering</i> the
 * application, no different in kind from an HTTP request, so it lives beside the controllers and obeys
 * the same rule (ADR-0011): bind the wire shape, call <b>one</b> use case, map the result — here onto
 * "delete the message or leave it". It holds no policy: whether an export may be assembled, whether a
 * delivery is a duplicate and when to give up are all decisions in the use case, where a plain-Java test
 * pins them.
 *
 * <h2>Ack semantics: deleting is the acknowledgement</h2>
 * SQS has no ack — a message stays invisible for the visibility timeout and comes back unless it is
 * deleted — so <b>not deleting is the retry</b>. {@link BuildStatementExportOutcome#messageMayBeDeleted()}
 * carries that decision out of the use case and this class only obeys it. A body that cannot be parsed,
 * or that names no export, is deliberately <b>not</b> deleted: it rides its deliveries into
 * {@code statement-export-queue-dlq}, where a message nobody can process belongs (ADR-0003 — a DLQ
 * message is flagged, not lost), and the DLQ-depth gauge next door is what makes it visible.
 *
 * <h2>Sequential, and why that is enough here</h2>
 * Unlike {@code SettlementQueueConsumer}, this one handles a batch's messages one after another. The
 * shapes of the two problems differ: settlement is a per-payment call to an external rail with a fixed
 * latency, where concurrency is the only way past the ceiling; an export is a bulk read a customer is
 * politely polling for, arriving at a rate measured in requests per hour rather than per second. Adding
 * a worker pool would be sizing for a load this queue does not have — and the day it does, the change is
 * the same bounded pool step 71 already proved safe, because the same two properties hold: every
 * delivery dedupes by {@code eventId} and every completion is a guarded transition.
 *
 * <p>{@code ApproximateReceiveCount} is read from SQS rather than counted here: it survives a restart of
 * this service, so the attempt budget cannot be reset into an infinite retry by a deploy.
 *
 * <p>The bean always exists; only {@code @EnableScheduling} is gated on {@code pix.schedulers.enabled}
 * ({@code SchedulingConfig}), which is what lets an integration test drive {@link #pollOnce()} explicitly
 * instead of racing a live poller for the shared queue.
 */
@Component
public class StatementExportQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(StatementExportQueueConsumer.class);

    /** The queue-hop boundary span, so a trace does not end at the 202. */
    private static final String CONSUME_SPAN = "pix.statement-export.consume";

    private final SqsClient sqs;
    private final String queueName;

    /** Resolved on the first poll, then reused. See the constructor's javadoc for why not at startup. */
    private volatile String queueUrl;

    private final ObjectMapper mapper;
    private final BuildStatementExportUseCase buildExport;
    private final int batchSize;
    private final int waitTimeSeconds;

    /** Nullable: an export must never depend on the observability stack being wired (ADR-0021). */
    private final Tracer tracer;

    private final TracePropagation tracing;

    /**
     * <b>The queue URL is resolved on the first poll, not at startup — and that is a deliberate
     * departure from every other consumer in the platform.</b>
     *
     * <p>settlement-service and notification-service resolve theirs in the constructor, and the argument
     * is good there: consuming <i>is</i> what those services are for, so one that booted healthy while
     * consuming nothing would be the worst of both worlds. payment-service is different in the one way
     * that matters — it serves {@code POST /v1/payments/pix}. Resolving here would make an unreachable
     * <i>export</i> queue a startup failure for the <i>money path</i>, which is the same priority
     * inversion ADR-0021 refuses for tracing and compose refuses with its {@code depends_on} on
     * Prometheus. A reporting feature must degrade on its own.
     *
     * <p>It also restored a property this service had and step 53 briefly destroyed: {@code
     * ApplicationContextIT} boots the whole context with no AWS reachable at all, which is what keeps it
     * a fast wiring smoke test rather than another Testcontainers run.
     *
     * <p>Nothing is swallowed in exchange. A queue that does not exist fails the first poll, loudly, and
     * every poll after it — and the DLQ-depth gauge next door reports the same fault to Prometheus.
     */
    public StatementExportQueueConsumer(
            SqsClient sqs,
            ObjectMapper mapper,
            BuildStatementExportUseCase buildExport,
            @Value("${pix.export.queue-name}") String queueName,
            @Value("${pix.export.consumer.batch-size}") int batchSize,
            @Value("${pix.export.consumer.wait-time-seconds}") int waitTimeSeconds,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<TracePropagation> tracing) {
        this.sqs = sqs;
        this.queueName = queueName;
        this.mapper = mapper;
        this.buildExport = buildExport;
        this.batchSize = batchSize;
        this.waitTimeSeconds = waitTimeSeconds;
        this.tracer = tracer.getIfAvailable();
        this.tracing = tracing.getIfAvailable();
        log.info("Statement export consumer ready, it will long-poll this queue and assemble each "
                        + "requested export from the cold archive | queueName={} batchSize={} "
                        + "waitTimeSeconds={}",
                queueName, batchSize, waitTimeSeconds);
    }

    /** The queue URL, resolved once on first use. Any failure surfaces to the tick, which logs it. */
    private String queueUrl() {
        String resolved = queueUrl;
        if (resolved == null) {
            resolved = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
            queueUrl = resolved;
            log.info("Resolved the statement export queue on first use | queueName={} queueUrl={}",
                    queueName, resolved);
        }
        return resolved;
    }

    /**
     * One poll. Never lets an exception escape: a scheduled task that throws is noise in a framework log
     * and there is nothing to abort — anything not deleted is still on the queue and comes back.
     *
     * @return how many messages this tick received, so a test can drive the loop deterministically
     *         instead of sleeping on a schedule
     */
    @Scheduled(fixedDelayString = "${pix.export.consumer.fixed-delay-ms}")
    public int pollOnce() {
        try {
            String url = queueUrl();
            var messages = sqs.receiveMessage(request -> request
                            .queueUrl(url)
                            .maxNumberOfMessages(batchSize)
                            .waitTimeSeconds(waitTimeSeconds)
                            // How many times SQS has handed this message out. It is the attempt budget's
                            // counter, and asking for it by name is the only way to get it.
                            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                            // SQS returns message attributes only when asked for by name; without this
                            // the publisher's traceparent is silently dropped and every trace ends at
                            // the queue, with no error anywhere to say so.
                            .messageAttributeNames(TracePropagation.TRACEPARENT))
                    .messages();

            if (messages.isEmpty()) {
                // DEBUG, not INFO: on an idle system this is nearly every tick and would drown the log
                // the INFO layer has to tell the story in (ADR-0012).
                log.debug("Statement export queue poll returned no messages | queueUrl={} batchSize={} "
                        + "waitTimeSeconds={}", url, batchSize, waitTimeSeconds);
                return 0;
            }

            log.info("Statement export queue poll received messages | received={} batchSize={}",
                    messages.size(), batchSize);
            messages.forEach(this::handle);
            return messages.size();
        } catch (RuntimeException e) {
            log.error("The statement export consumer tick failed before it could handle its messages, "
                    + "nothing was acked so every message returns after the visibility timeout | "
                    + "queueName={}", queueName, e);
            return 0;
        }
    }

    private void handle(Message message) {
        StatementExportMessage parsed;
        try {
            parsed = mapper.readValue(message.body(), StatementExportMessage.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("A statement export message could not be parsed, leaving it on the queue so it "
                            + "rides its deliveries into the DLQ rather than being dropped | "
                            + "messageId={} body={}",
                    message.messageId(), message.body(), e);
            return;
        }

        if (!parsed.isComplete()) {
            log.error("A statement export message is missing its eventId or exportId, leaving it on the "
                            + "queue for the DLQ | messageId={} eventId={} eventType={} body={}",
                    message.messageId(), parsed.eventId(), parsed.eventType(), message.body());
            return;
        }

        // Restore the correlation id so every line the assembly logs greps under the id of the request
        // that asked for it, minutes earlier and in another thread (ADR-0012).
        CorrelationId.restore(parsed.correlationId(), parsed.exportId());
        Span span = openConsumeSpan(message);

        try {
            int attempt = deliveryAttempt(message);
            log.info("Handling a statement export request off the queue | messageId={} eventId={} "
                            + "exportId={} months={} deliveryAttempt={}",
                    message.messageId(), parsed.eventId(), parsed.exportId(), parsed.monthRange(),
                    attempt);

            BuildStatementExportOutcome outcome = buildExport.execute(
                    new BuildStatementExportCommand(parsed.eventId(), parsed.exportId(), attempt));

            if (outcome.messageMayBeDeleted()) {
                delete(message);
                log.info("Statement export message handled and acked | messageId={} eventId={} "
                                + "exportId={} result={} lines={}",
                        message.messageId(), parsed.eventId(), parsed.exportId(), outcome.result(),
                        outcome.linesExported());
            } else {
                log.warn("Statement export message left on the queue for another delivery | "
                                + "messageId={} eventId={} exportId={} result={}",
                        message.messageId(), parsed.eventId(), parsed.exportId(), outcome.result());
            }
        } catch (RuntimeException e) {
            // Nothing was acked, so the message comes back after the visibility timeout. The use case
            // owns the attempt budget; an escape past it is a defect, hence ERROR with the trace.
            log.error("Handling a statement export message failed unexpectedly, it was not acked and "
                            + "will be redelivered | messageId={} eventId={} exportId={}",
                    message.messageId(), parsed.eventId(), parsed.exportId(), e);
        } finally {
            if (span != null) {
                span.end();
            }
            CorrelationId.clear();
        }
    }

    /** SQS's own delivery counter; 1 when the attribute is absent, which is the first-delivery reading. */
    private static int deliveryAttempt(Message message) {
        String count = message.attributes()
                .get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        try {
            return count == null ? 1 : Integer.parseInt(count);
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    /**
     * Continue the trace of the request that asked for this export, so the asynchronous half is part of
     * the same trace as the {@code 202} rather than an orphan that starts at the queue.
     */
    private Span openConsumeSpan(Message message) {
        if (tracing == null) {
            return null;
        }
        String traceparent = message.messageAttributes().containsKey(TracePropagation.TRACEPARENT)
                ? message.messageAttributes().get(TracePropagation.TRACEPARENT).stringValue()
                : null;
        return tracing.continuedSpan(CONSUME_SPAN, traceparent);
    }

    private void delete(Message message) {
        String url = queueUrl();
        log.debug("SQS DeleteMessage acknowledging a handled export message | queueUrl={} messageId={}",
                url, message.messageId());
        sqs.deleteMessage(request -> request
                .queueUrl(url)
                .receiptHandle(message.receiptHandle()));
    }
}
