package com.platinumcoin.pix.payment.domain;

import com.platinumcoin.pix.payment.domain.exception.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.model.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The money invariant, tested explicitly (CLAUDE.md): a decimal string becomes exact integer cents,
 * or it is refused — never a rounded {@code double}, never a non-positive amount.
 */
class MoneyTest {

    @Test
    void parsesADecimalStringToExactCents() {
        assertThat(Money.toCents("125.50")).isEqualTo(12550L);
        assertThat(Money.toCents("0.01")).isEqualTo(1L);
        assertThat(Money.toCents("1.00")).isEqualTo(100L);
    }

    @Test
    void keepsTheLargestContractValueExactWithoutAnyDouble() {
        // Nine integer digits + two decimals is the pattern's ceiling; the value must survive as an
        // exact long, which a double could not represent.
        assertThat(Money.toCents("999999999.99")).isEqualTo(99999999999L);
    }

    @Test
    void refusesZeroBecauseAmountMustBeStrictlyPositive() {
        // The wire pattern accepts "0.00"; this strictly-positive rule is what it cannot express.
        assertThatThrownBy(() -> Money.toCents("0.00"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void refusesANegativeAmount() {
        assertThatThrownBy(() -> Money.toCents("-1.00"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void refusesSubCentPrecisionRatherThanRoundingMoney() {
        assertThatThrownBy(() -> Money.toCents("1.005"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void refusesANonNumericOrBlankAmount() {
        assertThatThrownBy(() -> Money.toCents("abc")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.toCents("  ")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.toCents(null)).isInstanceOf(InvalidAmountException.class);
    }
}
