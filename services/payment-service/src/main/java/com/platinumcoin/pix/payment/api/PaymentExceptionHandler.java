package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import com.platinumcoin.pix.payment.domain.exception.AccountLookupException;
import com.platinumcoin.pix.payment.domain.exception.BalanceNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.FraudDeniedException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.exception.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.exception.InsufficientFundsException;
import com.platinumcoin.pix.payment.domain.exception.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.exception.InvalidStatementCursorException;
import com.platinumcoin.pix.payment.domain.exception.KeyNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.LedgerUnavailableException;
import com.platinumcoin.pix.payment.domain.exception.LimitExceededException;
import com.platinumcoin.pix.payment.domain.exception.PaymentNotFoundException;
import com.platinumcoin.pix.payment.domain.exception.RequestInProgressException;
import com.platinumcoin.pix.payment.domain.exception.UnresolvedOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place where payment-service's domain failures become HTTP (ADR-0011 rule 7), reusing
 * common-lib's {@link ProblemDetailFactory} so the error contract — a stable {@code code} plus the
 * {@code correlationId} — is identical to every other service's. Bean-validation failures on the
 * request body ({@code @NotBlank}/{@code @Pattern}/{@code @Size}) are handled one level up by
 * common-lib's {@code GlobalExceptionHandler} as {@code 400 VALIDATION_ERROR}.
 *
 * <ul>
 *   <li>{@code 400 INVALID_AMOUNT} — the amount is well-formed on the wire but is not strictly
 *       positive money ({@code "0.00"}) or carries sub-cent precision. A distinct code from the
 *       generic {@code VALIDATION_ERROR} so the log says <i>why</i> the value was refused.</li>
 *   <li>{@code 400 IDEMPOTENCY_KEY_REQUIRED} — the required {@code Idempotency-Key} header was
 *       absent/blank (ADR-0002).</li>
 *   <li>{@code 409 IDEMPOTENCY_KEY_REUSED} — the same key was replayed with a different payload
 *       (client bug).</li>
 *   <li>{@code 409 OPERATION_UNRESOLVED} — the key names a money operation that never resolved and
 *       cannot be safely resumed (ADR-0014); no {@code Retry-After}, it needs a human</li>
 *   <li>{@code 409 REQUEST_IN_PROGRESS} — a concurrent request with the same key is still in flight;
 *       carries {@code Retry-After: 2} so the client backs off and later replays the result.</li>
 *   <li>{@code 422 KEY_NOT_FOUND} — the destination Pix key does not resolve to an internal account
 *       (step 21). {@code 422}, not {@code 404}: the request is well-formed and understood; it is the
 *       destination it names that does not exist.</li>
 *   <li>{@code 422 LIMIT_EXCEEDED} — the send would breach the debtor's daily Pix limit (step 20,
 *       ADR-0007). {@code 422}, not {@code 403}: the request is well-formed and authorized, it just
 *       violates a business rule a later send or the next calendar day may satisfy.</li>
 *   <li>{@code 422 FRAUD_DENIED} — the in-path fraud check returned {@code DENY} (step 25, ADR-0005).
 *       {@code 422}, not {@code 403}: the request is well-formed and authorized, refused by a risk
 *       decision. The daily-limit reservation is released by the use case before this maps; no money
 *       moved. A fraud-service <i>timeout or error</i> never reaches here — that path fails open.</li>
 *   <li>{@code 422 INSUFFICIENT_FUNDS} — the ledger refused the debit for lack of funds (step 21). The
 *       daily-limit reservation is released by the use case before this maps; no money moved.</li>
 *   <li>{@code 503 LEDGER_UNAVAILABLE} — the ledger was unreachable, timed out, or lost to contention
 *       (step 21). Carries {@code Retry-After: 5}: nothing was debited and the same {@code txId} is
 *       safe to retry (ADR-0002).</li>
 *   <li>{@code 502 ACCOUNT_LOOKUP_FAILED} — account-service could not supply the debtor's limit
 *       (not found / unreachable); the fault is a dependency of ours, not the caller's request.</li>
 *   <li>{@code 404 PAYMENT_NOT_FOUND} — the queried transaction does not exist <i>or</i> belongs to
 *       another account (step 22). The two cases are deliberately indistinguishable: a {@code 403}
 *       would confirm a foreign transaction id is real, so both answer {@code 404} and leak nothing.</li>
 *   <li>{@code 404 BALANCE_NOT_FOUND} — the ledger holds no balance for the caller's own account
 *       (step 40). Never a {@code 200} with zero: a customer must not be shown R$ 0,00 for an account
 *       that was never opened.</li>
 *   <li>{@code 400 INVALID_CURSOR} — the statement's pagination cursor is malformed or names a
 *       different account than the caller's own (step 41). A well-formed refusal, not a server fault:
 *       never a {@code 503} that would invite the client to retry the same bad cursor forever.</li>
 * </ul>
 *
 * <p><b>Logging (ADR-0012).</b> The <i>reason</i> is logged by the use case/domain; this class logs
 * the <i>outcome</i> — the status and code the caller received — so one {@code grep} on a
 * correlationId shows the decision and its HTTP consequence in order.
 */
@RestControllerAdvice
public class PaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    /** Seconds a client should wait before retrying an in-flight idempotent request (ADR-0002). */
    private static final String RETRY_AFTER_SECONDS = "2";

    /** Seconds a client should wait before retrying a send the ledger could not serve (step 21). */
    private static final String RETRY_AFTER_LEDGER_SECONDS = "5";

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAmount(InvalidAmountException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", ex.getMessage());
    }

    @ExceptionHandler(KeyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleKeyNotFound(KeyNotFoundException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "KEY_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(FraudDeniedException.class)
    public ResponseEntity<ProblemDetail> handleFraudDenied(FraudDeniedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "FRAUD_DENIED", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientFunds(InsufficientFundsException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", ex.getMessage());
    }

    @ExceptionHandler(LedgerUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleLedgerUnavailable(LedgerUnavailableException ex) {
        // Nothing was debited and the same txId is safe to retry, so — like REQUEST_IN_PROGRESS — this
        // carries Retry-After telling the client to back off and re-send rather than give up. The 503
        // message is safe (no internals); the cause chain is logged, not returned.
        log.warn("Mapped a domain failure to the client response | status={} code={} detail={} retryAfter={}",
                HttpStatus.SERVICE_UNAVAILABLE.value(), "LEDGER_UNAVAILABLE", ex.getMessage(),
                RETRY_AFTER_LEDGER_SECONDS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_LEDGER_SECONDS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(
                        HttpStatus.SERVICE_UNAVAILABLE, "LEDGER_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<ProblemDetail> handleKeyRequired(IdempotencyKeyRequiredException ex) {
        return problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ProblemDetail> handleKeyReuse(IdempotencyKeyReuseException ex) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleLimitExceeded(LimitExceededException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotFound(PaymentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BalanceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleBalanceNotFound(BalanceNotFoundException ex) {
        // The ledger holds no BALANCE item for the caller's own account. Deliberately not a 200 with
        // zero: "no such account" and "no money" are different facts and a customer must never be shown
        // the second when the first is true.
        return problem(HttpStatus.NOT_FOUND, "BALANCE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidStatementCursorException.class)
    public ResponseEntity<ProblemDetail> handleInvalidStatementCursor(InvalidStatementCursorException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", ex.getMessage());
    }

    @ExceptionHandler(AccountLookupException.class)
    public ResponseEntity<ProblemDetail> handleAccountLookup(AccountLookupException ex) {
        // The debtor's limit could not be read; fail the send rather than guess a limit. The message
        // is safe (no internals) — the cause chain is logged, not returned.
        return problem(HttpStatus.BAD_GATEWAY, "ACCOUNT_LOOKUP_FAILED", ex.getMessage());
    }

    @ExceptionHandler(UnresolvedOperationException.class)
    public ResponseEntity<ProblemDetail> handleUnresolvedOperation(UnresolvedOperationException ex) {
        // Sibling of REQUEST_IN_PROGRESS, and deliberately WITHOUT Retry-After: this one never resolves
        // on its own, so telling the client to come back in two seconds would invite an infinite retry
        // over a defect. The operator-facing detail (the stranded txId) is in the ERROR log the use
        // case already emitted, never in the response.
        return problem(HttpStatus.CONFLICT, "OPERATION_UNRESOLVED", ex.getMessage());
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ResponseEntity<ProblemDetail> handleInProgress(RequestInProgressException ex) {
        // The one error that carries Retry-After: the operation may still succeed, so the client is
        // told to back off and retry the SAME key rather than treat this as a permanent failure.
        log.warn("Mapped a domain failure to the client response | status={} code={} detail={} retryAfter={}",
                HttpStatus.CONFLICT.value(), "REQUEST_IN_PROGRESS", ex.getMessage(), RETRY_AFTER_SECONDS);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", ex.getMessage()));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        log.warn("Mapped a domain failure to the client response | status={} code={} detail={}",
                status.value(), code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(status, code, detail));
    }
}
