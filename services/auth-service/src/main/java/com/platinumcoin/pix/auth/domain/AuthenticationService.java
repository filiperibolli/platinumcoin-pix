package com.platinumcoin.pix.auth.domain;

/**
 * The one piece of business logic in auth-service: authenticate a username/password pair and, on
 * success, mint a token. Plain Java wired to three ports — no Spring, no jjwt, no bcrypt import
 * (ADR-0010). The order matters: we always run the password verification against the found user's
 * hash, and collapse "unknown user" and "wrong password" into one {@link InvalidCredentialsException}.
 */
public class AuthenticationService {

    private final UserRepository users;
    private final PasswordVerifier passwordVerifier;
    private final TokenIssuer tokenIssuer;

    public AuthenticationService(UserRepository users, PasswordVerifier passwordVerifier,
            TokenIssuer tokenIssuer) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.tokenIssuer = tokenIssuer;
    }

    public IssuedToken login(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordVerifier.matches(rawPassword, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        return tokenIssuer.issue(user.userId(), user.accountId());
    }
}
