package com.platinumcoin.pix.auth.api;

import com.platinumcoin.pix.auth.domain.InvalidCredentialsException;
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
 * Maps the domain-local {@link InvalidCredentialsException} to a 401 {@code application/problem+json}
 * at the api edge, reusing common-lib's {@link ProblemDetailFactory} so the error contract
 * (code + correlationId) matches every other service. Kept here (not in common-lib) because the
 * domain exception is auth-specific. Logs at WARN — a failed login is a degradation, not an error.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("auth.login.denied reason=invalid_credentials");
        ProblemDetail body = ProblemDetailFactory.of(
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
