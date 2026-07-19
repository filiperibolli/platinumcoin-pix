package com.platinumcoin.pix.auth.domain;

/**
 * Raised when a login cannot be authenticated. Deliberately says nothing about <em>why</em>
 * (unknown user vs. wrong password) so the API cannot leak which usernames exist — both paths
 * surface as the same 401. Mapped to {@code application/problem+json} at the api edge.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password.");
    }
}
