package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.common.event.OutboxEvent;
import com.platinumcoin.pix.common.web.CorrelationId;
import com.platinumcoin.pix.settlement.domain.exception.InboundAlreadyRecordedException;
import com.platinumcoin.pix.settlement.domain.exception.InboundKeyNotFoundException;
import com.platinumcoin.pix.settlement.domain.exception.InvalidWebhookTokenException;
import com.platinumcoin.pix.settlement.domain.model.InboundTransaction;
import com.platinumcoin.pix.settlement.domain.port.InboundTransactionStore;
import com.platinumcoin.pix.settlement.domain.port.LedgerClient;
import com.platinumcoin.pix.settlement.domain.port.PixKeyResolver;
import com.platinumcoin.pix.settlement.domain.service.LedgerOutcomes;
import com.platinumcoin.pix.settlement.domain.service.SettlementOutboxEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Take one Pix delivered to us by the rail (step 37) — settlement-service's second capability (ADR-0011),
 * and the receiving half of the platform's money path.
 *
 * <h2>Receiving is the mirror of sending</h2>
 * An outbound send debits the payer and credits the clearing account; an inbound one debits clearing and
 * credits the payee. Same double entry, same clearing account standing in for "the rest of the Pix network"
 * on our books, opposite direction. There is no new money-movement concept here at all — which is the point
 * worth noticing: if receiving had needed a different mechanism, the ledger would have been modelled wrong.
 *
 * <h2>The sequence, and why it is in this order</h2>
 * <ol>
 *   <li><b>The webhook token, before literally anything else.</b> This endpoint credits money and is
 *       JWT-exempt (BACEN holds no PlatinumCoin token), so the shared secret is the only thing between an
 *       arbitrary local process and freshly minted spendable balance (threat model, boundary B4). Checking
 *       it first also means a forged call touches no directory and no ledger — it is refused before it can
 *       cost anything or reveal, by timing or by side effect, whether the key it named exists.</li>
 *   <li><b>Resolve the key to an account of <i>ours</i>.</b> The payee is whatever our directory says the
 *       key belongs to, never an account id the caller supplies — the inbound mirror of Domain Safety
 *       Rule #1. An unknown key is refused <i>before</i> any posting, so a bounced payment leaves no
 *       trace on the ledger.</li>
 *   <li><b>Credit the payee</b>, keyed by the deterministic {@code in-<endToEndId>}.</li>
 *   <li><b>Record the transaction + {@code PixReceived}</b> in one conditional, atomic write.</li>
 * </ol>
 *
 * <h2>Why the posting comes before the dedupe, and why that is the safe order</h2>
 * The instinct is to dedupe first — claim the {@code endToEndId}, then do the work. That is the right shape
 * when the work has a <b>non-idempotent external effect</b>, which is exactly why {@link SettlePixUseCase}
 * claims its {@code eventId} before touching BACEN: asking the rail twice is a real second event. Here the
 * work is a ledger posting that is idempotent by {@code txId} (Domain Safety Rule #2), so a second attempt
 * cannot double-credit — and claiming first would introduce a failure mode that does not otherwise exist:
 * a crash between the claim and the posting would leave an {@code endToEndId} marked "handled" whose money
 * never arrived, and every redelivery would be politely refused by our own guard. The payment would be lost
 * silently, which is the worst outcome available.
 *
 * <p>Posting first inverts the residual risk into a harmless one. A crash before the record leaves a
 * committed credit and no transaction row; the redelivery replays the posting as a no-op and completes the
 * record. Both writes are guarded, so the only thing repetition costs is a wasted directory lookup.
 *
 * <p>The conditional write itself is the dedupe: {@code attribute_not_exists(pk)} on {@code TX#in-<e2e>}.
 * The alternative — querying {@code gsi1} for the {@code endToEndId} and writing if absent — is a
 * read-then-check over an <b>eventually consistent</b> index, i.e. no guard at all under the concurrency it
 * exists to survive.
 *
 * <p>Plain Java, no Spring and no AWS type (ADR-0010/0011): the HTTP endpoint lives in {@code api/}; the
 * directory, the ledger and the transaction store each sit behind a port.
 */
public class ReceiveInboundPixUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReceiveInboundPixUseCase.class);

    /** The ledger's word for "money arrived from the Pix network" (docs/data-model.md §3). */
    private static final String ENTRY_DESCRIPTION_PREFIX = "Pix received ";

    private final PixKeyResolver keys;
    private final LedgerClient ledger;
    private final InboundTransactionStore transactions;
    private final byte[] expectedToken;
    private final String clearingAccountId;
    private final Clock clock;

    public ReceiveInboundPixUseCase(PixKeyResolver keys, LedgerClient ledger,
            InboundTransactionStore transactions, String webhookToken, String clearingAccountId,
            Clock clock) {
        this.keys = keys;
        this.ledger = ledger;
        this.transactions = transactions;
        // A null or BLANK token counts as unconfigured, and unconfigured refuses everything (see
        // requireValidToken). Blank matters: a stray-whitespace value in an env file would otherwise be a
        // real, guessable secret on a route that credits money.
        this.expectedToken = webhookToken == null || webhookToken.isBlank()
                ? new byte[0]
                : webhookToken.getBytes(StandardCharsets.UTF_8);
        this.clearingAccountId = clearingAccountId;
        this.clock = clock;
    }

    /**
     * @param presentedToken the {@code X-Webhook-Token} header exactly as received, or {@code null}
     * @throws InvalidWebhookTokenException  missing or wrong token — nothing is resolved, posted or written
     * @throws InboundKeyNotFoundException   no account here answers for the key — permanent, nothing posted
     * @throws com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException
     *                                       the directory could not be consulted — retryable, nothing posted
     * @throws com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException
     *                                       nothing was posted; the same {@code txId} is safe to retry
     */
    public ReceiveInboundOutcome execute(ReceiveInboundPixCommand command, String presentedToken) {
        requireValidToken(presentedToken, command);

        log.info("Inbound Pix accepted for processing, the webhook token checked out | endToEndId={} "
                        + "creditorKey={} amountCents={} payerName={} payerIspb={}",
                command.endToEndId(), command.creditorKey(), command.amountCents(), command.payerName(),
                command.payerIspb());

        String creditorAccountId = resolvePayee(command);
        String txId = InboundTransaction.txIdFor(command.endToEndId());
        Instant now = clock.instant();

        // Debit clearing / credit the payee — the mirror of an outbound send. Idempotent by txId, which is
        // what makes running it BEFORE the dedupe guard safe (see the class javadoc). A refused OR
        // unknown outcome throws (step 66): nothing is recorded locally, so the rail's retry re-posts
        // the same in-<endToEndId> identity and resolves whether the credit landed.
        LedgerOutcomes.requireMoneyMoved(
                ledger.creditInbound(txId, clearingAccountId, creditorAccountId, command.amountCents(),
                        ENTRY_DESCRIPTION_PREFIX + command.endToEndId()),
                txId, "PIX_IN");

        InboundTransaction transaction = new InboundTransaction(txId, command.endToEndId(),
                creditorAccountId, command.creditorKey(), clearingAccountId, command.amountCents(),
                command.payerName(), command.payerIspb(), now);
        OutboxEvent event = SettlementOutboxEvents.pixReceived(transaction, CorrelationId.current(), now);

        try {
            transactions.recordReceived(transaction, event);
        } catch (InboundAlreadyRecordedException alreadyRecorded) {
            // The dedupe fired: this endToEndId is already on record, so the posting just replayed as a
            // no-op and no second credit happened. A redelivery, not a fault — acked as a success.
            log.warn("Duplicate inbound Pix delivery: this endToEndId is already recorded, the credit "
                            + "posting replayed as a no-op and nothing was written, acking the redelivery "
                            + "| endToEndId={} txId={} creditorAccountId={} amountCents={}",
                    command.endToEndId(), txId, creditorAccountId, command.amountCents());
            return ReceiveInboundOutcome.ALREADY_PROCESSED;
        }

        log.info("Inbound Pix credited and recorded: the payee was credited from the clearing account and "
                        + "PixReceived was written to the outbox in the same atomic write | endToEndId={} "
                        + "txId={} creditorAccountId={} creditorKey={} clearingAccountId={} amountCents={} "
                        + "receivedAt={} receivedEventId={}",
                command.endToEndId(), txId, creditorAccountId, command.creditorKey(), clearingAccountId,
                command.amountCents(), now, event.eventId());
        return ReceiveInboundOutcome.CREDITED;
    }

    /**
     * Constant-time comparison of the presented secret against the configured one. {@link
     * MessageDigest#isEqual} rather than {@link String#equals} because the latter returns on the first
     * differing byte, which leaks the length of a correct prefix to anyone who can time the endpoint — a
     * small edge, but a free one to remove on the check guarding a money-minting route.
     *
     * <p>Neither token is logged, ever (ADR-0012). What is logged is the fact and the payment it was
     * refused for, which is what an operator needs.
     */
    private void requireValidToken(String presentedToken, ReceiveInboundPixCommand command) {
        byte[] presented = presentedToken == null
                ? new byte[0]
                : presentedToken.getBytes(StandardCharsets.UTF_8);

        if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, presented)) {
            log.warn("Inbound Pix webhook refused, the shared token is missing or wrong, nothing was "
                            + "resolved, posted or recorded, returning 401 | endToEndId={} creditorKey={} "
                            + "amountCents={} tokenPresented={}",
                    command.endToEndId(), command.creditorKey(), command.amountCents(),
                    presentedToken != null && !presentedToken.isBlank());
            throw new InvalidWebhookTokenException(
                    "The inbound Pix webhook requires a valid X-Webhook-Token.");
        }
    }

    /**
     * The payee is whoever <b>our</b> directory says the key belongs to. An empty answer covers both "no
     * such key" and "that key belongs to another participant" — for an inbound payment the rail routed it
     * to the wrong bank, and either way it is undeliverable here and must be bounced permanently. A
     * directory outage is a different answer entirely and propagates untouched, so the rail retries.
     */
    private String resolvePayee(ReceiveInboundPixCommand command) {
        Optional<String> resolved = keys.resolveToInternalAccount(command.creditorKey());
        if (resolved.isEmpty()) {
            log.warn("Inbound Pix refused permanently, no PlatinumCoin account answers for this Pix key, "
                            + "nothing was posted and no transaction was recorded, returning 422 so the "
                            + "rail bounces the payment back to the payer instead of retrying "
                            + "| endToEndId={} creditorKey={} amountCents={}",
                    command.endToEndId(), command.creditorKey(), command.amountCents());
            throw new InboundKeyNotFoundException(command.creditorKey());
        }
        log.info("Inbound Pix destination key resolved to a PlatinumCoin account, it will be credited "
                        + "| endToEndId={} creditorKey={} creditorAccountId={}",
                command.endToEndId(), command.creditorKey(), resolved.get());
        return resolved.get();
    }
}
