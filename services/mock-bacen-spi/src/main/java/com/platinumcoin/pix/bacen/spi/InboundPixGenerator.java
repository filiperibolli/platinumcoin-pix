package com.platinumcoin.pix.bacen.spi;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Mints the {@code endToEndId} of a Pix the rail is <b>delivering to us</b> (step 37).
 *
 * <p>Same BACEN shape payment-service mints for an outgoing send — {@code E<ISPB><yyyyMMddHHmm><random>},
 * 32 characters — with one deliberate difference that is the whole point: the ISPB is the <b>payer's</b>
 * participant, not PlatinumCoin's. An end-to-end id names the participant that <i>originated</i> the
 * payment, so an inbound id stamped with our own ISPB would be a fiction, and a fiction that would make
 * the demo's ids indistinguishable from our own sends.
 *
 * <p>The timestamp is UTC from an injected {@link Clock}, so the id is deterministic across environments
 * and pinnable in a test.
 */
@Component
public class InboundPixGenerator {

    /** Minute precision, always UTC — the standard's {@code yyyyMMddHHmm} field. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC);

    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int RANDOM_LENGTH = 11;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public InboundPixGenerator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param payerIspb the 8-digit participant the payment comes from
     * @throws IllegalArgumentException if it is not exactly 8 digits — a malformed ISPB would mint a
     *                                  malformed id, so it fails at the edge rather than downstream
     */
    public String newEndToEndId(String payerIspb) {
        if (payerIspb == null || !payerIspb.matches("\\d{8}")) {
            throw new IllegalArgumentException("payerIspb must be exactly 8 digits, was: " + payerIspb);
        }
        StringBuilder id = new StringBuilder(32)
                .append('E')
                .append(payerIspb)
                .append(TIMESTAMP.format(clock.instant()));
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            id.append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]);
        }
        return id.toString();
    }
}
