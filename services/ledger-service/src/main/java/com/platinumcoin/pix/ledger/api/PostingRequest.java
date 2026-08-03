package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.ledger.domain.PostingCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Wire shape of a posting request. A DTO rather than the domain record itself, for the two reasons
 * CLAUDE.md allows one: it carries bean-validation annotations (a framework concern the domain must
 * not import), and it is the contract a caller is coupled to — the domain type stays free to evolve.
 *
 * <p>Bean validation here is the cheap, generic half of the check: present, non-blank, positive. The
 * decisions that need a reason a human can read — a self-posting, a non-positive amount seen next to
 * its {@code txId} — are made in the use case, which is why those rules appear in both places and
 * disagree about nothing (ADR-0011: no business policy in {@code api/}).
 *
 * <p><b>Money on the wire is integer cents.</b> This endpoint has no human audience, so unlike the
 * balance response there is no decimal string to accept: a caller that could send {@code "125.50"}
 * would be a caller that could send a float.
 *
 * @param txId          the caller's idempotency key for this posting (domain safety rule 2)
 * @param debitAccount  the account money leaves — an explicit input, never inferred by the ledger
 * @param creditAccount the account money arrives at
 * @param amountCents   positive integer cents
 * @param entryType     why the money moves ({@code PIX_INTERNAL}, {@code PIX_OUT}, …)
 * @param description   optional free text for the statement
 */
public record PostingRequest(
        @NotBlank String txId,
        @NotBlank String debitAccount,
        @NotBlank String creditAccount,
        @Positive long amountCents,
        @NotBlank String entryType,
        String description) {

    PostingCommand toCommand() {
        return new PostingCommand(txId, debitAccount, creditAccount, amountCents, entryType, description);
    }
}
