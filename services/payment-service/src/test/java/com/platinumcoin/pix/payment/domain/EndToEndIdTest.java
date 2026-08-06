package com.platinumcoin.pix.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The Pix end-to-end id format {@code E<ISPB(8)><yyyyMMddHHmm-UTC(12)><random(11)>} — a fixed
 * 32 characters. This id later becomes the idempotency key toward BACEN, so its shape is a contract,
 * not a cosmetic detail.
 */
class EndToEndIdTest {

    private static final String ISPB = "12345678";
    // 2026-07-02T12:34:56Z — minute precision keeps only "202607021234".
    private static final Instant NOW = Instant.parse("2026-07-02T12:34:56Z");

    private final EndToEndIdGenerator generator = new EndToEndIdGenerator(ISPB);

    @Test
    void hasTheStandardShapeAndFixedLength() {
        String id = generator.generate(NOW);

        assertThat(id).hasSize(32);
        assertThat(id).matches("^E\\d{8}\\d{12}[A-Za-z0-9]{11}$");
    }

    @Test
    void embedsTheIspbAndTheUtcMinuteTimestamp() {
        String id = generator.generate(NOW);

        assertThat(id).startsWith("E" + ISPB);
        // 'E'(1) + ISPB(8) = index 9, then the 12-digit yyyyMMddHHmm.
        assertThat(id.substring(9, 21)).isEqualTo("202607021234");
    }

    @Test
    void mintsADifferentRandomSuffixEachTime() {
        // Same instant, different ids — the 11-char suffix is random, so two calls never collide.
        assertThat(generator.generate(NOW)).isNotEqualTo(generator.generate(NOW));
    }

    @Test
    void rejectsAMisconfiguredIspbAtConstructionSoItFailsFast() {
        assertThatThrownBy(() -> new EndToEndIdGenerator("123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndToEndIdGenerator("abcdefgh"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
