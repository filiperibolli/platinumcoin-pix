package com.platinumcoin.pix.ledger.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platinumcoin.pix.ledger.domain.AccountPolicy;
import com.platinumcoin.pix.ledger.domain.Balance;
import com.platinumcoin.pix.ledger.domain.Direction;
import com.platinumcoin.pix.ledger.domain.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.InvalidCursorException;
import com.platinumcoin.pix.ledger.domain.LedgerAccountNotFoundException;
import com.platinumcoin.pix.ledger.domain.LedgerBusyException;
import com.platinumcoin.pix.ledger.domain.LedgerEntry;
import com.platinumcoin.pix.ledger.domain.LedgerRepository;
import com.platinumcoin.pix.ledger.domain.PostingCommand;
import com.platinumcoin.pix.ledger.domain.PostingConflictException;
import com.platinumcoin.pix.ledger.domain.PostingResult;
import com.platinumcoin.pix.ledger.domain.StatementPage;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * The only place AWS SDK types touch the ledger (ADR-0010). Implements {@link LedgerRepository}
 * against table {@code pix_ledger} (docs/data-model.md §3), whose single-table layout puts the
 * balance and the whole history of an account in one partition:
 * {@code pk = ACCOUNT#<id>}, {@code sk = BALANCE} for the one balance item, {@code sk = ENTRY#<ts>#<txId>}
 * for the immutable postings, plus one {@code pk = TX#<txId>, sk = POSTING} guard item per posting.
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
 *       <i>inside</i> the posting transaction and never a read-then-check around it.</li>
 *   <li>It is unavailable on a GSI — global secondary indexes are always eventually consistent. Every
 *       read that must be fresh therefore has to be answered by the base table, which is one reason
 *       the balance lives at a known base-table key instead of behind an index — and the reason the
 *       idempotency guard below is a base-table item rather than a GSI1 lookup.</li>
 * </ul>
 * LocalStack is a single node and will happily return the right answer either way, so nothing here
 * would fail without the flag locally — which is exactly why {@code DynamoLedgerRepositoryTest}
 * asserts the flag on the request itself rather than relying on an observed value.
 *
 * <h2>Learning note — the five items of a posting (step 14)</h2>
 * A posting is one {@code TransactWriteItems}, and DynamoDB transactions are ACID and
 * all-or-nothing: if any condition fails, <b>every</b> write is cancelled. The items, in the order
 * their cancellation reasons come back:
 *
 * <ol start="0">
 *   <li>debit {@code BALANCE} — {@code attribute_exists(pk) AND balanceCents >= :amount} (the
 *       no-negative-balance guard; system accounts are exempt from the second half, never the first)</li>
 *   <li>credit {@code BALANCE} — {@code attribute_exists(pk)}, because {@code UpdateItem} is an
 *       upsert and a typo'd payee must not mint a ledger account</li>
 *   <li>debit {@code ENTRY} — {@code attribute_not_exists(pk)}</li>
 *   <li>credit {@code ENTRY} — {@code attribute_not_exists(pk)}</li>
 *   <li>{@code TX#<txId> / POSTING} — {@code attribute_not_exists(pk)}: the idempotency guard</li>
 * </ol>
 *
 * <p><b>Why the guard item exists at all</b>, when the data model already conditions the entry puts
 * on {@code attribute_not_exists}: an entry's key is {@code ENTRY#<timestamp>#<txId>}, and the
 * timestamp comes from the clock. A caller that retries after an ambiguous outcome (a timeout, a lost
 * response) sends the same {@code txId} but arrives at a new instant, so the entry keys differ, the
 * condition passes, and the payer is debited twice. Keying the guard on the {@code txId} <i>alone</i>
 * removes the clock from the identity of a posting. The 5th write is what makes "idempotent by txId"
 * true rather than nearly true (see docs/data-model.md §3).
 *
 * <p><b>Why not {@code ClientRequestToken}?</b> DynamoDB does offer transaction-level idempotency
 * through that parameter, but only for ~10 minutes and only for a byte-identical request — our
 * request carries a fresh timestamp on every retry, so it would raise
 * {@code IdempotentParameterMismatchException} exactly in the case it is meant to cover. The guard
 * item is durable and belongs to the domain, not to a client SDK window.
 */
@Repository
public class DynamoLedgerRepository implements LedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoLedgerRepository.class);

    private static final String TABLE = "pix_ledger";
    private static final String BALANCE_SK = "BALANCE";
    private static final String POSTING_SK = "POSTING";

    /** Every immutable posting leg is an {@code ENTRY#…} item; the statement is a range over them. */
    private static final String ENTRY_SK_PREFIX = "ENTRY#";

    /** Cursor JSON is tiny and structural (the DynamoDB key), so one shared, thread-safe mapper. */
    private static final ObjectMapper CURSOR_MAPPER = new ObjectMapper();

    /** Positions of the five items in the transaction, and therefore in {@code cancellationReasons}. */
    private static final int DEBIT_BALANCE = 0;
    private static final int CREDIT_BALANCE = 1;
    private static final int DEBIT_ENTRY = 2;
    private static final int CREDIT_ENTRY = 3;
    private static final int POSTING_GUARD = 4;

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final String TRANSACTION_CONFLICT = "TransactionConflict";

    /**
     * Contention budget. Three attempts with a short jittered backoff absorb the ordinary collision
     * on a hot item (every external send credits {@code SPI_CLEARING}); beyond that the caller is told
     * to come back, because turning contention into unbounded latency is how a thread pool dies.
     * The jitter matters as much as the delay: without it, everything that collided once retries in
     * the same millisecond and collides again.
     */
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MILLIS = 25L;

    /**
     * Fixed-width milliseconds, always UTC. The ENTRY sort key is {@code ENTRY#<ts>#<txId>} and its
     * chronological ordering is <i>lexicographic</i> ordering — that is the whole trick behind the
     * newest-first statement of step 16. {@code Instant.toString()} omits trailing zeros, and
     * {@code 'Z'} sorts after {@code '.'}, so {@code 10:15:30Z} would sort <b>after</b>
     * {@code 10:15:30.500Z}: the ordering would break for exactly the entries whose timestamp lands
     * on a round second.
     */
    private static final DateTimeFormatter ENTRY_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DynamoDbClient dynamo;
    private final AccountPolicy accountPolicy;

    public DynamoLedgerRepository(DynamoDbClient dynamo, AccountPolicy accountPolicy) {
        this.dynamo = dynamo;
        this.accountPolicy = accountPolicy;
    }

    @Override
    public Optional<Balance> getBalance(String accountId) {
        log.debug("DynamoDB GetItem on the ledger base table, strongly consistent so the ledger "
                        + "reads its own writes | table={} pk=ACCOUNT#{} sk={} consistentRead=true",
                TABLE, accountId, BALANCE_SK);
        Map<String, AttributeValue> item = readBalanceItem(accountId);
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
     * The double-entry posting. One request object is built once and re-sent unchanged on every
     * retry — same timestamp, therefore the same entry keys — so a retry can never become a second
     * posting even before the guard item is consulted.
     */
    @Override
    public PostingResult post(PostingCommand command, Instant postedAt) {
        String timestamp = ENTRY_TIMESTAMP.format(postedAt);
        TransactWriteItemsRequest request = transactionFor(command, timestamp);

        for (int attempt = 1; ; attempt++) {
            try {
                log.debug("DynamoDB TransactWriteItems on the ledger, five items committing atomically "
                                + "| table={} txId={} debit=ACCOUNT#{} credit=ACCOUNT#{} amountCents={} "
                                + "entrySk=ENTRY#{}#{} attempt={}",
                        TABLE, command.txId(), command.debitAccount(), command.creditAccount(),
                        command.amountCents(), timestamp, command.txId(), attempt);

                dynamo.transactWriteItems(request);

                log.debug("DynamoDB TransactWriteItems committed | txId={} attempt={}",
                        command.txId(), attempt);
                return new PostingResult(command, postedAt, false);

            } catch (TransactionCanceledException cancelled) {
                List<CancellationReason> reasons = cancelled.cancellationReasons();
                log.debug("DynamoDB cancelled the posting transaction, no item was written "
                                + "| txId={} attempt={} reasons={}",
                        command.txId(), attempt, codesOf(reasons));

                // Order matters and is a business decision: idempotency outranks everything. A
                // replayed posting that would *also* now be short of funds is still a replay — the
                // money it names moved when it first committed, and answering 422 would report a
                // payment as failed that in fact succeeded.
                PostingResult replay = replayOrNull(command, postedAt, reasons);
                if (replay != null) {
                    return replay;
                }
                failIfBalanceConditionFailed(command, reasons);
                failIfEntryAlreadyExists(command, reasons);

                if (!isTransactionConflict(reasons)) {
                    // An unmapped cancellation (throughput, item-collision, a validation error) is
                    // not a business outcome — let it surface as a 500 with its stack trace rather
                    // than be dressed up as one of ours.
                    log.error("Ledger posting cancelled for a reason this adapter does not map "
                                    + "| txId={} reasons={}", command.txId(), codesOf(reasons), cancelled);
                    throw cancelled;
                }
                if (attempt >= MAX_TRANSACTION_ATTEMPTS) {
                    log.warn("Ledger posting lost to concurrent writers on every attempt, giving up "
                                    + "and returning 503 so the caller can safely re-send the same txId "
                                    + "| txId={} attempts={}", command.txId(), attempt);
                    throw new LedgerBusyException(("The ledger is busy: posting %s conflicted with "
                            + "concurrent writers %d times.").formatted(command.txId(), attempt));
                }
                backOff(command, attempt);
            }
        }
    }

    /**
     * One page of the statement. The whole ordering trick is in the key: {@code sk} is
     * {@code ENTRY#<isoTimestamp>#<txId>}, so a {@code begins_with(sk, "ENTRY#")} range query scanned
     * backwards ({@code ScanIndexForward=false}) is already newest-first — no sort, in DynamoDB or in
     * memory. The cursor is DynamoDB's own {@code LastEvaluatedKey}, base64-wrapped so the client
     * treats it as opaque and cannot turn it into an offset (which DynamoDB has no notion of).
     */
    @Override
    public StatementPage getEntries(String accountId, String cursor, int limit) {
        Map<String, AttributeValue> startKey = decodeCursor(cursor, accountId);

        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(TABLE)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :entryPrefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                        ":entryPrefix", AttributeValue.fromS(ENTRY_SK_PREFIX)))
                .scanIndexForward(false)
                .limit(limit);
        if (startKey != null) {
            request.exclusiveStartKey(startKey);
        }

        log.debug("DynamoDB Query for a statement page, newest first | table={} pk=ACCOUNT#{} "
                        + "beginsWith={} scanIndexForward=false limit={} hasCursor={}",
                TABLE, accountId, ENTRY_SK_PREFIX, limit, startKey != null);

        QueryResponse response = dynamo.query(request.build());
        List<LedgerEntry> entries = response.items().stream().map(DynamoLedgerRepository::toEntry).toList();
        String nextCursor = encodeCursor(response.lastEvaluatedKey());

        log.debug("DynamoDB Query returned a statement page | pk=ACCOUNT#{} entries={} hasNextPage={}",
                accountId, entries.size(), nextCursor != null);
        return new StatementPage(entries, nextCursor);
    }

    // ── the pagination cursor ───────────────────────────────────────────────────────────────────
    // The cursor is the base64 of DynamoDB's LastEvaluatedKey, serialized to the same {name:{S|N:…}}
    // JSON shape DynamoDB itself uses. Keeping the type tag means the token round-trips any key
    // attribute type; keeping it opaque means the client never depends on that shape.

    /**
     * {@code null}/blank cursor ⇒ first page ({@code null} start key). Otherwise decode the token and
     * <b>refuse it unless its partition key is exactly this account</b>: the key embeds
     * {@code ACCOUNT#<id>}, so a client that edits a cursor to page someone else's history is handing
     * over a key that does not match the path — an invalid cursor (400), never another account's page.
     */
    private Map<String, AttributeValue> decodeCursor(String cursor, String accountId) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            JsonNode root = CURSOR_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new InvalidCursorException("The pagination cursor is not a valid key.");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode typed = field.getValue();
                if (typed.hasNonNull("S")) {
                    key.put(field.getKey(), AttributeValue.fromS(typed.get("S").asText()));
                } else if (typed.hasNonNull("N")) {
                    key.put(field.getKey(), AttributeValue.fromN(typed.get("N").asText()));
                } else {
                    throw new InvalidCursorException("The pagination cursor has an unrecognized attribute.");
                }
            }
        } catch (IllegalArgumentException | IOException malformed) {
            // base64 that does not decode, or bytes that are not JSON — a tampered token.
            log.warn("A pagination cursor could not be decoded, returning 400 | accountId={}", accountId);
            throw new InvalidCursorException("The pagination cursor could not be decoded.");
        }

        AttributeValue pk = key.get("pk");
        String expectedPk = "ACCOUNT#" + accountId;
        if (pk == null || pk.s() == null || !expectedPk.equals(pk.s())) {
            log.warn("A pagination cursor named a different account than the request, refusing it, "
                            + "returning 400 | requestedAccountId={} cursorPk={}",
                    accountId, pk == null ? null : pk.s());
            throw new InvalidCursorException(
                    "The pagination cursor does not belong to account " + accountId + ".");
        }
        return key;
    }

    /** {@code null} when DynamoDB returned no continuation — i.e. this was the last page. */
    private static String encodeCursor(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        ObjectNode root = CURSOR_MAPPER.createObjectNode();
        for (Map.Entry<String, AttributeValue> attribute : lastEvaluatedKey.entrySet()) {
            AttributeValue value = attribute.getValue();
            ObjectNode typed = root.putObject(attribute.getKey());
            if (value.s() != null) {
                typed.put("S", value.s());
            } else if (value.n() != null) {
                typed.put("N", value.n());
            } else {
                throw new IllegalStateException(
                        "A LastEvaluatedKey attribute is neither S nor N: " + attribute.getKey());
            }
        }
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(CURSOR_MAPPER.writeValueAsBytes(root));
        } catch (JsonProcessingException impossible) {
            // Serializing an object we just built from strings cannot fail; treat it as a bug, not a
            // client error.
            throw new IllegalStateException("Failed to encode the pagination cursor.", impossible);
        }
    }

    /**
     * Map a raw ENTRY item to the domain record. {@code amountCents} is parsed straight into a signed
     * {@code long} (DEBIT negative, CREDIT positive) — DynamoDB numbers travel as strings, so this is
     * an exact decimal parse that never passes through a {@code double}. {@code createdAt} is the
     * fixed-width millisecond timestamp the posting wrote, parsed back to an {@link Instant}.
     */
    private static LedgerEntry toEntry(Map<String, AttributeValue> item) {
        return new LedgerEntry(
                item.get("txId").s(),
                Direction.valueOf(item.get("direction").s()),
                Long.parseLong(item.get("amountCents").n()),
                item.get("counterpartAccountId").s(),
                Instant.parse(item.get("createdAt").s()),
                item.get("entryType").s());
    }

    // ── building the transaction ────────────────────────────────────────────────────────────────

    private TransactWriteItemsRequest transactionFor(PostingCommand command, String timestamp) {
        return TransactWriteItemsRequest.builder()
                .transactItems(
                        debitBalance(command, timestamp),
                        creditBalance(command, timestamp),
                        entry(command, Direction.DEBIT, timestamp),
                        entry(command, Direction.CREDIT, timestamp),
                        postingGuard(command, timestamp))
                .build();
    }

    /**
     * Item 0 — the debit, and the single most important condition in this codebase. The guard is part
     * of the write: DynamoDB evaluates {@code balanceCents >= :amount} and applies the subtraction as
     * one operation, so no concurrent posting can slip between the check and the debit. Reading the
     * balance first and deciding in Java would be the same code with a race in it.
     *
     * <p>{@code attribute_exists(pk)} is deliberately part of both branches: {@code UpdateItem}
     * creates the item when it is missing, so without it a debit of an unknown account would either
     * fail with an opaque SDK validation error or invent an account.
     */
    private TransactWriteItem debitBalance(PostingCommand command, String timestamp) {
        String condition = accountPolicy.requiresSufficientFunds(command.debitAccount())
                ? "attribute_exists(pk) AND balanceCents >= :amount"
                : "attribute_exists(pk)";
        if (!accountPolicy.requiresSufficientFunds(command.debitAccount())) {
            log.debug("Debit account is a system account, so the no-negative-balance condition is "
                            + "deliberately omitted (its balance is a position, not a wallet) | account={}",
                    command.debitAccount());
        }
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(TABLE)
                        .key(balanceKey(command.debitAccount()))
                        .updateExpression("SET balanceCents = balanceCents - :amount, "
                                + "version = version + :one, updatedAt = :now")
                        .conditionExpression(condition)
                        .expressionAttributeValues(amountValues(command, timestamp))
                        // Without the old item a failed condition is anonymous: "no such account" and
                        // "not enough money" would arrive as the same event.
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build())
                .build();
    }

    /** Item 1 — the credit. Its only condition is existence; a credit can never make a balance negative. */
    private TransactWriteItem creditBalance(PostingCommand command, String timestamp) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(TABLE)
                        .key(balanceKey(command.creditAccount()))
                        .updateExpression("SET balanceCents = balanceCents + :amount, "
                                + "version = version + :one, updatedAt = :now")
                        .conditionExpression("attribute_exists(pk)")
                        .expressionAttributeValues(amountValues(command, timestamp))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build())
                .build();
    }

    /**
     * Items 2 and 3 — the immutable legs, one in each account's own partition. The amount carries the
     * sign of its {@link Direction} (DEBIT negative, CREDIT positive), so the two legs cancel out and
     * Σ over the whole table equals Σ of the balances.
     */
    private TransactWriteItem entry(PostingCommand command, Direction direction, String timestamp) {
        boolean debit = direction == Direction.DEBIT;
        String account = debit ? command.debitAccount() : command.creditAccount();
        String counterpart = debit ? command.creditAccount() : command.debitAccount();
        long signedAmount = debit ? -command.amountCents() : command.amountCents();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("ACCOUNT#" + account));
        item.put("sk", AttributeValue.fromS("ENTRY#" + timestamp + "#" + command.txId()));
        item.put("gsi1pk", AttributeValue.fromS("TX#" + command.txId()));
        item.put("txId", AttributeValue.fromS(command.txId()));
        item.put("direction", AttributeValue.fromS(direction.name()));
        item.put("amountCents", AttributeValue.fromN(Long.toString(signedAmount)));
        item.put("counterpartAccountId", AttributeValue.fromS(counterpart));
        item.put("description", AttributeValue.fromS(command.description()));
        item.put("entryType", AttributeValue.fromS(command.entryType()));
        item.put("createdAt", AttributeValue.fromS(timestamp));

        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(TABLE)
                        .item(item)
                        // Append-only (domain safety rule 5): an entry is never overwritten, and a
                        // correction is a compensating posting.
                        .conditionExpression("attribute_not_exists(pk)")
                        .build())
                .build();
    }

    /**
     * Item 4 — the idempotency guard, keyed by {@code txId} and nothing else. It doubles as the
     * stored posting record: when the condition fails, {@code ALL_OLD} hands back the committed
     * command, so "is this the same posting?" is answered from the cancellation itself, strongly
     * consistently and with no extra read. It carries no {@code gsi1pk}, so GSI1 keeps meaning
     * exactly "the two legs of this transaction".
     */
    private TransactWriteItem postingGuard(PostingCommand command, String timestamp) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.fromS("TX#" + command.txId()));
        item.put("sk", AttributeValue.fromS(POSTING_SK));
        item.put("txId", AttributeValue.fromS(command.txId()));
        item.put("debitAccount", AttributeValue.fromS(command.debitAccount()));
        item.put("creditAccount", AttributeValue.fromS(command.creditAccount()));
        item.put("amountCents", AttributeValue.fromN(Long.toString(command.amountCents())));
        item.put("entryType", AttributeValue.fromS(command.entryType()));
        item.put("description", AttributeValue.fromS(command.description()));
        item.put("postedAt", AttributeValue.fromS(timestamp));

        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(TABLE)
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)")
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build())
                .build();
    }

    private static Map<String, AttributeValue> balanceKey(String accountId) {
        return Map.of(
                "pk", AttributeValue.fromS("ACCOUNT#" + accountId),
                "sk", AttributeValue.fromS(BALANCE_SK));
    }

    private static Map<String, AttributeValue> amountValues(PostingCommand command, String timestamp) {
        return Map.of(
                ":amount", AttributeValue.fromN(Long.toString(command.amountCents())),
                ":one", AttributeValue.fromN("1"),
                ":now", AttributeValue.fromS(timestamp));
    }

    // ── reading the cancellation reasons ────────────────────────────────────────────────────────

    /**
     * Did this posting already commit? Only the guard item can answer that, and it answers with the
     * committed command, so a replay is recognized by <i>what money moved</i> rather than by the
     * {@code txId} alone — which is what turns "the same txId with a different amount" into a 409
     * instead of a silently swallowed payment.
     *
     * @return the stored posting when this is a replay, {@code null} when the guard did not fail
     * @throws PostingConflictException the txId names different money
     */
    private PostingResult replayOrNull(PostingCommand command, Instant postedAt,
            List<CancellationReason> reasons) {
        if (!conditionFailed(reasons, POSTING_GUARD)) {
            return null;
        }
        Map<String, AttributeValue> stored = reasons.get(POSTING_GUARD).item();
        if (stored == null || stored.isEmpty()) {
            // ALL_OLD should have carried the item; if an emulator or a future SDK does not return
            // it, ask the base table directly rather than guess. A replay decided without evidence
            // would either swallow a payment or double-spend one.
            log.debug("The posting guard failed without returning the stored item, re-reading it "
                    + "strongly consistently | txId={}", command.txId());
            stored = readPostingGuardItem(command.txId());
        }
        if (stored.isEmpty()) {
            log.warn("The posting guard for this txId failed but the stored posting cannot be read, "
                            + "refusing to assume it is the same money, returning 409 | txId={}",
                    command.txId());
            throw new PostingConflictException(("Transaction id %s is already in use and its stored "
                    + "posting could not be read.").formatted(command.txId()));
        }

        PostingCommand committed = toCommand(stored);
        if (!committed.movesTheSameMoneyAs(command)) {
            log.warn("This txId already posted different money, refusing to reuse the identity, "
                            + "returning 409 | txId={} storedDebit={} storedCredit={} storedAmountCents={} "
                            + "storedEntryType={} requestedDebit={} requestedCredit={} requestedAmountCents={} "
                            + "requestedEntryType={}",
                    command.txId(), committed.debitAccount(), committed.creditAccount(),
                    committed.amountCents(), committed.entryType(), command.debitAccount(),
                    command.creditAccount(), command.amountCents(), command.entryType());
            throw new PostingConflictException(("Transaction id %s was already used for a different "
                    + "posting (%d cents %s → %s).").formatted(command.txId(), committed.amountCents(),
                    committed.debitAccount(), committed.creditAccount()));
        }

        Instant committedAt = Instant.parse(stored.get("postedAt").s());
        log.debug("The posting guard matched a committed posting with the same money, answering as an "
                        + "idempotent replay | txId={} originalPostedAt={} retryInstant={}",
                command.txId(), committedAt, postedAt);
        return new PostingResult(committed, committedAt, true);
    }

    /**
     * The debit's condition covers two facts at once, so the ALL_OLD payload is what tells them
     * apart: an item came back ⇒ the account exists and was short of money (422); nothing came back
     * ⇒ there is no such balance item (404).
     *
     * <p>The follow-up {@code getBalance} in the ambiguous branch is <b>not</b> a read-then-check:
     * the decision has already been made, atomically, by DynamoDB. This read only explains a failure
     * that already happened and can never authorize a write.
     */
    private void failIfBalanceConditionFailed(PostingCommand command, List<CancellationReason> reasons) {
        if (conditionFailed(reasons, DEBIT_BALANCE)) {
            Map<String, AttributeValue> stored = reasons.get(DEBIT_BALANCE).item();
            if (stored == null || stored.isEmpty()) {
                stored = readBalanceItem(command.debitAccount());
            }
            if (stored.isEmpty()) {
                log.warn("The debit account has no BALANCE item, so nothing was written, returning 404 "
                        + "| txId={} debitAccount={}", command.txId(), command.debitAccount());
                throw new LedgerAccountNotFoundException(
                        "No ledger account found for id " + command.debitAccount() + ".");
            }
            long available = Long.parseLong(stored.get("balanceCents").n());
            log.warn("The debit was refused inside the transaction because the balance was short, "
                            + "so no leg was written, returning 422 | txId={} debitAccount={} "
                            + "availableCents={} requestedCents={}",
                    command.txId(), command.debitAccount(), available, command.amountCents());
            throw new InsufficientFundsException(command.debitAccount(), command.amountCents(), available);
        }
        if (conditionFailed(reasons, CREDIT_BALANCE)) {
            // The credit's only condition is existence, so there is nothing else this can mean.
            log.warn("The credit account has no BALANCE item, so nothing was written, returning 404 "
                    + "| txId={} creditAccount={}", command.txId(), command.creditAccount());
            throw new LedgerAccountNotFoundException(
                    "No ledger account found for id " + command.creditAccount() + ".");
        }
    }

    /**
     * An entry for this txId exists although the guard item does not — the shape the step-12 seed
     * postings have, written before the guard existed. Refusing is the conservative reading: posting
     * anyway would append a second pair of legs under an identity the ledger has already used.
     */
    private void failIfEntryAlreadyExists(PostingCommand command, List<CancellationReason> reasons) {
        if (conditionFailed(reasons, DEBIT_ENTRY) || conditionFailed(reasons, CREDIT_ENTRY)) {
            log.warn("An entry already exists for this txId while no posting guard does, refusing to "
                            + "append a second pair of legs under the same identity, returning 409 "
                            + "| txId={} debitAccount={} creditAccount={}",
                    command.txId(), command.debitAccount(), command.creditAccount());
            throw new PostingConflictException(("Transaction id %s already has ledger entries.")
                    .formatted(command.txId()));
        }
    }

    private static boolean isTransactionConflict(List<CancellationReason> reasons) {
        return reasons.stream().anyMatch(reason -> TRANSACTION_CONFLICT.equals(reason.code()));
    }

    private static boolean conditionFailed(List<CancellationReason> reasons, int index) {
        return index < reasons.size() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    private static String codesOf(List<CancellationReason> reasons) {
        return reasons.stream().map(CancellationReason::code).toList().toString();
    }

    /**
     * Short jittered backoff. The jitter is the point: peers that collided once would otherwise all
     * retry in the same millisecond and collide again, converting a burst into a stampede.
     */
    private void backOff(PostingCommand command, int attempt) {
        long delay = BACKOFF_BASE_MILLIS * attempt + ThreadLocalRandom.current().nextLong(BACKOFF_BASE_MILLIS);
        log.warn("Ledger posting lost a race with a concurrent transaction on the same items, nothing "
                        + "was written, retrying after a jittered pause | txId={} attempt={} backoffMillis={}",
                command.txId(), attempt, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LedgerBusyException(("The ledger posting %s was interrupted while backing off "
                    + "from a transaction conflict.").formatted(command.txId()));
        }
    }

    // ── item ↔ domain ───────────────────────────────────────────────────────────────────────────

    private Map<String, AttributeValue> readBalanceItem(String accountId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(balanceKey(accountId))).item();
    }

    private Map<String, AttributeValue> readPostingGuardItem(String txId) {
        return dynamo.getItem(request -> request
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of(
                        "pk", AttributeValue.fromS("TX#" + txId),
                        "sk", AttributeValue.fromS(POSTING_SK)))).item();
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

    /** The committed posting, read back from its guard item. */
    private static PostingCommand toCommand(Map<String, AttributeValue> item) {
        return new PostingCommand(
                item.get("txId").s(),
                item.get("debitAccount").s(),
                item.get("creditAccount").s(),
                Long.parseLong(item.get("amountCents").n()),
                item.get("entryType").s(),
                item.containsKey("description") ? item.get("description").s() : "");
    }
}
