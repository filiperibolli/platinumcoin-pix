package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import com.platinumcoin.pix.ledger.domain.InsufficientFundsException;
import com.platinumcoin.pix.ledger.domain.InvalidCursorException;
import com.platinumcoin.pix.ledger.domain.InvalidPostingException;
import com.platinumcoin.pix.ledger.domain.LedgerAccountNotFoundException;
import com.platinumcoin.pix.ledger.domain.LedgerBusyException;
import com.platinumcoin.pix.ledger.domain.PostingConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place where ledger-service's domain failures become HTTP (ADR-0011 rule 7), reusing
 * common-lib's {@link ProblemDetailFactory} so the error contract — a stable {@code code} plus the
 * {@code correlationId} — is identical to every other service's.
 *
 * <ul>
 *   <li>{@code 404 LEDGER_ACCOUNT_NOT_FOUND} — no BALANCE item for the requested account (either
 *       leg of a posting, or the account of a balance read).</li>
 *   <li>{@code 422 INSUFFICIENT_FUNDS} — the debtor was short. It is a 422 and not a 409 because the
 *       request was well-formed and understood; it is the <i>state of the world</i> that refuses it.</li>
 *   <li>{@code 422 INVALID_POSTING} — a command that is not a posting at all (non-positive amount,
 *       blank identity, both legs on one account).</li>
 *   <li>{@code 409 POSTING_TXID_MISMATCH} — the {@code txId} already posted different money. The one
 *       error whose absence would be dangerous: without it the ledger would have to guess between
 *       swallowing a payment and double-spending one.</li>
 *   <li>{@code 503 LEDGER_CONFLICT} — lost to concurrent writers past the retry budget. A 5xx on
 *       purpose: nothing is wrong with the request, and the caller may safely re-send the same
 *       {@code txId} — which is precisely what idempotency buys.</li>
 *   <li>{@code 400 INVALID_CURSOR} — the statement cursor is malformed or names another account. A
 *       client-error 400, because the request itself is wrong; the cross-account case is answered the
 *       same way so a forged cursor never pages someone else's history (step 16).</li>
 * </ul>
 *
 * <p>Every one of these comes from a condition evaluated <i>inside</i> the posting transaction, never
 * from a prior read: when the client sees a 422, DynamoDB has already refused the write.
 *
 * <p><b>Logging (ADR-0012).</b> The <i>reason</i> is logged by the use case; this class logs the
 * <i>outcome</i> — the status and code the caller actually received — so one {@code grep} on a
 * correlationId shows the decision and its HTTP consequence in order.
 */
@RestControllerAdvice
public class LedgerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerExceptionHandler.class);

    @ExceptionHandler(LedgerAccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleLedgerAccountNotFound(LedgerAccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "LEDGER_ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientFunds(InsufficientFundsException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", ex.getMessage());
    }

    @ExceptionHandler(InvalidPostingException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPosting(InvalidPostingException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POSTING", ex.getMessage());
    }

    @ExceptionHandler(PostingConflictException.class)
    public ResponseEntity<ProblemDetail> handlePostingConflict(PostingConflictException ex) {
        return problem(HttpStatus.CONFLICT, "POSTING_TXID_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(LedgerBusyException.class)
    public ResponseEntity<ProblemDetail> handleLedgerBusy(LedgerBusyException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "LEDGER_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCursor(InvalidCursorException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", ex.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        log.warn("Mapped a domain failure to the client response | status={} code={} detail={}",
                status.value(), code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(status, code, detail));
    }
}
