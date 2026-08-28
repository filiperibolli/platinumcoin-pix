package com.platinumcoin.pix.labs.ledgerpg;

import com.platinumcoin.pix.labs.ledgerpg.exception.InsufficientFundsException;
import com.platinumcoin.pix.labs.ledgerpg.exception.LedgerAccountNotFoundException;
import com.platinumcoin.pix.labs.ledgerpg.exception.LedgerBusyException;
import com.platinumcoin.pix.labs.ledgerpg.exception.PostingConflictException;
import java.time.Instant;

/**
 * The one operation this lab exists to implement twice: the atomic double-entry posting, on
 * PostgreSQL. ADR-0009 calls it {@code LedgerPort}; in the deployable it is the {@code post} half of
 * {@code ledger.domain.port.LedgerRepository}.
 *
 * <h2>Why this is a mirror and not the real interface</h2>
 * ADR-0009 asks the lab to implement "the same {@code LedgerPort} interface as ledger-service". The
 * compiler cannot be made to enforce that, and the reason is worth writing down rather than working
 * around: {@code ledger-service} runs {@code spring-boot-maven-plugin:repackage}, so its published
 * artifact is a fat jar with the classes under {@code BOOT-INF/classes} — unusable as a Maven
 * dependency. Making it usable means giving the deployable a second artifact (a {@code classifier})
 * purely to satisfy a lab, which is precisely the coupling ADR-0009 forbade ("no runtime dependency
 * in either direction") and ADR-0020 §2 reaffirmed. Extracting a shared {@code ledger-domain} module
 * would work, but that is a refactor of the deployable, not a lab.
 *
 * <p>So the parity is <b>asserted, not compiled</b>: the same records, the same exception types, the
 * same replay semantics, and — the part that actually matters — the same invariant suite run against
 * both engines. A benchmark whose two sides merely share an interface name proves nothing; a
 * benchmark whose two sides pass the same tests is comparable. The interface is documentation of
 * intent; the tests are the enforcement.
 *
 * <h2>What the two implementations must agree on</h2>
 * Everything below. They differ only in <i>how</i> they serialize conflicting writers — that is the
 * single variable of the experiment, and any other difference would contaminate the comparison.
 */
public interface LedgerPort {

    /**
     * Move {@code command.amountCents()} from the debit account to the credit account as <b>one</b>
     * atomic operation: both balances and both immutable entries commit together or nothing does
     * (domain safety rule 4). Idempotent by {@code txId} — replaying a committed posting returns it
     * instead of posting it again.
     *
     * <p>{@code postedAt} is a parameter for the same reason it is one in the deployable: the
     * ledger's notion of "now" is a decision its caller owns, and passing it in is what makes the
     * stored timestamp assertable in a test instead of whatever the machine clock happened to say.
     *
     * @return the committed posting, with {@link PostingResult#replayed()} telling whether this call
     *         is the one that committed it
     * @throws InsufficientFundsException     the debtor's balance was short — nothing was written
     * @throws LedgerAccountNotFoundException one of the two accounts has no row
     * @throws PostingConflictException       the {@code txId} was already used for different money
     * @throws LedgerBusyException            lost to concurrent writers past the retry budget
     */
    PostingResult post(PostingCommand command, Instant postedAt);
}
