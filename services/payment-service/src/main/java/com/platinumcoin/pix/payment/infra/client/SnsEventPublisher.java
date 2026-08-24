package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.common.event.EventEnvelope;
import com.platinumcoin.pix.common.tracing.TracePropagation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import com.platinumcoin.pix.payment.domain.model.PendingOutboxEvent;
import com.platinumcoin.pix.payment.domain.port.EventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Publishes an outbox event to the SNS topic {@code pix-events} (step 29, ADR-0004) — the only place an
 * SNS type appears in this service (ADR-0010).
 *
 * <h2>One topic, routed by message attribute</h2>
 * There is exactly one topic and the fan-out happens in the <b>subscriptions</b>: each consumer queue
 * subscribes with a filter policy on the {@code eventType} attribute (settlement takes
 * {@code PixDebited} today, step 26). That is why the type is set as a <b>message attribute</b> and not
 * merely carried inside the JSON body — SNS filters on attributes, never on the payload, so a queue
 * that does not care about an event type is never charged a receive for it. {@code eventId} rides along
 * for the consumer's dedup and {@code correlationId} so a consumer can log under the request that
 * caused the event before it has parsed a single byte of the body (ADR-0012).
 *
 * <h2>The body is the stored envelope, verbatim</h2>
 * {@link EventEnvelope} rebuilds the wire envelope from the item's own attributes and embeds the stored
 * payload as raw JSON, so what a consumer receives is exactly what the producing transaction committed.
 * Nothing here parses, validates or enriches the payload — the publisher is deliberately dumb, which is
 * what makes it replaceable (Streams/Kinesis/Kafka) without touching producers or consumers.
 *
 * <p>The subscription is configured with {@code RawMessageDelivery=true}, so the consumer reads this
 * exact body rather than an SNS notification wrapper — no broker-specific unwrapping step anywhere.
 *
 * <h2>Where the trace crosses the broker (step 72, ADR-0021 decision 4)</h2>
 * This publisher runs on a scheduler thread, seconds after the request that produced the event returned
 * {@code 202}. Left alone it would start a brand-new trace, and the platform would have two disconnected
 * halves — the synchronous one that the current tooling already explains best, and the asynchronous one
 * that is the entire point. So the item's stored {@code traceparent} is used to open a PRODUCER span that
 * <b>continues the accepting request's trace</b>, and the traceparent of <i>that</i> span is attached to
 * the message as a {@code traceparent} attribute. The consumer opens its span from it, and one trace runs
 * accept → outbox → SNS → SQS → settle → finalize.
 *
 * <p>A message attribute rather than a body field, for the same reason {@code eventType} is one: the body
 * is the business envelope, forwarded verbatim from what the producing transaction committed, and this is
 * transport metadata a consumer reads before parsing anything. RawMessageDelivery hands SNS attributes
 * straight through to SQS, so nothing has to unwrap it.
 *
 * <p>The publish itself never depends on any of this: no stored traceparent, or tracing switched off,
 * means the span is a fresh root and the attribute is simply absent.
 */
public class SnsEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnsEventPublisher.class);

    private static final String STRING_ATTRIBUTE = "String";

    private final SnsClient sns;
    private final String topicArn;
    private final Tracer tracer;
    private final TracePropagation tracing;

    public SnsEventPublisher(SnsClient sns, String topicArn, Tracer tracer, TracePropagation tracing) {
        this.sns = sns;
        this.topicArn = topicArn;
        this.tracer = tracer;
        this.tracing = tracing;
    }

    @Override
    public void publish(PendingOutboxEvent event) {
        if (tracing == null) {
            doPublish(event, null);
            return;
        }
        // Resume the accepting request's trace rather than starting a new one. The span measures the
        // publish itself; the gap between the item's occurredAt and this span's start is the outbox lag,
        // now visible as an interval in the trace instead of inferred from two log timestamps.
        Span publish = tracing.childSpan("pix.outbox.publish", event.traceparent());
        if (publish == null) {
            // Tracing failed to open a span. That is a degraded observability stack, never a reason not
            // to publish an event the producing transaction already committed.
            doPublish(event, null);
            return;
        }
        publish.tag("pix.event_type", event.eventType());
        publish.tag("pix.lane", event.lane().name());
        try (Tracer.SpanInScope scope = tracer.withSpan(publish)) {
            doPublish(event, tracing.currentTraceparent());
        } catch (RuntimeException e) {
            publish.error(e);
            throw e;
        } finally {
            publish.end();
        }
    }

    /**
     * @param traceparent the context to hand the consumer — the PUBLISH span's, not the stored one, so
     *                    the consumer's span nests under the publish rather than beside it
     */
    private void doPublish(PendingOutboxEvent event, String traceparent) {
        String body = EventEnvelope.toJson(
                event.eventId(),
                event.eventType(),
                event.occurredAt(),
                event.correlationId(),
                event.payloadJson());

        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("eventType", stringAttribute(event.eventType()));
        attributes.put("eventId", stringAttribute(event.eventId()));
        if (event.correlationId() != null && !event.correlationId().isBlank()) {
            attributes.put("correlationId", stringAttribute(event.correlationId()));
        }
        if (traceparent != null && !traceparent.isBlank()) {
            attributes.put(TracePropagation.TRACEPARENT, stringAttribute(traceparent));
        }

        log.debug("Publishing an outbox event to SNS | topicArn={} eventId={} eventType={} "
                        + "attributes={} traceparent={} body={}",
                topicArn, event.eventId(), event.eventType(), attributes.keySet(), traceparent, body);

        // Any failure (throttling, network, wrong ARN) propagates: the use case must NOT mark an event
        // published unless it really went out — the whole ordering depends on this throwing.
        PublishResponse response =
                sns.publish(request -> request.topicArn(topicArn).message(body).messageAttributes(attributes));

        log.info("Outbox item published to SNS, subscriptions matching its eventType will receive it | "
                        + "eventId={} eventType={} txId={} correlationId={} traceparent={} topicArn={} "
                        + "messageId={}",
                event.eventId(), event.eventType(), event.txId(), event.correlationId(), traceparent,
                topicArn, response.messageId());
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType(STRING_ATTRIBUTE).stringValue(value).build();
    }
}
