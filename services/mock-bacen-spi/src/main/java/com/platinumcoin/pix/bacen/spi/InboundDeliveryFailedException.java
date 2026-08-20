package com.platinumcoin.pix.bacen.spi;

/**
 * The rail could not hand a payment over to the participant (step 37) — either it was <b>refused
 * permanently</b> or every delivery attempt failed transiently.
 *
 * <p>The two are told apart by {@link #permanent()}, and the distinction is the retry contract read from
 * the other side: a {@code 4xx} from the participant (a bad token, an unknown key) is a decision no
 * repetition changes, so the rail stops and bounces the payment back to the payer's PSP; a {@code 5xx} or
 * a timeout leaves the outcome merely unknown, so the rail re-presents — and gives up only after its
 * attempt budget, which is what {@link #attempts()} reports.
 */
public class InboundDeliveryFailedException extends RuntimeException {

    private final boolean permanent;
    private final int attempts;
    private final Integer participantStatus;

    public InboundDeliveryFailedException(String message, boolean permanent, int attempts,
            Integer participantStatus) {
        super(message);
        this.permanent = permanent;
        this.attempts = attempts;
        this.participantStatus = participantStatus;
    }

    /** {@code true} when the participant refused with a {@code 4xx} — retrying would refuse identically. */
    public boolean permanent() {
        return permanent;
    }

    /** How many deliveries the rail made before giving up. */
    public int attempts() {
        return attempts;
    }

    /** The status the participant answered, or {@code null} when it never answered at all. */
    public Integer participantStatus() {
        return participantStatus;
    }
}
