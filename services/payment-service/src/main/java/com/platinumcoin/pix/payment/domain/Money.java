package com.platinumcoin.pix.payment.domain;

import java.math.BigDecimal;

/**
 * The one place a decimal BRL string becomes integer cents. Money is a {@code long} of cents
 * everywhere inside the platform (CLAUDE.md); the conversion happens exactly here, at the boundary,
 * and <b>never through a {@code double}</b> — {@link BigDecimal#movePointRight(int)} +
 * {@link BigDecimal#longValueExact()} is an exact base-10 shift that cannot silently lose or invent a
 * cent.
 *
 * <p>Two rules the wire {@code @Pattern} cannot fully carry live here, so the value is trustworthy by
 * the time a use case holds it:
 * <ul>
 *   <li><b>Strictly positive</b> — {@code "0.00"} and any negative are refused (the pattern alone
 *       accepts {@code "0.00"}; see docs/api/openapi.yaml).</li>
 *   <li><b>No sub-cent</b> — a third decimal place ({@code "1.005"}) has a non-zero remainder after
 *       the shift, so {@code longValueExact()} throws rather than rounding money.</li>
 * </ul>
 */
public final class Money {

    private Money() {
    }

    /**
     * Parse a decimal amount string to strictly-positive integer cents.
     *
     * @throws InvalidAmountException if the value is null/blank, not a number, has sub-cent precision,
     *                                or is not strictly positive
     */
    public static long toCents(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new InvalidAmountException("Amount is required.");
        }
        long cents;
        try {
            cents = new BigDecimal(amount.trim()).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException notMoney) {
            // Non-numeric, or more precision than a cent — either way it is not a monetary amount.
            throw new InvalidAmountException("Amount is not a valid monetary value: " + amount);
        }
        if (cents <= 0) {
            throw new InvalidAmountException("Amount must be strictly positive: " + amount);
        }
        return cents;
    }
}
