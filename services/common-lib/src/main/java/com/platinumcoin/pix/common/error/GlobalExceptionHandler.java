package com.platinumcoin.pix.common.error;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Platform-wide error contract. Every error a consuming service returns is an RFC 7807
 * {@code application/problem+json} body carrying {@code code} and {@code correlationId}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} to reuse Spring's handling of the standard
 * MVC exceptions (unreadable body, unsupported media type, …), overriding only where we need to
 * inject our extension members. Unexpected exceptions are logged with their stack trace
 * server-side but never expose it to the client — the body is a generic {@code INTERNAL_ERROR}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Expected, business-meaningful failures map to the status and code they declare. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        // A 4xx is a normal outcome, not an incident — WARN, no stack trace. It is logged here (and
        // not only in the use case that threw) so that every non-2xx the platform returns has a
        // matching line under the request's cid, with the exact code the client received.
        log.warn("Domain rule failed, returning HTTP {} to the client | code={} detail={}",
                ex.status().value(), ex.code(), ex.getMessage());
        return problemResponse(ex.status(), ex.code(), ex.getMessage());
    }

    /** Anything unhandled becomes a generic 500 — no message, no stack trace leaks to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception, returning HTTP 500 INTERNAL_ERROR "
                + "(the stack trace stays server-side)", ex);
        return problemResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.");
    }

    /** Bean-validation failures on a request body become a 400 with a VALIDATION_ERROR code. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The response stays generic ("one or more fields are invalid"); the log says exactly which
        // field failed and with which value, so "the API rejected my payload" is answerable from the
        // logs alone. Rejected values are printed verbatim — sandbox data only (ADR-0012).
        log.warn("Request body failed bean validation, returning HTTP 400 VALIDATION_ERROR "
                + "| invalidFields=[{}]", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> "%s=%s (%s)".formatted(e.getField(), e.getRejectedValue(), e.getDefaultMessage()))
                .collect(Collectors.joining(", ")));
        ProblemDetail body = ProblemDetailFactory.of(status, "VALIDATION_ERROR",
                "One or more fields are invalid.");
        return handleExceptionInternal(ex, body, problemJsonHeaders(headers), status, request);
    }

    /**
     * Every error Spring MVC maps itself funnels through here — unknown path (404), wrong method
     * (405), unsupported media type (415), unreadable body (400). Without this hook such a request
     * would produce <b>no log line at all</b>: it is rejected before any controller or use case runs,
     * and nothing else logs per request. One line, so the rule "every non-2xx the platform returns
     * has a line under its correlationId" holds for framework rejections too.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(ex instanceof MethodArgumentNotValidException)) {
            // Validation already logged its fields in the override above — don't say it twice.
            log.warn("Request rejected by Spring MVC before reaching a handler, returning HTTP {} "
                            + "| exception={} request={} detail={}",
                    status.value(), ex.getClass().getSimpleName(),
                    request.getDescription(false), ex.getMessage());
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    private ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetailFactory.of(status, code, detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static HttpHeaders problemJsonHeaders(HttpHeaders base) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(base);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return headers;
    }
}
