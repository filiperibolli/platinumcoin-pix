package com.platinumcoin.pix.ledger.api;

import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import com.platinumcoin.pix.ledger.domain.LedgerAccountNotFoundException;
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
 *   <li>{@code 404 LEDGER_ACCOUNT_NOT_FOUND} — no BALANCE item for the requested account.</li>
 * </ul>
 *
 * <p>The list grows with the flows: {@code 422 INSUFFICIENT_FUNDS} and the replayed-{@code txId}
 * outcome arrive with the posting in step 14, and both come from conditions evaluated <i>inside</i>
 * the transaction, never from a prior read.
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

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        log.warn("Mapped a domain failure to the client response | status={} code={} detail={}",
                status.value(), code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(status, code, detail));
    }
}
