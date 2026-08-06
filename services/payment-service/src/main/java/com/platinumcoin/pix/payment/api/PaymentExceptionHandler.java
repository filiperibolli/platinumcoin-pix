package com.platinumcoin.pix.payment.api;

import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyRequiredException;
import com.platinumcoin.pix.payment.domain.IdempotencyKeyReuseException;
import com.platinumcoin.pix.payment.domain.InvalidAmountException;
import com.platinumcoin.pix.payment.domain.RequestInProgressException;
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
 *   <li>{@code 409 REQUEST_IN_PROGRESS} — a concurrent request with the same key is still in flight;
 *       carries {@code Retry-After: 2} so the client backs off and later replays the result.</li>
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

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAmount(InvalidAmountException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<ProblemDetail> handleKeyRequired(IdempotencyKeyRequiredException ex) {
        return problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ProblemDetail> handleKeyReuse(IdempotencyKeyReuseException ex) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
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
