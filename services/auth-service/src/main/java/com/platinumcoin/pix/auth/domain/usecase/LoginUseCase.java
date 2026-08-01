package com.platinumcoin.pix.auth.domain.usecase;

import com.platinumcoin.pix.auth.domain.InvalidCredentialsException;
import com.platinumcoin.pix.auth.domain.IssuedToken;
import com.platinumcoin.pix.auth.domain.PasswordVerifier;
import com.platinumcoin.pix.auth.domain.TokenIssuer;
import com.platinumcoin.pix.auth.domain.User;
import com.platinumcoin.pix.auth.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticate a username/password pair and, on success, mint an access token. Renamed from
 * {@code AuthenticationService} by ADR-0011 so the class name states the operation; the logic is
 * unchanged. Plain Java wired to three ports — no Spring, no jjwt, no bcrypt import.
 *
 * <p>The order matters: we always run the password verification against the found user's hash, and
 * collapse "unknown user" and "wrong password" into one {@link InvalidCredentialsException} so the
 * API cannot be used to enumerate usernames.
 *
 * <p>Nothing secret is logged — never the password, never the token, never the hash. The success
 * line carries the username only because a failed login is already logged at the api edge by
 * {@code AuthExceptionHandler} and the pair is what makes the trace readable.
 */
public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    private final UserRepository users;
    private final PasswordVerifier passwordVerifier;
    private final TokenIssuer tokenIssuer;

    public LoginUseCase(UserRepository users, PasswordVerifier passwordVerifier,
            TokenIssuer tokenIssuer) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.tokenIssuer = tokenIssuer;
    }

    public IssuedToken execute(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordVerifier.matches(rawPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        IssuedToken token = tokenIssuer.issue(user.userId(), user.accountId());
        log.info("auth.login.success username={}", username);
        return token;
    }
}
