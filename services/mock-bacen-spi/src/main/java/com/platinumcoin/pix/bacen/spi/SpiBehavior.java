package com.platinumcoin.pix.bacen.spi;

import com.platinumcoin.pix.bacen.config.BacenProperties;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The dial the resilience work is tested against: how slow, how unreliable and how silent the SPI is
 * <i>right now</i>. Seeded from {@code bacen.*} at boot and mutated at runtime by
 * {@code POST /admin/config} — mutable on purpose, because a failure drill has to be armed while the
 * stack is already running and a payment is already in flight (see {@code docs/local-dev.md} §5.5).
 *
 * <p><b>Why an immutable snapshot behind an {@link AtomicReference}.</b> A settlement reads latency,
 * then rolls timeout, then rolls failure; if those three reads could interleave with an admin update,
 * a single request could act on half of one configuration and half of another — a maddening flake in
 * exactly the tests that are hardest to trust. Callers take one {@link Snapshot} and decide from it.
 *
 * <p><b>The extremes are exact, not probabilistic.</b> {@code rate <= 0} never fires and {@code rate >= 1}
 * always fires, short-circuited before any random draw. Every failure drill and every IT uses
 * {@code 0.0} or {@code 1.0}, so those two values must be deterministic guarantees rather than
 * overwhelmingly likely outcomes.
 */
@Component
public class SpiBehavior {

    private static final Logger log = LoggerFactory.getLogger(SpiBehavior.class);

    /** A coherent view of the dial: the three knobs a single settlement decides from. */
    public record Snapshot(long latencyMs, double failureRate, double timeoutRate) {
    }

    private final AtomicReference<Snapshot> current;
    private final long timeoutHangMs;

    /**
     * Creditor keys refused at settlement even when the DICT resolves them (step 35). Held apart from the
     * timing {@link Snapshot} because it is not part of the roll sequence a single settlement decides on —
     * it is a set the DICT-lookup step consults — and mutated as a whole so a lookup never sees a half-built
     * set. Normalised lowercase, like the DICT, so {@code Bob@OtherBank.com} and {@code bob@otherbank.com}
     * are the same key.
     */
    private final AtomicReference<Set<String>> rejectKeys;

    public SpiBehavior(BacenProperties properties) {
        this.current = new AtomicReference<>(new Snapshot(
                properties.latencyMs(), properties.failureRate(), properties.timeoutRate()));
        this.timeoutHangMs = properties.timeoutHangMs();
        this.rejectKeys = new AtomicReference<>(normalise(properties.rejectKeys()));
        log.info("SPI behaviour armed from configuration, this is what settlement calls will experience "
                        + "| latencyMs={} failureRate={} timeoutRate={} timeoutHangMs={} rejectKeys={}",
                properties.latencyMs(), properties.failureRate(), properties.timeoutRate(), timeoutHangMs,
                this.rejectKeys.get());
    }

    public Snapshot current() {
        return current.get();
    }

    /** The creditor keys refused at settlement right now — reported by {@code GET /admin/config}. */
    public Set<String> rejectKeys() {
        return rejectKeys.get();
    }

    /**
     * {@code true} ⇒ this creditor key must be refused at settlement even though the DICT knows it — the
     * send-reachable reversal trigger of step 35. Normalises the incoming key so the match is case- and
     * whitespace-insensitive, exactly like the DICT lookup it sits in front of.
     */
    public boolean shouldReject(String creditorKey) {
        return creditorKey != null && rejectKeys.get().contains(creditorKey.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Replace the reject-key set wholesale (a {@code null} from a partial admin update leaves it
     * unchanged, like every other dial knob). Returns the new effective set.
     */
    public Set<String> updateRejectKeys(Set<String> keys) {
        if (keys == null) {
            return rejectKeys.get();
        }
        Set<String> updated = rejectKeys.updateAndGet(now -> normalise(keys));
        log.info("SPI settlement reject-keys changed at runtime by an admin request, later settlements for "
                + "these keys will be refused even though the DICT knows them | rejectKeys={}", updated);
        return updated;
    }

    private static Set<String> normalise(Set<String> keys) {
        return keys == null ? Set.of() : keys.stream()
                .map(key -> key.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * How long a rolled timeout hangs before finally answering. Boot-time configuration rather than a
     * runtime knob: it must sit comfortably past the client's own timeout (settlement-service allows
     * 12s, step 31), and a value that can be lowered mid-drill would let a "timeout" quietly become a
     * slow success. Tests shorten it with a property so they don't have to wait 15 seconds to prove
     * the behaviour.
     */
    public long timeoutHangMs() {
        return timeoutHangMs;
    }

    /**
     * Apply a partial update — a {@code null} field leaves that knob untouched, so
     * {@code -d '{"failureRate":1.0}'} arms one drill without silently resetting the latency the
     * runbook set two commands earlier. Returns the new effective snapshot.
     */
    public Snapshot update(Long latencyMs, Double failureRate, Double timeoutRate) {
        Snapshot updated = current.updateAndGet(now -> new Snapshot(
                latencyMs == null ? now.latencyMs() : latencyMs,
                failureRate == null ? now.failureRate() : failureRate,
                timeoutRate == null ? now.timeoutRate() : timeoutRate));
        log.info("SPI behaviour changed at runtime by an admin request, later settlement calls will "
                        + "experience the new dial | latencyMs={} failureRate={} timeoutRate={}",
                updated.latencyMs(), updated.failureRate(), updated.timeoutRate());
        return updated;
    }

    /** {@code true} ⇒ answer {@code 503} and record nothing (a transient transport failure). */
    public boolean rollFailure(Snapshot snapshot) {
        return roll(snapshot.failureRate());
    }

    /** {@code true} ⇒ settle, then hang past the caller's timeout without ever answering usefully. */
    public boolean rollTimeout(Snapshot snapshot) {
        return roll(snapshot.timeoutRate());
    }

    private boolean roll(double rate) {
        if (rate <= 0.0) {
            return false;
        }
        if (rate >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    /**
     * Burn wall-clock time the way a remote rail does. Interruption is honoured (the flag is restored)
     * rather than swallowed, so a container shutdown does not have to wait out a 10-second latency.
     */
    public void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Simulated SPI wait was interrupted, answering early | requestedMillis={}", millis);
        }
    }
}
