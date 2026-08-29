package com.platinumcoin.pix.payment.domain.model;

import com.platinumcoin.pix.payment.domain.exception.InvalidExportRangeException;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The month range of a cold-statement export (step 53) — the value object that owns every rule a range
 * can break on its own, so the use case is left with only the two rules that need a fact from outside
 * (when the account was opened, and where the hot window ends).
 *
 * <p>The pair of predicates at the bottom is the interesting part. Both are stated <b>against a month
 * handed in</b> rather than against a clock this record reads, which is what keeps the boundary
 * arithmetic — the thing that is genuinely easy to get wrong by one month — a plain-Java test rather
 * than something only an integration test can reach.
 */
class MonthRangeTest {

    @Test
    void parsesAValidRangeAndEnumeratesEveryMonthInItInclusively() {
        MonthRange range = MonthRange.parse("2025-01", "2025-03");

        assertThat(range.from()).isEqualTo(YearMonth.of(2025, 1));
        assertThat(range.to()).isEqualTo(YearMonth.of(2025, 3));
        assertThat(range.months())
                .containsExactly(YearMonth.of(2025, 1), YearMonth.of(2025, 2), YearMonth.of(2025, 3));
    }

    @Test
    void aSingleMonthRangeIsOneMonthNotZero() {
        assertThat(MonthRange.parse("2025-07", "2025-07").months())
                .containsExactly(YearMonth.of(2025, 7));
    }

    @Test
    void refusesAnInvertedRange() {
        assertThatThrownBy(() -> MonthRange.parse("2025-03", "2025-01"))
                .isInstanceOf(InvalidExportRangeException.class)
                .hasMessageContaining("2025-03")
                .hasMessageContaining("2025-01");
    }

    @Test
    void acceptsExactlyTwentyFourMonthsAndRefusesTwentyFive() {
        // The boundary is inclusive on both ends, so 2024-01..2025-12 is 24 months, not 23.
        assertThat(MonthRange.parse("2024-01", "2025-12").months()).hasSize(24);

        assertThatThrownBy(() -> MonthRange.parse("2024-01", "2026-01"))
                .isInstanceOf(InvalidExportRangeException.class)
                .hasMessageContaining("24");
    }

    @Test
    void refusesAMonthThatIsNotAMonth() {
        List.of("2025-13", "202501", "2025-1", "", "not-a-month")
                .forEach(bad -> assertThatThrownBy(() -> MonthRange.parse(bad, "2025-12"))
                        .as("fromMonth=%s", bad)
                        .isInstanceOf(InvalidExportRangeException.class));
    }

    @Test
    void refusesANullMonth() {
        assertThatThrownBy(() -> MonthRange.parse(null, "2025-12"))
                .isInstanceOf(InvalidExportRangeException.class);
        assertThatThrownBy(() -> MonthRange.parse("2025-01", null))
                .isInstanceOf(InvalidExportRangeException.class);
    }

    @Test
    void knowsWhenItReachesBackBeforeTheAccountExisted() {
        MonthRange range = MonthRange.parse("2025-01", "2025-06");

        assertThat(range.startsBefore(YearMonth.of(2025, 2))).isTrue();
        // The opening month itself is in range: an account opened mid-month still has movement that
        // month, so refusing it would hide real history.
        assertThat(range.startsBefore(YearMonth.of(2025, 1))).isFalse();
        assertThat(range.startsBefore(YearMonth.of(2024, 12))).isFalse();
    }

    @Test
    void isEntirelyHotOnlyWhenEvenItsOldestMonthHasNoColdData() {
        // newestColdMonth is the most recent month the archive can hold anything for.
        MonthRange hot = MonthRange.parse("2026-07", "2026-08");
        assertThat(hot.isEntirelyHot(YearMonth.of(2026, 5))).isTrue();

        // Straddling the boundary is NOT entirely hot: the cold part is exactly what an export is for.
        MonthRange straddling = MonthRange.parse("2026-05", "2026-08");
        assertThat(straddling.isEntirelyHot(YearMonth.of(2026, 5))).isFalse();

        MonthRange cold = MonthRange.parse("2025-01", "2025-03");
        assertThat(cold.isEntirelyHot(YearMonth.of(2026, 5))).isFalse();
    }
}
