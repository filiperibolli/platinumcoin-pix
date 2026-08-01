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
 * <p><b>Logging (ADR-0012).</b> Identity <i>values</i> are logged in full — username, userId,
 * accountId — because this is a sandbox with seeded demo users and a trace you cannot read is worth
 * nothing. <b>Secrets never are:</b> not the password, not the bcrypt hash, not the minted token.
 * The distinction is deliberate — "log the data, never the credential".
 *
 * <p>Note the asymmetry between log and response: the log distinguishes {@code unknown_user} from
 * {@code bad_password} (that is what makes a failed login diagnosable), while both raise the same
 * {@link InvalidCredentialsException} so the API cannot be used to enumerate usernames.
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
        log.info("Login attempt received | username={}", username);

        User user = users.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("Login denied, no user with this username "
                            + "(the client is only told 'invalid credentials') | username={}", username);
                    return new InvalidCredentialsException();
                });

        if (!passwordVerifier.matches(rawPassword, user.passwordHash())) {
            log.warn("Login denied, the password does not match the stored bcrypt hash "
                    + "| username={} userId={}", username, user.userId());
            throw new InvalidCredentialsException();
        }

        IssuedToken token = tokenIssuer.issue(user.userId(), user.accountId());
        log.info("Login succeeded, access token issued "
                        + "| username={} userId={} accountId={} expiresInSeconds={}",
                username, user.userId(), user.accountId(), token.expiresInSeconds());
        return token;
    }
}
