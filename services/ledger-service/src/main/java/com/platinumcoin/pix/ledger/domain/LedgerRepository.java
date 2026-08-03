package com.platinumcoin.pix.ledger.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port for the ledger table (ADR-0010: the domain declares the interface, {@code infra/}
 * implements it against DynamoDB). Two access patterns so far:
 *
 * <ul>
 *   <li>{@link #getBalance(String)} — the BALANCE item of one account. The adapter reads it
 *       <b>strongly consistently</b>, because the ledger must read its own writes.</li>
 *   <li>{@link #post(PostingCommand, Instant)} — the double-entry posting, one atomic
 *       {@code TransactWriteItems}.</li>
 * </ul>
 *
 * <p>It returns {@link Optional} rather than throwing on a lookup miss: "no BALANCE item for this
 * account" is an ordinary empty result at this level. Turning it into
 * {@link LedgerAccountNotFoundException} is the use case's decision, and turning that into
 * {@code 404} is the edge's — the same three-layer split account-service uses, and the reason the
 * domain never imports {@code HttpStatus}.
 *
 * <p>The port grows with the flows: the paginated statement in step 16.
 */
public interface LedgerRepository {

    /** The balance of {@code accountId}, or empty if the account has no BALANCE item. */
    Optional<Balance> getBalance(String accountId);

    /**
     * Move {@code command.amountCents()} from the debit account to the credit account as <b>one</b>
     * atomic operation: both balances and both immutable entries commit together or nothing does
     * (domain safety rule 4). Idempotent by {@code txId} — replaying a committed posting returns it
     * instead of posting it again.
     *
     * <p>The instant is a parameter rather than something the adapter reads, because the ledger's
     * notion of "now" is a business decision the use case owns (ADR-0011) and because it becomes part
     * of an ENTRY sort key: passing it in is what makes that key assertable in a test instead of
     * whatever the machine clock happened to say.
     *
     * @param postedAt when the posting is considered to have happened, truncated to milliseconds by
     *                 the use case — the ENTRY sort keys carry exactly this value
     * @return the committed posting, with {@link PostingResult#replayed()} telling whether this call
     *         is the one that committed it
     * @throws InsufficientFundsException     the debtor's balance was short — nothing was written
     * @throws LedgerAccountNotFoundException one of the two accounts has no BALANCE item
     * @throws PostingConflictException       the {@code txId} was already used for different money
     * @throws LedgerBusyException            lost to concurrent writers past the retry budget
     */
    PostingResult post(PostingCommand command, Instant postedAt);
}
