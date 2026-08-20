package com.platinumcoin.pix.notification.domain.usecase;

import com.platinumcoin.pix.notification.domain.port.NotificationChannel;
import com.platinumcoin.pix.notification.domain.port.ProcessedEvents;
import com.platinumcoin.pix.notification.domain.service.NotificationRouting;
import com.platinumcoin.pix.notification.domain.service.NotificationVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Take one user-facing event and put it on the right customer's screen: dedupe, route, push.
 *
 * <h2>Why the claim comes first here, unlike the inbound webhook</h2>
 * {@code ReceiveInboundPixUseCase} credits money <i>before</i> it records the delivery, because a crash
 * in between must replay rather than mark a payment handled whose money never arrived (ARCHITECTURE
 * §6.8). This use case does the opposite and claims first, and the difference is entirely about what
 * the work is worth: pushing twice is a visible defect a customer reports, while losing a push in a
 * crash window costs nothing that {@code GET /payments/{id}} does not already answer. Same mechanism,
 * opposite ordering, because the risks are opposite — which is the point worth taking away.
 *
 * <h2>Best-effort, stated plainly</h2>
 * Three of the four outcomes do no work at all, and all four ack. A duplicate is dropped, an event
 * nobody is listening for is dropped, an event this service cannot address is dropped. Only a
 * <i>broken transport</i> — an exception, not an empty registry — releases the claim and lets the
 * message come back.
 */
public class DeliverNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliverNotificationUseCase.class);

    private final ProcessedEvents processedEvents;
    private final NotificationChannel channel;

    public DeliverNotificationUseCase(ProcessedEvents processedEvents, NotificationChannel channel) {
        this.processedEvents = processedEvents;
        this.channel = channel;
    }

    public DeliverOutcome execute(DeliverNotificationCommand command) {
        log.info("Notification event received off the queue, deciding whose stream it belongs on | "
                        + "eventId={} eventType={} txId={} amountCents={}",
                command.eventId(), command.eventType(), command.txId(), command.amountCents());

        // Dedup BEFORE routing: a duplicate must cost nothing, not even a decision.
        if (!processedEvents.claim(command.eventId())) {
            log.warn("Notification event already handled by this consumer, dropping the redelivery so "
                            + "the customer is not told twice about one payment | eventId={} eventType={} txId={}",
                    command.eventId(), command.eventType(), command.txId());
            return DeliverOutcome.duplicate();
        }

        String accountId = NotificationRouting.affectedAccountId(command);
        if (accountId == null) {
            // Acked, not retried: no number of redeliveries will teach this service an event type it
            // does not know, and riding five receives into the DLQ would only delay the same verdict.
            log.warn("Notification event names no account this service can push to, acking it since no "
                            + "retry could help — check the notification-queue subscription filter | "
                            + "eventId={} eventType={} txId={} debtorAccountId={} creditorAccountId={}",
                    command.eventId(), command.eventType(), command.txId(),
                    command.debtorAccountId(), command.creditorAccountId());
            return DeliverOutcome.unroutable();
        }

        // Two policy questions, asked in order and each in exactly one place: NotificationRouting
        // answered "whose stream?", NotificationVocabulary answers "in what words?" (step 39). Routing
        // first is what makes the vocabulary's refusal of an unknown type unreachable rather than a
        // crash — an event nobody can be named for never gets described.
        var notification = NotificationVocabulary.describe(command, accountId);

        int reached;
        try {
            reached = channel.deliver(accountId, notification);
        } catch (RuntimeException e) {
            // The claim means "I am handling this". We are not — give it back, so the redelivery is real
            // work rather than being deduped away and the customer never told at all.
            processedEvents.release(command.eventId());
            log.error("Pushing a notification failed, the dedup claim was released so the redelivery is "
                            + "handled for real | eventId={} eventType={} accountId={} txId={}",
                    command.eventId(), command.eventType(), accountId, command.txId(), e);
            throw e;
        }

        if (reached == 0) {
            log.info("Nobody has a stream open for this account, dropping the push — the outcome stays "
                            + "queryable on the payment status endpoint | eventId={} eventType={} "
                            + "accountId={} txId={}",
                    command.eventId(), command.eventType(), accountId, command.txId());
            return DeliverOutcome.noSubscriber(accountId);
        }

        log.info("Notification pushed to every stream this account has open | eventId={} eventType={} "
                        + "accountId={} txId={} amountCents={} status={} counterpart={} timestamp={} "
                        + "subscribersReached={}",
                command.eventId(), command.eventType(), accountId, command.txId(),
                command.amountCents(), notification.status(), notification.counterpart(),
                notification.timestamp(), reached);
        return DeliverOutcome.delivered(accountId, reached);
    }
}
