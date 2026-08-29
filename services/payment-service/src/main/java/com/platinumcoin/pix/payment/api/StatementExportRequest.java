package com.platinumcoin.pix.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /v1/accounts/me/statement/exports} (step 53).
 *
 * <p>Only the wire <i>shape</i> is validated here (ADR-0011): two present, well-formed {@code yyyy-MM}
 * strings. Everything that makes a range acceptable or not — the order of the two, the 24-month bound,
 * the account's opening date, the hot/cold boundary — is business policy and lives in the use case,
 * where the facts it needs (the account, the ledger's window) are available and where a plain-Java test
 * can pin it.
 *
 * <p>The pattern is deliberately loose about the month number ({@code \d{2}} accepts {@code 13}): a
 * bean-validation message cannot say which of the two fields was wrong in the platform's own error
 * vocabulary, so an impossible month is caught in {@code MonthRange} and answered as
 * {@code 422 INVALID_EXPORT_RANGE} alongside every other range mistake, instead of as a
 * {@code 400 VALIDATION_ERROR} that a client would have to handle differently for no reason.
 *
 * <p><b>Note what is absent: no account field.</b> Like {@link SendPixRequest}, the account is the
 * JWT's and is inexpressible on the wire (Domain Safety Rule #1) — a customer can only ever export
 * their own history.
 *
 * @param fromMonth first month of the range, inclusive
 * @param toMonth   last month of the range, inclusive
 */
public record StatementExportRequest(
        @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}$") String fromMonth,
        @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}$") String toMonth) {
}
