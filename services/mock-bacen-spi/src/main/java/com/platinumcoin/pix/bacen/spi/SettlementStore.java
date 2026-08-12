package com.platinumcoin.pix.bacen.spi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The SPI's memory: at most one terminal {@link Settlement} per {@code endToEndId}. This is the whole
 * mechanism behind "retrying a settlement after a timeout is safe" — the property the real SPI provides
 * and the one Sprint 7 leans on entirely.
 *
 * <p><b>Why the register-once shape and not check-then-put.</b> Two concurrent {@code POST}s with the
 * same {@code endToEndId} (a redelivered SQS message racing its original is exactly that) must produce
 * <i>one</i> settlement, and a {@code containsKey} followed by a {@code put} cannot promise it.
 * {@link #register} therefore computes the outcome inside {@link ConcurrentHashMap#computeIfAbsent},
 * so the loser of the race receives the winner's record and reports {@code replayed=true}.
 *
 * <p><b>What is deliberately outside this class.</b> The injected latency is slept <i>before</i>
 * calling {@link #register}, never inside the mapping function: {@code computeIfAbsent} holds the
 * bin's lock for the duration of the lambda, so a 2s sleep in there would serialise unrelated
 * {@code endToEndId}s that happen to share a bin — a self-inflicted bottleneck under the step-47 load
 * profiles. The mapping function stays fast and pure.
 *
 * <p>In-memory on purpose: a restart wipes BACEN's memory, which turns "the SPI forgot a settlement we
 * believe happened" into a drill we can run rather than a scenario we can only argue about.
 */
@Component
public class SettlementStore {

    private static final Logger log = LoggerFactory.getLogger(SettlementStore.class);

    private final Map<String, Settlement> settlements = new ConcurrentHashMap<>();

    /**
     * The outcome of a registration attempt. {@code replayed} distinguishes "I decided this now" from
     * "this {@code endToEndId} was already terminal", which is the difference the caller logs and the
     * tests assert on — the HTTP response is identical either way, and that identity is the point.
     */
    public record Registration(Settlement settlement, boolean replayed) {
    }

    /**
     * Register {@code outcome} for {@code endToEndId}, or hand back whatever is already recorded.
     * {@code outcome} is only invoked when the id is new.
     */
    public Registration register(String endToEndId, Supplier<Settlement> outcome) {
        var created = new AtomicBoolean(false);
        Settlement settlement = settlements.computeIfAbsent(endToEndId, id -> {
            created.set(true);
            return outcome.get();
        });
        if (!created.get()) {
            log.info("SPI already holds a terminal outcome for this endToEndId, replaying it instead of "
                            + "settling again | endToEndId={} status={} amountCents={} recordedAt={}",
                    endToEndId, settlement.status(), settlement.amountCents(), settlement.recordedAt());
        }
        return new Registration(settlement, !created.get());
    }

    /** What the SPI knows about this id, if anything. An empty result means {@code UNKNOWN}. */
    public Optional<Settlement> find(String endToEndId) {
        Optional<Settlement> found = Optional.ofNullable(settlements.get(endToEndId));
        log.debug("Looked up a settlement in the SPI store | endToEndId={} found={} storedCount={}",
                endToEndId, found.isPresent(), settlements.size());
        return found;
    }
}
