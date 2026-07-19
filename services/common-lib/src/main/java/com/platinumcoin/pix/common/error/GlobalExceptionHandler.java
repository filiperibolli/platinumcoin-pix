package com.platinumcoin.pix.common.error;

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
        return problemResponse(ex.status(), ex.code(), ex.getMessage());
    }

    /** Anything unhandled becomes a generic 500 — no message, no stack trace leaks to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("unhandled.exception", ex);
        return problemResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.");
    }

    /** Bean-validation failures on a request body become a 400 with a VALIDATION_ERROR code. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetailFactory.of(status, "VALIDATION_ERROR",
                "One or more fields are invalid.");
        return handleExceptionInternal(ex, body, problemJsonHeaders(headers), status, request);
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
