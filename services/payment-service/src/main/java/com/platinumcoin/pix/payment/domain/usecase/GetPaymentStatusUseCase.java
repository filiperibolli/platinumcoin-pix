package com.platinumcoin.pix.payment.domain.usecase;

import com.platinumcoin.pix.payment.domain.exception.PaymentNotFoundException;
import com.platinumcoin.pix.payment.domain.model.Transaction;
import com.platinumcoin.pix.payment.domain.port.TransactionRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read a send-Pix transaction for its owner: the single business operation behind {@code GET
 * /v1/payments/{transactionId}} (step 22). The one decision that lives here (ADR-0011 — not in the
 * controller) is <b>ownership</b>: the caller may only read a transaction that is theirs.
 *
 * <p><b>Whose it is depends on which way the money went</b> (step 45). The endpoint also serves the
 * arrivals settlement-service writes into the same table (step 37), because ARCHITECTURE §6.8 makes this
 * poll the authoritative view behind the best-effort push — for the payee's {@code PixReceived} too. So
 * ownership is {@link Transaction#ownerAccountId()}: the payer for a send, the payee for an arrival, and
 * deliberately <b>not</b> "the debtor or the creditor", which would additionally expose an internal
 * send's record to its payee.
 *
 * <p><b>Not-found and not-yours are the same answer (Domain Safety Rule #1).</b> A transaction that
 * does not exist and one that belongs to another account both raise {@link PaymentNotFoundException},
 * which the edge maps to {@code 404} — never {@code 403}. Answering {@code 403} for "exists but not
 * yours" would confirm the id is real and let a caller enumerate other accounts' transactions; a
 * uniform {@code 404} leaks nothing. The debtor identity is the JWT {@code accountId} the controller
 * forwards, never a path or body field.
 *
 * <p>The internal→external status mapping is <i>not</i> here: it is wire presentation, applied at the
 * {@code api/} edge ({@code PaymentResponse.from}). This use case returns the domain {@link Transaction}
 * unchanged so the domain stays free of the external vocabulary.
 */
public class GetPaymentStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetPaymentStatusUseCase.class);

    private final TransactionRepository transactions;

    public GetPaymentStatusUseCase(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /**
     * Load the transaction {@code txId} on behalf of {@code callerAccountId}.
     *
     * @throws PaymentNotFoundException the transaction does not exist, or exists but belongs to a
     *                                  different account (uniform 404 — no existence leak)
     */
    public Transaction execute(String txId, String callerAccountId) {
        Optional<Transaction> found = transactions.findById(txId);
        if (found.isEmpty()) {
            log.warn("Payment status requested for an unknown transaction, returning 404 | "
                    + "transactionId={} callerAccountId={}", txId, callerAccountId);
            throw new PaymentNotFoundException();
        }

        Transaction transaction = found.get();
        if (!callerAccountId.equals(transaction.ownerAccountId())) {
            // Exists, but not the caller's — answer exactly as we would for a missing id so the caller
            // cannot tell the difference and enumerate other accounts' transactions. Compared with the
            // caller's id on the left: an arrival whose creditor attribute were somehow absent must be
            // refused, never crash into a 500 that would itself distinguish it from an unknown id.
            log.warn("Payment status refused, the transaction belongs to another account, returning 404 "
                            + "| transactionId={} callerAccountId={} direction={} ownerAccountId={}",
                    txId, callerAccountId, transaction.direction(), transaction.ownerAccountId());
            throw new PaymentNotFoundException();
        }

        log.info("Payment status served to its owner | transactionId={} callerAccountId={} direction={} "
                        + "status={} settledAt={}",
                txId, callerAccountId, transaction.direction(), transaction.status(),
                transaction.settledAt());
        return transaction;
    }
}
