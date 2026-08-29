package com.platinumcoin.pix.payment.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Publishes {@code pix.statement.export.dlq.depth} — how many messages are sitting in
 * {@code statement-export-queue-dlq} right now (step 53).
 *
 * <h2>What reaching this DLQ actually means</h2>
 * Less than it does for settlement, and that is the point of having the metric anyway. The export
 * worker turns a repeatedly failing job into a {@code FAILED} export with a reason the customer can
 * read, so an ordinary failure never gets here. What does get here is the class the worker cannot
 * answer: a message whose body will not parse, or one naming an export that is not in the store — a
 * <b>defect</b>, not an outage. A non-zero depth therefore means "the platform is producing messages it
 * cannot understand", which is exactly the kind of thing that otherwise surfaces months later as a
 * handful of customers whose exports never completed.
 *
 * <p>The queue URL is resolved on the first refresh rather than in the constructor — see
 * {@link StatementExportQueueConsumer}'s constructor for the argument: an unreachable <i>export</i>
 * queue must not stop the service that runs the money path from starting.
 *
 * <p>Same shape as {@code SettlementDlqDepthGauge} (step 32): a scheduled tick refreshes an
 * {@link AtomicLong} the gauge reads, rather than binding the gauge straight to
 * {@code GetQueueAttributes} — a Micrometer gauge is pulled at scrape time, and a gauge that called SQS
 * would put a round-trip on every Prometheus scrape.
 *
 * <p>{@code ApproximateNumberOfMessages} is approximate by name; for an "is anything stuck?" signal that
 * is the right resolution, since the alert cares about a sustained non-zero depth and not about being
 * off by one for a moment.
 */
@Component
public class StatementExportDlqDepthGauge {

    private static final Logger log = LoggerFactory.getLogger(StatementExportDlqDepthGauge.class);

    private final SqsClient sqs;
    private final String dlqName;

    /** Resolved on the first refresh, not at startup — same reason as the consumer next door. */
    private volatile String dlqUrl;

    /** Last measured depth; read by the gauge, written by each refresh. */
    private final AtomicLong depth = new AtomicLong();

    public StatementExportDlqDepthGauge(
            SqsClient sqs,
            MeterRegistry meterRegistry,
            @Value("${pix.export.dlq.queue-name}") String dlqName) {
        this.sqs = sqs;
        this.dlqName = dlqName;
        Gauge.builder("pix.statement.export.dlq.depth", depth, AtomicLong::doubleValue)
                .description("Messages in statement-export-queue-dlq — export requests the worker could "
                        + "not even parse or resolve (step 53, ADR-0003)")
                .baseUnit("messages")
                .register(meterRegistry);
        log.info("Statement export DLQ depth gauge ready, it will report how many export requests are "
                + "stuck in the dead-letter queue | dlqName={}", dlqName);
    }

    /**
     * One refresh. Never lets an exception escape, and never pretends a failed probe measured zero —
     * that would silence the very alert this metric exists to raise.
     *
     * @return the depth just measured, so an integration test can drive the probe deterministically
     */
    @Scheduled(fixedDelayString = "${pix.export.dlq.metric-refresh-ms}")
    public long refresh() {
        try {
            String url = dlqUrl();
            String value = sqs.getQueueAttributes(request -> request
                            .queueUrl(url)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    .attributes()
                    .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
            long measured = value == null ? 0L : Long.parseLong(value);
            depth.set(measured);
            if (measured > 0) {
                log.warn("Export requests are sitting in the dead-letter queue, the worker could not "
                        + "parse or resolve them | dlqUrl={} depth={}", url, measured);
            } else {
                log.debug("Statement export DLQ is empty | dlqUrl={}", url);
            }
            return measured;
        } catch (RuntimeException e) {
            log.error("Could not read the statement export DLQ depth, the last measured value is kept "
                    + "rather than being reset to zero | dlqName={}", dlqName, e);
            return depth.get();
        }
    }

    /** The DLQ's URL, resolved once on first use. */
    private String dlqUrl() {
        String resolved = dlqUrl;
        if (resolved == null) {
            resolved = sqs.getQueueUrl(request -> request.queueName(dlqName)).queueUrl();
            dlqUrl = resolved;
        }
        return resolved;
    }
}
