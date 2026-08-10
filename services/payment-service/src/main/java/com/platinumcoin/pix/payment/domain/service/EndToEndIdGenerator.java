package com.platinumcoin.pix.payment.domain.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Mints Pix end-to-end ids in the BACEN standard shape {@code E<ISPB><yyyyMMddHHmm><random>} — a
 * fixed 32-character string: {@code 'E'} + the 8-digit participant ISPB + a 12-digit minute-precision
 * UTC timestamp + 11 alphanumeric random characters.
 *
 * <p>This id is more than a label: it becomes the <b>idempotency key toward BACEN</b> on the external
 * flow (a redelivered settlement is deduped by it, ARCHITECTURE §6.6) and the {@code gsi1} lookup key
 * for reconciliation and inbound dedup. It is generated here, at acceptance time, so it is stable for
 * the whole life of the transaction.
 *
 * <p><b>Timezone.</b> The timestamp is always UTC, from an injected {@link java.time.Clock}, so the id
 * is deterministic across environments and pinnable in tests — it is an opaque id, never shown to a
 * user, so there is no reason to tie it to a local wall clock.
 *
 * <p>Thread-safe: {@link SecureRandom} and {@link DateTimeFormatter} are both safe for concurrent use,
 * so a single generator bean serves every request.
 */
public class EndToEndIdGenerator {

    /** Minute precision, always UTC — the standard's {@code yyyyMMddHHmm} field. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC);

    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int RANDOM_LENGTH = 11;

    private final String ispb;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param ispb the 8-digit participant identifier baked into every id (PlatinumCoin's ISPB)
     * @throws IllegalArgumentException if {@code ispb} is not exactly 8 digits — a misconfigured ISPB
     *                                  would silently mint malformed ids, so it fails fast at wiring
     */
    public EndToEndIdGenerator(String ispb) {
        if (ispb == null || !ispb.matches("\\d{8}")) {
            throw new IllegalArgumentException("ISPB must be exactly 8 digits, was: " + ispb);
        }
        this.ispb = ispb;
    }

    /** Generate a fresh 32-char end-to-end id stamped with {@code now} (truncated to the minute, UTC). */
    public String generate(Instant now) {
        StringBuilder id = new StringBuilder(32)
                .append('E')
                .append(ispb)
                .append(TIMESTAMP.format(now));
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            id.append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]);
        }
        return id.toString();
    }
}
