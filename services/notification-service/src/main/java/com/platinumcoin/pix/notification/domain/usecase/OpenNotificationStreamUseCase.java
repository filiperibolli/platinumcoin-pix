package com.platinumcoin.pix.notification.domain.usecase;

import com.platinumcoin.pix.notification.domain.model.Subscriber;
import com.platinumcoin.pix.notification.domain.port.SubscriberRegistry;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open a real-time stream for the authenticated customer.
 *
 * <p><b>Thin, and a use case anyway (ADR-0011).</b> Minting the subscription id and stamping the clock
 * are exactly the two things CLAUDE.md forbids a controller to do — generation and reading the clock
 * are policy — and {@code ls domain/usecase/} is meant to read as this service's capability list, which
 * it stops doing the moment "open a stream" is missing from it.
 *
 * @param <S> the transport handle type, supplied by whichever adapter implements the registry
 */
public class OpenNotificationStreamUseCase<S> {

    private static final Logger log = LoggerFactory.getLogger(OpenNotificationStreamUseCase.class);

    private final SubscriberRegistry<S> registry;
    private final Clock clock;

    public OpenNotificationStreamUseCase(SubscriberRegistry<S> registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public OpenStreamOutcome<S> execute(OpenNotificationStreamCommand command) {
        // A fresh id per CONNECTION, not per account: phone and laptop are two streams of one account
        // and both must receive every push (see Subscriber).
        String subscriptionId = "sub-" + UUID.randomUUID();
        var subscriber = new Subscriber(
                subscriptionId, command.userId(), command.accountId(), clock.instant());

        S stream = registry.subscribe(subscriber);

        log.info("Opened a real-time notification stream for the account in the token | "
                        + "subscriptionId={} userId={} accountId={} openedAt={}",
                subscriptionId, command.userId(), command.accountId(), subscriber.openedAt());
        return new OpenStreamOutcome<>(subscriptionId, command.accountId(), stream);
    }
}
