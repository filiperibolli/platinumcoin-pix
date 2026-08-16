package com.platinumcoin.pix.settlement.api;

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
 * Publishes {@code settlement.dlq.depth} — how many messages are sitting in {@code settlement-queue-dlq}
 * right now (step 32, task 4).
 *
 * <h2>Why a DLQ depth is a first-class signal</h2>
 * A message reaches the DLQ only after {@code maxReceiveCount} deliveries left it undeleted (step 26's
 * redrive policy) — a settlement that could never be completed: a permanent rail refusal with no reversal
 * yet (step 33), a poison body, an id the rail keeps refusing as unavailable. A DLQ message is <b>not</b>
 * a lost message (ADR-0003); it is a <i>flagged</i> one that the reconciliation loop and this metric own.
 * A depth that climbs and stays up is the platform saying "money is stuck in clearing and no automatic
 * path is releasing it" — which is exactly what step 44 alerts on.
 *
 * <h2>Why this is an {@code api/} scheduled adapter, and why it reads an {@code AtomicLong}</h2>
 * A schedule is a way of <i>entering</i> the application (ADR-0011), so this poller lives beside the
 * queue consumer. Micrometer gauges are pulled at scrape time, so binding the gauge straight to a
 * {@code GetQueueAttributes} call would put an SQS round-trip on every Prometheus scrape. Instead a cheap
 * scheduled tick refreshes an {@code AtomicLong} the gauge reads — the same shape payment-service's
 * {@code outbox.lag} uses. The tick obeys {@code pix.schedulers.enabled} (off in ITs, which call {@link
 * #refresh()} explicitly), since a live poller against the shared queue would fight the tests for it.
 *
 * <p>{@code ApproximateNumberOfMessages} is approximate by name: SQS is distributed and the count can lag
 * a redrive by seconds. For a "is anything stuck?" signal that is exactly the right resolution — the
 * alert cares about a sustained non-zero depth, not about being off by one for a moment.
 */
@Component
public class SettlementDlqDepthGauge {

    private static final Logger log = LoggerFactory.getLogger(SettlementDlqDepthGauge.class);

    private final SqsClient sqs;
    private final String dlqUrl;

    /** Last measured depth; read by the gauge, written by each refresh. */
    private final AtomicLong depth = new AtomicLong();

    public SettlementDlqDepthGauge(
            SqsClient sqs,
            MeterRegistry meterRegistry,
            @Value("${pix.settlement.dlq.queue-name}") String dlqName) {
        this.sqs = sqs;
        this.dlqUrl = sqs.getQueueUrl(request -> request.queueName(dlqName)).queueUrl();
        Gauge.builder("settlement.dlq.depth", depth, AtomicLong::doubleValue)
                .description("Messages in settlement-queue-dlq — settlements that could not be completed "
                        + "(step 32, ADR-0003)")
                .baseUnit("messages")
                .register(meterRegistry);
        log.info("Settlement DLQ depth gauge ready, it will report how many settlements are stuck in the "
                        + "dead-letter queue | dlqName={} dlqUrl={}", dlqName, this.dlqUrl);
    }

    /**
     * One refresh. Never lets an exception escape: a scheduled task that throws is noise, and a failed
     * probe must not crash the service — it keeps the last value (pretending it is zero would silence the
     * very alert this metric exists to raise).
     *
     * @return the depth just measured, so an integration test can drive the probe deterministically
     *         instead of waiting on the schedule
     */
    @Scheduled(fixedDelayString = "${pix.settlement.dlq.metric-refresh-ms}")
    public long refresh() {
        try {
            String value = sqs.getQueueAttributes(request -> request
                            .queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    .attributes()
                    .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
            long measured = value == null ? 0L : Long.parseLong(value);
            depth.set(measured);
            log.debug("Refreshed the settlement DLQ depth gauge | dlqUrl={} depth={}", dlqUrl, measured);
            return measured;
        } catch (RuntimeException e) {
            log.error("Could not read the settlement DLQ depth, the gauge keeps its last value | dlqUrl={}",
                    dlqUrl, e);
            return depth.get();
        }
    }
}
