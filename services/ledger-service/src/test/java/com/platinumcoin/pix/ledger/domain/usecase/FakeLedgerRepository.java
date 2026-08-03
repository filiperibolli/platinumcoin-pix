package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.Balance;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.PostingCommand;
import com.platinumcoin.pix.ledger.domain.PostingResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link LedgerRepository} for the use-case unit tests — a fake, not a mock: it really
 * stores balances, so "known account" and "unknown account" are produced by the same object rather
 * than by two stubbings. Everything DynamoDB-specific (ConsistentRead, item shapes, the GetItem key,
 * the transaction and its cancellation reasons) stays covered by {@code DynamoLedgerRepositoryTest}
 * and the ITs.
 *
 * <p>{@link #post} deliberately performs <b>no</b> money arithmetic and enforces no guard: the whole
 * point of this platform is that the guards live inside one DynamoDB transaction, so a fake that
 * re-implemented them would be asserting against a second, fictional ledger. It records what it was
 * asked to do, which is exactly what the use-case tests are about.
 */
final class FakeLedgerRepository implements LedgerRepository {

    private final Map<String, Balance> byAccountId = new LinkedHashMap<>();

    private PostingCommand lastCommand;
    private Instant lastPostedAt;
    private int postCount;
    private boolean replayNext;

    FakeLedgerRepository(Balance... seeded) {
        for (Balance balance : seeded) {
            byAccountId.put(balance.accountId(), balance);
        }
    }

    @Override
    public Optional<Balance> getBalance(String accountId) {
        return Optional.ofNullable(byAccountId.get(accountId));
    }

    @Override
    public PostingResult post(PostingCommand command, Instant postedAt) {
        this.lastCommand = command;
        this.lastPostedAt = postedAt;
        this.postCount++;
        boolean replayed = replayNext;
        replayNext = false;
        return new PostingResult(command, postedAt, replayed);
    }

    /** Make the next {@link #post} answer as the idempotent replay of an earlier posting. */
    void replayNextPost() {
        this.replayNext = true;
    }

    PostingCommand lastCommand() {
        return lastCommand;
    }

    Instant lastPostedAt() {
        return lastPostedAt;
    }

    int postCount() {
        return postCount;
    }
}
