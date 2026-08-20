package com.platinumcoin.pix.settlement.api;

import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import com.platinumcoin.pix.settlement.domain.exception.DirectoryUnavailableException;
import com.platinumcoin.pix.settlement.domain.exception.InboundKeyNotFoundException;
import com.platinumcoin.pix.settlement.domain.exception.InvalidWebhookTokenException;
import com.platinumcoin.pix.settlement.domain.exception.LedgerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the inbound webhook's refusals onto HTTP (step 37), in the platform's error contract — RFC 7807
 * {@code application/problem+json} with a stable {@code code} and the {@code correlationId}, via
 * common-lib's {@link ProblemDetailFactory}.
 *
 * <h2>The status codes are a retry protocol, not decoration</h2>
 * mock-bacen's generator — and a real rail — branch on them, so getting one wrong is a money bug:
 * <ul>
 *   <li>{@code 401 WEBHOOK_UNAUTHORIZED} — the shared token is missing or wrong. <b>Permanent</b>: no
 *       amount of retrying fixes a credential, and nothing was credited.</li>
 *   <li>{@code 422 KEY_NOT_FOUND} — no account here answers for the key. <b>Permanent</b>: the rail should
 *       bounce the payment back to the payer's PSP rather than keep re-presenting it.</li>
 *   <li>{@code 503 DIRECTORY_UNAVAILABLE} / {@code 503 LEDGER_UNAVAILABLE} + {@code Retry-After} —
 *       <b>transient</b>. Nothing was credited and the outcome is genuinely unknown or merely delayed, so
 *       the payment must be re-presented. Answering {@code 422} here instead would destroy a deliverable
 *       payment because <i>our</i> dependency blinked.</li>
 * </ul>
 * The whole point of separating {@link InboundKeyNotFoundException} from
 * {@link DirectoryUnavailableException} in the domain is to make this table expressible.
 *
 * <p><b>Logging (ADR-0012).</b> The <i>reason</i> is logged where the decision was taken; this class logs
 * the <i>outcome</i> — the status the rail actually received — so one {@code grep <correlationId>} shows
 * the delivery, the decision and its HTTP consequence in order. WARN, never ERROR: each of these is a
 * designed answer. The presented token is never logged or echoed.
 */
@RestControllerAdvice
public class SettlementExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SettlementExceptionHandler.class);

    /** Seconds the rail should wait before re-presenting a payment we could not decide on right now. */
    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(InvalidWebhookTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(InvalidWebhookTokenException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "WEBHOOK_UNAUTHORIZED", ex.getMessage());
    }

    @ExceptionHandler(InboundKeyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleKeyNotFound(InboundKeyNotFoundException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "KEY_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DirectoryUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDirectoryUnavailable(DirectoryUnavailableException ex) {
        return retryable("DIRECTORY_UNAVAILABLE",
                "The key directory could not be consulted; re-present this payment.");
    }

    @ExceptionHandler(LedgerUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleLedgerUnavailable(LedgerUnavailableException ex) {
        return retryable("LEDGER_UNAVAILABLE",
                "The ledger could not be reached; nothing was credited, re-present this payment.");
    }

    private static ResponseEntity<ProblemDetail> retryable(String code, String detail) {
        log.warn("Mapped an inbound-webhook refusal to the rail's response, it is transient so the rail "
                        + "should re-present the payment | status={} code={} detail={} retryAfter={}",
                HttpStatus.SERVICE_UNAVAILABLE.value(), code, detail, RETRY_AFTER_SECONDS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(HttpStatus.SERVICE_UNAVAILABLE, code, detail));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        log.warn("Mapped an inbound-webhook refusal to the rail's response, it is permanent so retrying "
                + "would refuse identically | status={} code={} detail={}", status.value(), code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(status, code, detail));
    }
}
