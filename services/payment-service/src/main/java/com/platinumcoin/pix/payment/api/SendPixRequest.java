package com.platinumcoin.pix.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /v1/payments/pix}. Only the wire <i>shape</i> is validated here
 * (ADR-0011): the destination key is present, the amount is a bounded two-decimal string, the
 * description is within its length. The strictly-positive money rule the pattern cannot express is
 * enforced in the domain ({@code Money.toCents}), and the amount is parsed to cents in the use case.
 *
 * <p><b>Note what is absent: no source-account field.</b> The debited account is derived exclusively
 * from the JWT {@code accountId} claim (Domain Safety Rule #1); the safest way to enforce "never from
 * the payload" is to make it inexpressible on the wire — a client cannot name a debtor here even by
 * accident, and an extra JSON field is silently ignored, never bound.
 *
 * @param pixKey      destination Pix key (CPF, e-mail, phone or EVP) — required
 * @param amount      decimal BRL string, at most 9 integer digits and exactly 2 decimals; the pattern
 *                    keeps the value comfortably inside 64-bit integer cents
 * @param description free-text note, ≤ 140 chars, optional
 */
public record SendPixRequest(
        @NotBlank String pixKey,
        @NotBlank @Pattern(regexp = "^\\d{1,9}\\.\\d{2}$") String amount,
        @Size(max = 140) String description) {
}
