package com.platinumcoin.pix.payment.domain.model;

import java.time.Instant;

/**
 * One line of the cold statement archive, as payment-service reads it back (step 53).
 *
 * <h2>Why payment-service has its own record for it</h2>
 * ledger-service writes {@code ArchivedEntry} into {@code account=<id>/yyyy-MM.jsonl} (step 43), and
 * this record is deliberately a <b>separate</b> declaration of the same shape rather than a shared
 * class. The two services own different tables and different artifacts (ADR-0006), and the archive
 * object is the contract between them — a file format, not a Java type. Sharing the class would make a
 * five-year-old file's readability depend on a jar version, which is the opposite of what a cold
 * archive is for: the reader must be able to parse an object written by a build that no longer exists.
 * The cost is honest and stated: a field renamed on the writing side is caught by
 * {@code StatementExportWorkerIT}, not by the compiler.
 *
 * <p>Money is <b>signed integer cents</b> (DEBIT negative, CREDIT positive), exactly as the archive
 * stores it. The decimal string a customer reads is produced only when the CSV is rendered
 * ({@link com.platinumcoin.pix.payment.domain.service.StatementCsv}), which is this flow's API edge.
 *
 * @param accountId            whose statement this line belongs to — carried in the file, so an object
 *                             read on its own can say whose it is
 * @param txId                 the transaction that produced the entry
 * @param direction            DEBIT or CREDIT
 * @param amountCents          signed integer cents
 * @param counterpartAccountId the other leg's account
 * @param timestamp            when the entry was posted
 * @param entryType            the ledger's movement vocabulary ({@code PIX_OUT}, {@code PIX_IN}, …)
 * @param description          free text; may be {@code null}
 */
public record ArchivedStatementLine(
        String accountId,
        String txId,
        Direction direction,
        long amountCents,
        String counterpartAccountId,
        Instant timestamp,
        String entryType,
        String description) {
}
