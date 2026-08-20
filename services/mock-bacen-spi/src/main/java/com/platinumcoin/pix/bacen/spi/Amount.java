package com.platinumcoin.pix.bacen.spi;

import java.math.BigDecimal;

/**
 * The one place a decimal BRL string becomes integer cents inside the stub (step 37) — the mirror of
 * payment-service's {@code Money}, and deliberately the same rules, because a fake that is laxer than the
 * real thing lets a bug through that the real edge would have caught.
 *
 * <p>Never through a {@code double}: {@link BigDecimal#movePointRight(int)} +
 * {@link BigDecimal#longValueExact()} is an exact base-10 shift that cannot lose or invent a cent, and a
 * third decimal place ({@code "1.005"}) throws rather than rounding someone's money.
 *
 * <p>Why the simulate endpoint takes a decimal at all, when the webhook it drives carries integer cents:
 * this one is typed by a <b>human</b> in a runbook, a Postman request or the API explorer, and
 * {@code "300.00"} is what a human means by three hundred reais. The machine-to-machine hop downstream
 * has no such reader and never sees a decimal.
 */
public final class Amount {

    private Amount() {
    }

    /**
     * @throws IllegalArgumentException if the value is not a strictly-positive monetary amount with at
     *                                  most cent precision — surfaced as {@code 400 VALIDATION_ERROR}
     */
    public static long toCents(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("Amount is required.");
        }
        long cents;
        try {
            cents = new BigDecimal(amount.trim()).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException notMoney) {
            throw new IllegalArgumentException("Amount is not a valid monetary value: " + amount);
        }
        if (cents <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive: " + amount);
        }
        return cents;
    }
}
