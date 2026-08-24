package com.platinumcoin.pix.payment.support;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The arming switch for the step-69 crash scenarios: a test names a {@link CrashPoint}, and the next
 * thread to reach that point dies there.
 *
 * <h2>No production code knows this exists</h2>
 * The whole mechanism lives in {@code src/test}: this switch, plus two {@code @Primary} decorators
 * ({@link CrashingIdempotencyRepository}, {@link CrashingTransactionRepository}) that wrap the
 * <b>real</b> Dynamo repositories. Nothing in {@code src/main} carries a test hook, a "fail here" flag or
 * an if-testing branch — a platform that needs production seams to prove its recovery has already lost
 * the argument, because the seam itself becomes a thing that can be wrong in production.
 *
 * <h2>One-shot, by construction</h2>
 * {@code armAt} sets the point; the first hit clears it atomically and throws. That matters for more
 * than tidiness: a resume runs the <i>same</i> code path the crash interrupted, so a crash that re-armed
 * itself would kill the recovery too and no scenario could ever reach its assertion.
 *
 * <p>Disarmed is the default and is a pure pass-through, which is what lets these decorators sit in the
 * shared {@link PaymentTestSupport} without changing the behaviour — or the Spring context identity — of
 * every other payment IT.
 */
public class CrashInjector {

    private static final Logger log = LoggerFactory.getLogger(CrashInjector.class);

    private final AtomicReference<CrashPoint> armed = new AtomicReference<>();
    private final AtomicReference<CrashPoint> fired = new AtomicReference<>();

    /** Arm the next (and only the next) arrival at {@code point} to die there. */
    public void armAt(CrashPoint point) {
        armed.set(point);
        log.info("Crash injector armed for the next arrival at a kill point | crashPoint={} why={}",
                point, point.why());
    }

    /** Clear any pending arm and the last-fired record — an {@code @AfterEach} safety net. */
    public void disarm() {
        armed.set(null);
        fired.set(null);
    }

    /**
     * The point the last simulated death fired at, or {@code null} if none did.
     *
     * <p>A test asserts on this rather than on the HTTP status alone, and the reason is worth stating:
     * an escaping {@link Error} is wrapped by the servlet container and then rendered as a {@code 500}
     * by common-lib's generic handler, so "the request failed" is a status a genuine bug would produce
     * too. Only this says the failure was <i>the fault we injected, at the instant we chose</i>.
     */
    public CrashPoint firedAt() {
        return fired.get();
    }

    /**
     * Die if this is the armed point. Called by the decorators at the exact instant they model; a
     * disarmed injector returns immediately and the decorator delegates as if it were not there.
     */
    public void crashIfArmedAt(CrashPoint point) {
        if (armed.compareAndSet(point, null)) {
            fired.set(point);
            log.warn("Simulated process death fired at a kill point, nothing after this line runs | "
                    + "crashPoint={} why={}", point, point.why());
            throw new SimulatedCrash(point);
        }
    }
}
