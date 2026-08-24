package com.platinumcoin.pix.payment.support;

/**
 * The simulated process death of the step-69 recovery scenarios.
 *
 * <h2>Why an {@link Error} and not an exception</h2>
 * The send path catches a great deal on purpose — {@code InsufficientFundsException} to unwind the
 * daily-limit reservation, {@code TransactionWriteConflictException} to recognise its own earlier
 * attempt, {@code RuntimeException} inside the advisory phase write which is contractually forbidden to
 * fail the request. Every one of those handlers is correct, and every one of them would <b>absorb</b> a
 * test crash thrown as an exception, turning "this process died" into "this process handled something",
 * which is the opposite of the scenario. An {@code Error} passes through all of them untouched, which
 * is the closest a single JVM can get to a {@code SIGKILL} at a chosen instruction.
 *
 * <p>It is also why the tests assert on the <i>root cause</i>: the servlet container wraps whatever
 * escapes a handler, but nothing in the application layer gets to reinterpret this.
 */
public class SimulatedCrash extends Error {

    public SimulatedCrash(CrashPoint point) {
        super("simulated process death at " + point + " — " + point.why());
    }
}
