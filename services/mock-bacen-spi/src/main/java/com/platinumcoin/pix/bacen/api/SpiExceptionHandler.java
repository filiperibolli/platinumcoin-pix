package com.platinumcoin.pix.bacen.api;

import com.platinumcoin.pix.bacen.spi.DictKeyNotFoundException;
import com.platinumcoin.pix.bacen.spi.SettlementRejectedException;
import com.platinumcoin.pix.bacen.spi.SpiTimeoutException;
import com.platinumcoin.pix.bacen.spi.SpiUnavailableException;
import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the stub's four refusals into HTTP. Even a stub answers in the platform's error contract (RFC 7807
 * {@code application/problem+json} with a stable {@code code} plus the {@code correlationId}, via
 * common-lib's {@link ProblemDetailFactory}) — because the code on the other side of these responses is
 * <i>real</i>, and letting the fake dependency speak a different error dialect would mean the client's
 * error handling is only ever exercised against a shape it will not meet in production.
 *
 * <p>The four statuses are chosen so a caller can tell the three fundamentally different things apart:
 *
 * <ul>
 *   <li>{@code 503 SPI_UNAVAILABLE} — transient. Nothing was recorded; retry the same {@code endToEndId}.</li>
 *   <li>{@code 504 SPI_TIMEOUT} — <b>unknown outcome</b>. The settlement may well have happened (it did, in
 *       the injected case). Query before retrying (step 32).</li>
 *   <li>{@code 422 SPI_REJECTED} — permanent business refusal. Retrying is pointless; reverse instead
 *       (step 33). Carries the recorded reason.</li>
 *   <li>{@code 404 DICT_KEY_NOT_FOUND} — the directory has no such key. The one case in which a payer
 *       should be told the key does not exist.</li>
 * </ul>
 *
 * <p><b>Logging (ADR-0012).</b> The <i>reason</i> is already logged where the decision happened; this class
 * logs the <i>outcome</i> — the status and code the participant actually received — so one
 * {@code grep <correlationId>} shows the send, the settlement attempt, the injected fault and its HTTP
 * consequence in order across services. WARN, never ERROR: every one of these is a designed answer, and
 * reserving ERROR for actionable faults is what keeps it meaningful.
 */
@RestControllerAdvice
public class SpiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SpiExceptionHandler.class);

    /** Seconds a participant should wait before re-attempting a settlement the rail could not serve. */
    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(SpiUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(SpiUnavailableException ex) {
        // Retry-After is the honest hint: nothing was recorded, so the same endToEndId is safe to re-send.
        log.warn("Mapped an SPI refusal to the participant response | status={} code={} detail={} "
                        + "retryAfter={}",
                HttpStatus.SERVICE_UNAVAILABLE.value(), "SPI_UNAVAILABLE", ex.getMessage(),
                RETRY_AFTER_SECONDS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(
                        HttpStatus.SERVICE_UNAVAILABLE, "SPI_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(SpiTimeoutException.class)
    public ResponseEntity<ProblemDetail> handleTimeout(SpiTimeoutException ex) {
        // A well-behaved client has already given up by the time this is written; it exists so the drill
        // is legible in the logs rather than looking like a dropped connection.
        return problem(HttpStatus.GATEWAY_TIMEOUT, "SPI_TIMEOUT", ex.getMessage());
    }

    @ExceptionHandler(SettlementRejectedException.class)
    public ResponseEntity<ProblemDetail> handleRejected(SettlementRejectedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "SPI_REJECTED",
                ex.getMessage() + " | endToEndId=" + ex.settlement().endToEndId());
    }

    @ExceptionHandler(DictKeyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleDictKeyNotFound(DictKeyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "DICT_KEY_NOT_FOUND", ex.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        log.warn("Mapped an SPI refusal to the participant response | status={} code={} detail={}",
                status.value(), code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(status, code, detail));
    }
}
