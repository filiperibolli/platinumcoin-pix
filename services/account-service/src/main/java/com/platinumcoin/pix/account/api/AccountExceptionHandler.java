package com.platinumcoin.pix.account.api;

import com.platinumcoin.pix.account.domain.AccountNotFoundException;
import com.platinumcoin.pix.account.domain.InvalidPixKeyException;
import com.platinumcoin.pix.account.domain.PixKeyAlreadyExistsException;
import com.platinumcoin.pix.account.domain.PixKeyNotFoundException;
import com.platinumcoin.pix.account.domain.PixKeyNotOwnedException;
import com.platinumcoin.pix.common.error.ProblemDetailFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place where account-service's domain failures become HTTP (ADR-0011 rule 7). The
 * {@code domain/} layer raises plain-Java exceptions that know nothing about status codes; this
 * advice owns the mapping, reusing common-lib's {@link ProblemDetailFactory} so the error contract
 * (a stable {@code code} plus the {@code correlationId}) is identical across every service.
 *
 * <p>The status choices are the contract documented in {@code docs/api/openapi.yaml} and unchanged
 * by the ADR-0011 refactor:
 *
 * <ul>
 *   <li>{@code 404 ACCOUNT_NOT_FOUND} — no account for the caller's token, or for a requested id.</li>
 *   <li>{@code 422 INVALID_PIX_KEY} — parseable body, but the value is not a valid key of its type.</li>
 *   <li>{@code 409 KEY_ALREADY_EXISTS} — the conditional put lost the global-uniqueness race.</li>
 *   <li>{@code 404 KEY_NOT_FOUND} — no key answers for the value (delete, and DICT resolve).</li>
 *   <li>{@code 403 KEY_FORBIDDEN} — the key exists but belongs to another account. Deliberately not
 *       a 404: Pix keys are globally resolvable, so their existence is not secret.</li>
 * </ul>
 *
 * <p>Logging stays in the use cases (where the business stage happens); this class only translates,
 * so it does not duplicate a WARN that {@code domain/} already emitted.
 */
@RestControllerAdvice
public class AccountExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAccountNotFound(AccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidPixKeyException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPixKey(InvalidPixKeyException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIX_KEY", ex.getMessage());
    }

    @ExceptionHandler(PixKeyAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleKeyAlreadyExists(PixKeyAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, "KEY_ALREADY_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(PixKeyNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleKeyNotFound(PixKeyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(PixKeyNotOwnedException.class)
    public ResponseEntity<ProblemDetail> handleKeyNotOwned(PixKeyNotOwnedException ex) {
        return problem(HttpStatus.FORBIDDEN, "KEY_FORBIDDEN", ex.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetailFactory.of(status, code, detail));
    }
}
