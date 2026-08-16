package com.platinumcoin.pix.settlement.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.settlement.domain.usecase.SettleOutcome;
import com.platinumcoin.pix.settlement.domain.usecase.SettlePixUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * The platform's first queue-driven inbound adapter: it long-polls {@code settlement-queue} and hands
 * each {@code PixDebited} to {@link SettlePixUseCase}.
 *
 * <p><b>Why this is {@code api/} and not {@code infra/}.</b> A queue is a way of <i>entering</i> the
 * application, no different in kind from an HTTP request, so it lives beside the controllers and obeys
 * the same rule (ADR-0011): bind the wire shape, call <b>one</b> use case, map the result — here onto
 * "delete the message or leave it". It holds no policy of its own; whether a payment may be settled and
 * whether a delivery is a duplicate are money decisions and they live in the use case, where a
 * plain-Java test pins them.
 *
 * <h2>Long polling, and why the tick is a {@code fixedDelay}</h2>
 * The receive blocks up to 20s waiting for work (queue attribute of step 26), so an idle system costs
 * one request every 20s instead of a stream of empty ones, and a message that arrives is picked up
 * within milliseconds rather than at the next tick. {@code fixedDelay} means the next poll starts after
 * the previous one <i>finished</i> — with a rate, a slow batch (a 12s SPI call ×10 messages) would have
 * ticks overlapping and two threads receiving the same queue, which is a self-inflicted version of the
 * concurrency the visibility timeout is there to prevent.
 *
 * <h2>Ack semantics: deleting is the acknowledgement</h2>
 * SQS has no ack — a message stays invisible for its visibility timeout (30s, step 26) and comes back
 * unless it is deleted. So <b>not deleting is the retry</b>, and the direction of each decision matters:
 * deleting a message whose work did not happen loses a payment; leaving one whose work did happen costs
 * a redelivery the dedup gate absorbs. {@link SettleOutcome#messageMayBeDeleted()} carries that
 * decision from the use case, and this class only obeys it.
 *
 * <p>A body that cannot be parsed is deliberately <b>not</b> deleted: a poison message rides its five
 * receives into {@code settlement-queue-dlq}, which is where a message nobody can process belongs — a
 * DLQ message is flagged, not lost (ADR-0003), and step 32 makes that path first-class.
 */
@Component
public class SettlementQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementQueueConsumer.class);

    /** The one event type this queue's subscription filter lets through (step 26). */
    private static final String PIX_DEBITED = "PixDebited";

    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper;
    private final SettlePixUseCase settlePix;
    private final int batchSize;
    private final int waitTimeSeconds;
    private final int backoffBaseSeconds;
    private final int backoffCapSeconds;

    /**
     * The queue's URL is resolved from its <b>name</b> here, at startup. Unlike the SNS topic ARN of step
     * 29 (handed in as configuration, because a deployed publisher holds {@code sns:Publish} on exactly
     * one ARN and has no business listing topics), a consumer has to call {@code GetQueueUrl} anyway
     * before it can receive anything, and the URL is not a stable value worth pinning per environment.
     * Failing to start when the queue does not exist is the honest behaviour: a settlement service that
     * boots healthy while consuming nothing is the worst of both worlds. Resolving it in the adapter that
     * uses it also keeps {@code api/} from importing anything out of {@code infra/} (ADR-0010).
     */
    public SettlementQueueConsumer(
            SqsClient sqs,
            ObjectMapper mapper,
            SettlePixUseCase settlePix,
            @Value("${pix.settlement.queue-name}") String queueName,
            @Value("${pix.settlement.consumer.batch-size}") int batchSize,
            @Value("${pix.settlement.consumer.wait-time-seconds}") int waitTimeSeconds,
            @Value("${pix.settlement.consumer.retry-backoff-base-seconds}") int backoffBaseSeconds,
            @Value("${pix.settlement.consumer.retry-backoff-cap-seconds}") int backoffCapSeconds) {
        this.sqs = sqs;
        this.queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        this.mapper = mapper;
        this.settlePix = settlePix;
        this.batchSize = batchSize;
        this.waitTimeSeconds = waitTimeSeconds;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffCapSeconds = backoffCapSeconds;
        log.info("Settlement consumer ready, it will long-poll this queue for PixDebited events | "
                        + "queueName={} queueUrl={} batchSize={} waitTimeSeconds={} "
                        + "retryBackoffBaseSeconds={} retryBackoffCapSeconds={}",
                queueName, this.queueUrl, batchSize, waitTimeSeconds, backoffBaseSeconds, backoffCapSeconds);
    }

    /**
     * One poll. Never lets an exception escape: a scheduled task that throws is noise in a framework log
     * and there is nothing to abort — anything not deleted is still on the queue and comes back.
     *
     * @return how many messages this tick received, so a test can drive the loop deterministically
     *         instead of sleeping on a schedule
     */
    @Scheduled(fixedDelayString = "${pix.settlement.consumer.fixed-delay-ms}")
    public int pollOnce() {
        try {
            var messages = sqs.receiveMessage(request -> request
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(batchSize)
                    .waitTimeSeconds(waitTimeSeconds)
                    // ApproximateReceiveCount is how many times SQS has handed this message out. >1 means
                    // a prior attempt did not delete it — a redelivery — which is the signal step 32 uses
                    // to query the rail before re-sending.
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                    .messages();

            if (messages.isEmpty()) {
                // DEBUG, not INFO: on an idle system this is nearly every tick and would drown the log
                // the INFO layer has to tell the story in (ADR-0012).
                log.debug("Settlement queue poll returned no messages | queueUrl={} batchSize={} "
                        + "waitTimeSeconds={}", queueUrl, batchSize, waitTimeSeconds);
                return 0;
            }

            log.info("Settlement queue poll received messages, handling them one by one | received={} "
                    + "batchSize={}", messages.size(), batchSize);
            for (Message message : messages) {
                handle(message);
            }
            return messages.size();
        } catch (RuntimeException e) {
            log.error("The settlement consumer tick failed before it could handle its messages, nothing "
                    + "was acked so every message returns after the visibility timeout | queueUrl={}",
                    queueUrl, e);
            return 0;
        }
    }

    private void handle(Message message) {
        SettlementMessage parsed;
        try {
            parsed = mapper.readValue(message.body(), SettlementMessage.class);
        } catch (Exception e) {
            // Left on the queue on purpose — see the class javadoc: it belongs in the DLQ, not in a
            // silent delete. ERROR because a body we cannot read is an actionable defect somewhere.
            log.error("A settlement message could not be parsed, leaving it on the queue so it redrives "
                            + "to the DLQ rather than disappearing | messageId={} body={}",
                    message.messageId(), message.body(), e);
            return;
        }

        if (!parsed.isComplete()) {
            log.error("A settlement message is missing the fields a PixDebited must carry, leaving it on "
                            + "the queue so it redrives to the DLQ | messageId={} body={}",
                    message.messageId(), message.body());
            return;
        }

        // The scheduler's thread never saw an HTTP filter, so nothing put anything in the MDC. Adopting
        // the event's ids makes the shared log pattern prefix every line below — ours and the AWS SDK's —
        // with [cid=… tx=…], which is what keeps `grep <correlationId>` returning the WHOLE path of a
        // payment across services once the flow has left the request thread (ADR-0012). Cleared in the
        // finally: the thread is reused and a leaked id would mislabel the next message.
        CorrelationId.restore(parsed.correlationId(), parsed.payload().txId());
        try {
            if (!PIX_DEBITED.equals(parsed.eventType())) {
                // The subscription filter should make this impossible; if it happens, this consumer can
                // never handle it, so keeping it would only cost five receives before the DLQ.
                log.warn("A message this consumer does not handle reached the settlement queue, acking it "
                                + "since no retry could ever help | messageId={} eventType={} eventId={}",
                        message.messageId(), parsed.eventType(), parsed.eventId());
                delete(message);
                return;
            }

            int receiveCount = receiveCount(message);
            boolean redelivery = receiveCount > 1;

            SettleOutcome outcome = settlePix.execute(parsed.toCommand(), redelivery);
            if (outcome.messageMayBeDeleted()) {
                delete(message);
                log.info("Settlement message handled and acked | messageId={} eventId={} txId={} "
                        + "receiveCount={} redelivery={} outcome={}", message.messageId(), parsed.eventId(),
                        parsed.payload().txId(), receiveCount, redelivery, outcome);
            } else {
                int backoff = backoffSeconds(receiveCount);
                extendVisibility(message, backoff);
                log.warn("Settlement message NOT acked, it stays invisible for the backoff window then "
                                + "SQS redelivers it (or redrives to the DLQ once it has been received "
                                + "maxReceiveCount times) | messageId={} eventId={} txId={} receiveCount={} "
                                + "backoffSeconds={} outcome={}",
                        message.messageId(), parsed.eventId(), parsed.payload().txId(), receiveCount,
                        backoff, outcome);
            }
        } catch (RuntimeException e) {
            // The use case releases its own claim on any failure, so leaving the message here is enough
            // for the redelivery to be real work.
            log.error("Handling a settlement message failed unexpectedly, leaving it on the queue for "
                            + "redelivery | messageId={} eventId={} txId={}",
                    message.messageId(), parsed.eventId(), parsed.payload().txId(), e);
        } finally {
            CorrelationId.clear();
        }
    }

    private void delete(Message message) {
        log.debug("SQS DeleteMessage acknowledging a handled message | queueUrl={} messageId={}",
                queueUrl, message.messageId());
        sqs.deleteMessage(request -> request
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle()));
    }

    /**
     * Reset this message's visibility to the backoff window, so it is redelivered after {@code seconds}
     * rather than after the queue's static 30s timeout. This is the "per-attempt backoff via visibility
     * extension" of step 32's task 1: the failed attempt already finished, so the window is not about
     * protecting an in-flight call — it spaces the retries out, quickly at first and further apart the
     * more an id keeps failing, until {@code maxReceiveCount} sends it to the DLQ.
     *
     * <p>Best-effort: if the reset itself fails, the message simply keeps its current visibility and
     * comes back on the default schedule — never a reason to crash the tick.
     */
    private void extendVisibility(Message message, int seconds) {
        try {
            sqs.changeMessageVisibility(request -> request
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(seconds));
        } catch (RuntimeException e) {
            log.warn("Could not set the retry backoff on a settlement message, it will be redelivered on "
                            + "the queue's default visibility timeout instead | messageId={} backoffSeconds={}",
                    message.messageId(), seconds, e);
        }
    }

    /**
     * Exponential backoff capped: {@code base·2^(receiveCount-1)}, never above the cap. With the default
     * base of 5s that is 5, 10, 20, 40, 60(cap) across the five deliveries before the DLQ. A base of 0
     * (integration tests) yields 0 — immediate redelivery, so a retry drill does not wait on wall-clock.
     */
    int backoffSeconds(int receiveCount) {
        long shift = Math.min(Math.max(receiveCount - 1, 0), 20);
        long backoff = (long) backoffBaseSeconds << shift;
        return (int) Math.min(backoff, backoffCapSeconds);
    }

    /**
     * How many times SQS has delivered this message. Absent or unparseable (a hand-placed test message,
     * a broker that omits it) is treated as a first delivery — the safe default, since it only means the
     * consumer sends before querying, which {@code endToEndId} idempotency makes safe anyway.
     */
    private static int receiveCount(Message message) {
        String raw = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
