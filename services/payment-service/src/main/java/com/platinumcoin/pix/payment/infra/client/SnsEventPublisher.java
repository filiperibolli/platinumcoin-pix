package com.platinumcoin.pix.payment.infra.client;

import com.platinumcoin.pix.common.event.EventEnvelope;
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
 */
public class SnsEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnsEventPublisher.class);

    private static final String STRING_ATTRIBUTE = "String";

    private final SnsClient sns;
    private final String topicArn;

    public SnsEventPublisher(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Override
    public void publish(PendingOutboxEvent event) {
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

        log.debug("Publishing an outbox event to SNS | topicArn={} eventId={} eventType={} "
                        + "attributes={} body={}",
                topicArn, event.eventId(), event.eventType(), attributes.keySet(), body);

        // Any failure (throttling, network, wrong ARN) propagates: the use case must NOT mark an event
        // published unless it really went out — the whole ordering depends on this throwing.
        PublishResponse response =
                sns.publish(request -> request.topicArn(topicArn).message(body).messageAttributes(attributes));

        log.info("Outbox item published to SNS, subscriptions matching its eventType will receive it | "
                        + "eventId={} eventType={} txId={} correlationId={} topicArn={} messageId={}",
                event.eventId(), event.eventType(), event.txId(), event.correlationId(), topicArn,
                response.messageId());
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType(STRING_ATTRIBUTE).stringValue(value).build();
    }
}
