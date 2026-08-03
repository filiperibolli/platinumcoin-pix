package com.platinumcoin.pix.ledger.infra;

import com.platinumcoin.pix.ledger.domain.Balance;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The only place AWS SDK types touch the ledger (ADR-0010). Implements {@link LedgerRepository}
 * against table {@code pix_ledger} (docs/data-model.md §3), whose single-table layout puts the
 * balance and the whole history of an account in one partition:
 * {@code pk = ACCOUNT#<id>}, {@code sk = BALANCE} for the one balance item, {@code sk = ENTRY#<ts>#<txId>}
 * for the immutable postings.
 *
 * <h2>Learning note — why {@code ConsistentRead=true} here</h2>
 * DynamoDB reads are <b>eventually consistent by default</b>: a read may be served by a replica that
 * has not yet caught up with a write committed a moment ago, and the default is not an accident —
 * an eventually-consistent read costs half the capacity units of a strongly-consistent one.
 *
 * <p>For a ledger that trade is not available. This service must <b>read its own writes</b>: the
 * balance is queried right after a posting (a client refreshing after a transfer, the reconciliation
 * job checking {@code SPI_CLEARING}, the cache-aside reload of step 40 repopulating Redis after an
 * invalidation). A stale read there does not merely look odd — it shows money that has already been
 * spent, and if anything downstream ever decided on that number it would decide wrongly.
 *
 * <p>Two boundaries of what the flag actually buys, worth knowing before trusting it too far:
 * <ul>
 *   <li>It is a guarantee about <i>this</i> read, not a lock. Between this read and any later write
 *       the balance can change; that is precisely why the no-negative-balance rule is a condition
 *       <i>inside</i> the posting transaction (step 14) and never a read-then-check around it.</li>
 *   <li>It is unavailable on a GSI — global secondary indexes are always eventually consistent. Every
 *       read that must be fresh therefore has to be answered by the base table, which is one reason
 *       the balance lives at a known base-table key instead of behind an index.</li>
 * </ul>
 * LocalStack is a single node and will happily return the right answer either way, so nothing here
 * would fail without the flag locally — which is exactly why {@code DynamoLedgerRepositoryTest}
 * asserts the flag on the request itself rather than relying on an observed value.
 */
@Repository
public class DynamoLedgerRepository implements LedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoLedgerRepository.class);

    private static final String TABLE = "pix_ledger";
    private static final String BALANCE_SK = "BALANCE";

    private final DynamoDbClient dynamo;

    public DynamoLedgerRepository(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public Optional<Balance> getBalance(String accountId) {
        log.debug("DynamoDB GetItem on the ledger base table, strongly consistent so the ledger "
                        + "reads its own writes | table={} pk=ACCOUNT#{} sk={} consistentRead=true",
                TABLE, accountId, BALANCE_SK);
        Map<String, AttributeValue> item = dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        "sk", AttributeValue.fromS(BALANCE_SK)))).item();
        if (item.isEmpty()) {
            log.debug("DynamoDB GetItem found no BALANCE item for this account | pk=ACCOUNT#{} sk={}",
                    accountId, BALANCE_SK);
            return Optional.empty();
        }
        Balance balance = toBalance(accountId, item);
        // The record's toString prints every field — the raw balance as stored (ADR-0012).
        log.debug("DynamoDB GetItem returned the balance | balance={}", balance);
        return Optional.of(balance);
    }

    /**
     * Map the raw item to the domain record. {@code balanceCents} is parsed straight into a
     * {@code long}: DynamoDB numbers travel as strings on the wire, so this is an exact decimal
     * parse — the value never passes through a {@code double} where it could lose a cent.
     *
     * <p>The account id comes from the argument rather than from the item: it is encoded in the
     * partition key ({@code ACCOUNT#<id>}), and re-deriving it by string-splitting {@code pk} would
     * be a second, weaker source of truth for the same fact.
     */
    private static Balance toBalance(String accountId, Map<String, AttributeValue> item) {
        return new Balance(
                accountId,
                Long.parseLong(item.get("balanceCents").n()),
                Long.parseLong(item.get("version").n()));
    }
}
