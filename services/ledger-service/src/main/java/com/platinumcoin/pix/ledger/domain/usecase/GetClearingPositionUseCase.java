package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.common.ledger.ClearingAccountResolver;
import com.platinumcoin.pix.ledger.domain.model.ClearingPosition;
import com.platinumcoin.pix.ledger.domain.port.LedgerRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read the platform's clearing position — the sum over every clearing sub-account (step 52, task 3).
 *
 * <h2>Why this is a use case and not "just another balance read"</h2>
 * Before sharding, "how much money is in flight?" was {@code GET .../SPI_CLEARING/balance}: one item,
 * one read, no policy. Sharding makes the answer a <i>derived</i> quantity — sixteen reads and a sum —
 * and the moment an answer is derived, someone has to own the rules for deriving it: which accounts
 * count, what happens when one of them is missing, and whether the caller is told. Leaving that in a
 * controller (or worse, in each caller) is how three services end up with three slightly different
 * ideas of what the clearing balance is.
 *
 * <h2>Not strongly consistent as a whole</h2>
 * Each individual read is strongly consistent, but the sixteen of them are not one snapshot: a posting
 * can commit between the fourth and the fifth. The total is therefore a <b>point-in-time estimate</b>,
 * exactly like the un-sharded read always was under concurrent traffic — and it is honest about it here
 * rather than in the caller's head. Nothing in the platform makes a money decision from this number;
 * reconciliation and the operator use it to <i>notice</i>, and the per-shard breakdown is what turns
 * "the total looks odd" into "shard 07 is the odd one".
 */
public class GetClearingPositionUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetClearingPositionUseCase.class);

    private final LedgerRepository ledger;
    private final ClearingAccountResolver clearing;

    public GetClearingPositionUseCase(LedgerRepository ledger, ClearingAccountResolver clearing) {
        this.ledger = ledger;
        this.clearing = clearing;
    }

    public ClearingPosition execute() {
        var accounts = clearing.clearingAccounts();
        log.info("Clearing position requested, summing every clearing sub-account | shardCount={} "
                + "accountsToRead={}", clearing.shardCount(), accounts.size());

        var shards = new ArrayList<ClearingPosition.ShardBalance>(accounts.size());
        var missing = new ArrayList<String>();
        long total = 0L;

        for (String accountId : accounts) {
            var balance = ledger.getBalance(accountId);
            if (balance.isEmpty()) {
                // Not an error here (see ClearingPosition's javadoc) but it IS a misconfiguration, so
                // it is a WARN with the account named: the platform's own clearing figure is short.
                log.warn("A configured clearing account has no BALANCE item, the position below is "
                        + "missing whatever it holds | accountId={} shardCount={}",
                        accountId, clearing.shardCount());
                missing.add(accountId);
                continue;
            }
            total += balance.get().balanceCents();
            shards.add(new ClearingPosition.ShardBalance(
                    accountId, balance.get().balanceCents(), balance.get().version()));
        }

        log.info("Clearing position resolved by summing the shards | totalCents={} accountsRead={} "
                + "accountsMissing={}", total, shards.size(), missing.size());
        return new ClearingPosition(total, List.copyOf(shards), List.copyOf(missing));
    }
}
